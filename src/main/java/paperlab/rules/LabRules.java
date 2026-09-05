package paperlab.rules;

import io.papermc.paper.lab.rules.LabRuleState;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;
import paperlab.core.CoreBridge;

/**
 * Реестр правил — то, что в Carpet живёт под {@code /carpet}.
 *
 * <p><b>Правило и инструмент — разные вещи.</b> Инструмент отвечает на вопрос
 * («сколько мобов в капе», «какие чанки тикают») и ничего не меняет. Правило <b>меняет
 * поведение мира</b>, и потому: по умолчанию выключено, всегда видно, что оно отличается
 * от ванильного, и не переживает перезапуск, пока об этом не попросят явно.
 *
 * <p>Последнее — не перестраховка. Прогон с включённым правилом несопоставим с прогоном
 * без него, а забытое правило превращает все последующие замеры в мусор, причём молча.
 * Поэтому стенд всегда стартует в ванильном состоянии, если только значение не сохранено
 * командой {@code setDefault}.
 *
 * <p>Правила, которым нужен код в ядре, читают {@link LabRuleState}. Если ядра под нами
 * нет (чистый Paper), такие правила недоступны — {@link #available(LabRule)}.
 */
public final class LabRules {

    private static final Map<String, LabRule<?>> RULES = new LinkedHashMap<>();

    // --- разборщики ---

    private static final LabRule.Parser<Boolean> BOOLEAN = raw -> {
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        return null;
    };

    private static final LabRule.Parser<Double> DOUBLE = raw -> {
        try {
            return Double.valueOf(raw);
        } catch (final NumberFormatException ignored) {
            return null;
        }
    };

    /** Пустое значение задаётся словом {@code none}: пустую строку в команду не ввести. */
    private static final LabRule.Parser<String> STRING = raw ->
        "none".equalsIgnoreCase(raw) ? "" : raw;

    private static final Function<Object, String> PLAIN = value -> {
        final String text = String.valueOf(value);
        return text.isEmpty() ? "none" : text;
    };

    // --- правила ---

    /**
     * Суффикс к именам ботов.
     *
     * <p>Бот с именем живого игрока занимает его UUID, и сам игрок войти уже не может.
     * С суффиксом имена расходятся, а скин по-прежнему берётся по имени <b>без</b>
     * суффикса — бот выглядит как нужный игрок и никому не мешает.
     */
    public static final LabRule<String> FAKE_PLAYER_NAME_SUFFIX = register(new LabRule<>(
        "fakePlayerNameSuffix",
        "suffix appended to bot names",
        "skin comes from the name without the suffix; none = no suffix",
        "",
        List.of("none", "_bot", "_fake", "_afk"),
        STRING,
        PLAIN::apply,
        value -> value.length() > 8 ? "a suffix longer than 8 chars leaves no room for the name" : null,
        value -> LabRuleState.fakePlayerNameSuffix = value));

    /**
     * Обновления соседей от {@code /fill}, {@code /setblock} и {@code /clone}.
     *
     * <p>{@code false} — блоки ставятся «тихо»: не срабатывают наблюдатели, не отваливаются
     * факелы и репитеры, не запускается редстоун. Нужно, чтобы собрать конструкцию по
     * шаблону и включить её один раз, а не смотреть, как она сама стартует по ходу заливки.
     */
    public static final LabRule<Boolean> FILL_UPDATES = register(new LabRule<>(
        "fillUpdates",
        "/fill, /setblock and /clone cause neighbour updates",
        "false = place blocks without updates",
        Boolean.TRUE,
        List.of("true", "false"),
        BOOLEAN,
        PLAIN::apply,
        value -> null,
        value -> LabRuleState.fillUpdates = value));

    /** Действуют ли наши дополнения к ванильному {@code /tick}. Имя как в Carpet. */
    public static final LabRule<Boolean> TICK_COMMAND_CARPETFIED = register(new LabRule<>(
        "tickCommandCarpetfied",
        "additions to vanilla /tick: toggle and warp",
        "disabling does not remove the nodes, it makes them unavailable",
        Boolean.TRUE,
        List.of("true", "false"),
        BOOLEAN,
        PLAIN::apply,
        value -> null,
        value -> LabRuleState.tickCommandCarpetfied = value));

    /**
     * Фиксированный горизонтальный угол разлёта у зажжённого динамита.
     *
     * <p>Ванильный TNT получает случайный угол, поэтому одна и та же пушка каждый раз
     * стреляет чуть иначе и сравнивать прогоны нельзя. С фиксированным углом конструкция
     * ведёт себя одинаково. Значение в радианах, {@code -1} — ванильное поведение.
     */
    public static final LabRule<Double> HARDCODE_TNT_ANGLE = register(new LabRule<>(
        "hardcodeTNTangle",
        "fixed horizontal TNT launch angle, in radians",
        "-1 = vanilla random behaviour; otherwise 0 to 2pi",
        -1.0D,
        List.of("-1", "0", "1.5707963", "3.1415926", "4.712389"),
        DOUBLE,
        PLAIN::apply,
        value -> value == -1.0D || (value >= 0.0D && value < Math.PI * 2.0D)
            ? null : "must be between 0 and 2pi, or -1",
        value -> {
            // Применяется слушателем при появлении сущности: конструктор уже отработал,
            // но до первого тика скорость ещё никто не читал.
        }));

    /**
     * Включение сбора и вывода микротаймингов редстоуна (/log microtiming).
     *
     * <p>Как в Carpet-TIS-Addition, правило microTiming активирует трекеры для
     * компонентов, помеченных шерстью или маркерами красителей.
     */
    public static final LabRule<Boolean> MICRO_TIMING = register(new LabRule<>(
        "microTiming",
        "enable redstone components microtiming logger",
        "log actions and updates of blocks marked with wool or dye",
        Boolean.FALSE,
        List.of("false", "true"),
        BOOLEAN,
        PLAIN::apply,
        value -> null,
        value -> {
            if (CoreBridge.PRESENT) {
                io.papermc.paper.lab.microtiming.LabMicroTiming.enabled = value;
            }
        }));

    private LabRules() {
    }

    private static <T> LabRule<T> register(final LabRule<T> rule) {
        RULES.put(rule.name().toLowerCase(Locale.ROOT), rule);
        return rule;
    }

    public static @Nullable LabRule<?> get(final String name) {
        return RULES.get(name.toLowerCase(Locale.ROOT));
    }

    public static Collection<LabRule<?>> all() {
        return RULES.values();
    }

    /**
     * Доступно ли правило на этой сборке.
     *
     * <p>Три правила из четырёх читаются кодом ядра. Без нашего ядра менять их бессмысленно:
     * значение сохранится, а поведение не изменится — а молча не работающее правило хуже,
     * чем отсутствующее.
     */
    public static boolean available(final LabRule<?> rule) {
        if (rule == HARDCODE_TNT_ANGLE || rule == MICRO_TIMING) {
            return true;
        }
        return CoreBridge.PRESENT;
    }

    /** Сколько правил сейчас отличается от ванильного. */
    public static int changedCount() {
        int count = 0;
        for (final LabRule<?> rule : RULES.values()) {
            if (rule.changed()) {
                count++;
            }
        }
        return count;
    }

    /** Вернуть все правила к ванильному поведению. */
    public static void resetAll() {
        for (final LabRule<?> rule : RULES.values()) {
            rule.reset();
        }
    }

    /** Применить сохранённые значения по умолчанию. Вызывается при включении плагина. */
    public static void applyDefaults(final RuleDefaults defaults, final Consumer<String> log) {
        for (final Map.Entry<String, String> entry : defaults.all().entrySet()) {
            final LabRule<?> rule = get(entry.getKey());
            if (rule == null) {
                log.accept("default rule '" + entry.getKey() + "' not found, skipping");
                continue;
            }
            if (!available(rule)) {
                log.accept("rule '" + rule.name() + "' needs our core, skipping");
                continue;
            }
            final String rejected = rule.set(entry.getValue());
            if (rejected != null) {
                log.accept("default rule '" + rule.name() + "': " + rejected);
            } else {
                log.accept("default rule: " + rule.name() + " = " + rule.printed());
            }
        }
    }
}
