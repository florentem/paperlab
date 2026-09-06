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
 * Command registration.
 *
 * <h2>What lives where</h2>
 * <p>Commands that also exist in Carpet are named and placed <b>the way they are there</b> —
 * as separate top-level commands, and only there: {@code /log}, {@code /counter},
 * {@code /player}, {@code /tick}. Duplicating them as {@code /carpet} subcommands is not an
 * option: in the mod {@code /carpet} is the settings command, where every rule has one
 * settable value, while the tools live separately. Diverging from the mod breaks muscle
 * memory, and that costs more than a single entry point is worth.
 *
 * <p>Our own tools, which Carpet does not have, are attached <b>twice</b>: as a
 * {@code /carpet} subcommand, so the whole set tab-completes from one place, and as a
 * top-level command, so they can be typed directly. Those are {@code ghost}, {@code spawn},
 * {@code chunks} and {@code perms}.
 *
 * <p>The tree is rebuilt for each registration: the same builder cannot be reused for two
 * roots.
 *
 * <h2>What the core registers</h2>
 * <p>{@code /player} and the additions to vanilla {@code /tick} are registered by the core:
 * they need vanilla argument types and the already existing {@code tick} node. The plugin
 * does not touch them.
 */
public final class LabCommands {

    private record Entry(String name, String help) {
    }

    /** Our tools as {@code /carpet} subcommands. Same order as in the overview. */
    private static final List<Entry> INDEX = List.of(
        new Entry("ghost", LabMiscCommands.GHOST_HELP),
        new Entry("spawn", LabMiscCommands.SPAWN_HELP),
        new Entry("chunks", LabMiscCommands.CHUNKS_HELP),
        new Entry("cplay", paperlab.cplay.command.CPlayCommands.CPLAY_HELP),
        new Entry("zone", paperlab.zone.ZoneCommands.HELP),
        new Entry("perms", "permission list and which ones you have"));

    /** Carpet's commands. They are separate; listed here only for the overview. */
    private static final List<Entry> CARPET_LIKE = List.of(
        new Entry("/log", LabLogCommand.HELP),
        new Entry("/counter", LabMiscCommands.COUNTER_HELP),
        new Entry("/player", "bots: spawn, actions, kill"),
        new Entry("/playback", paperlab.cplay.command.CPlayCommands.PLAYBACK_HELP),
        new Entry("/capture", paperlab.cplay.command.CPlayCommands.CAPTURE_HELP),
        new Entry("/tick toggle", LabMiscCommands.TICK_HELP),
        new Entry("/tick zone", paperlab.zone.ZoneCommands.HELP),
        new Entry("/perimeterinfo", LabInfoCommands.PERIMETER_HELP),
        new Entry("/info block", LabInfoCommands.INFO_HELP),
        new Entry("/distance", LabInfoCommands.DISTANCE_HELP));

    private LabCommands() {
    }

    public static void register(final JavaPlugin plugin, final paperlab.zone.ZoneService zoneService) {
        final String version = plugin.getPluginMeta().getVersion();
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final var registrar = event.registrar();

            // /carpet holds only our tools and, below, the single-value rules. Carpet's own
            // commands are deliberately absent here: they live separately, as in the mod.
            final var carpet = Commands.literal("carpet")
                .executes(ctx -> overview(ctx.getSource(), version))
                .then(LabMiscCommands.ghostNode("ghost"))
                .then(LabMiscCommands.spawnNode("spawn"))
                .then(LabMiscCommands.chunksNode("chunks"))
                .then(paperlab.cplay.command.CPlayCommands.cplayNode("cplay"))
                .then(paperlab.zone.ZoneCommands.paperZoneNode(zoneService, "zone"))
                .then(Commands.literal("perms").executes(ctx -> perms(ctx.getSource())));
            // Rules — what /carpet exists for in the mod itself.
            RuleCommands.attach(carpet);
            registrar.register(carpet.build(), "Technical Lab tools", List.of("lab"));

            // Carpet's commands, under their own names.
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

            // Tick Zones
            registrar.register(paperlab.zone.ZoneCommands.paperZoneNode(zoneService, "zone").build(),
                paperlab.zone.ZoneCommands.HELP);

            // Ours also at top level, so they can be typed directly. The name chunks is taken
            // by vanilla up there, hence the prefix.
            registrar.register(LabMiscCommands.ghostNode("ghost").build(),
                LabMiscCommands.GHOST_HELP);
            registrar.register(LabMiscCommands.spawnNode("labspawn").build(),
                LabMiscCommands.SPAWN_HELP);
            registrar.register(LabMiscCommands.chunksNode("labchunks").build(),
                LabMiscCommands.CHUNKS_HELP);
        });
    }

    /**
     * The toolset's permissions and which of them the sender holds.
     *
     * <p>Not for checking but for setup: seeing the full node list without reading the
     * sources, and spotting at once what is missing. The nodes themselves are registered with
     * Bukkit, so LuckPerms suggests them in its own commands too.
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
                .clickEvent(ClickEvent.suggestCommand(
                    "/lp user " + sender.getName() + " permission set " + node.getKey()))
                .append(Component.text("  " + node.getValue(), NamedTextColor.DARK_GRAY)));
        }
        return 1;
    }

    /** A short list of what this build offers at all. The lines are clickable. */
    private static int overview(final CommandSourceStack source, final String version) {
        final var sender = source.getSender();
        sender.sendMessage(Component.text("Technical Lab", NamedTextColor.AQUA)
            .append(Component.text("  " + CoreBridge.describe(), NamedTextColor.DARK_GRAY)));

        for (final Entry entry : INDEX) {
            sender.sendMessage(Component.text("  /carpet " + entry.name(), NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand("/carpet " + entry.name() + " "))
                .append(Component.text("  " + entry.help(), NamedTextColor.GRAY)));
        }

        // The separate commands are shown here too: there is no other way to learn about
        // them, and they cannot be attached as subcommands — in Carpet they stand alone.
        sender.sendMessage(Component.text("  Carpet-style, separate commands:",
            NamedTextColor.DARK_GRAY));
        for (final Entry entry : CARPET_LIKE) {
            sender.sendMessage(Component.text("  " + entry.name(), NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand(entry.name() + " "))
                .append(Component.text("  " + entry.help(), NamedTextColor.GRAY)));
        }

        // What follows is exactly what /carpet prints in the mod: changed rules, version and
        // categories. A deviation from vanilla must be visible at once and unasked: a
        // forgotten rule is precisely what spoils measurements.
        RuleCommands.listChanged(sender, version);

        return INDEX.size();
    }
}
