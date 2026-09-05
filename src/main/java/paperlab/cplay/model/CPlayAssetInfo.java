package paperlab.cplay.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

public final class CPlayAssetInfo implements Comparable<CPlayAssetInfo> {
    public static final UUID UNKNOWN_OWNER_UUID = new UUID(0L, 0L);

    private final int typeIndex;
    private final UUID assetUUID;
    private final CPlayAssetHandle handle;
    private String assetName;
    private final long createdTimestamp;
    private long lastModifiedTimestamp;
    private final UUID createdByUUID;
    private UUID ownerUUID;
    private final Set<UUID> collaboratorUUIDs = new HashSet<>();

    public CPlayAssetInfo(int typeIndex, UUID assetUUID, CPlayAssetHandle handle, String assetName,
                          long createdTimestamp, long lastModifiedTimestamp, UUID createdByUUID,
                          UUID ownerUUID) {
        this.typeIndex = typeIndex;
        this.assetUUID = Objects.requireNonNull(assetUUID, "assetUUID");
        this.handle = handle;
        this.assetName = (assetName != null) ? assetName : "";
        this.createdTimestamp = createdTimestamp;
        this.lastModifiedTimestamp = lastModifiedTimestamp;
        this.createdByUUID = (createdByUUID != null) ? createdByUUID : UNKNOWN_OWNER_UUID;
        this.ownerUUID = (ownerUUID != null) ? ownerUUID : UNKNOWN_OWNER_UUID;
    }

    public int getTypeIndex() { return typeIndex; }
    public CPlayAssetType getType() { return CPlayAssetType.fromIndex(typeIndex); }
    public UUID getAssetUUID() { return assetUUID; }
    public CPlayAssetHandle getHandle() { return handle; }
    public String getAssetName() { return assetName; }
    public void setAssetName(String name) { this.assetName = name; }
    public long getCreatedTimestamp() { return createdTimestamp; }
    public long getLastModifiedTimestamp() { return lastModifiedTimestamp; }
    public void setLastModifiedTimestamp(long ts) { this.lastModifiedTimestamp = ts; }
    public UUID getCreatedByUUID() { return createdByUUID; }
    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID owner) { this.ownerUUID = owner; }
    public Set<UUID> getCollaboratorUUIDs() { return Collections.unmodifiableSet(collaboratorUUIDs); }

    public void addCollaborator(UUID uuid) { collaboratorUUIDs.add(uuid); }
    public void removeCollaborator(UUID uuid) { collaboratorUUIDs.remove(uuid); }
    public boolean isCollaborator(UUID uuid) { return collaboratorUUIDs.contains(uuid); }

    public boolean hasPermission(Player player) {
        if (player.isOp() || player.hasPermission("paperlab.cplay.manage")) {
            return true;
        }
        UUID uuid = player.getUniqueId();
        return ownerUUID.equals(uuid) || ownerUUID.equals(UNKNOWN_OWNER_UUID) || isCollaborator(uuid);
    }

    @Override
    public int compareTo(CPlayAssetInfo o) {
        int cmp = Long.compare(o.lastModifiedTimestamp, this.lastModifiedTimestamp);
        if (cmp != 0) return cmp;
        cmp = Long.compare(o.createdTimestamp, this.createdTimestamp);
        if (cmp != 0) return cmp;
        return assetUUID.compareTo(o.assetUUID);
    }
}
