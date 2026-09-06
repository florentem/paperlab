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
 * Установка схематики Litematica на сервере.
 *
 * <p>Порядок преобразований, распаковка блоков и флаги установки воспроизведены по
 * <b>клиентскому</b> коду Litematica ({@code LitematicaSchematic.placeBlocksToWorld},
 * {@code PositionUtils}, {@code LitematicaBitArray}). Иначе нельзя: расхождение здесь
 * не ломается заметно, а тихо сдвигает или разворачивает постройку.
 *
 * <h2>Что уже переносится</h2>
 * Блоки, палитра, повороты и отражения (в том числе у подрегионов), режим замены,
 * данные тайл-энтити и сущности. Флаг {@code IgnoreEntities} уважается и на уровне
 * запроса, и на уровне подрегиона.
 *
 * <h2>Про обновления соседей</h2>
 * Блоки ставятся с флагами {@code 0x12} — как это делает сама Litematica:
 * клиентам сообщаем, соседей не трогаем. Это ровно то поведение, ради которого
 * существует правило {@code fillUpdates}: конструкция не должна запускаться по ходу
 * укладки.
 */
public final class LitematicaPaste {

    /**
     * Флаги установки: показать клиентам и <b>не давать блоку менять себя при установке</b>.
     *
     * <p>{@code UPDATE_CLIENTS | UPDATE_SKIP_ALL_SIDEEFFECTS}, то есть
     * {@code 2 | 16 | 32 | 256 | 512}.
     *
     * <p>Ключевой здесь — {@code UPDATE_SKIP_ON_PLACE} (512). Без него
     * {@code LevelChunk.setBlockState} зовёт {@code state.onPlace}, а у редстоуновой пыли
     * это {@code updatePowerStrength}: она пересчитывает сигнал по соседям и <b>перезаписывает
     * собственное состояние</b>. Схематика с горящей пылью встаёт погашенной, и конструкция
     * стартует сама — ровно то, что мы увидели на первой рабочей вставке.
     *
     * <p>Флаги {@code Litematica} ({@code 0x12}) от этого не спасают: они гасят обновления
     * соседей, но не {@code onPlace}. У клиента это не так заметно, потому что там за
     * вставкой обычно идут команды, которые всё равно всё пересчитывают.
     *
     * <p>{@code UPDATE_SUPPRESS_DROPS} (32) заодно не даёт заменяемым блокам сыпаться
     * предметами, а {@code UPDATE_KNOWN_SHAPE} (16) — соседям менять форму под новый блок.
     */
    private static final int SET_BLOCK_FLAGS =
        net.minecraft.world.level.block.Block.UPDATE_CLIENTS
            | net.minecraft.world.level.block.Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    /** Режим замены существующих блоков. Имена — как в настройках Litematica. */
    private enum Replace {
        /** Ставить только в воздух. */
        NONE,
        /** Заменять всё. */
        ALL,
        /** Заменять всё, но не ставить воздух поверх блоков. */
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

    /** Итог вставки — для отчёта в чат. */
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

            // Внимание: точка берётся из ПОДРЕГИОНА, а не из Regions.<name>.Position.
            // В Litematica это placement.getPos(); перепутать легко, а последствие —
            // постройка встанет не туда.
            final BlockPos regionPos = subRegions.contains(name)
                ? readVec(sub, "Pos") : readVec(region, "Position");
            final BlockPos regionSize = readVec(region, "Size");

            final Rotation subRotation = rotation(sub.getIntOr("Rotation", 0));
            final Mirror subMirrorRaw = mirror(sub.getIntOr("Mirror", 0));

            final Result partial = pasteRegion(level, region, origin, regionPos, regionSize,
                mainRotation, mainMirror, subRotation, subMirrorRaw, replace);
            placed += partial.placed();
            skipped += partial.skipped();

            // Подрегион может запрещать сущности отдельно от общего флага запроса.
            if (withEntities && !sub.getBooleanOr("IgnoreEntities", false)) {
                entities += pasteEntities(level, region, origin, regionPos,
                    mainRotation, mainMirror, subRotation, subMirrorRaw);
            }
        }
        return new Result(placed, skipped, entities, regions.size());
    }

    /**
     * Сущности региона.
     *
     * <p>Позиция сущности хранится в её же NBT полем {@code Pos} и отсчитывается от угла
     * региона. Преобразование — как у блоков, но в дробных координатах: у Litematica это
     * {@code getTransformedPosition}, где отражение считается от единицы, а не от нуля,
     * потому что речь про точку внутри клетки, а не про саму клетку.
     *
     * <p>Смещение здесь <b>без</b> поправки на минимальный угол: у сущностей координата
     * уже абсолютна внутри региона.
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
                    // Метод не возвращает результат: сущность либо добавится, либо будет
                    // отброшена движком. Считаем попытки — точнее отсюда не узнать.
                    level.addFreshEntityWithPassengers(entity);
                    spawned++;
                }
            } catch (final Throwable ignored) {
                // Одна испорченная сущность не должна срывать всю вставку.
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
     * {@code PositionUtils.getTransformedPosition} — дробный вариант.
     *
     * <p>Отражение считается от единицы: точка внутри клетки при зеркалировании должна
     * остаться внутри той же клетки, а не уехать на её край.
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

        // Untransformed relative positions, как в оригинале.
        final BlockPos endRelSub = relativeEnd(regionSize);
        final BlockPos endRel = endRelSub.offset(regionPos);
        final BlockPos minRel = min(regionPos, endRel);
        final BlockPos regionPosTransformed = transform(regionPos, mainMirror, mainRotation);

        final Rotation combined = mainRotation.getRotated(subRotation);
        Mirror subMirror = subMirrorRaw;
        // Поворот основного размещения на 90 градусов меняет ось отражения подрегиона.
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

                    // Старый контейнер сначала опустошаем, иначе его содержимое выпадет
                    // на пол при замене. Так же поступает и Litematica.
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

    /** Данные тайл-энтити: координаты в них региональные, заменяем на мировые. */
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
            // Испорченные данные одного блока не должны срывать всю вставку.
        }
    }

    // ------------------------------------------------------------------ разбор

    /**
     * Распаковка спаннинг-битмассива Litematica.
     *
     * <p>Запись может пересекать границу двух {@code long} — этим формат отличается от
     * ванильного палитрового контейнера, где записи не пересекаются.
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
     * Позиция может лежать компаундом {@code {x,y,z}} или массивом из трёх чисел —
     * Litematica пишет по-разному в разных местах.
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

    // ------------------------------------------------------- геометрия Litematica

    /** Размер может быть отрицательным: конец считается со сдвигом к нулю на единицу. */
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

    /** {@code PositionUtils.getTransformedBlockPos} — воспроизведено дословно. */
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
     * Отражение блока с исправлением известных ванильных багов (двойные сундуки, ступени).
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
     * Поворот блока с исправлением ванильного бага поворота прямых рельсов на 180°.
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
