package paperlab.cplay.playback;

import io.papermc.paper.lab.cplay.CPlayBlockRegion;
import io.papermc.paper.lab.cplay.CPlaySignalEdge;
import io.papermc.paper.lab.cplay.CPlaySignalEvent;
import io.papermc.paper.lab.cplay.CPlayTickPhase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CPlayPlaybackControllerTest {

    @Test
    public void testFramesSerializationRoundTrip() {
        final List<List<CPlaySignalEvent>> originalFrames = new ArrayList<>();

        // Tick 0: 2 events
        final List<CPlaySignalEvent> tick0 = new ArrayList<>();
        tick0.add(new CPlaySignalEvent(CPlayTickPhase.IMMEDIATE, 0, 0, CPlaySignalEdge.RISING_EDGE, new BlockPos(10, 64, -20), false));
        tick0.add(new CPlaySignalEvent(CPlayTickPhase.BLOCK_EVENTS, 2, 1, CPlaySignalEdge.FALLING_EDGE, new BlockPos(10, 65, -20), true));
        originalFrames.add(tick0);

        // Tick 1: empty
        originalFrames.add(Collections.emptyList());

        // Tick 2: 1 event
        final List<CPlaySignalEvent> tick2 = new ArrayList<>();
        tick2.add(new CPlaySignalEvent(CPlayTickPhase.BLOCK_EVENTS, 5, 0, CPlaySignalEdge.RISING_EDGE, new BlockPos(100, 70, 200), false));
        originalFrames.add(tick2);

        final byte[] bytes = CPlayPlaybackController.serializeFrames(originalFrames);
        assertNotNull(bytes);
        assertTrue(bytes.length > 8);

        final List<List<CPlaySignalEvent>> decoded = CPlayPlaybackController.deserializeFrames(bytes);
        assertNotNull(decoded);
        assertEquals(3, decoded.size());

        // Check tick 0
        assertEquals(2, decoded.get(0).size());
        final CPlaySignalEvent ev0 = decoded.get(0).get(0);
        assertEquals(CPlayTickPhase.IMMEDIATE, ev0.getPhase());
        assertEquals(0, ev0.getMicrotick());
        assertEquals(0, ev0.getIndex());
        assertEquals(CPlaySignalEdge.RISING_EDGE, ev0.getEdge());
        assertEquals(new BlockPos(10, 64, -20), ev0.getPos());
        assertFalse(ev0.isShadow());

        final CPlaySignalEvent ev1 = decoded.get(0).get(1);
        assertEquals(CPlayTickPhase.BLOCK_EVENTS, ev1.getPhase());
        assertEquals(2, ev1.getMicrotick());
        assertEquals(1, ev1.getIndex());
        assertEquals(CPlaySignalEdge.FALLING_EDGE, ev1.getEdge());
        assertEquals(new BlockPos(10, 65, -20), ev1.getPos());
        assertTrue(ev1.isShadow());

        // Check tick 1
        assertTrue(decoded.get(1).isEmpty());

        // Check tick 2
        assertEquals(1, decoded.get(2).size());
        final CPlaySignalEvent ev2 = decoded.get(2).get(0);
        assertEquals(CPlayTickPhase.BLOCK_EVENTS, ev2.getPhase());
        assertEquals(5, ev2.getMicrotick());
        assertEquals(new BlockPos(100, 70, 200), ev2.getPos());
    }

    @Test
    public void testDeserializeCorruptedDataReturnsEmptyList() {
        assertTrue(CPlayPlaybackController.deserializeFrames(null).isEmpty());
        assertTrue(CPlayPlaybackController.deserializeFrames(new byte[0]).isEmpty());
        assertTrue(CPlayPlaybackController.deserializeFrames(new byte[]{0, 0, 0}).isEmpty());
        // Wrong version
        assertTrue(CPlayPlaybackController.deserializeFrames(new byte[]{0, 0, 0, 99, 0, 0, 0, 0}).isEmpty());
    }

    @Test
    public void testCPlayBlockRegionContains() {
        final CPlayBlockRegion region = new CPlayBlockRegion(0, 0, 0, 10, 10, 10);
        assertTrue(region.contains(new BlockPos(0, 0, 0)));
        assertTrue(region.contains(new BlockPos(5, 5, 5)));
        assertTrue(region.contains(new BlockPos(10, 10, 10)));
        assertFalse(region.contains(new BlockPos(-1, 5, 5)));
        assertFalse(region.contains(new BlockPos(11, 5, 5)));
    }
}
