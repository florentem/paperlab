package paperlab.chunkmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import paperlab.command.LabPermissions;
import org.jetbrains.annotations.NotNull;

/**
 * Server side of the ChunkDebug protocol, for the existing client mod.
 *
 * <h2>Handshake</h2>
 * The <b>server</b> starts it: after a player joins, the server sends {@code hello} with
 * the protocol version, and only on receiving it does the mod enable the map. The client
 * never sends {@code hello} — in the mod that channel is registered as clientbound only.
 *
 * <p>That was the cause of "ChunkDebug is unavailable": the first version waited for a
 * client {@code hello} to reply to, and therefore never replied. Channel announcement via
 * {@code minecraft:register} has nothing to do with it — Bukkit does that itself.
 *
 * <p>Sending is deferred by a tick after the join: LuckPerms loads permissions in the same
 * event, and we need the permission before sending. The mod itself does the same.
 *
 * <h2>Deltas, not full snapshots</h2>
 * The first version sent a full snapshot once a second. That was not only expensive — over
 * two thousand chunks per second in the nether — but <b>wrong</b>: the client's
 * {@code updateChunks} only appends to its map and removes nothing. An unloaded chunk stayed
 * on the map forever, until a {@code chunk_unload} arrived.
 *
 * <p>So we keep the last state sent to each watcher and send only the difference: changed
 * chunks in {@code chunk_data}, vanished ones in {@code chunk_unload}. A full snapshot goes
 * out once, on subscription.
 */
public final class ChunkMapService implements PluginMessageListener {

    private static final int PERIOD_TICKS = 20;
    private static int tickCounter;

    private static final Map<UUID, Set<ResourceKey<Level>>> WATCHERS = new HashMap<>();

    /**
     * What has already been sent to a watcher: dimension -&gt; packed chunk position -&gt;
     * its state. Used to compute the difference; cleared along with the subscription.
     */
    private static final Map<UUID, Map<ResourceKey<Level>, Map<Long, ChunkMapProtocol.ChunkInfo>>> SENT =
        new HashMap<>();

    private static Plugin plugin;

    public static void enable(final Plugin owner) {
        plugin = owner;
        final ChunkMapService service = new ChunkMapService();
        for (final String channel : ChunkMapWire.INCOMING) {
            Bukkit.getMessenger().registerIncomingPluginChannel(owner, channel, service);
        }
        for (final String channel : ChunkMapWire.OUTGOING) {
            Bukkit.getMessenger().registerOutgoingPluginChannel(owner, channel);
        }
    }

    /**
     * Begin the handshake with a joining player.
     *
     * <p>A tick after the join: we read the permission once LuckPerms has had time to load it.
     */
    public static void onJoin(final Player player) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (player.isOnline() && player.hasPermission(LabPermissions.CHUNKMAP)) {
                sendHello(player);
            }
        }, 1L);
    }

    /** Repeat the handshake — after granting permissions, say, or if the map never opened. */
    public static void sendHello(final Player player) {
        send(player, ChunkMapWire.HELLO, ChunkMapWire.encodeHello(ChunkMapProtocol.PROTOCOL_VERSION));
        // The diagnostic is deferred: the list of announced channels arrives from the client
        // at its own pace and is still empty right after the join. This does not affect
        // sending — hello has already gone out — but without this line "the map does not
        // work" is indistinguishable from "the mod is not installed".
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline()) {
                return;
            }
            final boolean announced = player.getListeningPluginChannels()
                .contains(ChunkMapWire.HELLO);
            plugin.getLogger().info("ChunkDebug: hello → " + player.getName()
                + (announced ? " (mod present)" : " (channel not announced, mod likely missing)"));
        }, 60L);
    }

    /** Revoke access: the mod will blank the map. */
    public static void sendBye(final Player player) {
        WATCHERS.remove(player.getUniqueId());
        SENT.remove(player.getUniqueId());
        send(player, ChunkMapWire.BYE, new byte[0]);
    }

    public static void disable() {
        if (plugin != null) {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin);
        }
        WATCHERS.clear();
        SENT.clear();
    }

    @Override
    public void onPluginMessageReceived(final @NotNull String channel,
                                        final @NotNull Player player,
                                        final byte @NotNull [] message) {
        try {
            if (DEBUG) {
                plugin.getLogger().info("ChunkDebug: in " + channel + " from " + player.getName()
                    + ", " + message.length + " bytes");
            }
            if (ChunkMapWire.CHUNK_REFRESH.equals(channel)) {
                onRefresh(player);
            } else if (ChunkMapWire.START_WATCHING.equals(channel)) {
                onStartWatching(player, ChunkMapWire.decodeDimensions(message));
            } else if (ChunkMapWire.STOP_WATCHING.equals(channel)) {
                onStopWatching(player, ChunkMapWire.decodeDimensions(message));
            }
        } catch (final Throwable t) {
            // A corrupt body from a client must not bring down packet handling.
            plugin.getLogger().warning("ChunkDebug: malformed packet " + channel
                + " from " + player.getName() + ": " + t);
        }
    }

    /** The client pressed refresh: send a full snapshot for all of its dimensions. */
    private void onRefresh(final Player player) {
        final Set<ResourceKey<Level>> dims = WATCHERS.get(player.getUniqueId());
        if (dims == null) {
            return;
        }
        for (final ResourceKey<Level> dimension : dims) {
            final ServerLevel level = levelOf(dimension);
            if (level != null) {
                sendFull(player, level);
            }
        }
    }

    private void onStartWatching(final Player player, final List<ResourceKey<Level>> dimensions) {
        for (final ResourceKey<Level> dimension : dimensions) {
            final ServerLevel level = levelOf(dimension);
            if (level == null) {
                plugin.getLogger().warning("ChunkDebug: " + player.getName()
                    + " requested unknown dimension " + dimension.identifier());
                continue;
            }
            WATCHERS.computeIfAbsent(player.getUniqueId(), key -> new HashSet<>()).add(dimension);
            sendFull(player, level);
        }
    }

    /** An empty list means "stop everything" — that is how the protocol intends it. */
    private void onStopWatching(final Player player, final List<ResourceKey<Level>> dimensions) {
        if (dimensions.isEmpty()) {
            WATCHERS.remove(player.getUniqueId());
            SENT.remove(player.getUniqueId());
            return;
        }
        final Set<ResourceKey<Level>> dims = WATCHERS.get(player.getUniqueId());
        if (dims != null) {
            dimensions.forEach(dims::remove);
            if (dims.isEmpty()) {
                WATCHERS.remove(player.getUniqueId());
            }
        }
        final var sent = SENT.get(player.getUniqueId());
        if (sent != null) {
            dimensions.forEach(sent::remove);
        }
    }

    public static void onDisconnect(final Player player) {
        WATCHERS.remove(player.getUniqueId());
        SENT.remove(player.getUniqueId());
    }

    public static void tick() {
        if (WATCHERS.isEmpty() || ++tickCounter % PERIOD_TICKS != 0) {
            return;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final Set<ResourceKey<Level>> dims = WATCHERS.get(player.getUniqueId());
            if (dims == null) {
                continue;
            }
            for (final ResourceKey<Level> dimension : dims) {
                final ServerLevel level = levelOf(dimension);
                if (level != null) {
                    sendDelta(player, level);
                }
            }
        }
    }

    /** Full snapshot: on subscription and on the refresh button. */
    private static void sendFull(final Player player, final ServerLevel level) {
        final List<ChunkMapProtocol.ChunkInfo> chunks = ChunkMapTracker.snapshot(level, true);
        send(player, ChunkMapWire.CHUNK_DATA, ChunkMapWire.encodeChunkData(
            level.dimension(), chunks, (int) level.getGameTime(), true));
        remember(player, level, chunks);
        if (DEBUG) {
            plugin.getLogger().info("ChunkDebug: full " + chunks.size() + " chunks to "
                + player.getName() + " in " + level.dimension().identifier().getPath());
        }
    }

    /** The difference from last time: what changed and what vanished. */
    private static void sendDelta(final Player player, final ServerLevel level) {
        final Map<Long, ChunkMapProtocol.ChunkInfo> previous = SENT
            .computeIfAbsent(player.getUniqueId(), key -> new HashMap<>())
            .computeIfAbsent(level.dimension(), key -> new HashMap<>());

        final List<ChunkMapProtocol.ChunkInfo> chunks = ChunkMapTracker.snapshot(level, true);
        final Map<Long, ChunkMapProtocol.ChunkInfo> current = new HashMap<>(chunks.size());
        final List<ChunkMapProtocol.ChunkInfo> changed = new ArrayList<>();

        for (final ChunkMapProtocol.ChunkInfo info : chunks) {
            final long key = info.position().pack();
            current.put(key, info);
            if (!same(info, previous.get(key))) {
                changed.add(info);
            }
        }

        final List<Long> gone = new ArrayList<>();
        for (final Long key : previous.keySet()) {
            if (!current.containsKey(key)) {
                gone.add(key);
            }
        }

        if (!changed.isEmpty()) {
            send(player, ChunkMapWire.CHUNK_DATA, ChunkMapWire.encodeChunkData(
                level.dimension(), changed, (int) level.getGameTime(), false));
        }
        if (!gone.isEmpty()) {
            final long[] positions = new long[gone.size()];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = gone.get(i);
            }
            send(player, ChunkMapWire.CHUNK_UNLOAD,
                ChunkMapWire.encodeChunkUnload(level.dimension(), positions));
        }

        SENT.get(player.getUniqueId()).put(level.dimension(), current);

        if (DEBUG && (!changed.isEmpty() || !gone.isEmpty())) {
            plugin.getLogger().info("ChunkDebug: delta +" + changed.size() + " -" + gone.size()
                + " to " + player.getName() + " in " + level.dimension().identifier().getPath());
        }
    }

    /**
     * Whether a chunk counts as changed.
     *
     * <p>A ticket's countdown (<i>ticksLeft</i>) is deliberately <b>excluded</b>. For delayed
     * tickets it decrements every tick, and comparing "everything" put a thousand chunks a
     * second into the delta — at which point the delta stopped being a delta. The reference
     * server does the same: its tracker marks a chunk dirty on a status or ticket-set change,
     * not on a counter tick.
     *
     * <p>The price: the countdown shown on the map refreshes not every second but whenever
     * something else about the chunk changes. That is immaterial for reading the map, and the
     * traffic difference is two orders of magnitude.
     */
    private static boolean same(final ChunkMapProtocol.ChunkInfo now,
                                final ChunkMapProtocol.ChunkInfo before) {
        if (before == null) {
            return false;
        }
        if (now.statusLevel() != before.statusLevel()
            || now.tickingStatusLevel() != before.tickingStatusLevel()
            || now.unloading() != before.unloading()
            || !java.util.Objects.equals(now.stage(), before.stage())
            || now.tickets().size() != before.tickets().size()) {
            return false;
        }
        for (int i = 0; i < now.tickets().size(); i++) {
            final var a = now.tickets().get(i);
            final var b = before.tickets().get(i);
            if (!a.type().equals(b.type()) || a.level() != b.level()) {
                return false;
            }
        }
        return true;
    }

    private static void remember(final Player player, final ServerLevel level,
                                 final List<ChunkMapProtocol.ChunkInfo> chunks) {
        final Map<Long, ChunkMapProtocol.ChunkInfo> map = new HashMap<>(chunks.size());
        for (final ChunkMapProtocol.ChunkInfo info : chunks) {
            map.put(info.position().pack(), info);
        }
        SENT.computeIfAbsent(player.getUniqueId(), key -> new HashMap<>())
            .put(level.dimension(), map);
    }

    /**
     * Verbose exchange log, enabled with the system property
     * {@code -Dpaperlab.chunkdebug.debug=true}: normally we stay quiet, but working out an
     * empty map is impossible without it — the client never reports errors at all.
     */
    private static final boolean DEBUG =
        Boolean.getBoolean("paperlab.chunkdebug.debug");

    private static @org.jetbrains.annotations.Nullable ServerLevel levelOf(final ResourceKey<Level> dimension) {
        for (final org.bukkit.World world : Bukkit.getWorlds()) {
            final ServerLevel level = ((CraftWorld) world).getHandle();
            if (level.dimension().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Sending that bypasses {@code Player#sendPluginMessage}.
     *
     * <p>Bukkit's normal path silently <b>does nothing</b> if the client has not yet announced
     * the channel via {@code minecraft:register} ({@code CraftPlayer.sendPluginMessage}, the
     * {@code channels().contains(channel)} check). The ChunkDebug mod announces at its own
     * pace, and a handshake one tick after the join does not fit inside that check — the
     * first {@code hello} simply vanished.
     *
     * <p>The mod itself sends and receives with no registration checks, so we put the packet
     * straight onto the connection. Paper keeps the body of an unknown channel as-is
     * ({@code DiscardedPayload}); no codecs need registering.
     */
    private static void send(final Player player, final String channel, final byte[] body) {
        final var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection == null) {
            return;
        }
        connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
            new net.minecraft.network.protocol.common.custom.DiscardedPayload(
                net.minecraft.resources.Identifier.parse(channel), body)));
    }

    public static int watcherCount() {
        return WATCHERS.size();
    }
}
