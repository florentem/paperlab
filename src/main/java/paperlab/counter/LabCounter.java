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
 * Один счётчик: цвет шерсти в одном мире.
 *
 * <p>Отличия от донора Leaves, каждое — исправление найденного там дефекта:
 * <ul>
 *   <li>ключ предмета включает мету, поэтому зачарованные и именованные предметы
 *       не сливаются с обычными;</li>
 *   <li>время считается по игровым тикам мира и по <b>монотонным</b> наносекундам —
 *       перевод системных часов не портит рейт;</li>
 *   <li>при нулевом интервале рейт равен {@code null}, а не бесконечности.</li>
 * </ul>
 */
public final class LabCounter {

    /** Предмет вместе с метой: два стека с разной метой считаются раздельно. */
    private record ItemKey(Material material, @Nullable ItemMeta meta) {
    }

    private static final long TICKS_PER_HOUR = 20L * 60L * 60L;

    private final DyeColor colour;
    private final String world;

    private final Map<ItemKey, Long> counts = new LinkedHashMap<>();
    private long total;

    /** {@code -1} — счётчик ещё не начинал считать. */
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

    /** Реальные секунды по монотонным часам — для сверки с игровым временем. */
    public double elapsedRealSeconds() {
        return this.startTick < 0L ? 0.0D : (System.nanoTime() - this.startNanos) / 1_000_000_000.0D;
    }

    /**
     * Предметов в час по игровому времени.
     *
     * @return {@code null}, если интервал нулевой — делить нельзя, и выдумывать число нельзя
     */
    public @Nullable Double perHour(final long gameTime) {
        final long ticks = this.elapsedTicks(gameTime);
        if (ticks <= 0L) {
            return null;
        }
        return this.total * (double) TICKS_PER_HOUR / ticks;
    }

    /** Разбивка по предметам, по убыванию количества. */
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
