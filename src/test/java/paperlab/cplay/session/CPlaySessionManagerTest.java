package paperlab.cplay.session;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CPlaySessionManagerTest {

    @Test
    public void testSessionStateParticipants() {
        final UUID assetId = UUID.randomUUID();
        final CPlaySessionManager.SessionState state = new CPlaySessionManager.SessionState(assetId, 0, "TestAsset");

        assertTrue(state.isEmpty());
        final UUID player1 = UUID.randomUUID();
        final UUID player2 = UUID.randomUUID();

        state.addParticipant(player1);
        assertFalse(state.isEmpty());
        assertEquals(1, state.getParticipants().size());
        assertTrue(state.getParticipants().contains(player1));

        state.addParticipant(player2);
        assertEquals(2, state.getParticipants().size());

        state.removeParticipant(player1);
        assertEquals(1, state.getParticipants().size());
        assertFalse(state.isEmpty());

        state.removeParticipant(player2);
        assertTrue(state.isEmpty());
    }

    @Test
    public void testDeltaHistoryCap() {
        final UUID assetId = UUID.randomUUID();
        final CPlaySessionManager.SessionState state = new CPlaySessionManager.SessionState(assetId, 1, "DeltaTest");

        // Add 1200 deltas; cap is 1000
        for (int i = 0; i < 1200; i++) {
            state.addDelta(new byte[]{(byte) (i & 0xFF)});
        }

        assertEquals(1000, state.getDeltaHistory().size());
        // Oldest delta should be 200 (since 0..199 were evicted)
        assertEquals((byte) (200 & 0xFF), state.getDeltaHistory().get(0)[0]);
        assertEquals((byte) (1199 & 0xFF), state.getDeltaHistory().get(999)[0]);
    }
}
