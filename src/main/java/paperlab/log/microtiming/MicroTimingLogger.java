package paperlab.log.microtiming;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.world.item.DyeColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import paperlab.log.LabLoggers;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Redstone microtiming logger (/log microtiming).
 *
 * <p>Matches Carpet-TIS-Addition's behaviour:
 * <ul>
 *   <li>{@code merged}: groups repeated consecutive events with {@code +Nx};</li>
 *   <li>{@code all}: prints every event without merging;</li>
 *   <li>{@code unique}: prints only the first unique event per game tick.</li>
 * </ul>
 */
public final class MicroTimingLogger {

    public static final String OPTION_MERGED = "merged";
    public static final String OPTION_ALL = "all";
    public static final String OPTION_UNIQUE = "unique";

    public record MicroEvent(long gameTime, String dimension, int x, int y, int z,
                             DyeColor color, String blockName, String action, String details,
                             int depth, String phase, String stackTrace) {
        public MicroEvent(long gameTime, String dimension, int x, int y, int z,
                          DyeColor color, String blockName, String action, String details,
                          int depth) {
            this(gameTime, dimension, x, y, z, color, blockName, action, details, depth, null, null);
        }
    }

    private static final Queue<MicroEvent> CURRENT_TICK_EVENTS = new ConcurrentLinkedQueue<>();
    private static long lastFlushTime = -1;

    private MicroTimingLogger() {
    }

    public static boolean hasSubscribers() {
        return LabLoggers.MICROTIMING.hasSubscribers();
    }

    public static void recordEvent(final long gameTime, final String dim,
                                   final int x, final int y, final int z,
                                   final DyeColor color, final String blockName,
                                   final String action, final String details,
                                   final int depth) {
        recordEvent(gameTime, dim, x, y, z, color, blockName, action, details, depth, null, null);
    }

    public static void recordEvent(final long gameTime, final String dim,
                                   final int x, final int y, final int z,
                                   final DyeColor color, final String blockName,
                                   final String action, final String details,
                                   final int depth, final String phase,
                                   final String stackTrace) {
        if (!hasSubscribers()) return;
        CURRENT_TICK_EVENTS.add(new MicroEvent(gameTime, dim, x, y, z, color, blockName, action, details, depth, phase, stackTrace));
    }

    public static void flushTick() {
        if (CURRENT_TICK_EVENTS.isEmpty()) return;

        final List<MicroEvent> events = new ArrayList<>();
        MicroEvent ev;
        while ((ev = CURRENT_TICK_EVENTS.poll()) != null) {
            events.add(ev);
        }
        if (events.isEmpty()) return;

        for (final var entry : LabLoggers.MICROTIMING.subscribers().entrySet()) {
            final Player player = Bukkit.getPlayerExact(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            String opt = OPTION_MERGED;
            for (final String s : entry.getValue()) {
                if (s.equalsIgnoreCase(OPTION_ALL) || s.equalsIgnoreCase(OPTION_UNIQUE) || s.equalsIgnoreCase(OPTION_MERGED)) {
                    opt = s.toLowerCase(Locale.ROOT);
                    break;
                }
            }

            final List<Component> lines = formatEvents(events, opt);
            for (final Component line : lines) {
                player.sendMessage(line);
            }
        }
    }

    public static List<Component> formatEvents(final List<MicroEvent> events, final String option) {
        if (events.isEmpty()) return List.of();

        final List<Component> output = new ArrayList<>();
        final MicroEvent first = events.get(0);
        output.add(Component.text("[Game time " + first.gameTime() + "  @ " + first.dimension() + "] ------------", NamedTextColor.WHITE));

        if (option.equalsIgnoreCase(OPTION_UNIQUE)) {
            final Set<String> seen = new HashSet<>();
            for (final MicroEvent ev : events) {
                final String key = ev.x() + ":" + ev.y() + ":" + ev.z() + ":" + ev.action() + ":" + ev.details();
                if (seen.add(key)) {
                    output.add(renderEvent(ev, 0));
                }
            }
        } else if (option.equalsIgnoreCase(OPTION_ALL)) {
            for (final MicroEvent ev : events) {
                output.add(renderEvent(ev, 0));
            }
        } else {
            // MERGED mode (default)
            MicroEvent prev = null;
            int repeatCount = 0;

            for (final MicroEvent ev : events) {
                if (prev != null && isSameEvent(prev, ev)) {
                    repeatCount++;
                } else {
                    if (prev != null) {
                        output.add(renderEvent(prev, repeatCount));
                    }
                    prev = ev;
                    repeatCount = 0;
                }
            }
            if (prev != null) {
                output.add(renderEvent(prev, repeatCount));
            }
        }

        return output;
    }

    private static boolean isSameEvent(final MicroEvent a, final MicroEvent b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z()
            && a.action().equals(b.action()) && a.details().equals(b.details());
    }

    private static Component renderEvent(final MicroEvent ev, final int mergedCount) {
        final TextColor tagColor = dyeToTextColor(ev.color());
        final String indent = "  ".repeat(Math.max(0, Math.min(ev.depth(), 8)));

        final Component hoverText = Component.text("Position: " + ev.x() + " " + ev.y() + " " + ev.z()
            + "\nColor: " + ev.color().getName()
            + "\nIndentation: " + ev.depth());

        final String tpCmd = String.format(Locale.ROOT, "/tp %d %d %d", ev.x(), ev.y(), ev.z());

        Component line = Component.text(indent)
            .append(Component.text("# ", tagColor)
                .hoverEvent(HoverEvent.showText(hoverText))
                .clickEvent(ClickEvent.suggestCommand(tpCmd)))
            .append(Component.text("[" + ev.blockName() + "] ", NamedTextColor.GRAY))
            .append(Component.text(ev.action() + " -> " + ev.details(), NamedTextColor.WHITE));

        if (ev.phase() != null && !ev.phase().isEmpty()) {
            line = line.append(Component.text("  @ ", NamedTextColor.DARK_GRAY))
                .append(Component.text(ev.phase(), NamedTextColor.GOLD));
        }

        if (ev.stackTrace() != null && !ev.stackTrace().isEmpty()) {
            final Component stHover = Component.text("Caller Stack Trace:\n", NamedTextColor.YELLOW)
                .append(Component.text(ev.stackTrace(), NamedTextColor.WHITE));
            line = line.append(Component.text("  $", NamedTextColor.YELLOW)
                .hoverEvent(HoverEvent.showText(stHover)));
        }

        if (mergedCount > 0) {
            line = line.append(Component.text(" +" + (mergedCount + 1) + "x", NamedTextColor.GRAY));
        }

        return line;
    }

    public static TextColor dyeToTextColor(final DyeColor color) {
        if (color == null) return NamedTextColor.WHITE;
        return switch (color) {
            case WHITE -> NamedTextColor.WHITE;
            case ORANGE -> NamedTextColor.GOLD;
            case MAGENTA -> NamedTextColor.LIGHT_PURPLE;
            case LIGHT_BLUE -> NamedTextColor.AQUA;
            case YELLOW -> NamedTextColor.YELLOW;
            case LIME -> NamedTextColor.GREEN;
            case PINK -> NamedTextColor.RED;
            case GRAY -> NamedTextColor.DARK_GRAY;
            case LIGHT_GRAY -> NamedTextColor.GRAY;
            case CYAN -> NamedTextColor.DARK_AQUA;
            case PURPLE -> NamedTextColor.DARK_PURPLE;
            case BLUE -> NamedTextColor.BLUE;
            case BROWN -> TextColor.color(0x835432);
            case GREEN -> NamedTextColor.DARK_GREEN;
            case RED -> NamedTextColor.RED;
            case BLACK -> NamedTextColor.BLACK;
        };
    }
}
