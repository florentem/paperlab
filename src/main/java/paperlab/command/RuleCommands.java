package paperlab.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import paperlab.rules.LabRule;
import paperlab.rules.LabRules;
import paperlab.rules.RuleDefaults;
import paperlab.text.Msg;

/**
 * Rules under {@code /carpet} — as in Carpet: a rule and one settable value.
 *
 * <pre>
 * /carpet                        changed rules and the category list
 * /carpet list                   every rule
 * /carpet list &lt;category&gt;        rules in a category
 * /carpet list defaults          what applies after a restart
 * /carpet &lt;rule&gt;                 rule card
 * /carpet &lt;rule&gt; &lt;value&gt;         set until restart
 * /carpet setDefault &lt;rule&gt; &lt;value&gt;
 * /carpet removeDefault &lt;rule&gt;
 * </pre>
 *
 * <p><b>The output follows Carpet literally</b> — same headers, same buttons, same colours,
 * down to strings like {@code Change permanently?}. This is not decoration: someone arriving
 * from Carpet should not have to guess what a different shade means here. The markup is built
 * through {@link Msg}, a port of Carpet's {@code Messenger}.
 *
 * <p><b>Why a set does not survive a restart.</b> A rule changes how the world behaves, and a
 * rule left on silently spoils every later measurement: the numbers come out plausible and
 * comparable to nothing. So an ordinary set lasts until restart, with a {@code setDefault}
 * button always next to it — making persistence a decision rather than a side effect.
 *
 * <p>Text and layout come from Carpet Mod (MIT, (c) gnembon), see THIRD-PARTY.md.
 */
public final class RuleCommands {

    /** How Carpet names itself in headers. */
    private static final String FANCY_NAME = "PaperLab";

    /** Command root: used in buttons and hints. */
    private static final String IDENTIFIER = "carpet";

    /** The file the default values live in — mentioned in the tooltip. */
    private static final String CONF = "rules.conf";

    private static RuleDefaults defaults;

    private RuleCommands() {
    }

    public static void bind(final RuleDefaults store) {
        defaults = store;
    }

    /** Rule nodes attached to {@code /carpet}. */
    public static void attach(final LiteralArgumentBuilder<CommandSourceStack> carpet) {
        carpet.then(Commands.literal("list")
            .executes(ctx -> listSettings(ctx.getSource().getSender(),
                String.format("All %s settings", FANCY_NAME), sorted(LabRules.all())))
            .then(Commands.literal("defaults")
                .executes(ctx -> listDefaults(ctx.getSource().getSender())))
            .then(Commands.argument("tag", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    LabRules.categories().forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .executes(ctx -> {
                    final String tag = StringArgumentType.getString(ctx, "tag");
                    return listSettings(ctx.getSource().getSender(),
                        String.format("%s settings matching \"%s\" ", FANCY_NAME, tag),
                        sorted(LabRules.inCategory(tag)));
                })));

        carpet.then(Commands.literal("setDefault")
            .requires(source -> source.getSender().hasPermission(LabPermissions.RULE_DEFAULT))
            .then(ruleArgument()
                .then(Commands.argument("value", StringArgumentType.greedyString())
                    .suggests((ctx, builder) -> {
                        final LabRule<?> rule = LabRules.get(StringArgumentType.getString(ctx, "rule"));
                        if (rule != null) {
                            rule.options().forEach(builder::suggest);
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> setDefault(ctx.getSource().getSender(),
                        StringArgumentType.getString(ctx, "rule"),
                        StringArgumentType.getString(ctx, "value"))))));

        carpet.then(Commands.literal("removeDefault")
            .requires(source -> source.getSender().hasPermission(LabPermissions.RULE_DEFAULT))
            .then(ruleArgument()
                .executes(ctx -> removeDefault(ctx.getSource().getSender(),
                    StringArgumentType.getString(ctx, "rule")))));

        // One literal per rule: that is what makes name completion, value suggestion and,
        // above all, a separate permission per rule work.
        for (final LabRule<?> rule : LabRules.all()) {
            carpet.then(Commands.literal(rule.name())
                .requires(source -> source.getSender().hasPermission(rule.permission()))
                .executes(ctx -> displayRuleMenu(ctx.getSource().getSender(), rule))
                .then(Commands.argument("value", StringArgumentType.greedyString())
                    .suggests((ctx, builder) -> {
                        rule.options().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> set(ctx.getSource().getSender(), rule,
                        StringArgumentType.getString(ctx, "value")))));
        }
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> ruleArgument() {
        return Commands.argument("rule", StringArgumentType.word())
            .suggests((ctx, builder) -> {
                LabRules.all().forEach(rule -> builder.suggest(rule.name()));
                return builder.buildFuture();
            });
    }

    private static List<LabRule<?>> sorted(final Collection<LabRule<?>> rules) {
        final List<LabRule<?>> out = new ArrayList<>(rules);
        out.sort(Comparator.comparing(rule -> rule.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------ output

    /**
     * The no-argument overview: changed rules only, plus version and category list.
     *
     * <p>Exactly like {@code /carpet} in the mod. Showing every rule here would do more harm
     * than it looks: the screen means "what on this server is not vanilla", and that is lost
     * if the changed rules drown in the full list.
     */
    public static int listChanged(final CommandSender sender, final String version) {
        final int count = listSettings(sender,
            String.format("Current %s settings", FANCY_NAME), sorted(LabRules.changed()));

        Msg.m(sender, "g " + FANCY_NAME + " version: " + version);

        final List<Object> tags = new ArrayList<>();
        tags.add("w Browse categories:\n");
        for (final String category : LabRules.categories()) {
            tags.add("c [" + category + "]");
            tags.add("^g list all " + category + " settings");
            tags.add("!/" + IDENTIFIER + " list " + category);
            tags.add("w  ");
        }
        if (!tags.isEmpty()) {
            tags.remove(tags.size() - 1);
        }
        Msg.m(sender, tags.toArray());
        return count;
    }

    private static int listSettings(final CommandSender sender,
                                    final String title,
                                    final Collection<LabRule<?>> rules) {
        Msg.m(sender, "wb " + title + ":");
        for (final LabRule<?> rule : rules) {
            sender.sendMessage(displayInteractiveSetting(rule));
        }
        return rules.size();
    }

    private static int listDefaults(final CommandSender sender) {
        final List<LabRule<?>> overridden = new ArrayList<>();
        for (final LabRule<?> rule : sorted(LabRules.all())) {
            if (defaults != null && defaults.has(rule.name())) {
                overridden.add(rule);
            }
        }
        return listSettings(sender,
            String.format("Current %s Startup Settings from %s", FANCY_NAME, CONF), overridden);
    }

    /** A rule line with all its buttons — like {@code displayInteractiveSetting} in Carpet. */
    static Component displayInteractiveSetting(final LabRule<?> rule) {
        final List<Object> args = new ArrayList<>();
        args.add("w - " + rule.name() + " ");
        args.add("!/" + IDENTIFIER + " " + rule.name());
        args.add("^y " + rule.description());

        final List<String> options = new ArrayList<>(rule.options());
        if (!containsIgnoreCase(options, rule.printed())) {
            options.add(rule.printed());
        }
        for (final String option : options) {
            args.add(makeSetRuleButton(rule, option, true));
            args.add("w  ");
        }
        if (!args.isEmpty()) {
            args.remove(args.size() - 1);
        }
        // Unavailability is our addition: on stock Paper nothing reads some of these rules,
        // and showing them silently alongside working ones would be a lie.
        if (!LabRules.available(rule)) {
            args.add("r  (needs our core)");
        }
        return Msg.c(args.toArray());
    }

    /**
     * A value button. Colouring exactly as in Carpet: grey when the rule is at its vanilla
     * value, dark green for the vanilla option of a changed rule, yellow for the rest. The
     * current one is underlined; the vanilla one is bold on top of that.
     */
    private static Component makeSetRuleButton(final LabRule<?> rule,
                                               final String option,
                                               final boolean brackets) {
        final boolean isVanillaValue = !rule.changed();
        final boolean optionIsVanilla = option.equalsIgnoreCase(rule.printedVanilla());
        final boolean optionIsCurrent = option.equalsIgnoreCase(rule.printed());

        String style = isVanillaValue ? "g" : (optionIsVanilla ? "e" : "y");
        if (optionIsCurrent) {
            style = style + "u";
            if (optionIsVanilla) {
                style = style + "b";
            }
        }
        final String text = style + (brackets ? " [" : " ") + option + (brackets ? "]" : "");
        if (optionIsCurrent) {
            return Msg.c(text);
        }
        return Msg.c(text,
            "^g Switch to " + option + (optionIsVanilla ? " (default)" : ""),
            "?/" + IDENTIFIER + " " + rule.name() + " " + option);
    }

    /** The rule card — like {@code displayRuleMenu} in Carpet. */
    private static int displayRuleMenu(final CommandSender sender, final LabRule<?> rule) {
        Msg.m(sender, "");
        Msg.m(sender, "wb " + rule.name(), "!/" + IDENTIFIER + " " + rule.name(), "^g refresh");
        Msg.m(sender, "w " + rule.description());
        if (!rule.extra().isEmpty()) {
            Msg.m(sender, "g " + rule.extra());
        }

        if (!rule.categories().isEmpty()) {
            final List<Object> tags = new ArrayList<>();
            tags.add(Msg.c("w Tags: "));
            for (final String category : rule.categories()) {
                tags.add(Msg.c("c [" + category + "]",
                    "^g list all " + category + " settings",
                    "!/" + IDENTIFIER + " list " + category));
                tags.add(Msg.c("w , "));
            }
            tags.remove(tags.size() - 1);
            Msg.m(sender, tags.toArray());
        }

        Msg.m(sender, "w current value: ", String.format("%s %s (%s value)",
            booleanish(rule) ? "lb" : "nb", rule.printed(), rule.changed() ? "modified" : "default"));

        final List<Object> options = new ArrayList<>();
        options.add(Msg.c("w Options: ", "y [ "));
        for (final String option : rule.options()) {
            options.add(makeSetRuleButton(rule, option, false));
            options.add(Msg.c("w  "));
        }
        options.remove(options.size() - 1);
        options.add(Msg.c("y  ]"));
        Msg.m(sender, options.toArray());

        if (!LabRules.available(rule)) {
            Msg.m(sender, "r unavailable: this rule is read by core code, and we run on plain Paper");
        }
        if (defaults != null && defaults.has(rule.name())) {
            Msg.m(sender, "g on restart: " + defaults.get(rule.name()) + " ",
                "c [remove]",
                "^w stop applying this rule on restart",
                "!/" + IDENTIFIER + " removeDefault " + rule.name());
        }
        return 1;
    }

    /**
     * A value counting as "on" in Carpet's terms: boolean {@code true} or a number above zero.
     * Only the colour of the current value on the card depends on it.
     */
    private static boolean booleanish(final LabRule<?> rule) {
        final Object value = rule.value();
        if (value instanceof final Boolean flag) {
            return flag;
        }
        if (value instanceof final Number number) {
            return number.doubleValue() > 0.0D;
        }
        return false;
    }

    private static boolean containsIgnoreCase(final Collection<String> haystack, final String needle) {
        for (final String candidate : haystack) {
            if (candidate.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------- setting

    private static int set(final CommandSender sender, final LabRule<?> rule, final String raw) {
        if (!LabRules.available(rule)) {
            Msg.m(sender, "r this rule is read by core code, and we run on plain Paper");
            return 0;
        }
        final String rejected = rule.set(raw);
        if (rejected != null) {
            Msg.m(sender, "r " + rejected);
            if (!rule.extra().isEmpty()) {
                Msg.m(sender, "g " + rule.extra());
            }
            return 0;
        }

        // Carpet's line: "<rule>: <value>, [Change permanently?]".
        if (sender.hasPermission(LabPermissions.RULE_DEFAULT)) {
            Msg.m(sender, "w " + rule.name() + ": " + rule.printed() + ", ",
                "c [Change permanently?]",
                "^w click to keep the settings in " + CONF + " to save across restarts",
                "?/" + IDENTIFIER + " setDefault " + rule.name() + " " + rule.printed());
        } else {
            Msg.m(sender, "w " + rule.name() + ": " + rule.printed());
        }
        return 1;
    }

    private static int setDefault(final CommandSender sender, final String name, final String raw) {
        final LabRule<?> rule = LabRules.get(name);
        if (rule == null) {
            Msg.m(sender, "r Unknown rule: ", "rb " + name);
            return 0;
        }
        if (!sender.hasPermission(rule.permission())) {
            Msg.m(sender, "r Missing permission: ", "rb " + rule.permission());
            return 0;
        }
        // Validated through the same set path: a stored value and a hand-typed one must not
        // be interpreted differently.
        final String rejected = rule.set(raw);
        if (rejected != null) {
            Msg.m(sender, "r " + rejected);
            return 0;
        }
        defaults.set(rule.name(), rule.printed());
        Msg.m(sender, "gi Rule " + rule.name() + " will now default to " + rule.printed());
        return 1;
    }

    private static int removeDefault(final CommandSender sender, final String name) {
        final LabRule<?> rule = LabRules.get(name);
        if (rule == null) {
            Msg.m(sender, "r Unknown rule: ", "rb " + name);
            return 0;
        }
        if (!defaults.remove(rule.name())) {
            Msg.m(sender, "g Rule " + rule.name() + " was not being set on restart anyway");
            return 0;
        }
        Msg.m(sender, "gi Rule " + rule.name() + " will no longer be set on restart");
        return 1;
    }
}
