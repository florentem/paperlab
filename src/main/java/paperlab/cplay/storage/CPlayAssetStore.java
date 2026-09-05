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
        final UUID assetUUID = UUID.randomUUID();
        final CPlayAssetNamespace ns = (type == CPlayAssetType.COMPOSITION) ? CPlayAssetNamespace.COMPOSITION : CPlayAssetNamespace.SEQUENCE;
        final CPlayAssetHandle handle = CPlayAssetHandle.random(ns, 6);
        final long now = System.currentTimeMillis();
        final UUID creatorUUID = (creator != null) ? creator.getUniqueId() : CPlayAssetInfo.UNKNOWN_OWNER_UUID;

        final CPlayAssetInfo info = new CPlayAssetInfo(type.getIndex(), assetUUID, handle, name, now, now, creatorUUID, creatorUUID);
        registerAsset(info);
        saveHistory();
        return info;
    }

    public void registerAsset(final CPlayAssetInfo info) {
        assetsByUuid.put(info.getAssetUUID(), info);
        if (info.getHandle() != null) {
            assetsByHandle.put(info.getHandle(), info);
        }
    }

    public boolean deleteAsset(final UUID assetUUID) {
        final CPlayAssetInfo info = assetsByUuid.remove(assetUUID);
        if (info != null) {
            if (info.getHandle() != null) {
                assetsByHandle.remove(info.getHandle());
            }
            final File file = new File(assetsDir, assetUUID.toString() + info.getType().getExtension());
            if (file.exists()) {
                file.delete();
            }
            saveHistory();
            return true;
        }
        return false;
    }

    public byte[] getAssetData(final UUID assetUUID) {
        final CPlayAssetInfo info = getAsset(assetUUID);
        if (info == null) return null;
        final File file = new File(assetsDir, assetUUID.toString() + info.getType().getExtension());
        if (file.exists()) {
            try {
                return Files.readAllBytes(file.toPath());
            } catch (final IOException e) {
                logger.warning("Failed to read asset " + assetUUID + ": " + e.getMessage());
            }
        }
        return null;
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
        final File file = new File(assetsDir, assetUUID.toString() + info.getType().getExtension());
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
            // Пропускаем заголовок пакета (8 байт), чтобы сохранить сырой файл history.dat
            final ByteBuf buf = Unpooled.wrappedBuffer(encoded);
            buf.readLong(); // пропускаем packetId
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
            buf.readLong(); // пропускаем packetId
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
