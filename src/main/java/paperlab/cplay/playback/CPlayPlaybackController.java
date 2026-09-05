package paperlab.cplay.playback;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.lab.cplay.CPlayBlockRegion;
import io.papermc.paper.lab.cplay.CPlayCaptureStream;
import io.papermc.paper.lab.cplay.CPlayPlaybackStream;
import io.papermc.paper.lab.cplay.CPlaySignalEdge;
import io.papermc.paper.lab.cplay.CPlaySignalEvent;
import io.papermc.paper.lab.cplay.CPlayTickPhase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.entity.Player;
import paperlab.core.CPlayBridge;
import paperlab.cplay.model.CPlayAssetInfo;
import paperlab.cplay.model.CPlayAssetType;
import paperlab.cplay.storage.CPlayAssetStore;

public final class CPlayPlaybackController {

    private final CPlayAssetStore assetStore;

    public CPlayPlaybackController(final CPlayAssetStore assetStore) {
        this.assetStore = assetStore;
    }

    public boolean startPlayback(final ServerLevel level, final CPlayAssetInfo info, final int delay, final int repeatCount, final Player player) {
        if (!CPlayBridge.PRESENT) {
            if (player != null) {
                player.sendMessage("§cPhysical playback requires PaperLab core patch. Running in metadata-only mode.");
            }
            return false;
        }

        final byte[] data = assetStore.getAssetData(info.getAssetUUID());
        if (data == null || data.length < 8) {
            if (player != null) {
                player.sendMessage("§cAsset contains no recorded signal data.");
            }
            return false;
        }

        final SimplePlaybackStream stream = new SimplePlaybackStream(
            info.getAssetUUID(),
            new CPlayBlockRegion(-30000000, -64, -30000000, 30000000, 320, 30000000),
            data,
            delay,
            repeatCount
        );
        CPlayBridge.addPlaybackStream(level, stream);
        return true;
    }

    public boolean stopPlayback(final ServerLevel level, final UUID assetUUID) {
        return CPlayBridge.removePlaybackStream(level, assetUUID);
    }

    public void stopAllPlaybacks(final ServerLevel level) {
        if (CPlayBridge.PRESENT) {
            for (final CPlayPlaybackStream stream : CPlayBridge.getPlaybackStreams(level)) {
                stream.close();
            }
            for (final CPlayCaptureStream stream : CPlayBridge.getCaptureStreams(level)) {
                stream.close();
            }
        }
    }

    public boolean startCapture(final ServerLevel level, final String name, final BlockPos pos1, final BlockPos pos2, final Player player) {
        if (!CPlayBridge.PRESENT) {
            if (player != null) {
                player.sendMessage("§cPhysical capture requires PaperLab core patch.");
            }
            return false;
        }

        final CPlayAssetInfo info = assetStore.createAsset(CPlayAssetType.SEQUENCE, name, player);
        final CPlayBlockRegion region = CPlayBlockRegion.of(pos1, pos2);
        final SimpleCaptureStream stream = new SimpleCaptureStream(info.getAssetUUID(), region, assetStore);

        CPlayBridge.addCaptureStream(level, stream);
        return true;
    }

    public boolean stopCapture(final ServerLevel level, final UUID assetUUID) {
        if (!CPlayBridge.PRESENT) {
            return false;
        }
        for (final CPlayCaptureStream stream : CPlayBridge.getCaptureStreams(level)) {
            if (stream.getAssetId().equals(assetUUID)) {
                stream.close();
                CPlayBridge.removeCaptureStream(level, assetUUID);
                return true;
            }
        }
        return false;
    }

    public static byte[] serializeFrames(final List<List<CPlaySignalEvent>> frames) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeInt(1); // format version
        buf.writeInt(frames.size());
        for (final List<CPlaySignalEvent> frame : frames) {
            buf.writeInt(frame.size());
            for (final CPlaySignalEvent ev : frame) {
                buf.writeByte((byte) ev.getPhase().getIndex());
                buf.writeInt(ev.getMicrotick());
                buf.writeInt(ev.getIndex());
                buf.writeByte((byte) ev.getEdge().getIndex());
                buf.writeLong(ev.getPos().asLong());
                buf.writeBoolean(ev.isShadow());
            }
        }
        final byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    public static List<List<CPlaySignalEvent>> deserializeFrames(final byte[] data) {
        if (data == null || data.length < 8) {
            return Collections.emptyList();
        }
        final ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            final int version = buf.readInt();
            if (version != 1) {
                return Collections.emptyList();
            }
            final int tickCount = buf.readInt();
            if (tickCount < 0 || tickCount > 1_000_000) {
                return Collections.emptyList();
            }
            final List<List<CPlaySignalEvent>> result = new ArrayList<>(tickCount);
            for (int t = 0; t < tickCount; t++) {
                final int eventCount = buf.readInt();
                if (eventCount < 0 || eventCount > 100_000) {
                    return Collections.emptyList();
                }
                final List<CPlaySignalEvent> frame = new ArrayList<>(eventCount);
                for (int i = 0; i < eventCount; i++) {
                    final int phaseIndex = buf.readByte() & 0xFF;
                    final int microtick = buf.readInt();
                    final int index = buf.readInt();
                    final int edgeIndex = buf.readByte() & 0xFF;
                    final BlockPos pos = BlockPos.of(buf.readLong());
                    final boolean shadow = buf.readBoolean();
                    frame.add(new CPlaySignalEvent(
                        CPlayTickPhase.fromIndex(phaseIndex),
                        microtick,
                        index,
                        CPlaySignalEdge.fromIndex(edgeIndex),
                        pos,
                        shadow
                    ));
                }
                result.add(frame);
            }
            return result;
        } finally {
            buf.release();
        }
    }

    private static final class SimplePlaybackStream implements CPlayPlaybackStream {
        private final UUID assetId;
        private final CPlayBlockRegion region;
        private final List<List<CPlaySignalEvent>> frames;
        private final int delay;
        private final int repeatCount;
        private volatile boolean closed = false;
        private int currentTick = 0;
        private int currentRepeat = 0;

        SimplePlaybackStream(final UUID assetId, final CPlayBlockRegion region, final byte[] data, final int delay, final int repeatCount) {
            this.assetId = assetId;
            this.region = region;
            this.frames = deserializeFrames(data);
            this.delay = delay;
            this.repeatCount = repeatCount;
        }

        @Override public UUID getAssetId() { return assetId; }
        @Override public CPlayBlockRegion getRegion() { return region; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }

        @Override
        public synchronized List<CPlaySignalEvent> readNextTickEvents() {
            if (closed || frames.isEmpty()) {
                return Collections.emptyList();
            }
            if (currentTick < delay) {
                currentTick++;
                return Collections.emptyList();
            }

            final int frameIndex = currentTick - delay;
            if (frameIndex < frames.size()) {
                currentTick++;
                return frames.get(frameIndex);
            }

            currentRepeat++;
            if (repeatCount <= 0 || currentRepeat < repeatCount) {
                currentTick = delay;
                return frames.isEmpty() ? Collections.emptyList() : frames.get(0);
            }

            close();
            return Collections.emptyList();
        }
    }

    private static final class SimpleCaptureStream implements CPlayCaptureStream {
        private final UUID assetId;
        private final CPlayBlockRegion region;
        private final CPlayAssetStore assetStore;
        private volatile boolean closed = false;
        private final List<List<CPlaySignalEvent>> tickFrames = Collections.synchronizedList(new ArrayList<>());

        SimpleCaptureStream(final UUID assetId, final CPlayBlockRegion region, final CPlayAssetStore assetStore) {
            this.assetId = assetId;
            this.region = region;
            this.assetStore = assetStore;
        }

        @Override public UUID getAssetId() { return assetId; }
        @Override public CPlayBlockRegion getRegion() { return region; }
        @Override public boolean isClosed() { return closed; }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            final byte[] data = serializeFrames(tickFrames);
            if (assetStore != null) {
                assetStore.saveAssetData(assetId, data);
            }
        }

        @Override
        public void writeTickEvents(final List<CPlaySignalEvent> events) {
            if (closed) return;
            if (events == null || events.isEmpty()) {
                tickFrames.add(Collections.emptyList());
                return;
            }
            final List<CPlaySignalEvent> filtered = new ArrayList<>();
            for (final CPlaySignalEvent ev : events) {
                if (region.contains(ev.getPos())) {
                    filtered.add(ev);
                }
            }
            tickFrames.add(filtered);
        }
    }
}
