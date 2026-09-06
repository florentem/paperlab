package paperlab.zone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Data model for a tick zone in PaperLab.
 */
public final class ZoneModel {

    private final String name;
    private final String world;
    private UUID owner;
    private final Set<UUID> members = new HashSet<>();
    private final List<ZoneBox> boxes = new CopyOnWriteArrayList<>();

    private volatile boolean frozen = false;
    private volatile float tickRate = 20.0f;

    public ZoneModel(final String name, final String world, final UUID owner) {
        this.name = name;
        this.world = world;
        this.owner = owner;
    }

    public String name() {
        return this.name;
    }

    public String world() {
        return this.world;
    }

    public UUID owner() {
        return this.owner;
    }

    public void setOwner(final UUID owner) {
        this.owner = owner;
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(this.members);
    }

    public boolean isMember(final UUID uuid) {
        return (this.owner != null && this.owner.equals(uuid)) || this.members.contains(uuid);
    }

    public void addMember(final UUID uuid) {
        this.members.add(uuid);
    }

    public void removeMember(final UUID uuid) {
        this.members.remove(uuid);
    }

    public List<ZoneBox> boxes() {
        return Collections.unmodifiableList(this.boxes);
    }

    public synchronized void addBox(final ZoneBox box) {
        this.boxes.add(box);
    }

    public synchronized boolean removeBox(final int index) {
        if (index >= 0 && index < this.boxes.size()) {
            this.boxes.remove(index);
            return true;
        }
        return false;
    }

    public synchronized void clearBoxes() {
        this.boxes.clear();
    }

    public boolean isFrozen() {
        return this.frozen;
    }

    public void setFrozen(final boolean frozen) {
        this.frozen = frozen;
    }

    public float tickRate() {
        return this.tickRate;
    }

    public void setTickRate(final float tickRate) {
        this.tickRate = tickRate;
    }
}
