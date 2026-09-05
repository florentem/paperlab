package paperlab.cplay.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import java.util.Collection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import paperlab.core.CPlayBridge;
import paperlab.cplay.CPlayService;
import paperlab.cplay.model.CPlayAssetHandle;
import paperlab.cplay.model.CPlayAssetInfo;

public final class CPlayCommands {

    public static final String PLAYBACK_HELP = "Playback redstone captures: /playback start <handle>";
    public static final String CAPTURE_HELP = "Capture redstone signals: /capture start <name>";
    public static final String CPLAY_HELP = "Capture & Playback mod integration status and assets";

    private CPlayCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> playbackNode(final String name) {
        return Commands.literal(name)
            .requires(source -> source.getSender().hasPermission("paperlab.cplay.playback"))
            .then(Commands.literal("start")
                .then(Commands.argument("asset", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        final CPlayService s = CPlayService.get();
                        if (s != null) {
                            for (final CPlayAssetInfo info : s.getAssetStore().getAllAssets()) {
                                if (info.getHandle() != null) builder.suggest(info.getHandle().toString());
                                builder.suggest(info.getAssetName());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> startPlayback(ctx, StringArgumentType.getString(ctx, "asset"), 0, 1))
                    .then(Commands.argument("delay", IntegerArgumentType.integer(0))
                        .executes(ctx -> startPlayback(ctx, StringArgumentType.getString(ctx, "asset"),
                            IntegerArgumentType.getInteger(ctx, "delay"), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                            .executes(ctx -> startPlayback(ctx, StringArgumentType.getString(ctx, "asset"),
                                IntegerArgumentType.getInteger(ctx, "delay"),
                                IntegerArgumentType.getInteger(ctx, "count")))))))
            .then(Commands.literal("stop")
                .then(Commands.argument("asset", StringArgumentType.word())
                    .executes(ctx -> stopPlayback(ctx, StringArgumentType.getString(ctx, "asset")))))
            .then(Commands.literal("stopAll").executes(CPlayCommands::stopAllPlaybacks))
            .then(Commands.literal("list").executes(CPlayCommands::listPlaybacks));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> captureNode(final String name) {
        return Commands.literal(name)
            .requires(source -> source.getSender().hasPermission("paperlab.cplay.capture"))
            .then(Commands.literal("start")
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("from", ArgumentTypes.blockPosition())
                        .then(Commands.argument("to", ArgumentTypes.blockPosition())
                            .executes(ctx -> startCapture(ctx, StringArgumentType.getString(ctx, "name"),
                                ctx.getArgument("from", BlockPositionResolver.class),
                                ctx.getArgument("to", BlockPositionResolver.class)))))))
            .then(Commands.literal("stop")
                .then(Commands.argument("asset", StringArgumentType.word())
                    .executes(ctx -> stopCapture(ctx, StringArgumentType.getString(ctx, "asset")))))
            .then(Commands.literal("list").executes(CPlayCommands::listCaptures));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> cplayNode(final String name) {
        return Commands.literal(name)
            .requires(source -> source.getSender().hasPermission("paperlab.cplay"))
            .executes(CPlayCommands::cplayOverview)
            .then(Commands.literal("status").executes(CPlayCommands::cplayOverview))
            .then(Commands.literal("assets").executes(CPlayCommands::listAssets));
    }

    private static int cplayOverview(final CommandContext<CommandSourceStack> ctx) {
        final var src = ctx.getSource();
        final var sender = src.getSender();
        sender.sendMessage(Component.text("--- Capture & Playback (PaperLab) ---", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Bridge status: ", NamedTextColor.GRAY)
            .append(Component.text(CPlayBridge.describe(), CPlayBridge.PRESENT ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));

        final CPlayService s = CPlayService.get();
        final int count = (s != null) ? s.getAssetStore().getAllAssets().size() : 0;
        sender.sendMessage(Component.text("Stored assets: " + count, NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Client channel: minecraft:mod/g4mespeed (UID: 0x4341504C v0.8.0)", NamedTextColor.DARK_GRAY));
        return 1;
    }

    private static int listAssets(final CommandContext<CommandSourceStack> ctx) {
        final var sender = ctx.getSource().getSender();
        final CPlayService s = CPlayService.get();
        if (s == null) return 0;

        final Collection<CPlayAssetInfo> assets = s.getAssetStore().getAllAssets();
        sender.sendMessage(Component.text("Assets (" + assets.size() + "):", NamedTextColor.GOLD));
        for (final CPlayAssetInfo info : assets) {
            sender.sendMessage(Component.text(" - ", NamedTextColor.DARK_GRAY)
                .append(Component.text(info.getHandle() != null ? info.getHandle().toString() : "no-handle", NamedTextColor.AQUA))
                .append(Component.text(" (" + info.getAssetName() + ", " + info.getType().getDisplayName() + ")", NamedTextColor.GRAY)));
        }
        return 1;
    }

    private static ServerLevel resolveLevel(final CommandSourceStack source) {
        final org.bukkit.Location loc = source.getLocation();
        final org.bukkit.World bWorld = (loc != null && loc.getWorld() != null)
            ? loc.getWorld()
            : (org.bukkit.Bukkit.getWorlds().isEmpty() ? null : org.bukkit.Bukkit.getWorlds().get(0));
        return (bWorld instanceof CraftWorld cw) ? cw.getHandle() : null;
    }

    private static int startPlayback(final CommandContext<CommandSourceStack> ctx, final String key, final int delay, final int count) {
        final var sender = ctx.getSource().getSender();
        final CPlayService s = CPlayService.get();
        if (s == null) return 0;

        CPlayAssetInfo info = null;
        try {
            info = s.getAssetStore().getAsset(CPlayAssetHandle.parse(key));
        } catch (Exception ignored) {}
        if (info == null) {
            info = s.getAssetStore().getAssetByName(key);
        }

        if (info == null) {
            sender.sendMessage(Component.text("Asset '" + key + "' not found.", NamedTextColor.RED));
            return 0;
        }

        final ServerLevel level = resolveLevel(ctx.getSource());
        if (level == null) {
            sender.sendMessage(Component.text("No world available.", NamedTextColor.RED));
            return 0;
        }

        final Player player = (sender instanceof Player p) ? p : null;
        final boolean started = s.getPlaybackController().startPlayback(level, info, delay, count, player);
        if (started) {
            sender.sendMessage(Component.text("Playback started for " + info.getAssetName() + " (" + info.getHandle() + ")", NamedTextColor.GREEN));
            return 1;
        }
        return 0;
    }

    private static int stopPlayback(final CommandContext<CommandSourceStack> ctx, final String key) {
        final var sender = ctx.getSource().getSender();
        final CPlayService s = CPlayService.get();
        if (s == null) return 0;

        CPlayAssetInfo info = null;
        try {
            info = s.getAssetStore().getAsset(CPlayAssetHandle.parse(key));
        } catch (Exception ignored) {}
        if (info == null) {
            info = s.getAssetStore().getAssetByName(key);
        }

        if (info == null) {
            sender.sendMessage(Component.text("Asset '" + key + "' not found.", NamedTextColor.RED));
            return 0;
        }

        final ServerLevel level = resolveLevel(ctx.getSource());
        if (level == null) {
            sender.sendMessage(Component.text("No world available.", NamedTextColor.RED));
            return 0;
        }

        final boolean stopped = s.getPlaybackController().stopPlayback(level, info.getAssetUUID());
        if (stopped) {
            sender.sendMessage(Component.text("Playback stopped for " + info.getAssetName(), NamedTextColor.YELLOW));
            return 1;
        } else {
            sender.sendMessage(Component.text("Asset was not playing.", NamedTextColor.GRAY));
            return 0;
        }
    }

    private static int stopAllPlaybacks(final CommandContext<CommandSourceStack> ctx) {
        final var sender = ctx.getSource().getSender();
        final CPlayService s = CPlayService.get();
        if (s == null) return 0;

        final ServerLevel level = resolveLevel(ctx.getSource());
        if (level == null) {
            sender.sendMessage(Component.text("No world available.", NamedTextColor.RED));
            return 0;
        }
        s.getPlaybackController().stopAllPlaybacks(level);
        sender.sendMessage(Component.text("All playbacks stopped.", NamedTextColor.YELLOW));
        return 1;
    }

    private static int listPlaybacks(final CommandContext<CommandSourceStack> ctx) {
        final var sender = ctx.getSource().getSender();
        final ServerLevel level = resolveLevel(ctx.getSource());
        if (level == null) {
            sender.sendMessage(Component.text("No world available.", NamedTextColor.RED));
            return 0;
        }
        final int active = CPlayBridge.getPlaybackStreams(level).size();
        sender.sendMessage(Component.text("Active playbacks in world: " + active, NamedTextColor.GOLD));
        return 1;
    }

    private static int startCapture(final CommandContext<CommandSourceStack> ctx, final String name,
                                    final BlockPositionResolver fromRes, final BlockPositionResolver toRes) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final var sender = ctx.getSource().getSender();
        final CPlayService s = CPlayService.get();
        if (s == null) return 0;

        final ServerLevel level = resolveLevel(ctx.getSource());
        if (level == null) {
            sender.sendMessage(Component.text("No world available.", NamedTextColor.RED));
            return 0;
        }

        final io.papermc.paper.math.BlockPosition bp1 = fromRes.resolve(ctx.getSource());
        final io.papermc.paper.math.BlockPosition bp2 = toRes.resolve(ctx.getSource());
        final BlockPos p1 = new BlockPos(bp1.blockX(), bp1.blockY(), bp1.blockZ());
        final BlockPos p2 = new BlockPos(bp2.blockX(), bp2.blockY(), bp2.blockZ());

        final Player player = (sender instanceof Player p) ? p : null;
        final boolean started = s.getPlaybackController().startCapture(level, name, p1, p2, player);
        if (started) {
            sender.sendMessage(Component.text("Capture started: " + name + " from " + bp1.blockX() + "," + bp1.blockY() + "," + bp1.blockZ() + " to " + bp2.blockX() + "," + bp2.blockY() + "," + bp2.blockZ(), NamedTextColor.GREEN));
            return 1;
        }
        return 0;
    }

    private static int stopCapture(final CommandContext<CommandSourceStack> ctx, final String key) {
        final var sender = ctx.getSource().getSender();
        final CPlayService s = CPlayService.get();
        if (s == null) return 0;

        CPlayAssetInfo info = null;
        try {
            info = s.getAssetStore().getAsset(CPlayAssetHandle.parse(key));
        } catch (Exception ignored) {}
        if (info == null) {
            info = s.getAssetStore().getAssetByName(key);
        }

        if (info == null) {
            sender.sendMessage(Component.text("Asset '" + key + "' not found.", NamedTextColor.RED));
            return 0;
        }

        final ServerLevel level = resolveLevel(ctx.getSource());
        if (level == null) {
            sender.sendMessage(Component.text("No world available.", NamedTextColor.RED));
            return 0;
        }

        final boolean stopped = s.getPlaybackController().stopCapture(level, info.getAssetUUID());
        if (stopped) {
            sender.sendMessage(Component.text("Capture stopped and saved for " + info.getAssetName(), NamedTextColor.GREEN));
            return 1;
        } else {
            sender.sendMessage(Component.text("No active capture found for " + info.getAssetName(), NamedTextColor.GRAY));
            return 0;
        }
    }

    private static int listCaptures(final CommandContext<CommandSourceStack> ctx) {
        final var sender = ctx.getSource().getSender();
        final ServerLevel level = resolveLevel(ctx.getSource());
        if (level == null) {
            sender.sendMessage(Component.text("No world available.", NamedTextColor.RED));
            return 0;
        }
        final int active = CPlayBridge.getCaptureStreams(level).size();
        sender.sendMessage(Component.text("Active captures in world: " + active, NamedTextColor.GOLD));
        return 1;
    }
}
