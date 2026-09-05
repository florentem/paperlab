package paperlab.counter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.world.item.DyeColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Hopper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Счётчики воронок.
 *
 * <p><b>Механика отличается от версии в ядре, и это ограничение плагина.</b>
 * Ядро перехватывало {@code HopperBlockEntity.ejectItems} и забирало содержимое ровно
 * в тот момент, когда воронка пыталась выгрузиться. Из плагина такого перехвата нет,
 * поэтому воронки, направленные в шерсть, опустошаются задачей раз в тик.
 *
 * <p>Практические следствия:
 * <ul>
 *   <li>момент учёта может отличаться от «настоящего» на тик;</li>
 *   <li>воронка успевает заполниться, поэтому её собственный cooldown ведёт себя как
 *       у обычной полной воронки, а не как у мгновенно опустошаемой.</li>
 * </ul>
 * Для сравнения рейтов между прогонами это приемлемо, для субтиковых измерений — нет.
 *
 * <p>Список отслеживаемых воронок наполняется при установке блока и командой
 * {@code /counter scan}: обходить все загруженные чанки каждый тик слишком дорого.
 */
public final class LabCounters {

    private record Key(String world, DyeColor colour) {
    }

    public record BlockPosKey(String worldName, int x, int y, int z) {
        public static BlockPosKey of(final Block block) {
            return new BlockPosKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    private static final Map<Key, LabCounter> COUNTERS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Отслеживаемые воронки: местоположение → цвет шерсти, в которую она смотрит. */
    private static final Map<BlockPosKey, DyeColor> TRACKED = new java.util.concurrent.ConcurrentHashMap<>();

    private LabCounters() {
    }

    /** Проверка воронки: смотрит ли она в блок шерсти. */
    public static @Nullable DyeColor targetColour(final Block block) {
        if (!(block.getState() instanceof Hopper)) {
            return null;
        }
        if (!(block.getBlockData() instanceof final org.bukkit.block.data.type.Hopper data)) {
            return null;
        }
        final BlockFace facing = data.getFacing();
        final Block target = block.getRelative(facing);
        return WoolColors.byMaterial(target.getType());
    }

    /** Добавить воронку под наблюдение, если она смотрит в шерсть. */
    public static boolean track(final Block block) {
        final DyeColor colour = targetColour(block);
        final BlockPosKey key = BlockPosKey.of(block);
        if (colour == null) {
            TRACKED.remove(key);
            return false;
        }
        TRACKED.put(key, colour);
        return true;
    }

    /** Обойти окрестности игрока и найти воронки, смотрящие в шерсть. */
    public static int scan(final Location centre, final int radius) {
        int found = 0;
        final World world = centre.getWorld();
        final int cx = centre.getBlockX();
        final int cy = centre.getBlockY();
        final int cz = centre.getBlockZ();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = Math.max(world.getMinHeight(), cy - radius);
                 y <= Math.min(world.getMaxHeight() - 1, cy + radius); y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    if (track(world.getBlockAt(x, y, z))) {
                        found++;
                    }
                }
            }
        }
        return found;
    }

    /** Раз в тик: опустошаем отслеживаемые воронки и учитываем содержимое. */
    public static void tick() {
        if (TRACKED.isEmpty()) {
            return;
        }
        final Set<BlockPosKey> stale = new HashSet<>();
        for (final Map.Entry<BlockPosKey, DyeColor> entry : TRACKED.entrySet()) {
            final BlockPosKey posKey = entry.getKey();
            final World world = Bukkit.getWorld(posKey.worldName());
            if (world == null || !world.isChunkLoaded(posKey.x() >> 4, posKey.z() >> 4)) {
                continue;
            }
            final Block block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
            final DyeColor colour = targetColour(block);
            if (colour == null) {
                // Воронку сломали или развернули — снимаем с наблюдения.
                stale.add(posKey);
                continue;
            }
            if (!(block.getState() instanceof final Hopper hopper)) {
                stale.add(posKey);
                continue;
            }
            final LabCounter counter = of(world, colour);
            final long gameTime = world.getGameTime();
            boolean changed = false;
            final ItemStack[] contents = hopper.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                final ItemStack stack = contents[slot];
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }
                counter.add(stack, gameTime);
                hopper.getInventory().setItem(slot, null);
                changed = true;
            }
            if (changed) {
                hopper.update();
            }
        }
        stale.forEach(TRACKED::remove);
    }

    public static LabCounter of(final World world, final DyeColor colour) {
        return COUNTERS.computeIfAbsent(new Key(world.getName(), colour),
            key -> new LabCounter(key.colour(), key.world()));
    }

    public static @Nullable LabCounter existing(final World world, final DyeColor colour) {
        return COUNTERS.get(new Key(world.getName(), colour));
    }

    public static List<LabCounter> active() {
        final List<LabCounter> out = new ArrayList<>();
        for (final LabCounter counter : COUNTERS.values()) {
            if (counter.started()) {
                out.add(counter);
            }
        }
        return out;
    }

    public static int resetAll(final long gameTime) {
        int count = 0;
        for (final LabCounter counter : COUNTERS.values()) {
            if (counter.started()) {
                counter.reset(gameTime);
                count++;
            }
        }
        return count;
    }

    public static int trackedCount() {
        return TRACKED.size();
    }

    /** Одна строка: цвет, всего, рейт, время. */
    public static TextComponent summary(final LabCounter counter, final long gameTime,
                                        final boolean withWorld) {
        final Double perHour = counter.perHour(gameTime);
        final double minutes = counter.elapsedTicks(gameTime) / 1200.0D;
        TextComponent line = Component.text(counter.colour().getName(), chatColour(counter.colour()));
        if (withWorld) {
            line = line.append(Component.text("@" + counter.world(), NamedTextColor.DARK_GRAY));
        }
        return line
            .append(Component.text("  " + counter.total(), NamedTextColor.WHITE))
            .append(Component.text("  " + (perHour == null
                ? "N/A"
                : String.format(Locale.ROOT, "%.0f/h", perHour)), NamedTextColor.AQUA))
            .append(Component.text(String.format(Locale.ROOT, "  %.1fm", minutes), NamedTextColor.DARK_GRAY));
    }

    public static NamedTextColor chatColour(final DyeColor dye) {
        return switch (dye) {
            case WHITE -> NamedTextColor.WHITE;
            case ORANGE -> NamedTextColor.GOLD;
            case MAGENTA, PINK -> NamedTextColor.LIGHT_PURPLE;
            case LIGHT_BLUE -> NamedTextColor.AQUA;
            case YELLOW -> NamedTextColor.YELLOW;
            case LIME -> NamedTextColor.GREEN;
            case GRAY, BLACK -> NamedTextColor.DARK_GRAY;
            case LIGHT_GRAY -> NamedTextColor.GRAY;
            case CYAN -> NamedTextColor.DARK_AQUA;
            case PURPLE -> NamedTextColor.DARK_PURPLE;
            case BLUE -> NamedTextColor.BLUE;
            case BROWN -> NamedTextColor.DARK_RED;
            case GREEN -> NamedTextColor.DARK_GREEN;
            case RED -> NamedTextColor.RED;
        };
    }

    /** Ставим воронку или шерсть — сразу берём под наблюдение. */
    public static final class Listener implements org.bukkit.event.Listener {

        @EventHandler
        public void onPlace(final BlockPlaceEvent event) {
            final Block placed = event.getBlockPlaced();
            track(placed);
            // Шерсть могли поставить перед уже существующей воронкой.
            for (final BlockFace face : BlockFace.values()) {
                if (face.isCartesian()) {
                    track(placed.getRelative(face));
                }
            }
        }
    }
}
