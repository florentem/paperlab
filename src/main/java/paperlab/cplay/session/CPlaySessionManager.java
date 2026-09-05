package paperlab.cplay.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import paperlab.cplay.model.CPlayAssetInfo;
import paperlab.cplay.protocol.CPlayWire;
import paperlab.cplay.storage.CPlayAssetStore;

public final class CPlaySessionManager {

    private final CPlayAssetStore assetStore;
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> playerSessions = new ConcurrentHashMap<>();

    public CPlaySessionManager(final CPlayAssetStore assetStore) {
        this.assetStore = assetStore;
    }

    public boolean handleRequest(final Player player, final int requestType, final UUID assetUUID) {
        final CPlayAssetInfo info = assetStore.getAsset(assetUUID);
        if (info == null || !info.hasPermission(player)) {
            return false;
        }

        if (requestType == 0) { // REQUEST_START
            final SessionState state = sessions.computeIfAbsent(assetUUID, k -> new SessionState(assetUUID, info.getTypeIndex(), info.getAssetName()));
            state.addParticipant(player.getUniqueId());
            playerSessions.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(assetUUID);

            final boolean isOwner = player.getUniqueId().equals(info.getOwnerUUID());
            final byte[] startPacket = CPlayWire.encodeSessionStart(assetUUID, info.getTypeIndex(), info.getAssetName(), isOwner, player.getUniqueId());
            sendTo(player, startPacket);

            // Синхронизируем уже накопленные дельты сессии
            for (final byte[] deltas : state.getDeltaHistory()) {
                final byte[] deltaPacket = CPlayWire.encodeSessionDeltas(assetUUID, deltas);
                sendTo(player, deltaPacket);
            }
            return true;
        } else if (requestType == 1) { // REQUEST_STOP
            stopSessionForPlayer(player, assetUUID);
            return true;
        }
        return false;
    }

    public void handleDeltas(final Player sender, final UUID assetUUID, final byte[] rawDeltas) {
        final SessionState state = sessions.get(assetUUID);
        if (state != null) {
            state.addDelta(rawDeltas);
            final byte[] packet = CPlayWire.encodeSessionDeltas(assetUUID, rawDeltas);

            for (final UUID pId : state.getParticipants()) {
                if (!pId.equals(sender.getUniqueId())) {
                    final Player p = org.bukkit.Bukkit.getPlayer(pId);
                    if (p != null && p.isOnline()) {
                        sendTo(p, packet);
                    }
                }
            }
        }
    }

    public void onPlayerQuit(final Player player) {
        final Set<UUID> assets = playerSessions.remove(player.getUniqueId());
        if (assets != null) {
            for (final UUID assetUUID : assets) {
                final SessionState state = sessions.get(assetUUID);
                if (state != null) {
                    state.removeParticipant(player.getUniqueId());
                    if (state.isEmpty()) {
                        sessions.remove(assetUUID);
                    }
                }
            }
        }
    }

    private void stopSessionForPlayer(final Player player, final UUID assetUUID) {
        final SessionState state = sessions.get(assetUUID);
        if (state != null) {
            state.removeParticipant(player.getUniqueId());
            if (state.isEmpty()) {
                sessions.remove(assetUUID);
            }
        }
        final Set<UUID> set = playerSessions.get(player.getUniqueId());
        if (set != null) {
            set.remove(assetUUID);
        }
        final byte[] stopPacket = CPlayWire.encodeSessionStop(assetUUID);
        sendTo(player, stopPacket);
    }

    private void sendTo(final Player player, final byte[] data) {
        if (player.isOnline()) {
            player.sendPluginMessage(paperlab.PaperLabPlugin.get(), paperlab.cplay.protocol.CPlayProtocol.CHANNEL, data);
        }
    }

    public static final class SessionState {
        private static final int MAX_DELTA_HISTORY = 1000;

        private final UUID assetUUID;
        private final int typeIndex;
        private final String name;
        private final Set<UUID> participants = ConcurrentHashMap.newKeySet();
        private final List<byte[]> deltaHistory = Collections.synchronizedList(new ArrayList<>());

        public SessionState(final UUID assetUUID, final int typeIndex, final String name) {
            this.assetUUID = assetUUID;
            this.typeIndex = typeIndex;
            this.name = name;
        }

        public UUID getAssetUUID() { return assetUUID; }
        public int getTypeIndex() { return typeIndex; }
        public String getName() { return name; }
        public Set<UUID> getParticipants() { return participants; }
        public List<byte[]> getDeltaHistory() { return deltaHistory; }

        public void addParticipant(final UUID p) { participants.add(p); }
        public void removeParticipant(final UUID p) { participants.remove(p); }
        public boolean isEmpty() { return participants.isEmpty(); }
        public void addDelta(final byte[] delta) {
            deltaHistory.add(delta);
            while (deltaHistory.size() > MAX_DELTA_HISTORY) {
                deltaHistory.remove(0);
            }
        }
    }
}
