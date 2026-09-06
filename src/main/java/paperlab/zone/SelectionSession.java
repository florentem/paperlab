package paperlab.zone;

import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * Tracks an active snowball-based box selection session for a player.
 */
public final class SelectionSession {

    private final UUID playerId;
    private final String zoneName;
    private final int slot;
    private final ItemStack originalItem;

    private boolean hasPos1 = false;
    private int pos1X, pos1Y, pos1Z;

    private boolean hasPos2 = false;
    private int pos2X, pos2Y, pos2Z;

    public SelectionSession(final UUID playerId, final String zoneName, final int slot, final ItemStack originalItem) {
        this.playerId = playerId;
        this.zoneName = zoneName;
        this.slot = slot;
        this.originalItem = originalItem != null ? originalItem.clone() : null;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public String zoneName() {
        return this.zoneName;
    }

    public int slot() {
        return this.slot;
    }

    public ItemStack originalItem() {
        return this.originalItem != null ? this.originalItem.clone() : null;
    }

    public boolean hasPos1() {
        return this.hasPos1;
    }

    public void setPos1(final int x, final int y, final int z) {
        this.pos1X = x;
        this.pos1Y = y;
        this.pos1Z = z;
        this.hasPos1 = true;
    }

    public int pos1X() { return this.pos1X; }
    public int pos1Y() { return this.pos1Y; }
    public int pos1Z() { return this.pos1Z; }

    public boolean hasPos2() {
        return this.hasPos2;
    }

    public void setPos2(final int x, final int y, final int z) {
        this.pos2X = x;
        this.pos2Y = y;
        this.pos2Z = z;
        this.hasPos2 = true;
    }

    public int pos2X() { return this.pos2X; }
    public int pos2Y() { return this.pos2Y; }
    public int pos2Z() { return this.pos2Z; }

    public boolean isComplete() {
        return this.hasPos1 && this.hasPos2;
    }

    public ZoneBox createBox() {
        if (!isComplete()) {
            throw new IllegalStateException("Selection is not complete");
        }
        return ZoneBox.of(this.pos1X, this.pos1Y, this.pos1Z, this.pos2X, this.pos2Y, this.pos2Z);
    }
}
