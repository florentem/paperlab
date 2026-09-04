package paperlab.rules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Значения правил, которые переживают перезапуск.
 *
 * <p><b>Почему это отдельная сущность, а не «просто сохранять текущее».</b> Правило меняет
 * поведение мира. Забытое включённое правило молча портит все последующие замеры: числа
 * получаются, выглядят правдоподобно и ни с чем не сопоставимы. Поэтому обычная установка
 * действует только до перезапуска, а чтобы значение осталось — нужно сказать об этом явно
 * командой {@code setDefault}. Так устроено и в Carpet.
 *
 * <p>Формат намеренно примитивный: строка {@code имя=значение} на правило. Значение
 * хранится текстом и разбирается тем же кодом, что и ввод из команды, — так сохранённое
 * и введённое руками не могут разойтись в трактовке.
 */
public final class RuleDefaults {

    private static final String HEADER = """
        # Rule values applied when the server starts.
        #
        # Written by /carpet setDefault <rule> <value>, read when the plugin enables.
        # A rule set by a plain command does NOT end up here and returns to vanilla after
        # a restart. That is deliberate: a forgotten rule silently ruins every later
        # measurement.
        #
        # Format: name=value, one per line.
        """;

    private final Path file;
    private final Logger logger;
    private final Map<String, String> values = new LinkedHashMap<>();

    public RuleDefaults(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public Map<String, String> all() {
        return Map.copyOf(this.values);
    }

    public boolean has(final String rule) {
        return this.values.containsKey(rule);
    }

    public String get(final String rule) {
        return this.values.get(rule);
    }

    public void load() {
        this.values.clear();
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        try {
            for (final String line : Files.readAllLines(this.file, StandardCharsets.UTF_8)) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                final int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    this.logger.warning("rules.conf: cannot parse line '" + trimmed + "'");
                    continue;
                }
                this.values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        } catch (final IOException e) {
            this.logger.warning("failed to read rules.conf: " + e);
        }
    }

    public void set(final String rule, final String value) {
        this.values.put(rule, value);
        this.save();
    }

    public boolean remove(final String rule) {
        if (this.values.remove(rule) == null) {
            return false;
        }
        this.save();
        return true;
    }

    private void save() {
        try {
            Files.createDirectories(this.file.getParent());
            final StringBuilder out = new StringBuilder(HEADER);
            this.values.forEach((rule, value) -> out.append(rule).append('=').append(value).append('\n'));
            Files.writeString(this.file, out.toString(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            this.logger.warning("failed to write rules.conf: " + e);
        }
    }
}
