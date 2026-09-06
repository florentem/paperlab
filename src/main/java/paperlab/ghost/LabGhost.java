package paperlab.ghost;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import paperlab.core.CoreBridge;

/**
 * Observer mode: the player stops affecting the simulation but keeps interacting with the
 * world.
 *
 * <p>The point is to fly along a chunk border and take a farm apart while a bot keeps it
 * running, without distorting what you are measuring.
 *
 * <p><b>This is not spectator.</b> Blocks place and break, containers open, the inventory
 * works. Only the effect on the server simulation is off.
 *
 * <h2>Full mode — on our core</h2>
 * Six exclusion sites, three of which have no API at all:
 * <ol>
 *   <li>the mobcap census {@code ChunkMap.updatePlayerMobTypeMap} and backoff accrual
 *       {@code updateFailurePlayerMobTypeMap} — the observer takes no cap;</li>
 *   <li>{@code NaturalSpawner.spawnForChunk} — does not eat a neighbour's chunk budget;</li>
 *   <li>{@code ChunkMap.isChunkNearPlayer} — does not widen the spawn area;</li>
 *   <li>{@code ChunkMap.skipPlayer} — takes no part in chunk loading;</li>
 *   <li>{@code ActivationRange.activateEntities} — does not wake mobs (EAR);</li>
 *   <li>{@code LivingEntity.canBeSeenByAnyone} — mobs do not target them.</li>
 * </ol>
 * Plus two stock mechanisms that need no patch: {@code affectsSpawning} (despawning, spawn
 * position choice, trial spawner) and the personal simulation distance.
 *
 * <h2>Reduced mode — on stock Paper</h2>
 * Only {@code affectsSpawning}, the simulation distance and invisibility remain. The observer
 * <b>still takes mobcap, still wakes mobs through EAR and is still noticed by them</b>.
 * Flying past a running farm in this mode still distorts the measurement.
 *
 * <h2>What matters when testing</h2>
 * Turning it on is <b>not instant</b>. {@code sim=0} applies immediately, but Moonrise
 * releases already-issued ticking tickets lazily and rate-limited — on the bench it settles
 * from 121 chunks to 1 in about 30 seconds. Turning it off, by contrast, takes seconds.
 * Forcibly re-registering the player in the loader does not remove the delay, so there is no
 * extra logic here.
 *
 * <p>Moonrise does not support a true zero: a negative value means "inherit the world's", so
 * one ticking chunk remains under the observer. A known limitation, not an oversight.
 *
 * <p>The state is held in memory and cleared on restart: this is a debugging mode, not a
 * property of the player.
 */
public final class LabGhost {

    /** Used in reduced mode only: with the core present, the core is the source of truth. */
    private static final Set<UUID> FALLBACK = new HashSet<>();

    private LabGhost() {
    }

    private static ServerPlayer nms(final Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    public static boolean isGhost(final Player player) {
        return CoreBridge.PRESENT
            ? Core.isGhost(nms(player))
            : FALLBACK.contains(player.getUniqueId());
    }

    public static boolean toggle(final Player player) {
        return set(player, !isGhost(player));
    }

    public static boolean set(final Player player, final boolean ghost) {
        applyVisibility(player, ghost);
        if (CoreBridge.PRESENT) {
            return Core.set(nms(player), ghost);
        }
        if (ghost) {
            FALLBACK.add(player.getUniqueId());
        } else {
            FALLBACK.remove(player.getUniqueId());
        }
        player.setAffectsSpawning(!ghost);
        player.setSimulationDistance(ghost ? 0 : player.getWorld().getSimulationDistance());
        player.setInvisible(ghost);
        return ghost;
    }

    public static void onDisconnect(final Player player) {
        if (CoreBridge.PRESENT) {
            // The core clears the mode itself in PlayerList.remove; nothing to do here.
            return;
        }
        if (FALLBACK.remove(player.getUniqueId())) {
            player.setAffectsSpawning(true);
            player.setInvisible(false);
            if (player.getWorld() != null) {
                player.setSimulationDistance(player.getWorld().getSimulationDistance());
            }
        }
    }

    /**
     * Remove the observer from other people's screens entirely: not just the model, but the
     * tab list and entity tracking too.
     *
     * <p>{@code setInvisible} alone is not enough — an invisible model still stays in the
     * player list and the tracker, visible through the name tag and the tab-list row. An
     * observer should not be visible at all: they are an instrument, not a participant.
     */
    private static void applyVisibility(final Player player, final boolean ghost) {
        final var plugin = paperlab.PaperLabPlugin.get();
        if (plugin == null) {
            return;
        }
        for (final Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player)) {
                continue;
            }
            if (ghost) {
                other.hidePlayer(plugin, player);
            } else {
                other.showPlayer(plugin, player);
            }
        }
    }

    /** A joining player must not see those already in observer mode. */
    public static void hideGhostsFrom(final Player viewer) {
        final var plugin = paperlab.PaperLabPlugin.get();
        if (plugin == null) {
            return;
        }
        for (final Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(viewer) && isGhost(other)) {
                viewer.hidePlayer(plugin, other);
            }
        }
    }

    /** On plugin disable, return everyone to the normal state. */
    public static void restoreAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (isGhost(player)) {
                set(player, false);
            }
        }
        FALLBACK.clear();
    }

    public static int count() {
        return CoreBridge.PRESENT ? Core.count() : FALLBACK.size();
    }

    /** Whether the mode is full. Shown in hints so the numbers are not read as exact. */
    public static boolean full() {
        return CoreBridge.PRESENT;
    }

    /**
     * Delegate to the core. A separate class: on stock Paper it is never loaded, so the
     * absence of core classes causes no resolution error.
     */
    private static final class Core {

        static boolean isGhost(final ServerPlayer player) {
            return io.papermc.paper.lab.ghost.LabGhost.isGhost(player);
        }

        static boolean set(final ServerPlayer player, final boolean ghost) {
            return io.papermc.paper.lab.ghost.LabGhost.set(player, ghost);
        }

        static int count() {
            return io.papermc.paper.lab.ghost.LabGhost.count();
        }
    }
}
