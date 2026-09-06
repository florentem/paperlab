package paperlab.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

/**
 * Three tools from Carpet: {@code /perimeterinfo}, {@code /info}, {@code /distance}.
 *
 * <p>Names and meanings follow the mod, which is why these are separate commands rather than
 * {@code /carpet} subcommands: the muscle-memory rule applies to them too.
 */
public final class LabInfoCommands {

    /** Radius of the spawn sphere around a point. As in Carpet and in the engine itself. */
    private static final int SPAWN_RADIUS = 128;

    /** Mobs never spawn closer than this: 24 blocks from a player. */
    private static final int MIN_SPAWN_DISTANCE = 24;

    public static final String PERIMETER_HELP = "spawnable spots in the spawn sphere around a point";
    public static final String INFO_HELP = "block state, block entity and ticking info";
    public static final String DISTANCE_HELP = "distance between two points";

    private LabInfoCommands() {
    }

    // ---------------------------------------------------------- perimeterinfo

    public static LiteralArgumentBuilder<CommandSourceStack> perimeterNode(final String name) {
        return Commands.literal(name)
            .requires(source -> source.getSender().hasPermission(LabPermissions.PERIMETER))
            .executes(ctx -> perimeter(ctx.getSource(), null))
            .then(Commands.argument("position", ArgumentTypes.blockPosition())
                .executes(ctx -> perimeter(ctx.getSource(),
                    resolve(ctx, "position"))));
    }

    /**
     * How many places in the spawn sphere a mob could appear at all.
     *
     * <p>Answers "why is the farm under-producing": if thousands of eligible spots surround it,
     * the farm competes with them for the same cap, and lighting the perimeter matters more
     * than tuning the farm.
     *
     * <p><b>Loaded chunks only.</b> Carpet has no such qualms and reads blocks as it goes,
     * loading the world; we cannot — an observation tool must not change what it observes. The
     * number of skipped chunks is printed next to the result, or the figure reads as complete.
     */
    private static int perimeter(final CommandSourceStack source,
                                 final io.papermc.paper.math.BlockPosition argument) {
        final Location origin = originOf(source, argument);
        if (origin == null) {
            source.getSender().sendMessage(Component.text(
                "give a position or run it as a player", NamedTextColor.RED));
            return 0;
        }
        final ServerLevel level = ((CraftWorld) origin.getWorld()).getHandle();
        final int centreX = origin.getBlockX();
        final int centreY = origin.getBlockY();
        final int centreZ = origin.getBlockZ();

        final long started = System.currentTimeMillis();
        int liquid = 0;
        int ground = 0;
        int chunksScanned = 0;
        int chunksMissing = 0;

        final int minY = Math.max(level.getMinY(), centreY - SPAWN_RADIUS);
        final int maxY = Math.min(level.getMaxY(), centreY + SPAWN_RADIUS);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        final int chunkMinX = (centreX - SPAWN_RADIUS) >> 4;
        final int chunkMaxX = (centreX + SPAWN_RADIUS) >> 4;
        final int chunkMinZ = (centreZ - SPAWN_RADIUS) >> 4;
        final int chunkMaxZ = (centreZ + SPAWN_RADIUS) >> 4;

        for (int chunkX = chunkMinX; chunkX <= chunkMaxX; chunkX++) {
            for (int chunkZ = chunkMinZ; chunkZ <= chunkMaxZ; chunkZ++) {
                final LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    chunksMissing++;
                    continue;
                }
                chunksScanned++;

                for (int localX = 0; localX < 16; localX++) {
                    final int x = (chunkX << 4) + localX;
                    final int dx = x - centreX;
                    if (Math.abs(dx) > SPAWN_RADIUS) {
                        continue;
                    }
                    for (int localZ = 0; localZ < 16; localZ++) {
                        final int z = (chunkZ << 4) + localZ;
                        final int dz = z - centreZ;
                        if (dx * dx + dz * dz > SPAWN_RADIUS * SPAWN_RADIUS) {
                            continue;
                        }
                        for (int y = minY; y < maxY; y++) {
                            final int dy = y - centreY;
                            final int distance = dx * dx + dy * dy + dz * dz;
                            if (distance > SPAWN_RADIUS * SPAWN_RADIUS
                                || distance < MIN_SPAWN_DISTANCE * MIN_SPAWN_DISTANCE) {
                                continue;
                            }
                            cursor.set(x, y, z);
                            switch (classify(level, chunk, cursor)) {
                                case 1 -> liquid++;
                                case 2 -> ground++;
                                default -> {
                                }
                            }
                        }
                    }
                }
            }
        }

        final long elapsed = System.currentTimeMillis() - started;
        final CommandSender sender = source.getSender();
        sender.sendMessage(Component.text("perimeter around "
            + centreX + " " + centreY + " " + centreZ, NamedTextColor.AQUA));
        sender.sendMessage(Component.text("  in water  ", NamedTextColor.DARK_GRAY)
            .append(Component.text(liquid, NamedTextColor.WHITE))
            .append(Component.text("   on ground  ", NamedTextColor.DARK_GRAY))
            .append(Component.text(ground, NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  chunks " + chunksScanned + " scanned, "
            + chunksMissing + " not loaded (skipped), " + elapsed + " ms",
            chunksMissing > 0 ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY));
        return liquid + ground;
    }

    /**
     * @return 0 not a spawnable spot, 1 in water, 2 on solid ground
     */
    private static int classify(final ServerLevel level, final LevelChunk chunk,
                                final BlockPos pos) {
        final BlockState state = chunk.getBlockState(pos);
        final BlockState above = level.getBlockState(pos.above());

        if (state.getFluidState().is(FluidTags.WATER) && !above.isRedstoneConductor(level, pos)) {
            return 1;
        }
        final BlockState below = level.getBlockState(pos.below());
        if (!below.isRedstoneConductor(level, pos)) {
            return 0;
        }
        if (below.is(Blocks.BEDROCK) || below.is(Blocks.BARRIER)) {
            return 0;
        }
        // Zombie is used as the type: it carries the usual ground-monster requirements, as in Carpet.
        if (NaturalSpawner.isValidEmptySpawnBlock(level, pos, state, state.getFluidState(), EntityTypes.ZOMBIE)
            && NaturalSpawner.isValidEmptySpawnBlock(level, pos.above(), above, above.getFluidState(), EntityTypes.ZOMBIE)) {
            return 2;
        }
        return 0;
    }

    // ------------------------------------------------------------------- info

    public static LiteralArgumentBuilder<CommandSourceStack> infoNode(final String name) {
        return Commands.literal(name)
            .requires(source -> source.getSender().hasPermission(LabPermissions.INFO))
            .then(Commands.literal("block")
                .executes(ctx -> info(ctx.getSource(), null))
                .then(Commands.argument("position", ArgumentTypes.blockPosition())
                    .executes(ctx -> info(ctx.getSource(),
                        resolve(ctx, "position")))));
    }

    /**
     * Everything the server knows about a block: state, properties, block entity data,
     * scheduled ticks and light.
     *
     * <p>Answers "why did that observer not fire": both the exact state and whether a scheduled
     * tick is pending on the block are visible.
     */
    private static int info(final CommandSourceStack source,
                            final io.papermc.paper.math.BlockPosition argument) {
        final Location location = originOf(source, argument);
        if (location == null) {
            source.getSender().sendMessage(Component.text(
                "give a position or run it as a player", NamedTextColor.RED));
            return 0;
        }
        final ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
        final BlockPos pos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        final BlockState state = level.getBlockState(pos);
        final CommandSender sender = source.getSender();

        sender.sendMessage(Component.text(
            state.getBlock().getName().getString() + "  at " + pos.getX() + " " + pos.getY() + " " + pos.getZ(),
            NamedTextColor.AQUA));

        if (state.getProperties().isEmpty()) {
            sender.sendMessage(Component.text("  no properties", NamedTextColor.DARK_GRAY));
        } else {
            for (final Property<?> property : state.getProperties()) {
                sender.sendMessage(Component.text("  " + property.getName() + " = ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(String.valueOf(state.getValue(property)), NamedTextColor.WHITE)));
            }
        }

        sender.sendMessage(Component.text("  light block " + level.getBrightness(
                net.minecraft.world.level.LightLayer.BLOCK, pos)
            + "  sky " + level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos),
            NamedTextColor.GRAY));

        final boolean blockTick = level.getBlockTicks().hasScheduledTick(pos, state.getBlock());
        final boolean fluidTick = level.getFluidTicks().hasScheduledTick(pos, state.getFluidState().getType());
        sender.sendMessage(Component.text("  scheduled tick: block " + blockTick + ", fluid " + fluidTick,
            blockTick || fluidTick ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY));

        final BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) {
            sender.sendMessage(Component.text("  no block entity", NamedTextColor.DARK_GRAY));
        } else {
            sender.sendMessage(Component.text("  block entity "
                + entity.getClass().getSimpleName(), NamedTextColor.WHITE));
        }
        return 1;
    }

    // --------------------------------------------------------------- distance

    public static LiteralArgumentBuilder<CommandSourceStack> distanceNode(final String name) {
        return Commands.literal(name)
            .requires(source -> source.getSender().hasPermission(LabPermissions.DISTANCE))
            // The first argument is "to" when alone and "from" when there are two. That reads
            // naturally: /distance <point> and /distance <from> <to>.
            .then(Commands.argument("first", ArgumentTypes.blockPosition())
                .executes(ctx -> distance(ctx.getSource(), null, resolve(ctx, "first")))
                .then(Commands.argument("second", ArgumentTypes.blockPosition())
                    .executes(ctx -> distance(ctx.getSource(),
                        resolve(ctx, "first"), resolve(ctx, "second")))));
    }

    /**
     * Distance between points: per axis, direct, and in blocks.
     *
     * <p>With one argument it measures from whoever asked. A small thing, but needed constantly:
     * checking whether a contraption fits inside the spawn radius or the despawn distance.
     */
    private static int distance(final CommandSourceStack source,
                                final io.papermc.paper.math.BlockPosition from0,
                                final io.papermc.paper.math.BlockPosition to0) {
        final Location from = from0 == null
            ? (source.getSender() instanceof final Player player ? player.getLocation() : null)
            : originOf(source, from0);
        final Location to = originOf(source, to0);
        if (from == null || to == null) {
            source.getSender().sendMessage(Component.text(
                "give two positions, or one and run it as a player", NamedTextColor.RED));
            return 0;
        }

        final double dx = to.getX() - from.getX();
        final double dy = to.getY() - from.getY();
        final double dz = to.getZ() - from.getZ();
        final double euclid = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final double flat = Math.sqrt(dx * dx + dz * dz);

        source.getSender().sendMessage(Component.text(String.format(Locale.ROOT,
            "dx %.1f  dy %.1f  dz %.1f", dx, dy, dz), NamedTextColor.WHITE));
        source.getSender().sendMessage(Component.text(String.format(Locale.ROOT,
            "  direct %.2f   flat %.2f   manhattan %.0f",
            euclid, flat, Math.abs(dx) + Math.abs(dy) + Math.abs(dz)), NamedTextColor.GRAY));
        return (int) euclid;
    }

    // ---------------------------------------------------------------- shared

    /**
     * Extract a position from an argument.
     *
     * <p>{@code ArgumentTypes.blockPosition()} returns a {@link BlockPositionResolver} rather
     * than a ready position: the coordinates may be relative ({@code ~ ~ ~}) and must be
     * resolved against whoever runs the command.
     */
    private static io.papermc.paper.math.BlockPosition resolve(
        final com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
        final String name) {
        try {
            return ctx.getArgument(name, BlockPositionResolver.class).resolve(ctx.getSource());
        } catch (final Exception e) {
            return null;
        }
    }

    private static Location originOf(final CommandSourceStack source,
                                     final io.papermc.paper.math.BlockPosition argument) {
        if (argument != null) {
            return argument.toLocation(source.getLocation().getWorld());
        }
        return source.getSender() instanceof final Player player ? player.getLocation() : null;
    }
}
