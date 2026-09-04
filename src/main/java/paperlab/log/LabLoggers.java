package paperlab.log;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** Реестр логгеров {@code /log}. */
public final class LabLoggers {

    private static final Map<String, LabLogger> LOGGERS = new LinkedHashMap<>();

    public static final LabLogger TPS = register(new LabLogger("tps", false));

    /**
     * Опция — ник игрока или бота, чей локальный кап показывать. Без опции — свой.
     *
     * <p>Опции измерения здесь бессмысленны: локальный кап привязан к позиции конкретного
     * игрока, а мир берётся из того, где этот игрок находится.
     */
    public static final LabLogger MOBCAPS = register(new LabLogger("mobcaps", true));

    /**
     * Опция — цвет шерсти. Подписок может быть несколько: каждый цвет своя строка,
     * включается и выключается независимо.
     */
    public static final LabLogger COUNTER = register(new LabLogger("counter", true));

    /**
     * Трасса спавна: где останавливаются попытки — кап, позиция, плагин, успех.
     *
     * <p>Опция — категория мобов ({@code monster}, {@code ambient}, …). На чистом Paper
     * трасса урезана до «появилось / отменено», см. {@link paperlab.spawn.SpawnView}.
     */
    public static final LabLogger SPAWN = register(new LabLogger("spawn", true));

    private LabLoggers() {
    }

    private static LabLogger register(final LabLogger logger) {
        LOGGERS.put(logger.name(), logger);
        return logger;
    }

    public static @Nullable LabLogger get(final String name) {
        return LOGGERS.get(name);
    }

    public static Collection<LabLogger> all() {
        return LOGGERS.values();
    }

    public static Collection<String> names() {
        return LOGGERS.keySet();
    }

    /** Снять все подписки игрока. */
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
     * Право на конкретный логгер. Нужно потому, что имя логгера — аргумент команды,
     * а не литерал: {@code requires} на узле их не различает.
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
