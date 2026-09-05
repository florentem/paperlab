package paperlab.cplay.playback;

import io.papermc.paper.lab.cplay.CPlayBlockRegion;
import io.papermc.paper.lab.cplay.CPlayCaptureStream;
import io.papermc.paper.lab.cplay.CPlayPlaybackStream;
import io.papermc.paper.lab.cplay.CPlaySignalEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.craftbukkit.CraftWorld;
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

        final SimplePlaybackStream stream = new SimplePlaybackStream(info.getAssetUUID(), new CPlayBlockRegion(-30000000, -64, -30000000, 30000000, 320, 30000000));
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
        final SimpleCaptureStream stream = new SimpleCaptureStream(info.getAssetUUID(), region);

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

    private static final class SimplePlaybackStream implements CPlayPlaybackStream {
        private final UUID assetId;
        private final CPlayBlockRegion region;
        private volatile boolean closed = false;

        SimplePlaybackStream(final UUID assetId, final CPlayBlockRegion region) {
            this.assetId = assetId;
            this.region = region;
        }

        @Override public UUID getAssetId() { return assetId; }
        @Override public CPlayBlockRegion getRegion() { return region; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }
        @Override public List<CPlaySignalEvent> readNextTickEvents() {
            return Collections.emptyList();
        }
    }

    private static final class SimpleCaptureStream implements CPlayCaptureStream {
        private final UUID assetId;
        private final CPlayBlockRegion region;
        private volatile boolean closed = false;
        private final List<CPlaySignalEvent> recorded = Collections.synchronizedList(new ArrayList<>());

        SimpleCaptureStream(final UUID assetId, final CPlayBlockRegion region) {
            this.assetId = assetId;
            this.region = region;
        }

        @Override public UUID getAssetId() { return assetId; }
        @Override public CPlayBlockRegion getRegion() { return region; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }
        @Override public void writeTickEvents(final List<CPlaySignalEvent> events) {
            if (events != null && !events.isEmpty()) {
                recorded.addAll(events);
            }
        }
    }
}
