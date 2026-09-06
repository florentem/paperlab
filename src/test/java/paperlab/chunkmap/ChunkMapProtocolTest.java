package paperlab.chunkmap;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ChunkMapProtocolTest {

    @Test
    public void testTicketInfoRecord() {
        final Identifier id = Identifier.fromNamespaceAndPath("minecraft", "player");
        final ChunkMapProtocol.TicketInfo ticket = new ChunkMapProtocol.TicketInfo(id, 31, 100);

        assertEquals(id, ticket.type());
        assertEquals(31, ticket.level());
        assertEquals(100, ticket.ticksLeft());
    }

    @Test
    public void testChunkInfoRecord() {
        final ChunkPos pos = new ChunkPos(10, -20);
        final Identifier id = Identifier.fromNamespaceAndPath("minecraft", "forced");
        final ChunkMapProtocol.TicketInfo ticket = new ChunkMapProtocol.TicketInfo(id, 31, 0);

        final ChunkMapProtocol.ChunkInfo chunk = new ChunkMapProtocol.ChunkInfo(
            pos,
            null,
            List.of(ticket),
            31,
            31,
            false
        );

        assertEquals(pos, chunk.position());
        assertNull(chunk.stage());
        assertEquals(31, chunk.statusLevel());
        assertEquals(31, chunk.tickingStatusLevel());
        assertFalse(chunk.unloading());
        assertEquals(1, chunk.tickets().size());
        assertEquals(ticket, chunk.tickets().get(0));
    }

    @Test
    public void testProtocolConstants() {
        assertEquals("chunk-debug", ChunkMapProtocol.NAMESPACE);
        assertEquals(4, ChunkMapProtocol.PROTOCOL_VERSION);
    }
}
