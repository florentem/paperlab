package paperlab.spawn;

import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Counters of spawns and cancellations, by spawn reason.
 *
 * <p><b>This is the reduced version of the spawn trace.</b> In the fork it distinguished where
 * exactly an attempt stopped: hit the cap, position rejected, cancelled by a plugin, succeeded.
 * The engine publishes none of the first two outwards — they are visible only inside
 * {@code NaturalSpawner}, where a plugin cannot reach.
 *
 * <p>What remains available through Bukkit:
 * <ul>
 *   <li>{@code CreatureSpawnEvent} — a spawn happened;</li>
 *   <li>{@code PreCreatureSpawnEvent} with {@code isCancelled()} — a spawn was cancelled by a
 *       handler. That is exactly what is needed to catch someone else's limiter.</li>
 * </ul>
 *
 * <p>What these numbers <b>cannot</b> tell you: whether the farm is limited by the mobcap or by
 * unsuitable positions. That needs access to the core.
 *
 * <p>The listeners sit on {@code MONITOR} and change nothing: a counter must not influence other
 * handlers' decisions.
 */
public final class SpawnCounters implements Listener {

    private record Key(String world, String reason) {
    }

    private static final Map<Key, LongAdder> SPAWNED = new ConcurrentHashMap<>();
    private static final Map<Key, LongAdder> CANCELLED = new ConcurrentHashMap<>();

    /** Per-world totals: how many spawned and how many were cancelled. */
    public record Snapshot(long spawned, long cancelled) {
    }

    public static Snapshot snapshot(final World world, final String reasonFilter) {
        long spawned = 0L;
        long cancelled = 0L;
        final String filter = reasonFilter == null ? null : reasonFilter.toUpperCase(Locale.ROOT);

        for (final Map.Entry<Key, LongAdder> entry : SPAWNED.entrySet()) {
            if (matches(entry.getKey(), world, filter)) {
                spawned += entry.getValue().sum();
            }
        }
        for (final Map.Entry<Key, LongAdder> entry : CANCELLED.entrySet()) {
            if (matches(entry.getKey(), world, filter)) {
                cancelled += entry.getValue().sum();
            }
        }
        return new Snapshot(spawned, cancelled);
    }

    private static boolean matches(final Key key, final World world, final String filter) {
        if (!key.world().equals(world.getName())) {
            return false;
        }
        return filter == null || key.reason().equals(filter);
    }

    public static void reset() {
        SPAWNED.clear();
        CANCELLED.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        SPAWNED.computeIfAbsent(
            new Key(event.getEntity().getWorld().getName(), event.getSpawnReason().name()),
            key -> new LongAdder()).increment();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreSpawn(final PreCreatureSpawnEvent event) {
        if (!event.isCancelled()) {
            return;
        }
        CANCELLED.computeIfAbsent(
            new Key(event.getSpawnLocation().getWorld().getName(), event.getReason().name()),
            key -> new LongAdder()).increment();
    }
}
