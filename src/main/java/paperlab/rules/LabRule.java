package paperlab.rules;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Одно правило: имя, описание, текущее значение и то, куда его положить.
 *
 * <p>Модель Carpet: у правила ровно одно задаваемое значение, ванильное значение известно,
 * и в любой момент видно, отличается ли текущее от ванильного.
 *
 * <p><b>Три разных значения, их нельзя путать:</b>
 * <ul>
 *   <li>{@link #vanilla()} — как ведёт себя нетронутый сервер. Не меняется никогда;</li>
 *   <li>{@link #value()} — что действует сейчас;</li>
 *   <li>значение по умолчанию — то, что применится после перезапуска. Живёт отдельно,
 *       в {@link RuleDefaults}, и по умолчанию его нет: правило, выставленное командой,
 *       <b>не переживает рестарт</b>. Так стенд всегда стартует в известном состоянии.</li>
 * </ul>
 */
public final class LabRule<T> {

    /** Разбор строки в значение. {@code null} — не разобралось. */
    @FunctionalInterface
    public interface Parser<T> extends Function<String, @Nullable T> {
    }

    private final String name;
    private final String description;
    private final String extra;
    private final T vanilla;
    private final List<String> options;
    private final Parser<T> parser;
    private final Function<T, String> printer;
    private final Consumer<T> apply;
    private final Function<T, @Nullable String> validator;

    private volatile T value;

    LabRule(final String name,
            final String description,
            final String extra,
            final T vanilla,
            final List<String> options,
            final Parser<T> parser,
            final Function<T, String> printer,
            final Function<T, @Nullable String> validator,
            final Consumer<T> apply) {
        this.name = name;
        this.description = description;
        this.extra = extra;
        this.vanilla = vanilla;
        this.options = List.copyOf(options);
        this.parser = parser;
        this.printer = printer;
        this.validator = validator;
        this.apply = apply;
        this.value = vanilla;
    }

    public String name() {
        return this.name;
    }

    public String description() {
        return this.description;
    }

    /** Уточнение, которое не влезает в описание. Пустая строка, если его нет. */
    public String extra() {
        return this.extra;
    }

    public T vanilla() {
        return this.vanilla;
    }

    public T value() {
        return this.value;
    }

    public List<String> options() {
        return this.options;
    }

    public String printed() {
        return this.printer.apply(this.value);
    }

    public String printedVanilla() {
        return this.printer.apply(this.vanilla);
    }

    /** Отличается ли текущее значение от ванильного. */
    public boolean changed() {
        return !this.value.equals(this.vanilla);
    }

    /**
     * Разобрать и применить.
     *
     * @return {@code null} при успехе, иначе причина отказа
     */
    public @Nullable String set(final String raw) {
        final T parsed = this.parser.apply(raw);
        if (parsed == null) {
            return "cannot parse value '" + raw + "'";
        }
        final String rejected = this.validator.apply(parsed);
        if (rejected != null) {
            return rejected;
        }
        this.value = parsed;
        this.apply.accept(parsed);
        return null;
    }

    /** Вернуть ванильное поведение. */
    public void reset() {
        this.value = this.vanilla;
        this.apply.accept(this.vanilla);
    }

    /** Передать текущее значение в ядро без изменения. */
    public void reapply() {
        this.apply.accept(this.value);
    }

    /** Право на это правило. */
    public String permission() {
        return "paperlab.rule." + this.name.toLowerCase(java.util.Locale.ROOT);
    }
}
