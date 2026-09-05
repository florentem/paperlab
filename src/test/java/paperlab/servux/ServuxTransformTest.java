package paperlab.servux;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ServuxTransformTest {

    @BeforeAll
    public static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void testRailRotationFix() {
        final BlockState nsRail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH);
        final BlockState rotated180 = LitematicaPaste.applyRotation(nsRail, Rotation.CLOCKWISE_180);
        assertEquals(RailShape.NORTH_SOUTH, rotated180.getValue(RailBlock.SHAPE),
            "Straight north-south rail should remain north-south when rotated 180 degrees");

        final BlockState ewRail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
        final BlockState rotatedEw180 = LitematicaPaste.applyRotation(ewRail, Rotation.CLOCKWISE_180);
        assertEquals(RailShape.EAST_WEST, rotatedEw180.getValue(RailBlock.SHAPE),
            "Straight east-west rail should remain east-west when rotated 180 degrees");

        final BlockState rotated90 = LitematicaPaste.applyRotation(nsRail, Rotation.CLOCKWISE_90);
        assertEquals(RailShape.EAST_WEST, rotated90.getValue(RailBlock.SHAPE),
            "Straight rail rotated 90 degrees should become east-west");
    }

    @Test
    public void testChestMirrorFix() {
        final BlockState chestLeft = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.LEFT);

        final BlockState mirrored = LitematicaPaste.applyMirror(chestLeft, Mirror.FRONT_BACK);
        assertEquals(ChestType.RIGHT, mirrored.getValue(ChestBlock.TYPE),
            "Front-back mirror of double chest should swap LEFT to RIGHT");
        assertEquals(Direction.NORTH, mirrored.getValue(ChestBlock.FACING),
            "Facing north (Z axis) should not flip facing on FRONT_BACK (X mirror)");

        final BlockState chestEastLeft = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.EAST)
            .setValue(ChestBlock.TYPE, ChestType.LEFT);

        final BlockState mirroredEast = LitematicaPaste.applyMirror(chestEastLeft, Mirror.FRONT_BACK);
        assertEquals(ChestType.RIGHT, mirroredEast.getValue(ChestBlock.TYPE),
            "Front-back mirror should swap LEFT to RIGHT");
        assertEquals(Direction.WEST, mirroredEast.getValue(ChestBlock.FACING),
            "Facing east (X axis) should flip facing on FRONT_BACK (X mirror)");
    }

    @Test
    public void testStairsMirrorFix() {
        final BlockState stairEastInnerLeft = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.EAST)
            .setValue(StairBlock.SHAPE, StairsShape.INNER_LEFT);

        final BlockState mirrored = LitematicaPaste.applyMirror(stairEastInnerLeft, Mirror.FRONT_BACK);
        assertEquals(StairsShape.INNER_RIGHT, mirrored.getValue(StairBlock.SHAPE),
            "Front-back mirror for X axis should fix inverted INNER_LEFT to INNER_RIGHT");
        assertEquals(Direction.WEST, mirrored.getValue(StairBlock.FACING),
            "Facing should rotate 180 degrees");
    }
}
