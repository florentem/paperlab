package paperlab.command;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;
import paperlab.core.CoreBridge;

/**
 * Регистрация команд.
 *
 * <h2>Что где лежит</h2>
 * <p>Команды, которые есть и в Carpet, называются и живут <b>так же, как там</b> —
 * отдельными командами верхнего уровня, и только там: {@code /log}, {@code /counter},
 * {@code /player}, {@code /tick}. Дублировать их подкомандами {@code /carpet} нельзя:
 * в самом моде {@code /carpet} — это команда настроек, где у каждого правила одно
 * задаваемое значение, а инструменты живут отдельно. Расхождение с модом ломает
 * мышечную память, и это дороже, чем польза от единого входа.
 *
 * <p>Наши собственные инструменты, которых в Carpet нет, вешаются <b>дважды</b>:
 * подкомандой {@code /carpet} — чтобы находиться табом из одной точки, — и командой
 * верхнего уровня, чтобы писать их напрямую. Это {@code ghost}, {@code spawn},
 * {@code chunks}, {@code perms}.
 *
 * <p>Дерево строится заново для каждой регистрации: один и тот же builder переиспользовать
 * для двух корней нельзя.
 *
 * <h2>Что регистрирует ядро</h2>
 * <p>{@code /player} и дополнения к ванильному {@code /tick} регистрирует ядро: им нужны
 * ванильные типы аргументов и уже существующий узел {@code tick}. Плагин их не трогает.
 */
public final class LabCommands {

    private record Entry(String name, String help) {
    }

    /** Наши инструменты — подкомандами {@code /carpet}. Порядок тот же, что в подсказке. */
    private static final List<Entry> INDEX = List.of(
        new Entry("ghost", LabMiscCommands.GHOST_HELP),
        new Entry("spawn", LabMiscCommands.SPAWN_HELP),
        new Entry("chunks", LabMiscCommands.CHUNKS_HELP),
        new Entry("cplay", paperlab.cplay.command.CPlayCommands.CPLAY_HELP),
        new Entry("perms", "permission list and which ones you have"));

    /** Команды Carpet. Они отдельные; здесь только для справки в подсказке. */
    private static final List<Entry> CARPET_LIKE = List.of(
        new Entry("/log", LabLogCommand.HELP),
        new Entry("/counter", LabMiscCommands.COUNTER_HELP),
        new Entry("/player", "bots: spawn, actions, kill"),
        new Entry("/playback", paperlab.cplay.command.CPlayCommands.PLAYBACK_HELP),
        new Entry("/capture", paperlab.cplay.command.CPlayCommands.CAPTURE_HELP),
        new Entry("/tick toggle", LabMiscCommands.TICK_HELP),
        new Entry("/perimeterinfo", LabInfoCommands.PERIMETER_HELP),
        new Entry("/info block", LabInfoCommands.INFO_HELP),
        new Entry("/distance", LabInfoCommands.DISTANCE_HELP));

    private LabCommands() {
    }

    public static void register(final JavaPlugin plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final var registrar = event.registrar();

            // /carpet — только наши инструменты и, позже, правила с одним значением.
            // Команд Carpet здесь нет намеренно: они живут отдельно, как в самом моде.
            final var carpet = Commands.literal("carpet")
                .executes(ctx -> overview(ctx.getSource()))
                .then(LabMiscCommands.ghostNode("ghost"))
                .then(LabMiscCommands.spawnNode("spawn"))
                .then(LabMiscCommands.chunksNode("chunks"))
                .then(paperlab.cplay.command.CPlayCommands.cplayNode("cplay"))
                .then(Commands.literal("perms").executes(ctx -> perms(ctx.getSource())));
            // Правила — то, ради чего /carpet и существует в самом моде.
            RuleCommands.attach(carpet);
            registrar.register(carpet.build(), "Technical Lab tools", List.of("lab"));

            // Команды Carpet — под их собственными именами.
            registrar.register(LabLogCommand.node("log").build(), LabLogCommand.HELP);
            registrar.register(LabMiscCommands.counterNode("counter").build(),
                LabMiscCommands.COUNTER_HELP);
            registrar.register(LabInfoCommands.perimeterNode("perimeterinfo").build(),
                LabInfoCommands.PERIMETER_HELP);
            registrar.register(LabInfoCommands.infoNode("info").build(),
                LabInfoCommands.INFO_HELP);
            registrar.register(LabInfoCommands.distanceNode("distance").build(),
                LabInfoCommands.DISTANCE_HELP);

            // Capture & Playback
            registrar.register(paperlab.cplay.command.CPlayCommands.playbackNode("playback").build(),
                paperlab.cplay.command.CPlayCommands.PLAYBACK_HELP);
            registrar.register(paperlab.cplay.command.CPlayCommands.captureNode("capture").build(),
                paperlab.cplay.command.CPlayCommands.CAPTURE_HELP);
            registrar.register(paperlab.cplay.command.CPlayCommands.cplayNode("cplay").build(),
                paperlab.cplay.command.CPlayCommands.CPLAY_HELP);

            // Наши — ещё и верхним уровнем, чтобы писать напрямую. Имя chunks наверху
            // занято ванилью, поэтому там префикс.
            registrar.register(LabMiscCommands.ghostNode("ghost").build(),
                LabMiscCommands.GHOST_HELP);
            registrar.register(LabMiscCommands.spawnNode("labspawn").build(),
                LabMiscCommands.SPAWN_HELP);
            registrar.register(LabMiscCommands.chunksNode("labchunks").build(),
                LabMiscCommands.CHUNKS_HELP);
        });
    }

    /**
     * Права инструментария и то, какие из них есть у отправителя.
     *
     * <p>Нужна не для проверки, а для настройки: увидеть полный список узлов, не читая
     * исходники, и сразу понять, чего не хватает. Сами узлы зарегистрированы в Bukkit,
     * поэтому LuckPerms подсказывает их и в своих командах.
     */
    private static int perms(final CommandSourceStack source) {
        final var sender = source.getSender();
        sender.sendMessage(Component.text("permissions", NamedTextColor.AQUA)
            .append(Component.text("  grant all: ", NamedTextColor.DARK_GRAY))
            .append(Component.text(LabPermissions.ROOT, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand(
                    "/lp user " + sender.getName() + " permission set " + LabPermissions.ROOT))));

        sender.sendMessage(Component.text("groups:", NamedTextColor.GOLD));
        for (final var group : LabPermissions.groups().entrySet()) {
            final boolean has = sender.hasPermission(group.getKey());
            sender.sendMessage(Component.text(has ? "  * " : "  o ",
                    has ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .append(Component.text(group.getKey(),
                    has ? NamedTextColor.WHITE : NamedTextColor.GRAY))
                .clickEvent(ClickEvent.suggestCommand(
                    "/lp user " + sender.getName() + " permission set " + group.getKey()))
                .append(Component.text("  " + group.getValue(), NamedTextColor.DARK_GRAY)));
        }

        sender.sendMessage(Component.text("nodes:", NamedTextColor.AQUA));
        for (final var node : LabPermissions.ordered()) {
            final boolean has = sender.hasPermission(node.getKey());
            sender.sendMessage(Component.text(has ? "  + " : "  - ",
                    has ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .append(Component.text(node.getKey(),
                    has ? NamedTextColor.WHITE : NamedTextColor.GRAY))
                .append(Component.text("  " + node.getValue(), NamedTextColor.DARK_GRAY)));
        }
        return 1;
    }

    /** Короткий список: что вообще есть в этом ядре. Строки кликабельны. */
    private static int overview(final CommandSourceStack source) {
        final var sender = source.getSender();
        sender.sendMessage(Component.text("Technical Lab", NamedTextColor.AQUA)
            .append(Component.text("  " + CoreBridge.describe(), NamedTextColor.DARK_GRAY)));

        for (final Entry entry : INDEX) {
            sender.sendMessage(Component.text("  /carpet " + entry.name(), NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand("/carpet " + entry.name() + " "))
                .append(Component.text("  " + entry.help(), NamedTextColor.GRAY)));
        }

        // Правила: показываем только изменённые. Полный список — /carpet list.
        // Смысл в том, чтобы отклонение от ванильного было видно сразу и без запроса:
        // именно забытое правило и портит замеры.
        final int changed = paperlab.rules.LabRules.changedCount();
        sender.sendMessage(Component.text("  /carpet list", NamedTextColor.WHITE)
            .clickEvent(ClickEvent.suggestCommand("/carpet list"))
            .append(Component.text("  rules"
                + (changed == 0 ? " - all vanilla" : ", changed: " + changed),
                changed == 0 ? NamedTextColor.GRAY : NamedTextColor.GOLD)));
        for (final paperlab.rules.LabRule<?> rule : paperlab.rules.LabRules.all()) {
            if (rule.changed()) {
                sender.sendMessage(RuleCommands.line(rule, sender));
            }
        }

        // Отдельные команды показываем здесь же: иначе о них никак не узнать,
        // а подкомандами вешать нельзя — в Carpet они отдельные.
        sender.sendMessage(Component.text("  Carpet-style, separate commands:",
            NamedTextColor.DARK_GRAY));
        for (final Entry entry : CARPET_LIKE) {
            sender.sendMessage(Component.text("  " + entry.name(), NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand(entry.name() + " "))
                .append(Component.text("  " + entry.help(), NamedTextColor.GRAY)));
        }

        return INDEX.size();
    }
}
