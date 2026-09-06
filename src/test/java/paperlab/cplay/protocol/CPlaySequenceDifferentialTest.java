package paperlab.cplay.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.g4mesoft.captureplayback.common.asset.GSAssetFileHeader;
import com.g4mesoft.captureplayback.sequence.GSChannel;
import com.g4mesoft.captureplayback.sequence.GSChannelEntry;
import com.g4mesoft.captureplayback.sequence.GSSequence;
import com.g4mesoft.util.GSDecodeBuffer;
import io.netty.buffer.Unpooled;
import io.papermc.paper.lab.cplay.CPlaySignalEdge;
import io.papermc.paper.lab.cplay.CPlaySignalEvent;
import io.papermc.paper.lab.cplay.CPlayTickPhase;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import paperlab.cplay.model.CPlayAssetHandle;
import paperlab.cplay.model.CPlayAssetInfo;
import paperlab.cplay.model.CPlayAssetType;

/**
 * The recording we write is read back by the mod's own {@link GSSequence#read}.
 *
 * <p>This test exists because of a specific failure. A composition the server had recorded killed
 * the client the moment anyone opened it: {@code minimumReadableBytes: -1}. Nothing failed on the
 * server, nothing was logged — the mod's decoder threw and the connection was gone. Reading our
 * own bytes back with our own code would have proved only that we are self-consistent.
 *
 * <p>So the bytes here go through the mod's real classes, loaded from its jar.
 */
class CPlaySequenceDifferentialTest {

    private static CPlayAssetInfo info(final String name) {
        final UUID owner = UUID.nameUUIDFromBytes(("owner-" + name).getBytes());
        return new CPlayAssetInfo(
            CPlayAssetType.SEQUENCE.getIndex(),
            UUID.nameUUIDFromBytes(name.getBytes()),
            CPlayAssetHandle.parse("g:" + name),
            name,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            owner,
            owner);
    }

    private static CPlaySignalEvent edge(final CPlaySignalEdge edge, final BlockPos pos, final int microtick) {
        return new CPlaySignalEvent(CPlayTickPhase.BLOCK_EVENTS, microtick, 0, edge, pos, false);
    }

    /** Header first, then the sequence — exactly how the mod reads an asset file. */
    private static GSSequence decode(final byte[] assetFile) throws Exception {
        final GSDecodeBuffer buf = GSDecodeBuffer.wrap(Unpooled.wrappedBuffer(assetFile));
        final GSAssetFileHeader header = GSAssetFileHeader.read(buf);
        assertNotNull(header, "the mod must accept the asset file header");
        final GSSequence sequence = GSSequence.read(buf);
        assertFalse(buf.isReadable(), "the mod must consume the whole asset file");
        return sequence;
    }

    @Test
    @DisplayName("One recorded position becomes one channel with one entry")
    void singlePulse() throws Exception {
        final BlockPos pos = new BlockPos(10, 64, -20);
        final List<List<CPlaySignalEvent>> frames = new ArrayList<>();
        frames.add(List.of());
        frames.add(List.of(edge(CPlaySignalEdge.RISING_EDGE, pos, 0)));
        frames.add(List.of());
        frames.add(List.of(edge(CPlaySignalEdge.FALLING_EDGE, pos, 0)));
        frames.add(List.of());

        final GSSequence sequence = decode(CPlaySequenceWriter.encodeAssetFile(info("pulse"), frames));

        final Collection<GSChannel> channels = sequence.getChannels();
        assertEquals(1, channels.size(), "one recorded position means one channel");

        final GSChannel channel = channels.iterator().next();
        assertTrue(channel.getInfo().getPositions().contains(pos),
            "the channel must carry the position it was recorded from");

        final Collection<GSChannelEntry> entries = channel.getEntries();
        assertEquals(1, entries.size(), "one high stretch means one entry");

        final GSChannelEntry entry = entries.iterator().next();
        assertEquals(1L, entry.getStartTime().getGametick(), "the entry starts on the rising edge");
        assertEquals(3L, entry.getEndTime().getGametick(), "and ends on the falling one");
    }

    @Test
    @DisplayName("Several positions in one tick become separate channels")
    void severalPositionsInOneTick() throws Exception {
        final BlockPos a = new BlockPos(0, 64, 0);
        final BlockPos b = new BlockPos(1, 64, 0);
        final BlockPos c = new BlockPos(2, 64, 0);

        final List<List<CPlaySignalEvent>> frames = new ArrayList<>();
        frames.add(List.of(edge(CPlaySignalEdge.RISING_EDGE, a, 0),
                           edge(CPlaySignalEdge.RISING_EDGE, b, 1),
                           edge(CPlaySignalEdge.RISING_EDGE, c, 2)));
        frames.add(List.of(edge(CPlaySignalEdge.FALLING_EDGE, a, 0),
                           edge(CPlaySignalEdge.FALLING_EDGE, b, 1),
                           edge(CPlaySignalEdge.FALLING_EDGE, c, 2)));

        final GSSequence sequence = decode(CPlaySequenceWriter.encodeAssetFile(info("burst"), frames));
        assertEquals(3, sequence.getChannels().size(), "three positions mean three channels");
    }

    @Test
    @DisplayName("A recording stopped while the signal is high still reads back")
    void unterminatedEntryIsClosed() throws Exception {
        // This is the ordinary case on the bench: you stop the capture with the piston extended.
        // An entry with no end would make the mod refuse the whole sequence.
        final BlockPos pos = new BlockPos(5, 70, 5);
        final List<List<CPlaySignalEvent>> frames = new ArrayList<>();
        frames.add(List.of(edge(CPlaySignalEdge.RISING_EDGE, pos, 0)));
        frames.add(List.of());
        frames.add(List.of());

        final GSSequence sequence = decode(CPlaySequenceWriter.encodeAssetFile(info("stuck"), frames));
        final GSChannelEntry entry = sequence.getChannels().iterator().next().getEntries().iterator().next();
        assertEquals(0L, entry.getStartTime().getGametick());
        assertEquals(2L, entry.getEndTime().getGametick(), "closed at the last recorded tick");
    }

    @Test
    @DisplayName("A recording with no events at all is still a valid empty sequence")
    void emptyRecording() throws Exception {
        final List<List<CPlaySignalEvent>> frames = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            frames.add(List.of());
        }
        // This is what a lever-and-lamp recording produces today: the capture only sees pistons.
        final GSSequence sequence = decode(CPlaySequenceWriter.encodeAssetFile(info("silent"), frames));
        assertTrue(sequence.getChannels().isEmpty(), "no events means no channels");
    }

    @Test
    @DisplayName("What is written is what plays back: edges survive the round trip")
    void roundTrip() {
        final BlockPos a = new BlockPos(3, 64, 3);
        final BlockPos b = new BlockPos(4, 64, 3);

        final List<List<CPlaySignalEvent>> frames = new ArrayList<>();
        frames.add(List.of(edge(CPlaySignalEdge.RISING_EDGE, a, 0)));
        frames.add(List.of(edge(CPlaySignalEdge.RISING_EDGE, b, 0)));
        frames.add(List.of(edge(CPlaySignalEdge.FALLING_EDGE, a, 0)));
        frames.add(List.of(edge(CPlaySignalEdge.FALLING_EDGE, b, 0)));

        final List<List<CPlaySignalEvent>> back = CPlaySequenceReader.readAssetFile(
            CPlaySequenceWriter.encodeAssetFile(info("round"), frames));

        assertEquals(4, back.size(), "the recording keeps its length");
        assertEquals(1, back.get(0).size());
        assertEquals(CPlaySignalEdge.RISING_EDGE, back.get(0).get(0).getEdge());
        assertEquals(a, back.get(0).get(0).getPos());
        assertEquals(CPlaySignalEdge.FALLING_EDGE, back.get(2).get(0).getEdge());
        assertEquals(a, back.get(2).get(0).getPos());
        assertEquals(b, back.get(3).get(0).getPos());
    }

    @Test
    @DisplayName("Randomised recordings always read back [2,000 iterations]")
    void fuzz() throws Exception {
        final Random rng = new Random(1234L);
        for (int iteration = 0; iteration < 2000; iteration++) {
            final int positions = 1 + rng.nextInt(4);
            final BlockPos[] pos = new BlockPos[positions];
            final boolean[] high = new boolean[positions];
            for (int i = 0; i < positions; i++) {
                pos[i] = new BlockPos(rng.nextInt(2000) - 1000, rng.nextInt(320) - 64,
                    rng.nextInt(2000) - 1000);
            }

            final List<List<CPlaySignalEvent>> frames = new ArrayList<>();
            for (int tick = 0; tick < 40; tick++) {
                final List<CPlaySignalEvent> frame = new ArrayList<>();
                for (int i = 0; i < positions; i++) {
                    if (rng.nextInt(5) == 0) {
                        high[i] = !high[i];
                        frame.add(edge(high[i] ? CPlaySignalEdge.RISING_EDGE
                            : CPlaySignalEdge.FALLING_EDGE, pos[i], rng.nextInt(3)));
                    }
                }
                frames.add(frame);
            }

            // The only assertion that matters: the mod reads it without throwing. Overlapping
            // entries, an end before a start or a bad position count would all fail here - and in
            // front of a player they would drop the connection instead.
            decode(CPlaySequenceWriter.encodeAssetFile(info("fuzz" + iteration), frames));
        }
    }
}
