package paperlab.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import paperlab.log.LabHud;
import paperlab.log.LabLogger;
import paperlab.log.LabLoggers;
import paperlab.text.Msg;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /log} — HUD subscriptions, as in Carpet.
 *
 * <pre>
 * /log                        what exists and what you are subscribed to
 * /log tps                    toggle
 * /log mobcaps [name] [full]
 * /log counter &lt;colour&gt; [full]
 * /log spawn [reason]
 * /log &lt;name&gt; clear           drop this logger's subscriptions
 * /log clear                  drop everything
 * </pre>
 *
 * A logger can hold several subscriptions, one per target. Repeating the command with the same
 * target turns it off; with a different flag, replaces it.
 *
 * <p>Text and layout come from Carpet Mod (MIT, (c) gnembon), see THIRD-PARTY.md.
 */
public final class LabLogCommand {

    private LabLogCommand() {
    }

    /** {@value} — the caption in the command list. */
    public static final String HELP = "tab-list subscriptions";

    public static LiteralArgumentBuilder<CommandSourceStack> node(final String name) {
        return Commands.literal(name)
                // The console sees the command but gets "For players only", as in Carpet.
                // Hiding it from the tree would be easier for us and more confusing for someone
                // looking for it in the list and not finding it.
                .requires(source -> source.getSender().hasPermission(LabPermissions.LOG))
                .executes(ctx -> list(ctx.getSource()))
                .then(Commands.literal("clear").executes(ctx -> clearAll(ctx.getSource())))
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (final LabLogger candidate : LabLoggers.all()) {
                                if (ctx.getSource().getSender()
                                    .hasPermission(LabLoggers.permissionOf(candidate))) {
                                    builder.suggest(candidate.name());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> toggle(ctx, null))
                        .then(
                            Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    suggestions(StringArgumentType.getString(ctx, "name"))
                                        .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> toggle(ctx, StringArgumentType.getString(ctx, "target")))
                                .then(
                                    Commands.argument("flag", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("full");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> toggle(ctx,
                                            StringArgumentType.getString(ctx, "target")
                                                + " " + StringArgumentType.getString(ctx, "flag")))
                                )
                        )
                )
        ;
    }

    private static List<String> suggestions(final String loggerName) {
        final LabLogger logger = LabLoggers.get(loggerName);
        final List<String> opts = new ArrayList<>();
        if (logger != null) {
            if (logger == LabLoggers.MOBCAPS) {
                opts.add("full");
                Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
            } else if (logger == LabLoggers.COUNTER) {
                opts.add("full");
                for (final net.minecraft.world.item.DyeColor colour
                    : net.minecraft.world.item.DyeColor.values()) {
                    opts.add(colour.getName());
                }
            } else if (logger == LabLoggers.SPAWN) {
                opts.addAll(paperlab.spawn.SpawnView.options());
            } else if (logger == LabLoggers.ITEM) {
                opts.addAll(logger.options());
            } else if (logger == LabLoggers.MICROTIMING) {
                opts.addAll(logger.options());
            } else if (logger == LabLoggers.MOVEMENT) {
                opts.addAll(logger.options());
                opts.add("@s");
                Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
            } else if (logger.freeform()) {
                opts.add("full");
            } else {
                opts.addAll(logger.options());
            }
        }
        opts.add("clear");
        return opts;
    }

    /**
     * The logger list — Carpet's layout: separator, header, one line per logger with option
     * buttons and an unsubscribe cross.
     *
     * <p>There is exactly one difference from Carpet, and it comes from our subscription model:
     * Carpet has one option per logger, we can have several (mobcaps of several players at
     * once). So <b>every</b> active option is highlighted green, not just one.
     */
    private static int list(final CommandSourceStack source) {
        if (!(source.getSender() instanceof final Player player)) {
            Msg.m(source.getSender(), "w For players only");
            return 0;
        }
        Msg.m(player, "w _____________________");
        Msg.m(player, "w Available logging options:");
        for (final LabLogger logger : LabLoggers.all()) {
            if (!player.hasPermission(LabLoggers.permissionOf(logger))) {
                continue;
            }
            final String name = logger.name();
            final Collection<String> active = logger.optionsFor(player.getName());
            final boolean subscribed = !active.isEmpty();
            final String colour = subscribed ? "w" : "g";

            final List<Object> comp = new ArrayList<>();
            comp.add("w  - " + name + ": ");

            final List<String> buttons = buttons(logger, active);
            if (buttons.isEmpty()) {
                if (subscribed) {
                    comp.add("l Subscribed ");
                } else {
                    comp.add(colour + " [Subscribe] ");
                    comp.add("^w subscribe to " + name);
                    comp.add("!/log " + name);
                }
            } else {
                for (final String option : buttons) {
                    if (containsIgnoreCase(active, option)) {
                        comp.add("l [" + option + "] ");
                    } else {
                        comp.add(colour + " [" + option + "] ");
                        comp.add("^w subscribe to " + name + " " + option);
                        comp.add("!/log " + name + " " + option);
                    }
                }
            }
            if (subscribed) {
                comp.add("nb [X]");
                comp.add("^w Click to unsubscribe");
                comp.add("!/log " + name + " clear");
            }
            Msg.m(player, comp.toArray());
        }
        return 1;
    }

    /**
     * Which buttons to show for a logger: its options plus any active subscription that is not
     * in the option list.
     *
     * <p>The second part is not a detail: for mobcaps the target is a player name, which no
     * list can anticipate, and without this your own subscription would have no off button.
     */
    private static List<String> buttons(final LabLogger logger, final Collection<String> active) {
        final List<String> out = new ArrayList<>();
        for (final String option : suggestions(logger.name())) {
            if (!option.equals("clear") && !containsIgnoreCase(out, option)) {
                out.add(option);
            }
        }
        for (final String option : active) {
            if (!containsIgnoreCase(out, option)) {
                out.add(option);
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(final Collection<String> haystack, final String needle) {
        for (final String candidate : haystack) {
            if (candidate.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int toggle(final CommandContext<CommandSourceStack> ctx, final @Nullable String option) {
        if (!(ctx.getSource().getSender() instanceof final Player player)) {
            Msg.m(ctx.getSource().getSender(), "w For players only");
            return 0;
        }
        final String loggerName = StringArgumentType.getString(ctx, "name");

        final LabLogger logger = LabLoggers.get(loggerName);
        if (logger == null) {
            Msg.m(player, "r Unknown logger: ", "rb " + loggerName);
            return 0;
        }
        // The logger is an argument rather than a literal, so the permission is checked here
        // rather than in requires.
        final String permission = LabLoggers.permissionOf(logger);
        if (!player.hasPermission(permission)) {
            Msg.m(player, "r Missing permission: ", "rb " + permission);
            return 0;
        }

        if (option != null && option.equalsIgnoreCase("clear")) {
            logger.unsubscribeAll(player.getName());
            refresh(player);
            Msg.m(player, "gi Unsubscribed from " + logger.name());
            return 1;
        }

        final boolean on = logger.toggle(player.getName(), option);
        refresh(player);
        final String suffix = option == null || option.isBlank() ? "" : "(" + option + ")";
        Msg.m(player, "gi " + (on ? "Subscribed to " : "Unsubscribed from ")
            + logger.name() + suffix);
        return 1;
    }

    private static int clearAll(final CommandSourceStack source) {
        if (!(source.getSender() instanceof final Player player)) {
            Msg.m(source.getSender(), "w For players only");
            return 0;
        }
        LabLoggers.unsubscribeAll(player.getName());
        LabHud.clear(player);
        Msg.m(player, "gi Unsubscribed from all logs");
        return 1;
    }


    /** If no subscriptions remain, clear the footer at once. */
    private static void refresh(final Player player) {
        for (final LabLogger logger : LabLoggers.all()) {
            if (logger.subscribed(player.getName())) {
                return;
            }
        }
        LabHud.clear(player);
    }
}
