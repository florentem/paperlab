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
import paperlab.text.Msg;
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
 * Hopper counters.
 *
 * <p><b>The mechanism differs from the core version, and that is a plugin limitation.</b>
 * The core used to intercept {@code HopperBlockEntity.ejectItems} and take the contents at the
 * exact moment the hopper tried to push. A plugin has no such interception, so hoppers pointed
 * into wool are drained by a once-per-tick task.
 *
 * <p>Practical consequences:
 * <ul>
 *   <li>the moment of accounting can be a tick off from the real one;</li>
 *   <li>the hopper does fill up, so its own cooldown behaves like an ordinary full hopper's
 *       rather than an instantly drained one.</li>
 * </ul>
 * That is acceptable for comparing rates between runs, but not for sub-tick measurements.
 *
 * <p>The tracked-hopper list is filled on block placement and by {@code /counter scan}: walking
 * every loaded chunk each tick would be far too expensive.
 *
 * <p>The counter summary line follows Carpet Mod (MIT, (c) gnembon), see THIRD-PARTY.md.
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

    /** Tracked hoppers: location -> the colour of the wool it points into. */
    private static final Map<BlockPosKey, DyeColor> TRACKED = new java.util.concurrent.ConcurrentHashMap<>();

    private LabCounters() {
    }

    /** Hopper check: does it point into a wool block. */
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

    /** Start tracking a hopper if it points into wool. */
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

    /** Walk the area around a player and find hoppers pointing into wool. */
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

    /** Once per tick: drain the tracked hoppers and account for the contents. */
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
                // The hopper was broken or turned — stop tracking it.
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

    /**
     * The counter summary line — Carpet's grammar: {@code name: total, N/h, M.M min}.
     *
     * <p>The name is painted in the dye's true colour (as in the mod) rather than an
     * approximate named one: {@code light_blue} and {@code cyan} merge into one another among
     * Minecraft's sixteen named colours, and several counters sit side by side in the tab list
     * where they have to be told apart at a glance.
     */
    public static Component summary(final LabCounter counter, final long gameTime,
                                    final boolean withWorld) {
        final Double perHour = counter.perHour(gameTime);
        final long ticks = Math.max(counter.elapsedTicks(gameTime), 1L);
        final double minutes = ticks / 1200.0D;
        final String name = hexStyle(counter.colour()) + " " + counter.colour().getName();

        if (!counter.started()) {
            return Msg.c("b" + name, "w : ", "gi -, -/h, - min ");
        }
        final java.util.List<Object> parts = new java.util.ArrayList<>();
        parts.add("b" + name);
        if (withWorld) {
            parts.add("g @" + counter.world());
        }
        parts.add("w : ");
        parts.add("wb " + counter.total());
        parts.add("w , ");
        parts.add("wb " + (perHour == null ? "-" : String.format(Locale.ROOT, "%.0f", perHour)));
        parts.add("w /h, ");
        parts.add(String.format(Locale.ROOT, "wb %.1f ", minutes));
        parts.add("w min");
        return Msg.c(parts.toArray());
    }

    /** The dye colour as an {@link Msg} style — {@code #rrggbb}, as in Carpet. */
    public static String hexStyle(final DyeColor dye) {
        String hex = Integer.toHexString(dye.getTextColor() & 0xFFFFFF);
        if (hex.length() < 6) {
            hex = "0".repeat(6 - hex.length()) + hex;
        }
        return "#" + hex;
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

    /** A hopper or wool was placed — start tracking immediately. */
    public static final class Listener implements org.bukkit.event.Listener {

        @EventHandler
        public void onPlace(final BlockPlaceEvent event) {
            final Block placed = event.getBlockPlaced();
            track(placed);
            // The wool may have been placed in front of an existing hopper.
            for (final BlockFace face : BlockFace.values()) {
                if (face.isCartesian()) {
                    track(placed.getRelative(face));
                }
            }
        }
    }
}
