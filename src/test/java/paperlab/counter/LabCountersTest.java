package paperlab.counter;

import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.world.item.DyeColor;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LabCountersTest {

    @Test
    public void testBlockPosKeyEqualityAndHashCode() {
        final LabCounters.BlockPosKey key1 = new LabCounters.BlockPosKey("world", 100, 64, -200);
        final LabCounters.BlockPosKey key2 = new LabCounters.BlockPosKey("world", 100, 64, -200);
        final LabCounters.BlockPosKey diffWorld = new LabCounters.BlockPosKey("world_the_end", 100, 64, -200);
        final LabCounters.BlockPosKey diffCoord = new LabCounters.BlockPosKey("world", 101, 64, -200);

        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
        assertNotEquals(key1, diffWorld);
        assertNotEquals(key1, diffCoord);

        assertEquals("world", key1.worldName());
        assertEquals(100, key1.x());
        assertEquals(64, key1.y());
        assertEquals(-200, key1.z());
    }

    @Test
    public void testWoolColors() {
        assertEquals(DyeColor.WHITE, WoolColors.byMaterial(Material.WHITE_WOOL));
        assertEquals(DyeColor.RED, WoolColors.byMaterial(Material.RED_WOOL));
        assertEquals(DyeColor.BLUE, WoolColors.byMaterial(Material.BLUE_WOOL));
        assertEquals(DyeColor.LIME, WoolColors.byMaterial(Material.LIME_WOOL));
        assertEquals(DyeColor.BLACK, WoolColors.byMaterial(Material.BLACK_WOOL));

        assertNull(WoolColors.byMaterial(Material.STONE));
        assertNull(WoolColors.byMaterial(Material.DIRT));
        assertNull(WoolColors.byMaterial(Material.HOPPER));
    }

    @Test
    public void testChatColourMappingForAllDyes() {
        for (final DyeColor dye : DyeColor.values()) {
            final NamedTextColor textColor = LabCounters.chatColour(dye);
            assertNotNull(textColor, "Chat color for dye " + dye + " should not be null");
        }
    }
}
