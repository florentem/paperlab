package paperlab.cplay.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.lab.cplay.CPlaySignalEdge;
import io.papermc.paper.lab.cplay.CPlaySignalEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import paperlab.cplay.model.CPlayAssetInfo;

/**
 * Turns a recording into a sequence in the mod's own format.
 *
 * <p>Without this the two halves never met: a recording was kept in a private frame format only
 * we could read, so playback worked while the mod's editor showed an empty timeline. The
 * composition existed and could be played, but not looked at.
 *
 * <p>The mapping is direct. Every recorded position becomes a <b>channel</b>; every stretch
 * during which that position was held high becomes an <b>entry</b> on it. A rising edge opens an
 * entry, the matching falling edge closes it, and a recording stopped while the signal was still
 * high closes the entry at the last recorded tick — the alternative would be an entry with no end,
 * which the mod refuses to read.
 *
 * <h2>The format, as read by the mod</h2>
 * <pre>
 * GSSequence      byte 0, UUID, string name, int channelCount
 * GSChannel       UUID, GSChannelInfo, boolean disabled, int entryCount
 * GSChannelInfo   string name, 3-byte colour, unsigned short positionCount, positions
 * GSChannelEntry  UUID, GSSignalTime start, GSSignalTime end, int type
 * GSSignalTime    long gametick, int microtick
 * </pre>
 *
 * <p>Recovered from the mod's own classes rather than guessed: getting this wrong does not fail
 * on the server, it drops the client's connection inside its decoder with nothing in our log.
 */
public final class CPlaySequenceWriter {

    /** {@code EVENT_BOTH}: the entry carries both its start and its end. */
    private static final int ENTRY_TYPE_BOTH = 0;

    /**
     * Channel colour. The mod reads three bytes and uses them for the timeline block; the value
     * only has to be a colour, so one readable default is enough.
     */
    private static final int CHANNEL_COLOUR = 0xC04040;

    /**
     * A position may be held by more than one recorded edge at a time. The mod refuses
     * overlapping entries on a channel, so nesting is collapsed: the entry spans from the first
     * rising edge to the last falling one.
     */
    private CPlaySequenceWriter() {
    }

    /**
     * Build the asset file — header plus sequence — for a finished recording.
     *
     * @param info   the asset the recording belongs to
     * @param frames one list of events per recorded tick, in tick order
     */
    public static byte[] encodeAssetFile(final CPlayAssetInfo info,
                                         final List<List<CPlaySignalEvent>> frames) {
        final ByteBuf buf = Unpooled.buffer();
        CPlayWire.writeAssetFileHeader(buf, info);
        writeSequence(buf, info, frames);

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    private static void writeSequence(final ByteBuf buf,
                                      final CPlayAssetInfo info,
                                      final List<List<CPlaySignalEvent>> frames) {
        final Map<BlockPos, List<long[]>> channels = collectChannels(frames);

        buf.writeByte(0); // reserved
        CPlayWire.writeUUID(buf, info.getAssetUUID());
        CPlayWire.writeString(buf, info.getAssetName());
        buf.writeInt(channels.size());

        int index = 0;
        for (final Map.Entry<BlockPos, List<long[]>> channel : channels.entrySet()) {
            writeChannel(buf, channel.getKey(), channel.getValue(), ++index);
        }
    }

    private static void writeChannel(final ByteBuf buf,
                                     final BlockPos pos,
                                     final List<long[]> entries,
                                     final int index) {
        CPlayWire.writeUUID(buf, UUID.randomUUID());

        // GSChannelInfo
        CPlayWire.writeString(buf, "channel " + index + " (" + pos.getX() + " " + pos.getY()
            + " " + pos.getZ() + ")");
        buf.writeMedium(CHANNEL_COLOUR);
        buf.writeShort(1);
        CPlayWire.writeBlockPos(buf, pos);

        buf.writeBoolean(false); // not disabled
        buf.writeInt(entries.size());
        for (final long[] span : entries) {
            CPlayWire.writeUUID(buf, UUID.randomUUID());
            writeSignalTime(buf, span[0], (int) span[1]);
            writeSignalTime(buf, span[2], (int) span[3]);
            buf.writeInt(ENTRY_TYPE_BOTH);
        }
    }

    private static void writeSignalTime(final ByteBuf buf, final long gametick, final int microtick) {
        buf.writeLong(Math.max(0L, gametick));
        buf.writeInt(Math.max(0, microtick));
    }

    /**
     * Never let an entry end before it starts.
     *
     * <p>Within one tick the microtick of a falling edge can be lower than that of the rising one:
     * the two are recorded at different points of the tick and nothing orders them. The mod
     * refuses such a span outright - {@code Invalid entry time-span} - and refusing means the
     * whole sequence fails to read, which for a client means a dropped connection. A zero-length
     * entry is the honest thing to store: the signal was up and down inside one tick.
     */
    private static void clampEnd(final long[] span) {
        if (span[2] < span[0] || (span[2] == span[0] && span[3] < span[1])) {
            span[2] = span[0];
            span[3] = span[1];
        }
    }

    /**
     * Walk the frames and turn edges into spans.
     *
     * <p>A span is {@code {startTick, startMicrotick, endTick, endMicrotick}}. The tick number is
     * the frame index, which is what makes the timeline line up with the recording: frame 0 is the
     * moment the capture started.
     */
    private static Map<BlockPos, List<long[]>> collectChannels(
            final List<List<CPlaySignalEvent>> frames) {
        final Map<BlockPos, List<long[]>> out = new LinkedHashMap<>();
        final Map<BlockPos, long[]> open = new LinkedHashMap<>();
        final Map<BlockPos, Integer> depth = new LinkedHashMap<>();

        for (int tick = 0; tick < frames.size(); tick++) {
            for (final CPlaySignalEvent event : frames.get(tick)) {
                final BlockPos pos = event.getPos().immutable();
                final int microtick = Math.max(0, event.getMicrotick());

                if (event.getEdge() == CPlaySignalEdge.RISING_EDGE) {
                    final int nesting = depth.merge(pos, 1, Integer::sum);
                    if (nesting == 1) {
                        open.put(pos, new long[] {tick, microtick, tick, microtick});
                    }
                } else {
                    final Integer nesting = depth.get(pos);
                    if (nesting == null) {
                        continue; // a falling edge with no rising one: nothing to close
                    }
                    if (nesting <= 1) {
                        depth.remove(pos);
                        final long[] span = open.remove(pos);
                        if (span != null) {
                            span[2] = tick;
                            span[3] = microtick;
                            clampEnd(span);
                            out.computeIfAbsent(pos, k -> new ArrayList<>()).add(span);
                        }
                    } else {
                        depth.put(pos, nesting - 1);
                    }
                }
            }
        }

        // A recording stopped while a signal was still high leaves an entry open. The mod will
        // not read an entry whose end is before its start, so it is closed at the last tick.
        final long lastTick = Math.max(0, frames.size() - 1);
        for (final Map.Entry<BlockPos, long[]> pending : open.entrySet()) {
            final long[] span = pending.getValue();
            span[2] = Math.max(span[0], lastTick);
            span[3] = 0;
            clampEnd(span);
            out.computeIfAbsent(pending.getKey(), k -> new ArrayList<>()).add(span);
        }
        return out;
    }
}
