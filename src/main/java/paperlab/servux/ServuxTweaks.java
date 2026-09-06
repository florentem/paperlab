package paperlab.servux;

import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import paperlab.command.LabPermissions;

/**
 * The {@code servux:tweaks} channel — inventory preview for the Tweakeroo mod.
 *
 * <p>Lets a client with Tweakeroo query the contents of shulkers, chests and mobs at range, under
 * the crosshair or the cursor.
 *
 * <p>Safeguards:
 * <ul>
 *   <li>a 128-block distance limit (as in {@link ServuxEntities});</li>
 *   <li>reading another player's NBT requires {@link LabPermissions#SERVUX_ENTITIES_PLAYERS};</li>
 *   <li>loaded chunks only.</li>
 * </ul>
 */
public final class ServuxTweaks implements PluginMessageListener {

    public static final String CHANNEL = "servux:tweaks";
    public static final int PROTOCOL_VERSION = 2;

    private static final int S2C_METADATA = 1;
    private static final int C2S_METADATA_REQUEST = 2;
    private static final int C2S_BLOCK_ENTITY_REQUEST = 3;
    private static final int C2S_ENTITY_REQUEST = 4;
    private static final int S2C_BLOCK_NBT_RESPONSE = 5;
    private static final int S2C_ENTITY_NBT_RESPONSE = 6;
    private static final int C2S_UNREGISTER_REPLY = 7;

    private static final double MAX_DISTANCE = 128.0D;
    private static final boolean DEBUG = Boolean.getBoolean("paperlab.servux.debug");

    private static final Set<UUID> REGISTERED = ConcurrentHashMap.newKeySet();
    private static Plugin plugin;

    public static void enable(final Plugin owner) {
        plugin = owner;
        Bukkit.getMessenger().registerIncomingPluginChannel(owner, CHANNEL, new ServuxTweaks());
        Bukkit.getMessenger().registerOutgoingPluginChannel(owner, CHANNEL);
    }

    public static void disable() {
        REGISTERED.clear();
    }

    public static void onQuit(final Player player) {
        REGISTERED.remove(player.getUniqueId());
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
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(message));
            final int type = buf.readVarInt();
            if (DEBUG) {
                plugin.getLogger().info("Servux tweaks: in type " + type
                    + " from " + player.getName());
            }
            switch (type) {
                case C2S_METADATA_REQUEST -> onRegister(player);
                case C2S_UNREGISTER_REPLY -> REGISTERED.remove(player.getUniqueId());
                case C2S_BLOCK_ENTITY_REQUEST -> {
                    if (buf.readableBytes() > 8) {
                        buf.readVarInt();
                    }
                    onBlockRequest(player, buf.readBlockPos());
                }
                case C2S_ENTITY_REQUEST -> {
                    final int first = buf.readVarInt();
                    final int entityId = buf.isReadable() ? buf.readVarInt() : first;
                    onEntityRequest(player, entityId);
                }
                default -> {
                }
            }
        } catch (final Throwable t) {
            plugin.getLogger().warning("Servux tweaks: bad packet from "
                + player.getName() + ": " + t);
        }
    }

    private void onRegister(final Player player) {
        if (!player.hasPermission(LabPermissions.SERVUX_TWEAKS)) {
            if (DEBUG) {
                plugin.getLogger().info("Servux tweaks: " + player.getName()
                    + " has no " + LabPermissions.SERVUX_TWEAKS);
            }
            return;
        }
        REGISTERED.add(player.getUniqueId());

        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "tweaks_data");
        tag.putString("id", CHANNEL);
        tag.putInt("version", PROTOCOL_VERSION);
        tag.putString("servux", ServuxHud.versionString());

        send(player, ServuxWire.metadata(S2C_METADATA, tag));
        if (DEBUG) {
            plugin.getLogger().info("Servux tweaks: metadata → " + player.getName());
        }
    }

    private void onBlockRequest(final Player player, final BlockPos pos) {
        if (!allowed(player)) {
            return;
        }
        final ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
        if (!withinReach(player, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)) {
            return;
        }
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return;
        }
        final BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) {
            return;
        }
        final CompoundTag data = ServuxEntities.capture(level, entity.problemPath(),
            output -> entity.saveWithId(output));

        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(S2C_BLOCK_NBT_RESPONSE);
        buf.writeBlockPos(pos);
        ServuxWire.appendNbtBody(buf, data);
        send(player, drain(buf));
    }

    private void onEntityRequest(final Player player, final int entityId) {
        if (!allowed(player)) {
            return;
        }
        final ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
        final Entity entity = level.getEntity(entityId);
        if (entity == null || !withinReach(player, entity.getX(), entity.getY(), entity.getZ())) {
            return;
        }
        if (entity instanceof ServerPlayer targetPlayer
            && !targetPlayer.getUUID().equals(player.getUniqueId())
            && !player.hasPermission(LabPermissions.SERVUX_ENTITIES_PLAYERS)) {
            return;
        }
        final CompoundTag data = ServuxEntities.capture(level, entity.problemPath(),
            output -> entity.saveWithoutId(output));
        final var typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (typeKey != null) {
            data.putString("id", typeKey.toString());
        }

        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(S2C_ENTITY_NBT_RESPONSE);
        buf.writeVarInt(entityId);
        ServuxWire.appendNbtBody(buf, data);
        send(player, drain(buf));
    }

    private static boolean allowed(final Player player) {
        return REGISTERED.contains(player.getUniqueId())
            && player.hasPermission(LabPermissions.SERVUX_TWEAKS);
    }

    private static boolean withinReach(final Player player, final double x, final double y, final double z) {
        final var location = player.getLocation();
        final double dx = location.getX() - x;
        final double dy = location.getY() - y;
        final double dz = location.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= MAX_DISTANCE * MAX_DISTANCE;
    }

    private static byte[] drain(final FriendlyByteBuf buf) {
        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    private static void send(final Player player, final byte[] body) {
        final var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null) {
            connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(Identifier.parse(CHANNEL), body)));
        }
    }
}
