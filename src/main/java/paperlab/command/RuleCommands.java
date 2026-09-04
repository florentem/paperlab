package paperlab.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import paperlab.rules.LabRule;
import paperlab.rules.LabRules;
import paperlab.rules.RuleDefaults;

/**
 * Правила под {@code /carpet} — как в Carpet: правило и одно задаваемое значение.
 *
 * <pre>
 * /carpet                        обзор: инструменты и изменённые правила
 * /carpet list                   все правила с текущими значениями
 * /carpet &lt;правило&gt;              описание, текущее и ванильное значение
 * /carpet &lt;правило&gt; &lt;значение&gt;   установить до перезапуска
 * /carpet setDefault &lt;правило&gt; &lt;значение&gt;
 * /carpet removeDefault &lt;правило&gt;
 * /carpet defaults               что применится после перезапуска
 * </pre>
 *
 * <p><b>Почему установка не переживает перезапуск.</b> Правило меняет поведение мира, и
 * забытое включённое правило молча портит все последующие замеры: числа получаются,
 * выглядят правдоподобно и ни с чем не сопоставимы. Поэтому обычная установка действует
 * до перезапуска, а после каждой установки предлагается ссылка на {@code setDefault} —
 * чтобы сохранение было решением, а не побочным эффектом.
 */
public final class RuleCommands {

    private static RuleDefaults defaults;

    private RuleCommands() {
    }

    public static void bind(final RuleDefaults store) {
        defaults = store;
    }

    /** Узлы правил, которые подвешиваются к {@code /carpet}. */
    public static void attach(final LiteralArgumentBuilder<CommandSourceStack> carpet) {
        carpet.then(Commands.literal("list").executes(ctx -> list(ctx.getSource().getSender())));
        carpet.then(Commands.literal("defaults").executes(ctx -> showDefaults(ctx.getSource().getSender())));

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

        // Каждое правило — свой литерал: так работает таб по именам, подсказка значений
        // и, главное, отдельное право на каждое правило.
        for (final LabRule<?> rule : LabRules.all()) {
            carpet.then(Commands.literal(rule.name())
                .requires(source -> source.getSender().hasPermission(rule.permission()))
                .executes(ctx -> info(ctx.getSource().getSender(), rule))
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

    // ------------------------------------------------------------------ вывод

    private static int list(final CommandSender sender) {
        sender.sendMessage(Component.text("rules", NamedTextColor.AQUA)
            .append(Component.text("  changed: " + LabRules.changedCount() + " of "
                + LabRules.all().size(), NamedTextColor.DARK_GRAY)));
        for (final LabRule<?> rule : LabRules.all()) {
            sender.sendMessage(line(rule, sender));
        }
        return LabRules.all().size();
    }

    /** Строка правила: имя, значение, признак изменения и недоступности. */
    static Component line(final LabRule<?> rule, final CommandSender sender) {
        final boolean available = LabRules.available(rule);
        Component out = Component.text("  " + rule.name(),
                available ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
            .clickEvent(ClickEvent.suggestCommand("/carpet " + rule.name() + " "))
            .hoverEvent(HoverEvent.showText(Component.text(rule.description())));
        out = out.append(Component.text("  " + rule.printed(),
            rule.changed() ? NamedTextColor.GOLD : NamedTextColor.GRAY));
        if (rule.changed()) {
            out = out.append(Component.text("  (vanilla " + rule.printedVanilla() + ")",
                NamedTextColor.DARK_GRAY));
        }
        if (!available) {
            out = out.append(Component.text("  needs our core", NamedTextColor.RED));
        }
        if (defaults != null && defaults.has(rule.name())) {
            out = out.append(Component.text("  ★", NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text(
                    "after restart: " + defaults.get(rule.name())))));
        }
        return out;
    }

    private static int info(final CommandSender sender, final LabRule<?> rule) {
        sender.sendMessage(Component.text(rule.name(), NamedTextColor.AQUA)
            .decoration(TextDecoration.BOLD, true));
        sender.sendMessage(Component.text("  " + rule.description(), NamedTextColor.GRAY));
        if (!rule.extra().isEmpty()) {
            sender.sendMessage(Component.text("  " + rule.extra(), NamedTextColor.DARK_GRAY));
        }
        sender.sendMessage(Component.text("  now ", NamedTextColor.DARK_GRAY)
            .append(Component.text(rule.printed(),
                rule.changed() ? NamedTextColor.GOLD : NamedTextColor.WHITE))
            .append(Component.text("   vanilla ", NamedTextColor.DARK_GRAY))
            .append(Component.text(rule.printedVanilla(), NamedTextColor.GRAY)));

        if (!LabRules.available(rule)) {
            sender.sendMessage(Component.text(
                "  unavailable: this rule is read by core code, and we run on plain Paper",
                NamedTextColor.RED));
            return 0;
        }

        Component options = Component.text("  ", NamedTextColor.DARK_GRAY);
        for (final String option : rule.options()) {
            options = options.append(Component.text("[" + option + "] ",
                    option.equals(rule.printed()) ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand("/carpet " + rule.name() + " " + option)));
        }
        sender.sendMessage(options);
        if (defaults != null && defaults.has(rule.name())) {
            sender.sendMessage(Component.text("  after restart: " + defaults.get(rule.name()),
                    NamedTextColor.AQUA)
                .append(Component.text("  [remove]", NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/carpet removeDefault " + rule.name()))));
        }
        return 1;
    }

    // -------------------------------------------------------------- установка

    private static int set(final CommandSender sender, final LabRule<?> rule, final String raw) {
        if (!LabRules.available(rule)) {
            sender.sendMessage(Component.text(
                "this rule is read by core code, and we run on plain Paper", NamedTextColor.RED));
            return 0;
        }
        final String rejected = rule.set(raw);
        if (rejected != null) {
            sender.sendMessage(Component.text(rejected, NamedTextColor.RED));
            if (!rule.extra().isEmpty()) {
                sender.sendMessage(Component.text("  " + rule.extra(), NamedTextColor.DARK_GRAY));
            }
            return 0;
        }

        sender.sendMessage(Component.text(rule.name() + " = ", NamedTextColor.GRAY)
            .append(Component.text(rule.printed(),
                rule.changed() ? NamedTextColor.GOLD : NamedTextColor.GREEN)));

        if (sender.hasPermission(LabPermissions.RULE_DEFAULT)) {
            // Предложение, а не действие: сохранение правила должно быть решением.
            sender.sendMessage(Component.text("  until restart. ", NamedTextColor.DARK_GRAY)
                .append(Component.text("[make it the default]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(
                        "/carpet setDefault " + rule.name() + " " + rule.printed()))
                    .hoverEvent(HoverEvent.showText(Component.text(
                        "/carpet setDefault " + rule.name() + " " + rule.printed())))));
        } else {
            sender.sendMessage(Component.text("  until restart", NamedTextColor.DARK_GRAY));
        }
        return 1;
    }

    private static int setDefault(final CommandSender sender, final String name, final String raw) {
        final LabRule<?> rule = LabRules.get(name);
        if (rule == null) {
            sender.sendMessage(Component.text("no such rule: " + name, NamedTextColor.RED));
            return 0;
        }
        if (!sender.hasPermission(rule.permission())) {
            sender.sendMessage(Component.text("missing permission: " + rule.permission(), NamedTextColor.RED));
            return 0;
        }
        // Проверяем через ту же установку: сохранённое и введённое руками не должны
        // расходиться в трактовке.
        final String rejected = rule.set(raw);
        if (rejected != null) {
            sender.sendMessage(Component.text(rejected, NamedTextColor.RED));
            return 0;
        }
        defaults.set(rule.name(), rule.printed());
        sender.sendMessage(Component.text(rule.name() + " = " + rule.printed(), NamedTextColor.GREEN)
            .append(Component.text("  saved, applies after restart too",
                NamedTextColor.DARK_GRAY)));
        return 1;
    }

    private static int removeDefault(final CommandSender sender, final String name) {
        final LabRule<?> rule = LabRules.get(name);
        if (rule == null) {
            sender.sendMessage(Component.text("no such rule: " + name, NamedTextColor.RED));
            return 0;
        }
        if (!defaults.remove(rule.name())) {
            sender.sendMessage(Component.text(rule.name() + ": there was no default anyway",
                NamedTextColor.DARK_GRAY));
            return 0;
        }
        sender.sendMessage(Component.text(rule.name() + ": default removed",
                NamedTextColor.GREEN)
            .append(Component.text("  current (" + rule.printed() + ") lasts until restart",
                NamedTextColor.DARK_GRAY)));
        return 1;
    }

    private static int showDefaults(final CommandSender sender) {
        if (defaults.all().isEmpty()) {
            sender.sendMessage(Component.text(
                "no defaults set: after a restart every rule is vanilla",
                NamedTextColor.DARK_GRAY));
            return 0;
        }
        sender.sendMessage(Component.text("applies after restart", NamedTextColor.AQUA));
        defaults.all().forEach((rule, value) ->
            sender.sendMessage(Component.text("  " + rule + " = " + value, NamedTextColor.WHITE)
                .append(Component.text("  [remove]", NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/carpet removeDefault " + rule)))));
        return defaults.all().size();
    }
}
