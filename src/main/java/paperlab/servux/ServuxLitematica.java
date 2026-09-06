package paperlab.servux;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import paperlab.command.LabPermissions;

/**
 * The {@code servux:litematics} channel — server-side schematic pasting without command spam.
 *
 * <h2>Why</h2>
 * Without a server side, Litematica places blocks through {@code /setblock}: every command is
 * a line in chat and in the log, which on a large schematic means thousands of lines. With a
 * server side the client sends the schematic as one body and the server places the blocks.
 *
 * <h2>The protocol, as recovered</h2>
 * Everything was checked against Litematica's <b>client</b> code rather than against Servux —
 * after the framing episode there is no other way.
 *
 * <ul>
 *   <li>this channel's protocol version is <b>2</b>, not 3 as for HUD and structures;</li>
 *   <li>the client sends the request as type {@code 12} (continuations {@code 13}) through the
 *       splitter;</li>
 *   <li>the body is a {@code SchematicPlacement.toData(true)} compound plus
 *       {@code Task=LitematicaPaste} and {@code Interval};</li>
 *   <li>inside it, {@code Schematics} in the ordinary {@code .litematic} format:
 *       {@code Regions -> { Position, Size, BlockStatePalette, BlockStates, TileEntities, Entities }}.</li>
 * </ul>
 *
 * <p><b>An important dependency:</b> Litematica offers Servux pasting only if this channel is
 * registered <i>and</i> {@code pasteUsingServux} is enabled in its settings. Without the former
 * the menu item simply does not appear.
 */
public final class ServuxLitematica implements PluginMessageListener {

    public static final String CHANNEL = "servux:litematics";

    /** This channel is version 2. HUD and structures are 3; easy to confuse. */
    public static final int PROTOCOL_VERSION = 2;

    private static final int S2C_METADATA = 1;
    private static final int C2S_METADATA_REQUEST = 2;
    private static final int C2S_UNREGISTER_REPLY = 8;
    private static final int C2S_NBT_RESPONSE_START = 12;
    private static final int C2S_NBT_RESPONSE_DATA = 13;

    private static final boolean DEBUG = Boolean.getBoolean("paperlab.servux.debug");

    private static final Set<UUID> REGISTERED = ConcurrentHashMap.newKeySet();
    private static Plugin plugin;

    public static void enable(final Plugin owner) {
        plugin = owner;
        if (Bukkit.getPluginManager().getPlugin("LitematicaFolia") != null) {
            owner.getLogger().info("Servux litematics: LitematicaFolia detected, skipping internal servux:litematics handler to prevent conflicts.");
            return;
        }
        Bukkit.getMessenger().registerIncomingPluginChannel(owner, CHANNEL, new ServuxLitematica());
        Bukkit.getMessenger().registerOutgoingPluginChannel(owner, CHANNEL);
    }

    public static void disable() {
        REGISTERED.clear();
    }

    public static void onQuit(final Player player) {
        REGISTERED.remove(player.getUniqueId());
        ServuxReassembler.forget(player.getUniqueId());
    }

    public static int registeredCount() {
        return REGISTERED.size();
    }

    @Override
    public void onPluginMessageReceived(final @NotNull String channel,
                                        final @NotNull Player player,
                                        final byte @NotNull [] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try {
            final int type = ServuxWire.readType(message);
            if (DEBUG) {
                plugin.getLogger().info("Servux litematics: in type " + type
                    + " from " + player.getName() + ", " + message.length + " bytes");
            }
            switch (type) {
                case C2S_METADATA_REQUEST -> onRegister(player);
                case C2S_UNREGISTER_REPLY -> {
                    REGISTERED.remove(player.getUniqueId());
                    ServuxReassembler.forget(player.getUniqueId());
                }
                case C2S_NBT_RESPONSE_START, C2S_NBT_RESPONSE_DATA -> onSlice(player, message);
                default -> {
                }
            }
        } catch (final Throwable t) {
            ServuxReassembler.forget(player.getUniqueId());
            plugin.getLogger().warning("Servux litematics: bad packet from "
                + player.getName() + ": " + t);
        }
    }

    private void onRegister(final Player player) {
        if (!player.hasPermission(LabPermissions.SERVUX_LITEMATICS)) {
            if (DEBUG) {
                plugin.getLogger().info("Servux litematics: " + player.getName()
                    + " has no " + LabPermissions.SERVUX_LITEMATICS);
            }
            return;
        }
        REGISTERED.add(player.getUniqueId());

        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "litematics");
        tag.putString("id", CHANNEL);
        tag.putInt("version", PROTOCOL_VERSION);
        tag.putString("servux", ServuxHud.versionString());

        send(player, ServuxWire.metadata(S2C_METADATA, tag));
        if (DEBUG) {
            plugin.getLogger().info("Servux litematics: metadata → " + player.getName());
        }
    }

    /** A chunk of a split body. The full body is reassembled and parsed once. */
    private void onSlice(final Player player, final byte[] message) throws java.io.IOException {
        if (!REGISTERED.contains(player.getUniqueId())) {
            return;
        }
        final byte[] body = ServuxReassembler.accept(player.getUniqueId(), CHANNEL, message);
        if (body == null) {
            return;
        }
        final CompoundTag request = ServuxReassembler.toNbt(body, "litematics paste");
        if (DEBUG) {
            plugin.getLogger().info("Servux litematics: body parsed as "
                + ServuxWire.lastVariant() + ", " + body.length + " bytes");
        }
        final String task = request.getStringOr("Task", "");
        if (!"LitematicaPaste".equals(task)) {
            plugin.getLogger().info("Servux litematics: unsupported task '" + task
                + "' from " + player.getName());
            return;
        }
        describe(player, request, body.length);
        place(player, request);
    }

    /**
     * Parse and report to the log, without writing to the world.
     *
     * <p>Deliberate. A paste changes the world irreversibly, and the schematic format was
     * recovered from someone else's code; the parse has to be seen matching expectations on a
     * real schematic before any block is placed.
     */
    private void describe(final Player player, final CompoundTag request, final int size) {
        final CompoundTag schematics = request.getCompoundOrEmpty("Schematics");
        final CompoundTag regions = schematics.getCompoundOrEmpty("Regions");

        final StringBuilder out = new StringBuilder();
        out.append("Servux litematics: paste from ").append(player.getName())
            .append(", ").append(size).append(" bytes, name='")
            .append(request.getStringOr("Name", "?")).append('\'')
            .append(", rotation=").append(request.getIntOr("Rotation", 0))
            .append(", mirror=").append(request.getIntOr("Mirror", 0))
            .append(", replace=").append(request.getStringOr("ReplaceMode", "?"))
            .append(", regions=").append(regions.size());

        for (final String name : regions.keySet()) {
            final CompoundTag region = regions.getCompoundOrEmpty(name);
            final CompoundTag pos = region.getCompoundOrEmpty("Position");
            final CompoundTag box = region.getCompoundOrEmpty("Size");
            final int palette = region.getListOrEmpty("BlockStatePalette").size();
            final int longs = region.getLongArray("BlockStates").map(a -> a.length).orElse(0);
            out.append("\n  region '").append(name).append("' at ")
                .append(pos.getIntOr("x", 0)).append(' ')
                .append(pos.getIntOr("y", 0)).append(' ')
                .append(pos.getIntOr("z", 0))
                .append(" size ")
                .append(box.getIntOr("x", 0)).append('x')
                .append(box.getIntOr("y", 0)).append('x')
                .append(box.getIntOr("z", 0))
                .append(", palette ").append(palette)
                .append(", longs ").append(longs)
                .append(", tile entities ").append(region.getListOrEmpty("TileEntities").size())
                .append(", entities ").append(region.getListOrEmpty("Entities").size());
        }

        plugin.getLogger().info(out.toString());
        player.sendMessage(net.kyori.adventure.text.Component.text(
            "PaperLab: paste request received (" + regions.size()
                + " regions, " + size + " bytes)",
            net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }

    /**
     * The paste itself.
     *
     * <p><b>Creative mode is required.</b> Servux does the same, for the same reason: the
     * request comes from a client and is unbounded in size, so the channel permission is backed
     * by the game mode as well — a paste pressed by accident in survival must not rewrite the
     * neighbourhood.
     */
    private void place(final Player player, final CompoundTag request) {
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                "PaperLab: schematic paste requires creative mode",
                net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }

        final long started = System.currentTimeMillis();
        final LitematicaPaste.Result result;
        try {
            result = LitematicaPaste.paste(
                ((org.bukkit.craftbukkit.CraftWorld) player.getWorld()).getHandle(), request);
        } catch (final Throwable t) {
            plugin.getLogger().warning("Servux litematics: paste failed for "
                + player.getName() + ": " + t);
            player.sendMessage(net.kyori.adventure.text.Component.text(
                "PaperLab: paste failed, see server log",
                net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }

        final long elapsed = System.currentTimeMillis() - started;
        plugin.getLogger().info("Servux litematics: placed " + result.placed()
            + " blocks, " + result.entities() + " entities, skipped " + result.skipped()
            + ", in " + elapsed + " ms for " + player.getName());
        player.sendMessage(net.kyori.adventure.text.Component.text(
            "PaperLab: pasted " + result.placed() + " blocks"
                + (result.entities() > 0 ? " and " + result.entities() + " entities" : "")
                + " in " + elapsed + " ms",
            net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }

    /** Sending that bypasses {@code sendPluginMessage}, for the reason given in {@link ServuxHud}. */
    private static void send(final Player player, final byte[] body) {
        final var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null) {
            connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(Identifier.parse(CHANNEL), body)));
        }
    }
}
