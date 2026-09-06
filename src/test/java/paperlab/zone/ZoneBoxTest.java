package paperlab.zone;

import org.bukkit.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ZoneBoxTest {

    @Test
    void testCoordinateNormalization() {
        final ZoneBox box = ZoneBox.of(100, 50, 200, 10, 80, -20);
        assertEquals(10, box.minX());
        assertEquals(100, box.maxX());
        assertEquals(50, box.minY());
        assertEquals(80, box.maxY());
        assertEquals(-20, box.minZ());
        assertEquals(200, box.maxZ());

        assertEquals(91, box.sizeX());
        assertEquals(31, box.sizeY());
        assertEquals(221, box.sizeZ());
        assertEquals(91L * 31L * 221L, box.volume());
    }

    @Test
    void testContains() {
        final ZoneBox box = ZoneBox.of(0, 0, 0, 10, 10, 10);
        assertTrue(box.contains(0, 0, 0));
        assertTrue(box.contains(10, 10, 10));
        assertTrue(box.contains(5, 5, 5));

        assertFalse(box.contains(-1, 5, 5));
        assertFalse(box.contains(11, 5, 5));
        assertFalse(box.contains(5, -1, 5));
        assertFalse(box.contains(5, 11, 5));
        assertFalse(box.contains(5, 5, -1));
        assertFalse(box.contains(5, 5, 11));
    }

    @Test
    void testDistinctColors() {
        final Color c0 = ZoneBox.getDistinctColor(0);
        final Color c1 = ZoneBox.getDistinctColor(1);
        final Color c2 = ZoneBox.getDistinctColor(2);

        assertNotNull(c0);
        assertNotNull(c1);
        assertNotNull(c2);

        // Different indices should generate distinct RGB values
        assertNotEquals(c0, c1);
        assertNotEquals(c1, c2);
        assertNotEquals(c0, c2);

        final String hex0 = ZoneBox.getHexColor(0);
        assertTrue(hex0.startsWith("#"));
        assertEquals(7, hex0.length());
    }
}
