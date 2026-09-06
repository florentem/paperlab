package paperlab.zone;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SelectionSessionTest {

    @Test
    void testSelectionLifecycle() {
        final UUID uuid = UUID.randomUUID();
        final SelectionSession session = new SelectionSession(uuid, "alpha", 0, null);

        assertEquals(uuid, session.playerId());
        assertEquals("alpha", session.zoneName());
        assertEquals(0, session.slot());
        assertNull(session.originalItem());

        assertFalse(session.hasPos1());
        assertFalse(session.hasPos2());
        assertFalse(session.isComplete());

        session.setPos1(10, 64, 20);
        assertTrue(session.hasPos1());
        assertEquals(10, session.pos1X());
        assertEquals(64, session.pos1Y());
        assertEquals(20, session.pos1Z());
        assertFalse(session.isComplete());

        session.setPos2(30, 80, 50);
        assertTrue(session.hasPos2());
        assertEquals(30, session.pos2X());
        assertEquals(80, session.pos2Y());
        assertEquals(50, session.pos2Z());
        assertTrue(session.isComplete());

        final ZoneBox box = session.createBox();
        assertEquals(10, box.minX());
        assertEquals(30, box.maxX());
        assertEquals(64, box.minY());
        assertEquals(80, box.maxY());
        assertEquals(20, box.minZ());
        assertEquals(50, box.maxZ());
    }
}
