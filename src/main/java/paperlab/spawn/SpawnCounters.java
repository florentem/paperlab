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
 * Счётчики появлений и отмен по причинам спавна.
 *
 * <p><b>Это урезанная версия трассы спавна.</b> В форке она различала, где именно
 * остановилась попытка: упёрлась в кап, не подошла позиция, отменил плагин, получилось.
 * Первые две причины движок наружу не публикует — их видно только внутри
 * {@code NaturalSpawner}, куда плагин попасть не может.
 *
 * <p>Что осталось доступным через Bukkit:
 * <ul>
 *   <li>{@code CreatureSpawnEvent} — появление состоялось;</li>
 *   <li>{@code PreCreatureSpawnEvent} с {@code isCancelled()} — появление отменено
 *       обработчиком. Именно это и нужно, чтобы поймать чужой ограничитель.</li>
 * </ul>
 *
 * <p>Чего <b>нельзя</b> сделать из этих чисел: сказать, упирается ферма в мобкап или
 * в неподходящие позиции. Для этого нужен доступ в ядро.
 *
 * <p>Слушатели стоят на {@code MONITOR} и ничего не меняют: счётчик не должен влиять
 * на решения других обработчиков.
 */
public final class SpawnCounters implements Listener {

    private record Key(String world, String reason) {
    }

    private static final Map<Key, LongAdder> SPAWNED = new ConcurrentHashMap<>();
    private static final Map<Key, LongAdder> CANCELLED = new ConcurrentHashMap<>();

    /** Итог по миру: сколько появилось и сколько отменено. */
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
