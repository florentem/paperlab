package paperlab.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import paperlab.log.LabHud;
import paperlab.log.LabLogger;
import paperlab.log.LabLoggers;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /log} — подписки HUD, как в Carpet.
 *
 * <pre>
 * /log                        что есть и на что подписан
 * /log tps                    вкл/выкл
 * /log mobcaps [ник] [full]
 * /log counter &lt;цвет&gt; [full]
 * /log spawn [причина]
 * /log &lt;имя&gt; clear            снять подписки этого логгера
 * /log clear                  снять всё
 * </pre>
 *
 * Подписок на один логгер может быть несколько — по одной на цель. Повторная команда
 * с той же целью выключает её, с другим флагом — заменяет.
 */
public final class LabLogCommand {

    private LabLogCommand() {
    }

    /** {@value} — подпись в списке команд. */
    public static final String HELP = "tab-list subscriptions";

    public static LiteralArgumentBuilder<CommandSourceStack> node(final String name) {
        return Commands.literal(name)
                .requires(source -> source.getSender() instanceof Player
                    && source.getSender().hasPermission(LabPermissions.LOG))
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
            } else if (logger.freeform()) {
                opts.add("full");
            } else {
                opts.addAll(logger.options());
            }
        }
        opts.add("clear");
        return opts;
    }

    private static int list(final CommandSourceStack source) {
        final Player player = (Player) source.getSender();
        Component line = Component.empty();
        boolean first = true;
        for (final LabLogger logger : LabLoggers.all()) {
            if (!player.hasPermission(LabLoggers.permissionOf(logger))) {
                continue;
            }
            if (!first) {
                line = line.append(Component.text("  "));
            }
            first = false;
            final var options = logger.optionsFor(player.getName());
            if (options.isEmpty()) {
                line = line.append(Component.text(logger.name(), NamedTextColor.DARK_GRAY));
                continue;
            }
            boolean firstOption = true;
            for (final String option : options) {
                if (!firstOption) {
                    line = line.append(Component.text(" "));
                }
                firstOption = false;
                line = line.append(Component.text(label(logger, option), NamedTextColor.GREEN));
            }
        }
        player.sendMessage(line);
        return 1;
    }

    private static int toggle(final CommandContext<CommandSourceStack> ctx, final @Nullable String option) {
        final Player player = (Player) ctx.getSource().getSender();
        final String loggerName = StringArgumentType.getString(ctx, "name");

        final LabLogger logger = LabLoggers.get(loggerName);
        if (logger == null) {
            player.sendMessage(Component.text("no such logger: " + loggerName, NamedTextColor.RED));
            return 0;
        }
        // Логгер — аргумент, а не литерал, поэтому право проверяется здесь, а не в requires.
        final String permission = LabLoggers.permissionOf(logger);
        if (!player.hasPermission(permission)) {
            player.sendMessage(Component.text("missing permission: " + permission, NamedTextColor.RED));
            return 0;
        }

        if (option != null && option.equalsIgnoreCase("clear")) {
            logger.unsubscribeAll(player.getName());
            refresh(player);
            player.sendMessage(Component.text(logger.name() + " off", NamedTextColor.DARK_GRAY));
            return 1;
        }

        final boolean on = logger.toggle(player.getName(), option);
        refresh(player);
        final String text = label(logger, option == null ? "" : option);
        player.sendMessage(Component.text(text + (on ? " on" : " off"),
            on ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        return 1;
    }

    private static int clearAll(final CommandSourceStack source) {
        final Player player = (Player) source.getSender();
        LabLoggers.unsubscribeAll(player.getName());
        LabHud.clear(player);
        player.sendMessage(Component.text("off", NamedTextColor.DARK_GRAY));
        return 1;
    }

    private static String label(final LabLogger logger, final String option) {
        return option.isEmpty() ? logger.name() : logger.name() + ":" + option.replace(' ', '/');
    }

    /** Если подписок больше нет — убрать футер сразу. */
    private static void refresh(final Player player) {
        for (final LabLogger logger : LabLoggers.all()) {
            if (logger.subscribed(player.getName())) {
                return;
            }
        }
        LabHud.clear(player);
    }
}
