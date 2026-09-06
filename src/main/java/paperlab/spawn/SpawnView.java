package paperlab.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import paperlab.core.CoreBridge;

/**
 * The spawn trace: where natural spawn attempts actually stop.
 *
 * <p>Answers "why is the farm not spawning" directly. The engine never reports which step an
 * attempt stopped at, and there are several reasons that mean different things.
 *
 * <h2>On our core — the full trace</h2>
 * <pre>
 * overworld  monster  cap 133295 · passes 184 · position 167 · plugin 0 · spawned 3
 * overworld  ambient  cap 0 · passes 133479 · position 569992 · plugin 0 · spawned 0
 * </pre>
 * It reads at a glance: monsters are limited by the <b>cap</b>, while ambient has cap headroom
 * but fails on <b>position</b>. No stock tool distinguishes the two.
 *
 * <p><b>The units differ and must not be added.</b> "cap" and "passes" are counted per
 * chunk × category pass; "position", "plugin" and "spawned" are counted per individual
 * position within a pass, and several positions are tried per pass. So "position" legitimately
 * exceeds "passes".
 *
 * <p>The <b>"plugin"</b> column is the one cause that should not exist on clean Paper. On a
 * production server it answers directly whether something is throttling spawns.
 *
 * <h2>On stock Paper — only two facts</h2>
 * "Hit the cap" and "position rejected" are not published outwards: they are visible only
 * inside {@code NaturalSpawner}. What remains is a successful spawn and a handler cancel —
 * see {@link SpawnCounters}.
 *
 * <p>Collection runs only while someone is subscribed: with the trace off, the hot spawn path
 * is left with a single {@code volatile} field read.
 */
public final class SpawnView {

    private SpawnView() {
    }

    /**
     * Two independent sources of "on": a tab-list subscription and a manual command. One flag
     * will not do — the HUD tick would see "no subscribers" and switch off collection that was
     * enabled from the console. And the console is needed: a run often has no live player at
     * all.
     */
    private static volatile boolean manual;
    private static volatile boolean subscribed;

    /** Manual switch via {@code /carpet spawn on|off}. */
    public static void setManual(final boolean enabled) {
        manual = enabled;
        apply();
    }

    /** Whether there are tab-list subscribers. Called from the HUD tick. */
    public static void setSubscribed(final boolean value) {
        subscribed = value;
        apply();
    }

    public static boolean enabled() {
        return manual || subscribed;
    }

    private static void apply() {
        if (CoreBridge.PRESENT) {
            Core.setEnabled(manual || subscribed);
        }
    }

    public static void reset() {
        if (CoreBridge.PRESENT) {
            Core.reset();
        }
        SpawnCounters.reset();
    }

    /** What to suggest: mob categories with the core, spawn reasons without it. */
    public static List<String> options() {
        final List<String> out = new ArrayList<>();
        if (CoreBridge.PRESENT) {
            for (final MobCategory category : MobCategory.values()) {
                out.add(category.getName());
            }
        } else {
            for (final org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason
                : org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.values()) {
                out.add(reason.name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** One HUD line. {@code option} is a category (with the core) or a spawn reason. */
    public static Component line(final World world, final String option) {
        final String arg = option == null || option.isBlank() ? null : option.trim();
        return CoreBridge.PRESENT ? Core.line(world, arg) : degraded(world, arg);
    }

    private static Component degraded(final World world, final String reason) {
        final SpawnCounters.Snapshot snapshot = SpawnCounters.snapshot(world, reason);
        return Component.text("spawn ", NamedTextColor.GRAY)
            .append(Component.text((reason == null ? "all" : reason) + "  ", NamedTextColor.DARK_GRAY))
            .append(Component.text("spawned ", NamedTextColor.DARK_GRAY))
            .append(Component.text(snapshot.spawned(),
                snapshot.spawned() > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY))
            .append(Component.text(" - cancelled ", NamedTextColor.DARK_GRAY))
            .append(Component.text(snapshot.cancelled(),
                snapshot.cancelled() > 0 ? NamedTextColor.RED : NamedTextColor.DARK_GRAY));
    }

    /** Delegate to the core: on stock Paper this class is never loaded. */
    private static final class Core {

        /** Default category: monsters — what the trace was written for. */
        private static final MobCategory DEFAULT = MobCategory.MONSTER;

        static void setEnabled(final boolean enabled) {
            io.papermc.paper.lab.spawn.SpawnTrace.setEnabled(enabled);
        }

        static void reset() {
            io.papermc.paper.lab.spawn.SpawnTrace.reset();
        }

        static Component line(final World world, final String option) {
            MobCategory category = DEFAULT;
            if (option != null) {
                for (final MobCategory candidate : MobCategory.values()) {
                    if (candidate.getName().equalsIgnoreCase(option)) {
                        category = candidate;
                        break;
                    }
                }
            }

            final ServerLevel level = ((CraftWorld) world).getHandle();
            final long[] counters = io.papermc.paper.lab.spawn.SpawnTrace.snapshot(level, category);

            Component line = Component.text("spawn ", NamedTextColor.GRAY)
                .append(Component.text(category.getName() + "  ", NamedTextColor.WHITE));
            if (counters == null) {
                return line.append(Component.text("no data", NamedTextColor.DARK_GRAY));
            }

            final var outcomes = io.papermc.paper.lab.spawn.SpawnTrace.Outcome.values();
            for (int i = 0; i < outcomes.length; i++) {
                if (i > 0) {
                    line = line.append(Component.text(" ", NamedTextColor.DARK_GRAY));
                }
                line = line.append(Component.text(outcomes[i].label() + " ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(counters[i], colour(outcomes[i], counters[i])));
            }
            return line;
        }

        /**
         * Colour by what the outcome means rather than by size: "spawned" is good, "plugin" is
         * worth investigating, the rest is neutral.
         */
        private static NamedTextColor colour(
            final io.papermc.paper.lab.spawn.SpawnTrace.Outcome outcome, final long value) {
            return switch (outcome) {
                case SPAWNED -> value > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY;
                case PLUGIN -> value > 0 ? NamedTextColor.RED : NamedTextColor.DARK_GRAY;
                case CAP_FULL -> value > 0 ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY;
                default -> NamedTextColor.WHITE;
            };
        }
    }
}
