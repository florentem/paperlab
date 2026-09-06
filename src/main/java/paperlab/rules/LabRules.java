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
 * The rule registry — what lives under {@code /carpet} in Carpet.
 *
 * <p><b>A rule and a tool are different things.</b> A tool answers a question ("how full is
 * the cap", "which chunks tick") and changes nothing. A rule <b>changes how the world
 * behaves</b>, and therefore: it is off by default, its deviation from vanilla is always
 * visible, and it does not survive a restart unless asked explicitly.
 *
 * <p>The last part is not caution. A run with a rule on is not comparable to a run without
 * it, and a forgotten rule turns every later measurement into rubbish, silently. So the lab
 * always starts in the vanilla state unless a value was saved with {@code setDefault}.
 *
 * <p>Rules that need code in the core read {@link LabRuleState}. With no core underneath
 * (stock Paper) such rules are unavailable — see {@link #available(LabRule)}.
 */
public final class LabRules {

    private static final Map<String, LabRule<?>> RULES = new LinkedHashMap<>();

    // --- parsers ---

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

    /** An empty value is written as {@code none}: an empty string cannot be typed into a command. */
    private static final LabRule.Parser<String> STRING = raw ->
        "none".equalsIgnoreCase(raw) ? "" : raw;

    private static final Function<Object, String> PLAIN = value -> {
        final String text = String.valueOf(value);
        return text.isEmpty() ? "none" : text;
    };

    // --- rules ---

    /**
     * Suffix appended to bot names.
     *
     * <p>A bot named after a live player takes their UUID, and that player can no longer log
     * in. A suffix makes the names diverge while the skin is still fetched for the name
     * <b>without</b> it — the bot looks like the intended player and blocks nobody.
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
        value -> LabRuleState.fakePlayerNameSuffix = value), "bot", "creative");

    /**
     * Neighbour updates from {@code /fill}, {@code /setblock} and {@code /clone}.
     *
     * <p>{@code false} places blocks quietly: observers do not fire, torches and repeaters do
     * not pop off, redstone does not start. Needed to assemble a contraption from a template
     * and switch it on once, instead of watching it start itself mid-fill.
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
        value -> LabRuleState.fillUpdates = value), "creative", "command");

    /** Whether our additions to vanilla {@code /tick} apply. The name follows Carpet. */
    public static final LabRule<Boolean> TICK_COMMAND_CARPETFIED = register(new LabRule<>(
        "tickCommandCarpetfied",
        "additions to vanilla /tick: toggle and warp",
        "disabling does not remove the nodes, it makes them unavailable",
        Boolean.TRUE,
        List.of("true", "false"),
        BOOLEAN,
        PLAIN::apply,
        value -> null,
        value -> LabRuleState.tickCommandCarpetfied = value), "command", "experimental");

    /**
     * Fixed horizontal launch angle for primed TNT.
     *
     * <p>Vanilla TNT gets a random angle, so the same cannon fires slightly differently every
     * time and runs cannot be compared. With a fixed angle the contraption behaves identically.
     * The value is in radians; {@code -1} means vanilla behaviour.
     */
    public static final LabRule<Double> HARDCODE_TNT_ANGLE = register(new LabRule<>(
        "hardcodeTNTangle",
        "fixed horizontal TNT launch angle, in radians",
        "-1 = vanilla random behaviour; otherwise 0 to 2pi",
        -1.0D,
        // The options are written exactly as the rule prints a value: otherwise the current
        // value matches no button and a duplicate like [-1.0] is appended to the list.
        List.of("-1.0", "0.0", "1.5707963", "3.1415926", "4.712389"),
        DOUBLE,
        PLAIN::apply,
        value -> value == -1.0D || (value >= 0.0D && value < Math.PI * 2.0D)
            ? null : "must be between 0 and 2pi, or -1",
        value -> {
            // Applied by an entity-spawn listener: the constructor has already run, but
            // nothing has read the velocity before the first tick.
        }), "tnt", "creative");

    /**
     * Sets the TNT random explosion range to a fixed value.
     * Set to -1 for default behavior.
     * Cannot be negative, except for -1.
     */
    public static final LabRule<Double> TNT_RANDOM_RANGE = register(new LabRule<>(
        "tntRandomRange",
        "sets the tnt random explosion range to a fixed value",
        "-1 for default behavior; cannot be negative except -1",
        -1.0D,
        List.of("-1.0", "0.0", "1.0", "2.0"),
        DOUBLE,
        PLAIN::apply,
        value -> value == -1.0D || value >= 0.0D ? null : "cannot be negative, except for -1",
        value -> {
            if (CoreBridge.PRESENT) {
                LabRuleState.tntRandomRange = value;
            }
        }), "tnt", "creative");

    /**
     * Eliminates random momentum and position offset when items drop from blocks.
     */
    public static final LabRule<Boolean> HARDCODE_ITEM_DROPS = register(new LabRule<>(
        "hardcodeItemDrops",
        "fixed zero momentum and centered position for dropped items",
        "ensures deterministic item drops during testing",
        Boolean.FALSE,
        List.of("false", "true"),
        BOOLEAN,
        PLAIN::apply,
        value -> null,
        value -> {
            if (CoreBridge.PRESENT) {
                LabRuleState.hardcodeItemDrops = value;
            }
        }), "tnt", "creative");

    /**
     * Enables collection and output of redstone microtiming (/log microtiming).
     *
     * <p>As in Carpet-TIS-Addition, the microTiming rule activates trackers for components
     * marked with wool or dye markers.
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
        }), "redstone", "experimental");

    /**
     * Independent tick rate, freeze and sprint per world (/tick freeze, rate and so on).
     *
     * <p>When on, each world ticks independently with its own ServerLevelTickRateManager.
     * When off, every world is synchronised with the overworld and the server.
     */
    public static final LabRule<Boolean> PER_WORLD_TICK = register(new LabRule<>(
        "perWorldTick",
        "independent tick rate, freeze and sprint per world/dimension",
        "allows /tick commands to execute independently per dimension",
        Boolean.FALSE,
        List.of("false", "true"),
        BOOLEAN,
        PLAIN::apply,
        value -> null,
        value -> {
            if (CoreBridge.PRESENT) {
                final org.bukkit.Server bServer = org.bukkit.Bukkit.getServer();
                final net.minecraft.server.MinecraftServer server =
                    (bServer instanceof final org.bukkit.craftbukkit.CraftServer cs) ? cs.getServer() : null;
                io.papermc.paper.lab.tick.LabPerWorldTick.setEnabled(value, server);
            }
        }), "tick", "experimental");

    private LabRules() {
    }

    private static <T> LabRule<T> register(final LabRule<T> rule, final String... categories) {
        RULES.put(rule.name().toLowerCase(Locale.ROOT), rule.categories(categories));
        return rule;
    }

    /** Every category in use, alphabetically. */
    public static List<String> categories() {
        final java.util.TreeSet<String> out = new java.util.TreeSet<>();
        RULES.values().forEach(rule -> out.addAll(rule.categories()));
        return List.copyOf(out);
    }

    /** Rules carrying this category. */
    public static Collection<LabRule<?>> inCategory(final String category) {
        final List<LabRule<?>> out = new java.util.ArrayList<>();
        for (final LabRule<?> rule : all()) {
            for (final String tag : rule.categories()) {
                if (tag.equalsIgnoreCase(category)) {
                    out.add(rule);
                    break;
                }
            }
        }
        return out;
    }

    /** Rules that differ from vanilla. */
    public static Collection<LabRule<?>> changed() {
        final List<LabRule<?>> out = new java.util.ArrayList<>();
        for (final LabRule<?> rule : all()) {
            if (rule.changed()) {
                out.add(rule);
            }
        }
        return out;
    }

    public static @Nullable LabRule<?> get(final String name) {
        return RULES.get(name.toLowerCase(Locale.ROOT));
    }

    public static Collection<LabRule<?>> all() {
        return RULES.values();
    }

    /**
     * Whether the rule is available on this build.
     *
     * <p>Most rules are read by core code. Without our core, changing them is pointless: the
     * value would be stored while the behaviour stayed the same — and a rule that silently
     * does nothing is worse than a missing one.
     */
    public static boolean available(final LabRule<?> rule) {
        if (rule == HARDCODE_TNT_ANGLE || rule == MICRO_TIMING) {
            return true;
        }
        return CoreBridge.PRESENT;
    }

    /** How many rules currently differ from vanilla. */
    public static int changedCount() {
        int count = 0;
        for (final LabRule<?> rule : RULES.values()) {
            if (rule.changed()) {
                count++;
            }
        }
        return count;
    }

    /** Return every rule to vanilla behaviour. */
    public static void resetAll() {
        for (final LabRule<?> rule : RULES.values()) {
            rule.reset();
        }
        // The core is dormant by default — its commands are off. Put them back to sleep.
        if (CoreBridge.PRESENT) {
            io.papermc.paper.lab.rules.LabRuleState.playerCommandEnabled = false;
            io.papermc.paper.lab.rules.LabRuleState.tickCommandCarpetfied = false;
        }
    }

    /**
     * Push the current value of every rule into the core. Called when the plugin enables,
     * because the core is dormant by default: every flag is off until the plugin sets the
     * values explicitly.
     */
    public static void applyAll() {
        for (final LabRule<?> rule : RULES.values()) {
            rule.reapply();
        }
        // The /player and /tick toggle|warp commands — enabled when the core is present.
        if (CoreBridge.PRESENT) {
            io.papermc.paper.lab.rules.LabRuleState.playerCommandEnabled = true;
        }
    }

    /** Apply the saved default values. Called when the plugin enables. */
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
