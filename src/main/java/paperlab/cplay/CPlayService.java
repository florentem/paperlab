package paperlab.cplay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import paperlab.cplay.model.CPlayAssetInfo;
import paperlab.cplay.model.CPlayAssetType;
import paperlab.cplay.playback.CPlayPlaybackController;
import paperlab.cplay.protocol.CPlayProtocol;
import paperlab.cplay.protocol.CPlayWire;
import paperlab.cplay.session.CPlaySessionManager;
import paperlab.cplay.storage.CPlayAssetStore;

public final class CPlayService implements PluginMessageListener {

    private static CPlayService instance;

    private final Plugin plugin;
    private final Logger logger;
    private final CPlayAssetStore assetStore;
    private final CPlaySessionManager sessionManager;
    private final CPlayPlaybackController playbackController;

    private CPlayService(final Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.assetStore = new CPlayAssetStore(plugin.getDataFolder(), logger);
        this.sessionManager = new CPlaySessionManager(assetStore);
        this.playbackController = new CPlayPlaybackController(assetStore);
    }

    public static CPlayService get() {
        return instance;
    }

    public static void enable(final Plugin owner) {
        instance = new CPlayService(owner);
        instance.assetStore.init();

        Bukkit.getMessenger().registerIncomingPluginChannel(owner, CPlayProtocol.CHANNEL, instance);
        Bukkit.getMessenger().registerOutgoingPluginChannel(owner, CPlayProtocol.CHANNEL);
    }

    public static void disable() {
        if (instance != null) {
            instance.assetStore.saveHistory();
            instance.assetStore.savePlayers();
            Bukkit.getMessenger().unregisterIncomingPluginChannel(instance.plugin, CPlayProtocol.CHANNEL, instance);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(instance.plugin, CPlayProtocol.CHANNEL);
            instance = null;
        }
    }

    public CPlayAssetStore getAssetStore() { return assetStore; }
    public CPlaySessionManager getSessionManager() { return sessionManager; }
    public CPlayPlaybackController getPlaybackController() { return playbackController; }

    public static void onJoin(final Player player) {
        if (instance == null) return;

        Bukkit.getGlobalRegionScheduler().runDelayed(instance.plugin, task -> {
            if (!player.isOnline() || !player.hasPermission("paperlab.cplay")) {
                return;
            }

            // 1. Отправляем рукопожатие соединений G4mespeed
            player.sendPluginMessage(instance.plugin, CPlayProtocol.CHANNEL, CPlayWire.encodeConnectionPacket());

            // 2. Отправляем историю ассетов и кэш игроков
            player.sendPluginMessage(instance.plugin, CPlayProtocol.CHANNEL,
                CPlayWire.encodeAssetHistory(instance.assetStore.getAllAssets()));
            player.sendPluginMessage(instance.plugin, CPlayProtocol.CHANNEL,
                CPlayWire.encodePlayerCache(instance.assetStore.getPlayerCache().getAll()));

            // 3. Регистрируем игрока в кэше и объявляем остальным
            instance.assetStore.getPlayerCache().put(player.getUniqueId(), player.getName());
            instance.assetStore.savePlayers();
            broadcast(CPlayWire.encodePlayerCacheAdded(player.getUniqueId(), player.getName()), player);
        }, 1L);
    }

    public static void onQuit(final Player player) {
        if (instance == null) return;
        instance.sessionManager.onPlayerQuit(player);
    }

    public static void broadcast(final byte[] packet, final Player exclude) {
        if (instance == null) return;
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(exclude) && p.hasPermission("paperlab.cplay")) {
                p.sendPluginMessage(instance.plugin, CPlayProtocol.CHANNEL, packet);
            }
        }
    }

    @Override
    public void onPluginMessageReceived(final String channel, final Player player, final byte[] message) {
        if (!CPlayProtocol.CHANNEL.equals(channel) || message.length < 8) {
            return;
        }

        final ByteBuf buf = Unpooled.wrappedBuffer(message);
        final long packetId = buf.readLong();
        final int extensionUID = (int) (packetId >> 32L);
        final int subId = (int) packetId;

        try {
            if (extensionUID == CPlayProtocol.CORE_UID) {
                if (subId == CPlayProtocol.PACKET_CORE_CONNECTION) {
                    // Клиент ответил своим ConnectionPacket
                    player.sendPluginMessage(plugin, CPlayProtocol.CHANNEL,
                        CPlayWire.encodeAssetHistory(assetStore.getAllAssets()));
                    player.sendPluginMessage(plugin, CPlayProtocol.CHANNEL,
                        CPlayWire.encodePlayerCache(assetStore.getPlayerCache().getAll()));
                }
            } else if (extensionUID == CPlayProtocol.CAPL_UID) {
                handleCaplPacket(subId, player, buf);
            }
        } catch (final Exception e) {
            logger.warning("Error processing CPlay packet " + extensionUID + ":" + subId + " from " + player.getName() + ": " + e.getMessage());
        }
    }

    private void handleCaplPacket(final int subId, final Player player, final ByteBuf buf) {
        switch (subId) {
            case CPlayProtocol.PACKET_CAPL_SESSION_REQUEST -> {
                final int reqType = buf.readInt();
                final UUID assetUUID = CPlayWire.readUUID(buf);
                sessionManager.handleRequest(player, reqType, assetUUID);
            }
            case CPlayProtocol.PACKET_CAPL_SESSION_DELTAS -> {
                final UUID assetUUID = CPlayWire.readUUID(buf);
                final byte[] rawDeltas = new byte[buf.readableBytes()];
                buf.readBytes(rawDeltas);
                sessionManager.handleDeltas(player, assetUUID, rawDeltas);
            }
            case CPlayProtocol.PACKET_CAPL_REQUEST_ASSET -> {
                final UUID assetUUID = CPlayWire.readUUID(buf);
                final CPlayAssetInfo info = assetStore.getAsset(assetUUID);
                if (info != null && (info.hasPermission(player) || player.hasPermission("paperlab.cplay.admin"))) {
                    final byte[] data = assetStore.getAssetData(assetUUID);
                    if (data != null) {
                        player.sendPluginMessage(plugin, CPlayProtocol.CHANNEL,
                            CPlayWire.encodeAssetRequestResponseSuccess(data));
                        return;
                    }
                }
                player.sendPluginMessage(plugin, CPlayProtocol.CHANNEL,
                    CPlayWire.encodeAssetRequestResponseDenied(assetUUID));
            }
            case CPlayProtocol.PACKET_CAPL_CREATE_ASSET -> {
                final int typeIndex = buf.readByte() & 0xFF;
                final String name = CPlayWire.readString(buf);
                final CPlayAssetInfo info = assetStore.createAsset(CPlayAssetType.fromIndex(typeIndex), name, player);
                broadcast(CPlayWire.encodeAssetInfoChanged(info), null);
            }
            case CPlayProtocol.PACKET_CAPL_DELETE_ASSET -> {
                final UUID assetUUID = CPlayWire.readUUID(buf);
                final CPlayAssetInfo info = assetStore.getAsset(assetUUID);
                if (info != null && info.hasPermission(player)) {
                    assetStore.deleteAsset(assetUUID);
                    broadcast(CPlayWire.encodeAssetInfoRemoved(assetUUID), null);
                }
            }
            case CPlayProtocol.PACKET_CAPL_IMPORT_ASSET -> {
                final int typeIndex = buf.readByte() & 0xFF;
                final String name = CPlayWire.readString(buf);
                final byte[] fileContent = new byte[buf.readableBytes()];
                buf.readBytes(fileContent);

                final CPlayAssetInfo info = assetStore.createAsset(CPlayAssetType.fromIndex(typeIndex), name, player);
                assetStore.saveAssetData(info.getAssetUUID(), fileContent);
                broadcast(CPlayWire.encodeAssetInfoChanged(info), null);
            }
            case CPlayProtocol.PACKET_CAPL_COLLABORATOR -> {
                final UUID assetUUID = CPlayWire.readUUID(buf);
                final UUID collabUUID = CPlayWire.readUUID(buf);
                final boolean add = buf.readBoolean();

                final CPlayAssetInfo info = assetStore.getAsset(assetUUID);
                if (info != null && info.getOwnerUUID().equals(player.getUniqueId())) {
                    if (add) {
                        info.addCollaborator(collabUUID);
                    } else {
                        info.removeCollaborator(collabUUID);
                    }
                    assetStore.saveHistory();
                    broadcast(CPlayWire.encodeAssetInfoChanged(info), null);
                }
            }
            default -> {
                // Игнорируем неизвестные подтипы
            }
        }
    }
}
