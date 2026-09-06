package paperlab.cplay.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.lab.cplay.CPlaySignalEdge;
import io.papermc.paper.lab.cplay.CPlaySignalEvent;
import io.papermc.paper.lab.cplay.CPlayTickPhase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Reads a sequence in the mod's format back into per-tick signal events.
 *
 * <p>The mirror of {@link CPlaySequenceWriter}, and the reason playback and the editor finally
 * agree: what the mod shows on the timeline is what actually plays. Before this, a recording was
 * played from a private frame file while the sequence was only something the editor looked at, so
 * anything edited in the editor changed nothing.
 *
 * <p>The conversion is the writer's, reversed. Every channel entry — a stretch during which a
 * position was held high — becomes two events: a rising edge at its start tick and a falling edge
 * at its end tick. Entries of zero length still produce both, in the same tick, which is what a
 * signal that went up and down inside one tick did.
 */
public final class CPlaySequenceReader {

    /** Guard against a corrupt count turning into an enormous allocation. */
    private static final int MAX_CHANNELS = 4096;

    /** Same, per channel. A recording of an hour holds 72000 ticks, so this is generous. */
    private static final int MAX_ENTRIES = 200_000;

    private CPlaySequenceReader() {
    }

    /**
     * Decode an asset file into frames, one list of events per tick.
     *
     * @return the frames, or an empty list if these bytes are not a sequence we wrote
     */
    public static List<List<CPlaySignalEvent>> readAssetFile(final byte[] assetFile) {
        if (assetFile == null || assetFile.length < 8) {
            return Collections.emptyList();
        }
        final ByteBuf buf = Unpooled.wrappedBuffer(assetFile);
        try {
            skipHeader(buf);

            buf.readByte(); // reserved
            CPlayWire.readUUID(buf);
            CPlayWire.readString(buf);

            final int channelCount = buf.readInt();
            if (channelCount < 0 || channelCount > MAX_CHANNELS) {
                return Collections.emptyList();
            }

            final List<long[]> spans = new ArrayList<>();
            final List<BlockPos> positions = new ArrayList<>();
            long lastTick = 0L;

            for (int channel = 0; channel < channelCount; channel++) {
                CPlayWire.readUUID(buf); // channel uuid

                CPlayWire.readString(buf); // channel name
                buf.readMedium();          // colour

                final int positionCount = buf.readUnsignedShort();
                BlockPos pos = null;
                for (int i = 0; i < positionCount; i++) {
                    final BlockPos read = CPlayWire.readBlockPos(buf);
                    if (pos == null) {
                        // A channel may carry several positions; we record one per channel, and
                        // for a hand-edited channel the first is the one to drive.
                        pos = read;
                    }
                }

                buf.readBoolean(); // disabled

                final int entryCount = buf.readInt();
                if (entryCount < 0 || entryCount > MAX_ENTRIES) {
                    return Collections.emptyList();
                }
                for (int i = 0; i < entryCount; i++) {
                    CPlayWire.readUUID(buf); // entry uuid
                    final long startTick = buf.readLong();
                    final int startMicro = buf.readInt();
                    final long endTick = buf.readLong();
                    final int endMicro = buf.readInt();
                    buf.readInt(); // entry type

                    if (pos != null) {
                        spans.add(new long[] {startTick, startMicro, endTick, endMicro,
                            positions.size()});
                        lastTick = Math.max(lastTick, endTick);
                    }
                }
                if (pos != null) {
                    positions.add(pos);
                }
            }
            return toFrames(spans, positions, lastTick);
        } catch (final RuntimeException e) {
            // Truncated or foreign bytes. Playing nothing is the right answer; the caller says so.
            return Collections.emptyList();
        } finally {
            buf.release();
        }
    }

    /** GSAssetFileHeader: version byte, optional type byte, timestamp, author, cache flag. */
    private static void skipHeader(final ByteBuf buf) {
        final int version = buf.readUnsignedByte();
        if ((version & 0x80) != 0) {
            buf.readUnsignedByte(); // type index
        }
        buf.readLong();          // created timestamp
        CPlayWire.readUUID(buf); // created by
        if ((version & 0x7F) >= 1 && buf.readBoolean()) {
            // A player cache entry we never write ourselves, but the client may send one back.
            CPlayWire.readString(buf);
            buf.readLong();
        }
    }

    private static List<List<CPlaySignalEvent>> toFrames(final List<long[]> spans,
                                                         final List<BlockPos> positions,
                                                         final long lastTick) {
        if (spans.isEmpty()) {
            return Collections.emptyList();
        }
        final int frameCount = (int) Math.min(Integer.MAX_VALUE - 1, lastTick) + 1;
        final List<List<CPlaySignalEvent>> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            frames.add(new ArrayList<>());
        }

        for (final long[] span : spans) {
            final BlockPos pos = positions.get((int) span[4]);
            final int start = (int) Math.min(frameCount - 1, Math.max(0, span[0]));
            final int end = (int) Math.min(frameCount - 1, Math.max(0, span[2]));

            frames.get(start).add(event(CPlaySignalEdge.RISING_EDGE, pos, (int) span[1]));
            frames.get(end).add(event(CPlaySignalEdge.FALLING_EDGE, pos, (int) span[3]));
        }

        for (final List<CPlaySignalEvent> frame : frames) {
            Collections.sort(frame);
        }
        return frames;
    }

    private static CPlaySignalEvent event(final CPlaySignalEdge edge, final BlockPos pos,
                                          final int microtick) {
        return new CPlaySignalEvent(CPlayTickPhase.BLOCK_EVENTS, Math.max(0, microtick), 0,
            edge, pos, false);
    }
}
