package paperlab.servux;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DetectorRailBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.StairsShape;

/**
 * Server-side placement of a Litematica schematic.
 *
 * <p>The transform order, block unpacking and placement flags are reproduced from
 * Litematica's <b>client</b> code ({@code LitematicaSchematic.placeBlocksToWorld},
 * {@code PositionUtils}, {@code LitematicaBitArray}). There is no other way: a mismatch
 * here does not break visibly, it quietly shifts or rotates the build.
 *
 * <h2>What is carried across</h2>
 * Blocks, palette, rotations and mirroring (subregions included), replace mode, block
 * entity data and entities. The {@code IgnoreEntities} flag is honoured both at request
 * level and at subregion level.
 *
 * <h2>On neighbour updates</h2>
 * Blocks are placed with flags {@code 0x12}, the way Litematica itself does it: tell the
 * clients, leave the neighbours alone. That is exactly the behaviour the {@code fillUpdates}
 * rule exists for — a contraption must not start up while it is still being laid out.
 */
public final class LitematicaPaste {

    /**
     * Placement flags: tell the clients and <b>do not let the block rewrite itself on
     * placement</b>.
     *
     * <p>{@code UPDATE_CLIENTS | UPDATE_SKIP_ALL_SIDEEFFECTS}, that is
     * {@code 2 | 16 | 32 | 256 | 512}.
     *
     * <p>The critical one is {@code UPDATE_SKIP_ON_PLACE} (512). Without it
     * {@code LevelChunk.setBlockState} calls {@code state.onPlace}, and for redstone dust
     * that is {@code updatePowerStrength}: it recomputes the signal from the neighbours and
     * <b>overwrites its own state</b>. A schematic with powered dust comes out unpowered
     * and the contraption starts itself — exactly what we saw on the first working paste.
     *
     * <p>Litematica's own flags ({@code 0x12}) do not save you here: they suppress neighbour
     * updates but not {@code onPlace}. On the client it is less noticeable, because a paste
     * there is usually followed by commands that recompute everything anyway.
     *
     * <p>{@code UPDATE_SUPPRESS_DROPS} (32) also keeps replaced blocks from dropping items,
     * and {@code UPDATE_KNOWN_SHAPE} (16) keeps neighbours from reshaping around the new
     * block.
     */
    private static final int SET_BLOCK_FLAGS =
        net.minecraft.world.level.block.Block.UPDATE_CLIENTS
            | net.minecraft.world.level.block.Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    /** How existing blocks are replaced. The names follow Litematica's settings. */
    private enum Replace {
        /** Place into air only. */
        NONE,
        /** Replace everything. */
        ALL,
        /** Replace everything, but do not place air over blocks. */
        WITH_NON_AIR;

        static Replace parse(final String raw) {
            final String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            return switch (value) {
                case "all" -> ALL;
                case "with_non_air", "withnonair" -> WITH_NON_AIR;
                default -> NONE;
            };
        }
    }

    /** Paste result, for the chat report. */
    public record Result(int placed, int skipped, int entities, int regions) {
    }

    private LitematicaPaste() {
    }

    public static Result paste(final ServerLevel level, final CompoundTag request) {
        final CompoundTag schematics = request.getCompoundOrEmpty("Schematics");
        final CompoundTag regions = schematics.getCompoundOrEmpty("Regions");
        final CompoundTag subRegions = request.getCompoundOrEmpty("SubRegions");

        final BlockPos origin = readVec(request, "Origin");
        final Rotation mainRotation = rotation(request.getIntOr("Rotation", 0));
        final Mirror mainMirror = mirror(request.getIntOr("Mirror", 0));
        final Replace replace = Replace.parse(request.getStringOr("ReplaceMode", "none"));

        final boolean withEntities = !request.getBooleanOr("IgnoreEntities", false);

        int placed = 0;
        int skipped = 0;
        int entities = 0;

        for (final String name : regions.keySet()) {
            final CompoundTag region = regions.getCompoundOrEmpty(name);
            final CompoundTag sub = subRegions.getCompoundOrEmpty(name);

            if (subRegions.contains(name) && !sub.getBooleanOr("Enabled", true)) {
                continue;
            }

            // Note: the origin comes from the SUBREGION, not from Regions.<name>.Position.
            // In Litematica that is placement.getPos(); the two are easy to confuse, and the
            // consequence is a build placed in the wrong spot.
            final BlockPos regionPos = subRegions.contains(name)
                ? readVec(sub, "Pos") : readVec(region, "Position");
            final BlockPos regionSize = readVec(region, "Size");

            final Rotation subRotation = rotation(sub.getIntOr("Rotation", 0));
            final Mirror subMirrorRaw = mirror(sub.getIntOr("Mirror", 0));

            final Result partial = pasteRegion(level, region, origin, regionPos, regionSize,
                mainRotation, mainMirror, subRotation, subMirrorRaw, replace);
            placed += partial.placed();
            skipped += partial.skipped();

            // A subregion can forbid entities independently of the request-wide flag.
            if (withEntities && !sub.getBooleanOr("IgnoreEntities", false)) {
                entities += pasteEntities(level, region, origin, regionPos,
                    mainRotation, mainMirror, subRotation, subMirrorRaw);
            }
        }
        return new Result(placed, skipped, entities, regions.size());
    }

    /**
     * Entities of a region.
     *
     * <p>An entity's position is stored in its own NBT under {@code Pos}, measured from the
     * region corner. The transform is the same as for blocks but in fractional coordinates:
     * in Litematica that is {@code getTransformedPosition}, where mirroring is taken about
     * one rather than zero, because this is a point inside a cell rather than the cell itself.
     *
     * <p>The offset here carries <b>no</b> minimum-corner correction: for entities the
     * coordinate is already absolute within the region.
     */
    private static int pasteEntities(final ServerLevel level,
                                     final CompoundTag region,
                                     final BlockPos origin,
                                     final BlockPos regionPos,
                                     final Rotation mainRotation,
                                     final Mirror mainMirror,
                                     final Rotation subRotation,
                                     final Mirror subMirrorRaw) {
        final ListTag list = region.getListOrEmpty("Entities");
        if (list.isEmpty()) {
            return 0;
        }
        final BlockPos regionPosTransformed = transform(regionPos, mainMirror, mainRotation);
        final int offX = regionPosTransformed.getX() + origin.getX();
        final int offY = regionPosTransformed.getY() + origin.getY();
        final int offZ = regionPosTransformed.getZ() + origin.getZ();

        Mirror subMirror = subMirrorRaw;
        if (subMirror != Mirror.NONE
            && (mainRotation == Rotation.CLOCKWISE_90 || mainRotation == Rotation.COUNTERCLOCKWISE_90)) {
            subMirror = subMirror == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        }

        int spawned = 0;
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag tag = list.getCompoundOrEmpty(i);
            final net.minecraft.world.phys.Vec3 stored = readPos(tag);
            if (stored == null) {
                continue;
            }
            net.minecraft.world.phys.Vec3 pos = transform(stored, mainMirror, mainRotation);
            pos = transform(pos, subMirror, subRotation);
            final double x = pos.x + offX;
            final double y = pos.y + offY;
            final double z = pos.z + offZ;
            if (!level.hasChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) {
                continue;
            }

            try {
                final net.minecraft.world.entity.Entity entity =
                    net.minecraft.world.entity.EntityType.loadEntityRecursive(
                        tag, level,
                        new net.minecraft.world.entity.EntitySpawnRequest(
                            net.minecraft.world.entity.EntitySpawnReason.COMMAND, false),
                        loaded -> {
                            loaded.snapTo(x, y, z, loaded.getYRot(), loaded.getXRot());
                            return loaded;
                        });
                if (entity != null) {
                    // The method returns nothing: the entity is either added or discarded by
                    // the engine. We count attempts — nothing more precise is available here.
                    level.addFreshEntityWithPassengers(entity);
                    spawned++;
                }
            } catch (final Throwable ignored) {
                // One corrupt entity must not abort the whole paste.
            }
        }
        return spawned;
    }

    private static net.minecraft.world.phys.@org.jetbrains.annotations.Nullable Vec3 readPos(
        final CompoundTag tag) {
        final ListTag pos = tag.getListOrEmpty("Pos");
        if (pos.size() < 3) {
            return null;
        }
        return new net.minecraft.world.phys.Vec3(
            pos.getDoubleOr(0, 0.0D), pos.getDoubleOr(1, 0.0D), pos.getDoubleOr(2, 0.0D));
    }

    /**
     * {@code PositionUtils.getTransformedPosition} — the fractional variant.
     *
     * <p>Mirroring is taken about one: a point inside a cell must stay inside the same cell
     * when mirrored, not slide onto its edge.
     */
    private static net.minecraft.world.phys.Vec3 transform(
        final net.minecraft.world.phys.Vec3 pos, final Mirror mirror, final Rotation rotation) {
        double x = pos.x;
        final double y = pos.y;
        double z = pos.z;
        boolean transformed = true;

        switch (mirror) {
            case LEFT_RIGHT -> z = 1.0D - z;
            case FRONT_BACK -> x = 1.0D - x;
            default -> transformed = false;
        }

        return switch (rotation) {
            case COUNTERCLOCKWISE_90 -> new net.minecraft.world.phys.Vec3(z, y, 1.0D - x);
            case CLOCKWISE_90 -> new net.minecraft.world.phys.Vec3(1.0D - z, y, x);
            case CLOCKWISE_180 -> new net.minecraft.world.phys.Vec3(1.0D - x, y, 1.0D - z);
            default -> transformed ? new net.minecraft.world.phys.Vec3(x, y, z) : pos;
        };
    }

    private static Result pasteRegion(final ServerLevel level,
                                      final CompoundTag region,
                                      final BlockPos origin,
                                      final BlockPos regionPos,
                                      final BlockPos regionSize,
                                      final Rotation mainRotation,
                                      final Mirror mainMirror,
                                      final Rotation subRotation,
                                      final Mirror subMirrorRaw,
                                      final Replace replace) {
        final BlockState[] palette = readPalette(level, region.getListOrEmpty("BlockStatePalette"));
        if (palette.length == 0) {
            return new Result(0, 0, 0, 0);
        }
        final long[] blockStates = region.getLongArray("BlockStates").orElse(new long[0]);
        if (blockStates.length == 0) {
            return new Result(0, 0, 0, 0);
        }

        final int sizeX = Math.abs(regionSize.getX());
        final int sizeY = Math.abs(regionSize.getY());
        final int sizeZ = Math.abs(regionSize.getZ());
        final long volume = (long) sizeX * sizeY * sizeZ;
        if (volume <= 0L || volume > 20_000_000L) {
            return new Result(0, 0, 0, 0);
        }
        final int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.length - 1));
        final long mask = (1L << bits) - 1L;

        final Map<BlockPos, CompoundTag> tiles = readTiles(region.getListOrEmpty("TileEntities"));

        // Untransformed relative positions, as in the original.
        final BlockPos endRelSub = relativeEnd(regionSize);
        final BlockPos endRel = endRelSub.offset(regionPos);
        final BlockPos minRel = min(regionPos, endRel);
        final BlockPos regionPosTransformed = transform(regionPos, mainMirror, mainRotation);

        final Rotation combined = mainRotation.getRotated(subRotation);
        Mirror subMirror = subMirrorRaw;
        // Rotating the main placement by 90 degrees changes the subregion's mirror axis.
        if (subMirror != Mirror.NONE
            && (mainRotation == Rotation.CLOCKWISE_90 || mainRotation == Rotation.COUNTERCLOCKWISE_90)) {
            subMirror = subMirror == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        }

        int placed = 0;
        int skipped = 0;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    final int index = paletteIndex(blockStates, bits, mask,
                        (long) y * sizeX * sizeZ + (long) z * sizeX + x);
                    if (index < 0 || index >= palette.length) {
                        skipped++;
                        continue;
                    }
                    BlockState state = palette[index];
                    if (state.is(Blocks.STRUCTURE_VOID)) {
                        continue;
                    }

                    cursor.set(x, y, z);
                    final CompoundTag tile = tiles.get(cursor.immutable());

                    cursor.set(minRel.getX() + x - regionPos.getX(),
                        minRel.getY() + y - regionPos.getY(),
                        minRel.getZ() + z - regionPos.getZ());

                    BlockPos target = transform(cursor, mainMirror, mainRotation);
                    target = transform(target, subMirror, subRotation);
                    target = target.offset(regionPosTransformed).offset(origin);

                    if (level.isOutsideBuildHeight(target) || !level.hasChunk(target.getX() >> 4, target.getZ() >> 4)) {
                        skipped++;
                        continue;
                    }

                    final BlockState old = level.getBlockState(target);
                    if ((replace == Replace.NONE && !old.isAir())
                        || (replace == Replace.WITH_NON_AIR && state.isAir())) {
                        skipped++;
                        continue;
                    }

                    if (mainMirror != Mirror.NONE) {
                        state = applyMirror(state, mainMirror);
                    }
                    if (subMirror != Mirror.NONE) {
                        state = applyMirror(state, subMirror);
                    }
                    if (combined != Rotation.NONE) {
                        state = applyRotation(state, combined);
                    }

                    if (old == state && !state.hasBlockEntity()) {
                        continue;
                    }

                    // Empty the old container first, otherwise its contents spill onto the
                    // floor when it is replaced. Litematica does the same.
                    final BlockEntity existing = level.getBlockEntity(target);
                    if (existing instanceof final net.minecraft.world.Container container) {
                        container.clearContent();
                    }

                    if (level.setBlock(target, state, SET_BLOCK_FLAGS)) {
                        placed++;
                        if (tile != null) {
                            applyTile(level, target, tile);
                        }
                    }
                }
            }
        }
        return new Result(placed, skipped, 0, 1);
    }

    /** Block entity data: the coordinates in it are region-local, so we make them world ones. */
    private static void applyTile(final ServerLevel level, final BlockPos pos, final CompoundTag tile) {
        final BlockEntity target = level.getBlockEntity(pos);
        if (target == null) {
            return;
        }
        final CompoundTag data = tile.copy();
        data.putInt("x", pos.getX());
        data.putInt("y", pos.getY());
        data.putInt("z", pos.getZ());
        try {
            try (final net.minecraft.util.ProblemReporter.ScopedCollector reporter =
                     new net.minecraft.util.ProblemReporter.ScopedCollector(
                         target.problemPath(), org.slf4j.LoggerFactory.getLogger("PaperLab"))) {
                target.loadWithComponents(
                    net.minecraft.world.level.storage.TagValueInput.create(
                        reporter, level.registryAccess(), data));
            }
            target.setChanged();
        } catch (final Throwable ignored) {
            // Corrupt data for one block must not abort the whole paste.
        }
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Unpacking Litematica's spanning bit array.
     *
     * <p>An entry may cross the boundary between two {@code long}s — that is how the format
     * differs from the vanilla palette container, where entries never straddle.
     */
    private static int paletteIndex(final long[] array, final int bits, final long mask,
                                    final long index) {
        final long startOffset = index * bits;
        final int startIndex = (int) (startOffset >> 6);
        final int endIndex = (int) (((index + 1L) * bits - 1L) >> 6);
        final int startBit = (int) (startOffset & 0x3F);

        if (startIndex < 0 || endIndex >= array.length) {
            return -1;
        }
        if (startIndex == endIndex) {
            return (int) (array[startIndex] >>> startBit & mask);
        }
        final int endOffset = 64 - startBit;
        return (int) ((array[startIndex] >>> startBit | array[endIndex] << endOffset) & mask);
    }

    private static BlockState[] readPalette(final ServerLevel level, final ListTag list) {
        final var blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        final BlockState[] out = new BlockState[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = NbtUtils.readBlockState(blocks, list.getCompoundOrEmpty(i));
        }
        return out;
    }

    private static Map<BlockPos, CompoundTag> readTiles(final ListTag list) {
        final Map<BlockPos, CompoundTag> out = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag tag = list.getCompoundOrEmpty(i);
            out.put(new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0)), tag);
        }
        return out;
    }

    /**
     * A position may be stored as a compound {@code {x,y,z}} or as an array of three
     * numbers — Litematica writes it differently in different places.
     */
    private static BlockPos readVec(final CompoundTag tag, final String key) {
        final List<Integer> array = tag.getIntArray(key)
            .map(values -> values.length >= 3
                ? List.of(values[0], values[1], values[2]) : List.<Integer>of())
            .orElse(List.of());
        if (array.size() == 3) {
            return new BlockPos(array.get(0), array.get(1), array.get(2));
        }
        final CompoundTag inner = tag.getCompoundOrEmpty(key);
        return new BlockPos(inner.getIntOr("x", 0), inner.getIntOr("y", 0), inner.getIntOr("z", 0));
    }

    // ------------------------------------------------------- Litematica geometry

    /** A size may be negative: the end is computed with a one-block shift towards zero. */
    private static BlockPos relativeEnd(final BlockPos size) {
        return new BlockPos(
            size.getX() >= 0 ? size.getX() - 1 : size.getX() + 1,
            size.getY() >= 0 ? size.getY() - 1 : size.getY() + 1,
            size.getZ() >= 0 ? size.getZ() - 1 : size.getZ() + 1);
    }

    private static BlockPos min(final BlockPos a, final BlockPos b) {
        return new BlockPos(
            Math.min(a.getX(), b.getX()),
            Math.min(a.getY(), b.getY()),
            Math.min(a.getZ(), b.getZ()));
    }

    /** {@code PositionUtils.getTransformedBlockPos} — reproduced literally. */
    private static BlockPos transform(final BlockPos pos, final Mirror mirror, final Rotation rotation) {
        int x = pos.getX();
        final int y = pos.getY();
        int z = pos.getZ();
        boolean mirrored = true;

        switch (mirror) {
            case LEFT_RIGHT -> z = -z;
            case FRONT_BACK -> x = -x;
            default -> mirrored = false;
        }

        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(-z, y, x);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, -x);
            case CLOCKWISE_180 -> new BlockPos(-x, y, -z);
            default -> mirrored ? new BlockPos(x, y, z) : pos.immutable();
        };
    }

    private static Rotation rotation(final int ordinal) {
        final Rotation[] values = Rotation.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Rotation.NONE;
    }

    private static Mirror mirror(final int ordinal) {
        final Mirror[] values = Mirror.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Mirror.NONE;
    }

    /**
     * Mirroring a block, working around the known vanilla bugs (double chests, stairs).
     */
    public static BlockState applyMirror(final BlockState state, final Mirror mirror) {
        if (mirror == Mirror.NONE) {
            return state;
        }
        if (state.getBlock() instanceof StairBlock) {
            return fixStairsMirror(state, mirror);
        }
        if (state.getBlock() instanceof ChestBlock) {
            final ChestType type = state.getValue(ChestBlock.TYPE);
            if (type != ChestType.SINGLE) {
                return fixChestMirror(state, mirror, type);
            }
        }
        return state.mirror(mirror);
    }

    /**
     * Rotating a block, working around the vanilla bug in rotating straight rails by 180°.
     */
    public static BlockState applyRotation(final BlockState state, final Rotation rotation) {
        if (rotation == Rotation.NONE) {
            return state;
        }
        if (rotation == Rotation.CLOCKWISE_180 && isStraightRail(state)) {
            return state;
        }
        return state.rotate(rotation);
    }

    private static boolean isStraightRail(final BlockState state) {
        if (state.getBlock() instanceof RailBlock) {
            final RailShape shape = state.getValue(RailBlock.SHAPE);
            return shape == RailShape.EAST_WEST || shape == RailShape.NORTH_SOUTH;
        }
        if (state.getBlock() instanceof DetectorRailBlock) {
            final RailShape shape = state.getValue(DetectorRailBlock.SHAPE);
            return shape == RailShape.EAST_WEST || shape == RailShape.NORTH_SOUTH;
        }
        if (state.getBlock() instanceof PoweredRailBlock) {
            final RailShape shape = state.getValue(PoweredRailBlock.SHAPE);
            return shape == RailShape.EAST_WEST || shape == RailShape.NORTH_SOUTH;
        }
        return false;
    }

    private static BlockState fixStairsMirror(final BlockState state, final Mirror mirror) {
        final Direction direction = state.getValue(StairBlock.FACING);
        final StairsShape stairShape = state.getValue(StairBlock.SHAPE);

        // Fixes X Axis for FRONT_BACK being inverted for INNER_LEFT / INNER_RIGHT
        if (direction.getAxis() == Direction.Axis.X && mirror == Mirror.FRONT_BACK) {
            return switch (stairShape) {
                case INNER_LEFT  -> state.rotate(Rotation.CLOCKWISE_180).setValue(StairBlock.SHAPE, StairsShape.INNER_RIGHT);
                case INNER_RIGHT -> state.rotate(Rotation.CLOCKWISE_180).setValue(StairBlock.SHAPE, StairsShape.INNER_LEFT);
                case OUTER_LEFT  -> state.rotate(Rotation.CLOCKWISE_180).setValue(StairBlock.SHAPE, StairsShape.OUTER_RIGHT);
                case OUTER_RIGHT -> state.rotate(Rotation.CLOCKWISE_180).setValue(StairBlock.SHAPE, StairsShape.OUTER_LEFT);
                default          -> state.rotate(Rotation.CLOCKWISE_180);
            };
        }
        // Fixes missing Axis STAIR_SHAPE flips
        if ((direction.getAxis() == Direction.Axis.X && mirror == Mirror.LEFT_RIGHT)
            || (direction.getAxis() == Direction.Axis.Z && mirror == Mirror.FRONT_BACK)) {
            return switch (stairShape) {
                case INNER_LEFT  -> state.setValue(StairBlock.SHAPE, StairsShape.INNER_RIGHT);
                case INNER_RIGHT -> state.setValue(StairBlock.SHAPE, StairsShape.INNER_LEFT);
                case OUTER_LEFT  -> state.setValue(StairBlock.SHAPE, StairsShape.OUTER_RIGHT);
                case OUTER_RIGHT -> state.setValue(StairBlock.SHAPE, StairsShape.OUTER_LEFT);
                default          -> state;
            };
        }
        return state.mirror(mirror);
    }

    private static BlockState fixChestMirror(BlockState state, final Mirror mirror, final ChestType type) {
        final Direction facing = state.getValue(ChestBlock.FACING);
        final Direction.Axis axis = facing.getAxis();

        if (mirror == Mirror.FRONT_BACK) {
            state = state.setValue(ChestBlock.TYPE, type.getOpposite());
            if (axis == Direction.Axis.X) {
                state = state.setValue(ChestBlock.FACING, facing.getOpposite());
            }
        } else if (mirror == Mirror.LEFT_RIGHT) {
            state = state.setValue(ChestBlock.TYPE, type.getOpposite());
            if (axis == Direction.Axis.Z) {
                state = state.setValue(ChestBlock.FACING, facing.getOpposite());
            }
        }
        return state;
    }
}
