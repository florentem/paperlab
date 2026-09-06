package paperlab.counter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.minecraft.world.item.DyeColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

/**
 * One counter: a wool colour in one world.
 *
 * <p>Differences from the Leaves reference, each fixing a defect found there:
 * <ul>
 *   <li>the item key includes metadata, so enchanted and named items do not merge with plain
 *       ones;</li>
 *   <li>time is measured in the world's game ticks and in <b>monotonic</b> nanoseconds — moving
 *       the system clock does not corrupt the rate;</li>
 *   <li>with a zero interval the rate is {@code null} rather than infinity.</li>
 * </ul>
 */
public final class LabCounter {

    /** An item together with its metadata: two stacks with different meta count separately. */
    private record ItemKey(Material material, @Nullable ItemMeta meta) {
    }

    private static final long TICKS_PER_HOUR = 20L * 60L * 60L;

    private final DyeColor colour;
    private final String world;

    private final Map<ItemKey, Long> counts = new LinkedHashMap<>();
    private long total;

    /** {@code -1} means the counter has not started counting yet. */
    private long startTick = -1L;
    private long startNanos;

    LabCounter(final DyeColor colour, final String world) {
        this.colour = colour;
        this.world = world;
    }

    public DyeColor colour() {
        return this.colour;
    }

    public String world() {
        return this.world;
    }

    public long total() {
        return this.total;
    }

    public boolean started() {
        return this.startTick >= 0L;
    }

    void add(final ItemStack stack, final long gameTime) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        if (this.startTick < 0L) {
            this.startTick = gameTime;
            this.startNanos = System.nanoTime();
        }
        final ItemKey key = new ItemKey(stack.getType(),
            stack.hasItemMeta() ? stack.getItemMeta() : null);
        this.counts.merge(key, (long) stack.getAmount(), Long::sum);
        this.total += stack.getAmount();
    }

    public void reset(final long gameTime) {
        this.counts.clear();
        this.total = 0L;
        this.startTick = gameTime;
        this.startNanos = System.nanoTime();
    }

    public long elapsedTicks(final long gameTime) {
        return this.startTick < 0L ? 0L : Math.max(0L, gameTime - this.startTick);
    }

    /** Real seconds from the monotonic clock — for cross-checking against game time. */
    public double elapsedRealSeconds() {
        return this.startTick < 0L ? 0.0D : (System.nanoTime() - this.startNanos) / 1_000_000_000.0D;
    }

    /**
     * Items per hour by game time.
     *
     * @return {@code null} if the interval is zero — there is nothing to divide by, and inventing
     *         a number is not an option
     */
    public @Nullable Double perHour(final long gameTime) {
        final long ticks = this.elapsedTicks(gameTime);
        if (ticks <= 0L) {
            return null;
        }
        return this.total * (double) TICKS_PER_HOUR / ticks;
    }

    /** Per-item breakdown, by descending count. */
    public List<Entry> entries() {
        final List<Entry> out = new ArrayList<>(this.counts.size());
        this.counts.forEach((key, count) -> {
            final ItemStack sample = new ItemStack(key.material());
            out.add(new Entry(sample.displayName().hoverEvent(null), count));
        });
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }

    public record Entry(Component name, long count) {
    }
}
