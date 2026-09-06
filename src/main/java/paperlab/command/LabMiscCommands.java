package paperlab.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import paperlab.counter.LabCounter;
import paperlab.counter.LabCounters;
import paperlab.counter.WoolColors;
import paperlab.ghost.LabGhost;
import paperlab.mobcap.MobcapService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Nodes for the {@code counter}, {@code ghost}, {@code tick} and {@code chunks} commands.
 *
 * <p>Each method returns a <b>new</b> builder rather than a finished node: the same tree is
 * attached twice — as a top-level command and as a {@code /carpet} subcommand. One builder
 * cannot be reused for two registrations.
 */
public final class LabMiscCommands {

    private LabMiscCommands() {
    }

    // ------------------------------------------------------------------ counter

    /** {@value} — the caption in the command list. */
    public static final String COUNTER_HELP = "hopper counters";

    public static LiteralArgumentBuilder<CommandSourceStack> counterNode(final String name) {
        return Commands.literal(name)
                .requires(source -> source.getSender().hasPermission(LabPermissions.COUNTER))
                .executes(ctx -> listCounters(ctx.getSource()))
                .then(Commands.literal("reset")
                    .requires(source -> source.getSender().hasPermission(LabPermissions.COUNTER_EDIT))
                    .executes(ctx -> {
                    final int n = LabCounters.resetAll(worldOf(ctx.getSource()).getGameTime());
                    ctx.getSource().getSender().sendMessage(
                        Component.text("reset: " + n, NamedTextColor.DARK_GRAY));
                    return n;
                }))
                .then(Commands.literal("scan")
                    .requires(source -> source.getSender().hasPermission(LabPermissions.COUNTER_EDIT))
                    .executes(ctx -> scan(ctx.getSource(), 16))
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> scan(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.argument("color", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final DyeColor colour : DyeColor.values()) {
                            builder.suggest(colour.getName());
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> showCounter(ctx.getSource(),
                        StringArgumentType.getString(ctx, "color")))
                    .then(Commands.literal("reset")
                        .requires(source -> source.getSender().hasPermission(LabPermissions.COUNTER_EDIT))
                        .executes(ctx -> resetCounter(ctx.getSource(),
                            StringArgumentType.getString(ctx, "color")))))
        ;
    }

    private static int scan(final CommandSourceStack source, final int radius) {
        if (!(source.getSender() instanceof final Player player)) {
            source.getSender().sendMessage(Component.text("players only", NamedTextColor.RED));
            return 0;
        }
        final int found = LabCounters.scan(player.getLocation(), radius);
        player.sendMessage(Component.text(
            "tracked hoppers: " + found + " (total " + LabCounters.trackedCount() + ")",
            NamedTextColor.AQUA));
        return found;
    }

    private static int listCounters(final CommandSourceStack source) {
        final var counters = LabCounters.active();
        if (counters.isEmpty()) {
            source.getSender().sendMessage(Component.text(
                "no counters. Point a hopper into wool and run /counter scan",
                NamedTextColor.DARK_GRAY));
            return 0;
        }
        final long gameTime = worldOf(source).getGameTime();
        for (final LabCounter counter : counters) {
            source.getSender().sendMessage(LabCounters.summary(counter, gameTime, true));
        }
        return counters.size();
    }

    private static int showCounter(final CommandSourceStack source, final String colourName) {
        final DyeColor colour = WoolColors.byName(colourName);
        if (colour == null) {
            source.getSender().sendMessage(Component.text("unknown colour", NamedTextColor.RED));
            return 0;
        }
        final var world = worldOf(source);
        final LabCounter counter = LabCounters.existing(world, colour);
        if (counter == null || !counter.started()) {
            source.getSender().sendMessage(Component.text(colour.getName() + " empty",
                NamedTextColor.DARK_GRAY));
            return 0;
        }
        // Carpet's layout: a header with the total and rate, a reset button, then the items.
        final long ticks = Math.max(counter.elapsedTicks(world.getGameTime()), 1L);
        final Double perHour = counter.perHour(world.getGameTime());
        source.getSender().sendMessage(paperlab.text.Msg.c(
            "w Items for ", LabCounters.hexStyle(colour) + " " + colour.getName(),
            String.format(java.util.Locale.ROOT, "w  (%.2f min), ", ticks / 1200.0D),
            "w total: ", "wb " + counter.total(),
            "w , (", "wb " + (perHour == null ? "-"
                : String.format(java.util.Locale.ROOT, "%.1f", perHour)), "w /h):",
            "nb  [X]", "^g reset", "!/counter " + colour.getName() + " reset"));
        for (final LabCounter.Entry entry : counter.entries()) {
            source.getSender().sendMessage(paperlab.text.Msg.c(
                "g - ", entry.name().color(NamedTextColor.GRAY),
                "g : ", "wb " + entry.count(), "g , ",
                String.format(java.util.Locale.ROOT, "wb %.1f",
                    entry.count() * (20.0D * 60.0D * 60.0D) / ticks), "w /h"));
        }
        return 1;
    }

    private static int resetCounter(final CommandSourceStack source, final String colourName) {
        final DyeColor colour = WoolColors.byName(colourName);
        if (colour == null) {
            source.getSender().sendMessage(Component.text("unknown colour", NamedTextColor.RED));
            return 0;
        }
        final var world = worldOf(source);
        LabCounters.of(world, colour).reset(world.getGameTime());
        source.getSender().sendMessage(Component.text(colour.getName() + " reset",
            NamedTextColor.DARK_GRAY));
        return 1;
    }

    // -------------------------------------------------------------------- ghost

    /** {@value} — the caption in the command list. */
    public static final String GHOST_HELP = "observer mode: loads no chunks, takes no mobcap";

    public static LiteralArgumentBuilder<CommandSourceStack> ghostNode(final String name) {
        return Commands.literal(name)
                // The "sender is a player" check must not sit on the root: requires applies to
                // the whole subtree, and the branch taking a name stopped working from the
                // console.
                .requires(source -> source.getSender().hasPermission(LabPermissions.GHOST))
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player)) {
                        ctx.getSource().getSender().sendMessage(Component.text(
                            "from console give a name: /ghost <player>", NamedTextColor.RED));
                        return 0;
                    }
                    final Player player = (Player) ctx.getSource().getSender();
                    final boolean on = LabGhost.toggle(player);
                    player.sendMessage(Component.text(on ? "ghost on" : "ghost off",
                        on ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY));
                    if (on) {
                        // Two things to know before building a measurement on this: ticket
                        // release is lazy, and the mode is reduced without our core.
                        player.sendMessage(Component.text(
                            LabGhost.full()
                                ? "  chunks stop ticking in about 30 seconds"
                                : "  partial: without our core, mobcap, EAR and mob attention stay",
                            LabGhost.full() ? NamedTextColor.DARK_GRAY : NamedTextColor.RED));
                    }
                    return 1;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                    .requires(source -> source.getSender().hasPermission(LabPermissions.GHOST_OTHER))
                    .suggests((ctx, builder) -> {
                        Bukkit.getOnlinePlayers().forEach(p -> builder.suggest(p.getName()));
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        final String who = StringArgumentType.getString(ctx, "player");
                        final Player target = Bukkit.getPlayerExact(who);
                        if (target == null) {
                            ctx.getSource().getSender().sendMessage(
                                Component.text("no player " + who, NamedTextColor.RED));
                            return 0;
                        }
                        final boolean on = LabGhost.toggle(target);
                        ctx.getSource().getSender().sendMessage(Component.text(
                            who + (on ? " ghost on" : " ghost off"),
                            on ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY));
                        return 1;
                    }))
        ;
    }

    // --------------------------------------------------------------------- tick

    /**
     * {@value} — the caption in the command list.
     *
     * <p>The command itself is not here. The {@code toggle} and {@code warp} nodes are appended
     * by the core straight into vanilla {@code /tick}, the way Carpet does it; there is no
     * separate {@code /labtick} any more.
     */
    public static final String TICK_HELP = "tick freeze toggle, for keybinds";

    // ---------------------------------------------------------------- labchunks

    /** {@value} — the caption in the command list. */
    public static final String CHUNKS_HELP = "chunk status summary around a player";

    public static LiteralArgumentBuilder<CommandSourceStack> chunksNode(final String name) {
        return Commands.literal(name)
                .requires(source -> source.getSender().hasPermission(LabPermissions.CHUNKS))
                // Repeat the handshake with the client mod. Needed if the mod connected after
                // the join, or if map permissions were granted mid-game.
                .then(Commands.literal("hello")
                    .requires(source -> source.getSender().hasPermission(LabPermissions.CHUNKMAP))
                    .executes(ctx -> hello(ctx.getSource(), null))
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            Bukkit.getOnlinePlayers().forEach(p -> builder.suggest(p.getName()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> hello(ctx.getSource(),
                            StringArgumentType.getString(ctx, "target")))))
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        Bukkit.getOnlinePlayers().forEach(p -> builder.suggest(p.getName()));
                        return builder.buildFuture();
                    })
                    .executes(ctx -> chunks(ctx.getSource(),
                        StringArgumentType.getString(ctx, "player"))))
        ;
    }

    private static int hello(final CommandSourceStack source, final String targetName) {
        final Player target = targetName == null
            ? (source.getSender() instanceof final Player self ? self : null)
            : Bukkit.getPlayerExact(targetName);
        if (target == null) {
            source.getSender().sendMessage(Component.text(
                targetName == null ? "from console give a name" : "no player " + targetName,
                NamedTextColor.RED));
            return 0;
        }
        paperlab.chunkmap.ChunkMapService.sendHello(target);
        source.getSender().sendMessage(Component.text(
            "ChunkDebug: hello sent to " + target.getName(), NamedTextColor.AQUA));
        return 1;
    }

    private static int chunks(final CommandSourceStack source, final String name) {
        final Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            source.getSender().sendMessage(Component.text("no player " + name, NamedTextColor.RED));
            return 0;
        }
        final ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
        final var centre = ((CraftPlayer) player).getHandle().chunkPosition();
        final var chunks = paperlab.chunkmap.ChunkMapTracker.snapshot(level, false);

        final java.util.Map<FullChunkStatus, Integer> total =
            new java.util.EnumMap<>(FullChunkStatus.class);
        final java.util.Map<FullChunkStatus, Integer> near =
            new java.util.EnumMap<>(FullChunkStatus.class);

        for (final var info : chunks) {
            final FullChunkStatus status = ChunkLevel.fullStatus(info.statusLevel());
            total.merge(status, 1, Integer::sum);
            final int distance = Math.max(
                Math.abs(info.position().x() - centre.x()),
                Math.abs(info.position().z() - centre.z()));
            if (distance <= 12) {
                near.merge(status, 1, Integer::sum);
            }
        }

        source.getSender().sendMessage(Component.text(
            name + " @ " + centre.x() + "," + centre.z()
                + "  sim=" + player.getSimulationDistance()
                + (LabGhost.isGhost(player) ? "  GHOST" : ""), NamedTextColor.GOLD));
        source.getSender().sendMessage(Component.text("  total: " + describe(total), NamedTextColor.GRAY));
        source.getSender().sendMessage(Component.text("  within 12: " + describe(near), NamedTextColor.WHITE));
        source.getSender().sendMessage(Component.text(
            "  per-player-mob-spawns=" + MobcapService.perPlayerEnabled(level)
                + "  count-all=" + MobcapService.countAllMobs(level), NamedTextColor.DARK_GRAY));
        return 1;
    }

    private static String describe(final java.util.Map<FullChunkStatus, Integer> counts) {
        if (counts.isEmpty()) {
            return "none";
        }
        final StringBuilder sb = new StringBuilder();
        counts.forEach((status, count) -> sb.append(status.name()).append(' ').append(count).append("  "));
        return sb.toString().stripTrailing();
    }

    // --------------------------------------------------------------------- spawn

    /** {@value} — the caption in the command list. */
    public static final String SPAWN_HELP = "spawn trace: where attempts stop";

    /**
     * Controlling the trace from the console. {@code /log spawn} gives the same thing in the tab
     * list, but a subscription needs a live player and a run often has none.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> spawnNode(final String name) {
        return Commands.literal(name)
                .requires(source -> source.getSender().hasPermission(LabPermissions.SPAWN))
                .executes(ctx -> showSpawn(ctx.getSource(), null))
                .then(Commands.literal("on").executes(ctx -> {
                    paperlab.spawn.SpawnView.setManual(true);
                    ctx.getSource().getSender().sendMessage(
                        Component.text("spawn trace: collecting", NamedTextColor.GREEN));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    paperlab.spawn.SpawnView.setManual(false);
                    ctx.getSource().getSender().sendMessage(
                        Component.text("spawn trace: off", NamedTextColor.DARK_GRAY));
                    return 1;
                }))
                .then(Commands.literal("reset").executes(ctx -> {
                    paperlab.spawn.SpawnView.reset();
                    ctx.getSource().getSender().sendMessage(
                        Component.text("spawn trace: reset", NamedTextColor.DARK_GRAY));
                    return 1;
                }))
                .then(Commands.argument("category", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        paperlab.spawn.SpawnView.options().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> showSpawn(ctx.getSource(),
                        StringArgumentType.getString(ctx, "category"))))
        ;
    }

    private static int showSpawn(final CommandSourceStack source, final String category) {
        source.getSender().sendMessage(
            paperlab.spawn.SpawnView.line(worldOf(source), category));
        return 1;
    }

    private static org.bukkit.World worldOf(final CommandSourceStack source) {
        if (source.getSender() instanceof final Player player) {
            return player.getWorld();
        }
        return Bukkit.getWorlds().get(0);
    }
}
