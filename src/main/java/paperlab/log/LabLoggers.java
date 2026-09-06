package paperlab.log;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** Registry of {@code /log} loggers. */
public final class LabLoggers {

    private static final Map<String, LabLogger> LOGGERS = new LinkedHashMap<>();

    public static final LabLogger TPS = register(new LabLogger("tps", false));

    /**
     * The option is the name of the player or bot whose local cap to show. With no option, your
     * own.
     *
     * <p>A dimension option would be meaningless here: the local cap is tied to a specific
     * player's position, and the world is taken from wherever that player is.
     */
    public static final LabLogger MOBCAPS = register(new LabLogger("mobcaps", true));

    /**
     * The option is a wool colour. There can be several subscriptions: each colour is its own
     * line, toggled independently.
     */
    public static final LabLogger COUNTER = register(new LabLogger("counter", true));

    /**
     * The spawn trace: where attempts stop — cap, position, plugin, success.
     *
     * <p>The option is a mob category ({@code monster}, {@code ambient}, …). On stock Paper the
     * trace is reduced to "spawned / cancelled", see {@link paperlab.spawn.SpawnView}.
     */
    public static final LabLogger SPAWN = register(new LabLogger("spawn", true));

    /**
     * Item entity lifecycle: creation, despawn after 5 minutes, destruction by damage.
     *
     * <p>Options: {@code despawn}, {@code die}, {@code create}, or comma-separated.
     */
    public static final LabLogger ITEM = register(new LabLogger("item", false, "despawn",
        List.of("despawn", "die", "create", "despawn,die", "despawn,die,create")));

    /**
     * Microtiming of redstone components and block events.
     *
     * <p>Options: {@code merged}, {@code all}, {@code unique}.
     */
    public static final LabLogger MICROTIMING = register(new LabLogger("microtiming", false, "merged",
        List.of("merged", "all", "unique")));

    /**
     * Breakdown of the entity movement calculation (move): piston clamping, sneaking, collisions.
     *
     * <p>The option is a target selector (for example {@code non_zero:@a[distance=..10]},
     * {@code @s}).
     */
    public static final LabLogger MOVEMENT = register(new LabLogger("movement", true, "non_zero:@a[distance=..10]",
        List.of("non_zero:@a[distance=..10]", "@s", "non_zero:@e[type=creeper,distance=..5]")));

    private LabLoggers() {
    }

    private static LabLogger register(final LabLogger logger) {
        LOGGERS.put(logger.name(), logger);
        return logger;
    }

    public static @Nullable LabLogger get(final String name) {
        return LOGGERS.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public static Collection<LabLogger> all() {
        return LOGGERS.values();
    }

    public static Collection<String> names() {
        return LOGGERS.keySet();
    }

    /** Drop all of a player's subscriptions. */
    public static int unsubscribeAll(final String playerName) {
        int count = 0;
        for (final LabLogger logger : LOGGERS.values()) {
            if (logger.unsubscribeAll(playerName)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The permission for one logger. Needed because a logger name is a command argument rather
     * than a literal: {@code requires} on the node cannot tell them apart.
     */
    public static String permissionOf(final LabLogger logger) {
        if (logger == TPS) {
            return paperlab.command.LabPermissions.LOG_TPS;
        }
        if (logger == MOBCAPS) {
            return paperlab.command.LabPermissions.LOG_MOBCAPS;
        }
        if (logger == COUNTER) {
            return paperlab.command.LabPermissions.LOG_COUNTER;
        }
        if (logger == SPAWN) {
            return paperlab.command.LabPermissions.LOG_SPAWN;
        }
        if (logger == ITEM) {
            return paperlab.command.LabPermissions.LOG_ITEM;
        }
        if (logger == MICROTIMING) {
            return paperlab.command.LabPermissions.LOG_MICROTIMING;
        }
        if (logger == MOVEMENT) {
            return paperlab.command.LabPermissions.LOG_MOVEMENT;
        }
        return paperlab.command.LabPermissions.LOG;
    }

    public static boolean anySubscribers() {
        for (final LabLogger logger : LOGGERS.values()) {
            if (logger.hasSubscribers()) {
                return true;
            }
        }
        return false;
    }
}
