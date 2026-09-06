package paperlab.cplay.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import paperlab.cplay.model.CPlayAssetHandle;
import paperlab.cplay.model.CPlayAssetInfo;
import paperlab.cplay.model.CPlayAssetNamespace;
import paperlab.cplay.model.CPlayAssetType;
import paperlab.cplay.model.CPlayPlayerCache;
import paperlab.cplay.protocol.CPlayWire;

public final class CPlayAssetStore {

    private final File baseDir;
    private final File assetsDir;
    private final File historyFile;
    private final File playersFile;
    private final Logger logger;

    private final Map<UUID, CPlayAssetInfo> assetsByUuid = new ConcurrentHashMap<>();
    private final Map<CPlayAssetHandle, CPlayAssetInfo> assetsByHandle = new ConcurrentHashMap<>();
    private final CPlayPlayerCache playerCache = new CPlayPlayerCache();

    public CPlayAssetStore(final File dataFolder, final Logger logger) {
        this.baseDir = new File(dataFolder, "cplay");
        this.assetsDir = new File(this.baseDir, "assets");
        this.historyFile = new File(this.baseDir, "history.dat");
        this.playersFile = new File(this.baseDir, "players.dat");
        this.logger = logger;
    }

    public void init() {
        if (!assetsDir.exists()) {
            assetsDir.mkdirs();
        }
        loadHistory();
        loadPlayers();
    }

    public CPlayPlayerCache getPlayerCache() {
        return playerCache;
    }

    public Collection<CPlayAssetInfo> getAllAssets() {
        return Collections.unmodifiableCollection(assetsByUuid.values());
    }

    public CPlayAssetInfo getAsset(final UUID uuid) {
        return assetsByUuid.get(uuid);
    }

    public CPlayAssetInfo getAsset(final CPlayAssetHandle handle) {
        return assetsByHandle.get(handle);
    }

    public CPlayAssetInfo getAssetByName(final String name) {
        for (final CPlayAssetInfo info : assetsByUuid.values()) {
            if (info.getAssetName().equalsIgnoreCase(name)) {
                return info;
            }
        }
        return null;
    }

    public CPlayAssetInfo createAsset(final CPlayAssetType type, final String name, final Player creator) {
        return createAsset(type, null, name, creator);
    }

    public CPlayAssetInfo createAsset(final CPlayAssetType type, final CPlayAssetHandle handle, final String name, final Player creator) {
        final UUID assetUUID = UUID.randomUUID();
        final long now = System.currentTimeMillis();
        final UUID creatorUUID = (creator != null) ? creator.getUniqueId() : CPlayAssetInfo.UNKNOWN_OWNER_UUID;

        final CPlayAssetHandle finalHandle;
        if (handle != null && !assetsByHandle.containsKey(handle)) {
            finalHandle = handle;
        } else {
            final CPlayAssetNamespace ns = (handle != null) ? handle.getNamespace() : CPlayAssetNamespace.GLOBAL;
            finalHandle = CPlayAssetHandle.fromNameUnique(ns, name, assetsByHandle::containsKey);
        }

        final CPlayAssetInfo info = new CPlayAssetInfo(type.getIndex(), assetUUID, finalHandle, name, now, now, creatorUUID, creatorUUID);
        registerAsset(info);
        saveAssetData(assetUUID, CPlayWire.encodeDefaultAssetFile(info));
        saveHistory();
        return info;
    }

    public CPlayAssetInfo createDuplicateAsset(final CPlayAssetHandle handle, final String name, final CPlayAssetInfo origInfo, final Player creator) {
        final UUID assetUUID = UUID.randomUUID();
        final long now = System.currentTimeMillis();
        final UUID creatorUUID = (creator != null) ? creator.getUniqueId() : CPlayAssetInfo.UNKNOWN_OWNER_UUID;

        final CPlayAssetHandle finalHandle;
        if (handle != null && !assetsByHandle.containsKey(handle)) {
            finalHandle = handle;
        } else {
            final CPlayAssetNamespace ns = (handle != null) ? handle.getNamespace() : origInfo.getHandle().getNamespace();
            finalHandle = CPlayAssetHandle.fromNameUnique(ns, name, assetsByHandle::containsKey);
        }

        final CPlayAssetType type = origInfo.getType();
        final CPlayAssetInfo info = new CPlayAssetInfo(type.getIndex(), assetUUID, finalHandle, name, now, now, origInfo.getCreatedByUUID(), creatorUUID);
        registerAsset(info);

        final byte[] origData = getAssetData(origInfo.getAssetUUID());
        if (origData != null) {
            saveAssetData(assetUUID, origData);
        } else {
            saveAssetData(assetUUID, CPlayWire.encodeDefaultAssetFile(info));
        }
        saveHistory();
        return info;
    }

    public void registerAsset(final CPlayAssetInfo info) {
        assetsByUuid.put(info.getAssetUUID(), info);
        if (info.getHandle() != null) {
            assetsByHandle.put(info.getHandle(), info);
        }
    }

    private File getAssetFile(final CPlayAssetInfo info) {
        final File primary = new File(assetsDir, info.getAssetUUID().toString() + ".gsa");
        if (primary.exists()) return primary;
        final File legacyComp = new File(assetsDir, info.getAssetUUID().toString() + ".gcomp");
        if (legacyComp.exists()) return legacyComp;
        final File legacySeq = new File(assetsDir, info.getAssetUUID().toString() + ".gseq");
        if (legacySeq.exists()) return legacySeq;
        return primary;
    }

    public boolean deleteAsset(final UUID assetUUID) {
        final CPlayAssetInfo info = assetsByUuid.remove(assetUUID);
        if (info != null) {
            if (info.getHandle() != null) {
                assetsByHandle.remove(info.getHandle());
            }
            final File file = getAssetFile(info);
            if (file.exists()) {
                file.delete();
            }
            saveHistory();
            return true;
        }
        return false;
    }

    /**
     * The asset file as the mod expects to receive it.
     *
     * <p>Recordings made before frames moved to their own file still carry our frame format in
     * the {@code .gsa}. Handing those bytes to a client kills the connection: the mod reads a
     * length that is not there and its decoder throws {@code minimumReadableBytes: -1}, with
     * nothing logged on our side. It happened on both paths that serve asset bytes — the
     * download and the editing session — so the repair lives here, at the single point they
     * both go through, rather than at each call site.
     *
     * <p>The frames are not thrown away: they are moved to where playback now looks for them.
     */
    public byte[] getAssetData(final UUID assetUUID) {
        final CPlayAssetInfo info = getAsset(assetUUID);
        if (info == null) return null;
        final File file = getAssetFile(info);
        if (file.exists()) {
            try {
                final byte[] data = Files.readAllBytes(file.toPath());
                if (!looksLikeCaptureFrames(data)) {
                    return data;
                }
                return repairAssetFile(assetUUID, info, data);
            } catch (final IOException e) {
                logger.warning("Failed to read asset " + assetUUID + ": " + e.getMessage());
            }
        }
        return null;
    }

    private byte[] repairAssetFile(final UUID assetUUID, final CPlayAssetInfo info, final byte[] frames) {
        try {
            final File framesFile = framesFile(assetUUID);
            if (!framesFile.exists()) {
                atomicWrite(framesFile, frames);
            }
            final byte[] fixed = CPlayWire.encodeDefaultAssetFile(info);
            atomicWrite(new File(assetsDir, assetUUID.toString() + ".gsa"), fixed);
            logger.info("Repaired asset file for " + info.getAssetName()
                + ": recorded frames moved out of the file the client downloads.");
            return fixed;
        } catch (final IOException e) {
            logger.warning("Failed to repair asset " + assetUUID + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Recorded signal frames, kept apart from the asset file the client sees.
     *
     * <p>They must not share a file. The {@code .gsa} the mod downloads has to be in the mod's
     * own asset format; our frames are a private format of our own. Writing them over the
     * asset file made the mod read a bogus length and drop the connection with
     * {@code minimumReadableBytes: -1} the moment anyone opened the composition.
     */
    private File framesFile(final UUID assetUUID) {
        return new File(assetsDir, assetUUID.toString() + ".frames");
    }

    public void saveCaptureFrames(final UUID assetUUID, final byte[] data) {
        final CPlayAssetInfo info = getAsset(assetUUID);
        if (info == null) return;
        try {
            atomicWrite(framesFile(assetUUID), data);
            info.setLastModifiedTimestamp(System.currentTimeMillis());
            saveHistory();
        } catch (final IOException e) {
            logger.warning("Failed to save capture frames for " + assetUUID + ": " + e.getMessage());
        }
    }

    public byte[] getCaptureFrames(final UUID assetUUID) {
        final File file = framesFile(assetUUID);
        if (file.exists()) {
            try {
                return Files.readAllBytes(file.toPath());
            } catch (final IOException e) {
                logger.warning("Failed to read capture frames for " + assetUUID + ": " + e.getMessage());
            }
        }
        // Assets recorded before frames moved to their own file still carry them in the .gsa.
        final byte[] legacy = getAssetData(assetUUID);
        return looksLikeCaptureFrames(legacy) ? legacy : null;
    }

    /**
     * Whether these bytes are our frame format rather than the mod's asset format.
     *
     * <p>Used in two places: to read pre-existing recordings, and to make sure such bytes are
     * never handed to a client.
     */
    public static boolean looksLikeCaptureFrames(final byte[] data) {
        if (data == null || data.length < 8) {
            return false;
        }
        final int version = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
            | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        final int frames = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16)
            | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        return version == 1 && frames >= 0;
    }

    private static void atomicWrite(final File targetFile, final byte[] data) throws IOException {
        final File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp." + UUID.randomUUID());
        Files.write(tempFile.toPath(), data);
        try {
            Files.move(tempFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tempFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void saveAssetData(final UUID assetUUID, final byte[] data) {
        final CPlayAssetInfo info = getAsset(assetUUID);
        if (info == null) return;
        final File file = new File(assetsDir, assetUUID.toString() + ".gsa");
        try {
            atomicWrite(file, data);
            info.setLastModifiedTimestamp(System.currentTimeMillis());
            saveHistory();
        } catch (final IOException e) {
            logger.warning("Failed to save asset " + assetUUID + ": " + e.getMessage());
        }
    }

    public synchronized void saveHistory() {
        try {
            final byte[] encoded = CPlayWire.encodeAssetHistory(assetsByUuid.values());
            // Skip the packet header (8 bytes) so that history.dat is stored as a raw file.
            final ByteBuf buf = Unpooled.wrappedBuffer(encoded);
            buf.readLong(); // skip the packetId
            final byte[] raw = new byte[buf.readableBytes()];
            buf.readBytes(raw);
            atomicWrite(historyFile, raw);
        } catch (final Exception e) {
            logger.warning("Failed to save history.dat: " + e.getMessage());
        }
    }

    public synchronized void loadHistory() {
        if (!historyFile.exists()) return;
        try {
            final byte[] bytes = Files.readAllBytes(historyFile.toPath());
            if (bytes.length < 5) return;
            final ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            buf.readByte(); // fileVersion
            final int count = buf.readInt();
            if (count < 0 || count > 100000) {
                logger.warning("Invalid asset count in history.dat: " + count);
                return;
            }
            for (int i = 0; i < count; i++) {
                final CPlayAssetInfo info = CPlayWire.readAssetInfo(buf);
                registerAsset(info);
            }
            logger.info("Loaded " + assetsByUuid.size() + " Capture & Playback assets.");
        } catch (final Exception e) {
            logger.warning("Failed to load history.dat: " + e.getMessage());
        }
    }

    public synchronized void savePlayers() {
        try {
            final byte[] encoded = CPlayWire.encodePlayerCache(playerCache.getAll());
            final ByteBuf buf = Unpooled.wrappedBuffer(encoded);
            buf.readLong(); // skip the packetId
            final byte[] raw = new byte[buf.readableBytes()];
            buf.readBytes(raw);
            atomicWrite(playersFile, raw);
        } catch (final Exception e) {
            logger.warning("Failed to save players.dat: " + e.getMessage());
        }
    }

    public synchronized void loadPlayers() {
        if (!playersFile.exists()) return;
        try {
            final byte[] bytes = Files.readAllBytes(playersFile.toPath());
            if (bytes.length < 4) return;
            final ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            final int count = buf.readInt();
            if (count < 0 || count > 100000) {
                logger.warning("Invalid player count in players.dat: " + count);
                return;
            }
            for (int i = 0; i < count; i++) {
                final UUID uuid = CPlayWire.readUUID(buf);
                final String name = CPlayWire.readString(buf);
                playerCache.put(uuid, name);
            }
        } catch (final Exception e) {
            logger.warning("Failed to load players.dat: " + e.getMessage());
        }
    }
}
