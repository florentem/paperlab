package paperlab.rules;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * One rule: name, description, current value, and where to put it.
 *
 * <p>Carpet's model: a rule has exactly one settable value, the vanilla value is known, and it
 * is always visible whether the current one differs from it.
 *
 * <p><b>Three different values that must not be confused:</b>
 * <ul>
 *   <li>{@link #vanilla()} — how an untouched server behaves. Never changes;</li>
 *   <li>{@link #value()} — what is in force right now;</li>
 *   <li>the default — what will apply after a restart. It lives separately, in
 *       {@link RuleDefaults}, and by default there is none: a rule set by command
 *       <b>does not survive a restart</b>. That way the lab always starts in a known state.</li>
 * </ul>
 */
public final class LabRule<T> {

    /** Parses a string into a value. {@code null} means it did not parse. */
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
    /** Categories for {@code /carpet list <category>} — like Carpet's rule categories. */
    private List<String> categories = List.of();

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

    public List<String> categories() {
        return this.categories;
    }

    /** Set once at registration, hence no synchronisation. */
    LabRule<T> categories(final String... tags) {
        this.categories = List.of(tags);
        return this;
    }

    public String description() {
        return this.description;
    }

    /** A clarification that does not fit in the description. Empty string if there is none. */
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

    /** Whether the current value differs from the vanilla one. */
    public boolean changed() {
        return !this.value.equals(this.vanilla);
    }

    /**
     * Parse and apply.
     *
     * @return {@code null} on success, otherwise the reason for refusal
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

    /** Return to vanilla behaviour. */
    public void reset() {
        this.value = this.vanilla;
        this.apply.accept(this.vanilla);
    }

    /** Push the current value into the core without changing it. */
    public void reapply() {
        this.apply.accept(this.value);
    }

    /** The permission for this rule. */
    public String permission() {
        return "paperlab.rule." + this.name.toLowerCase(java.util.Locale.ROOT);
    }
}
