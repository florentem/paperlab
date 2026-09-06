package paperlab.cplay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import paperlab.cplay.model.CPlayAssetHandle;
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
            if (paperlab.core.CPlayBridge.PRESENT) {
                for (final org.bukkit.World world : Bukkit.getWorlds()) {
                    if (world instanceof org.bukkit.craftbukkit.CraftWorld cw) {
                        instance.playbackController.stopAllPlaybacks(cw.getHandle());
                    }
                }
            }
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

    public static void send(final Player player, final byte[] body) {
        if (!player.isOnline()) {
            return;
        }
        final var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null) {
            connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(Identifier.parse(CPlayProtocol.CHANNEL), body)));
        }
    }

    public static void onJoin(final Player player) {
        if (instance == null) return;

        Bukkit.getGlobalRegionScheduler().runDelayed(instance.plugin, task -> {
            if (!player.isOnline()) {
                return;
            }

            // 1. Отправляем рукопожатие соединений G4mespeed в обход sendPluginMessage,
            //    чтобы клиентский мод сразу распознал наличие сервера CPlay.
            send(player, CPlayWire.encodeConnectionPacket());

            // 2. Отправляем историю ассетов и кэш игроков
            send(player, CPlayWire.encodeAssetHistory(instance.assetStore.getAllAssets()));
            send(player, CPlayWire.encodePlayerCache(instance.assetStore.getPlayerCache().getAll()));

            // 3. Регистрируем игрока в кэше и объявляем остальным
            instance.assetStore.getPlayerCache().put(player.getUniqueId(), player.getName());
            Bukkit.getAsyncScheduler().runNow(instance.plugin, t -> instance.assetStore.savePlayers());
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
            if (!p.equals(exclude)) {
                send(p, packet);
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
                    send(player, CPlayWire.encodeAssetHistory(assetStore.getAllAssets()));
                    send(player, CPlayWire.encodePlayerCache(assetStore.getPlayerCache().getAll()));
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
                        send(player, CPlayWire.encodeAssetRequestResponseSuccess(data));
                        return;
                    }
                }
                send(player, CPlayWire.encodeAssetRequestResponseDenied(assetUUID));
            }
            case CPlayProtocol.PACKET_CAPL_CREATE_ASSET -> {
                final String name = CPlayWire.readString(buf);
                final int typeIndex = buf.readByte() & 0xFF;
                final CPlayAssetType type = CPlayAssetType.fromIndex(typeIndex);
                if (type == null) {
                    logger.warning("Unknown asset type index: " + typeIndex);
                    return;
                }
                final CPlayAssetHandle handle = CPlayWire.readAssetHandle(buf);
                final boolean hasOriginal = buf.readBoolean();
                final UUID originalUUID = hasOriginal ? CPlayWire.readUUID(buf) : null;

                final CPlayAssetInfo info;
                if (originalUUID != null) {
                    final CPlayAssetInfo origInfo = assetStore.getAsset(originalUUID);
                    if (origInfo != null && (origInfo.hasPermission(player) || player.hasPermission("paperlab.cplay.admin"))) {
                        info = assetStore.createDuplicateAsset(handle, name, origInfo, player);
                    } else {
                        return;
                    }
                } else {
                    info = assetStore.createAsset(type, handle, name, player);
                }
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
                final String name = CPlayWire.readString(buf);
                final CPlayAssetHandle handle = CPlayWire.readAssetHandle(buf);
                final byte[] fileContent = new byte[buf.readableBytes()];
                buf.readBytes(fileContent);

                int typeIndex = 0;
                if (fileContent.length >= 2) {
                    int fmt = fileContent[0] & 0xFF;
                    if ((fmt & 0x80) != 0) {
                        typeIndex = fileContent[1] & 0xFF;
                    } else {
                        typeIndex = fmt;
                    }
                }
                final CPlayAssetType type = CPlayAssetType.fromIndex(typeIndex) != null ? CPlayAssetType.fromIndex(typeIndex) : CPlayAssetType.COMPOSITION;
                final CPlayAssetInfo info = assetStore.createAsset(type, handle, name, player);
                assetStore.saveAssetData(info.getAssetUUID(), fileContent);
                broadcast(CPlayWire.encodeAssetInfoChanged(info), null);
            }
            case CPlayProtocol.PACKET_CAPL_COLLABORATOR -> {
                final UUID assetUUID = CPlayWire.readUUID(buf);
                final UUID collabUUID = CPlayWire.readUUID(buf);
                final boolean removed = buf.readBoolean();

                final CPlayAssetInfo info = assetStore.getAsset(assetUUID);
                if (info != null && (info.getOwnerUUID().equals(player.getUniqueId()) || player.hasPermission("paperlab.cplay.admin"))) {
                    if (removed) {
                        info.removeCollaborator(collabUUID);
                    } else {
                        info.addCollaborator(collabUUID);
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
