package paperlab.zone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import paperlab.core.CoreBridge;

/**
 * Service managing tick zones, player focus, snowball selection sessions,
 * particle highlighting, and synchronization with core LabTickZones.
 */
public final class ZoneService {

    public record HighlightTarget(String zoneName, boolean allBoxes, Set<Integer> boxIndices) {}

    private final JavaPlugin plugin;
    private final NamespacedKey wandKey;
    private final Path storagePath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, ZoneModel> zones = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerFocus = new ConcurrentHashMap<>();
    private final Map<UUID, SelectionSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, HighlightTarget> highlights = new ConcurrentHashMap<>();

    private BukkitTask highlightTask;

    public ZoneService(final JavaPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "zone_wand");
        this.storagePath = plugin.getDataFolder().toPath().resolve("zones.json");
    }

    public void enable() {
        load();
        if (CoreBridge.PRESENT) {
            CoreDelegate.enableCore(this.zones.values());
        }

        this.plugin.getServer().getPluginManager().registerEvents(new ZoneSelectionListener(this), this.plugin);

        // Highlight rendering task: every 10 ticks (0.5s)
        this.highlightTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::renderHighlights, 10L, 10L);
    }

    public void disable() {
        if (this.highlightTask != null) {
            this.highlightTask.cancel();
            this.highlightTask = null;
        }

        // Restore items for all active selection sessions
        for (final SelectionSession session : this.sessions.values()) {
            final Player player = Bukkit.getPlayer(session.playerId());
            if (player != null && player.isOnline()) {
                player.getInventory().setItem(session.slot(), session.originalItem());
            }
        }
        this.sessions.clear();

        save();

        if (CoreBridge.PRESENT) {
            CoreDelegate.disableCore();
        }
    }

    // --- Zone Registry ---

    public Collection<ZoneModel> allZones() {
        return Collections.unmodifiableCollection(this.zones.values());
    }

    public ZoneModel getZone(final String name) {
        return this.zones.get(name.toLowerCase(Locale.ROOT));
    }

    public ZoneModel createZone(final String name, final String world, final UUID owner) {
        final ZoneModel model = new ZoneModel(name, world, owner);
        this.zones.put(name.toLowerCase(Locale.ROOT), model);
        if (CoreBridge.PRESENT) {
            CoreDelegate.createZone(world, name, owner);
        }
        save();
        return model;
    }

    public boolean removeZone(final String name) {
        final ZoneModel removed = this.zones.remove(name.toLowerCase(Locale.ROOT));
        if (removed != null) {
            if (CoreBridge.PRESENT) {
                CoreDelegate.removeZone(removed.world(), removed.name());
            }
            this.playerFocus.values().removeIf(z -> z.equalsIgnoreCase(name));
            this.highlights.values().removeIf(h -> h.zoneName().equalsIgnoreCase(name));
            save();
            return true;
        }
        return false;
    }

    public void addBoxToZone(final ZoneModel zone, final ZoneBox box) {
        zone.addBox(box);
        if (CoreBridge.PRESENT) {
            CoreDelegate.addBox(zone.world(), zone.name(), box);
        }
        save();
    }

    public boolean removeBoxFromZone(final ZoneModel zone, final int index) {
        final boolean removed = zone.removeBox(index);
        if (removed) {
            if (CoreBridge.PRESENT) {
                CoreDelegate.removeBox(zone.world(), zone.name(), index);
            }
            // Adjust any active highlight target box indices
            for (final HighlightTarget target : this.highlights.values()) {
                if (target.zoneName().equalsIgnoreCase(zone.name()) && !target.allBoxes()) {
                    final Set<Integer> updated = new HashSet<>();
                    for (final int idx : target.boxIndices()) {
                        if (idx < index) {
                            updated.add(idx);
                        } else if (idx > index) {
                            updated.add(idx - 1);
                        }
                    }
                    target.boxIndices().clear();
                    target.boxIndices().addAll(updated);
                }
            }
            save();
        }
        return removed;
    }

    public void clearBoxesFromZone(final ZoneModel zone) {
        zone.clearBoxes();
        if (CoreBridge.PRESENT) {
            CoreDelegate.clearBoxes(zone.world(), zone.name());
        }
        save();
    }

    public void setZoneFrozen(final ZoneModel zone, final boolean frozen) {
        zone.setFrozen(frozen);
        if (CoreBridge.PRESENT) {
            CoreDelegate.setFrozen(zone.world(), zone.name(), frozen);
        }
        save();
    }

    public void setZoneTickRate(final ZoneModel zone, final float tickRate) {
        zone.setTickRate(tickRate);
        if (CoreBridge.PRESENT) {
            CoreDelegate.setTickRate(zone.world(), zone.name(), tickRate);
        }
        save();
    }

    public void addMemberToZone(final ZoneModel zone, final UUID member) {
        zone.addMember(member);
        if (CoreBridge.PRESENT) {
            CoreDelegate.addMember(zone.world(), zone.name(), member);
        }
        save();
    }

    public void removeMemberFromZone(final ZoneModel zone, final UUID member) {
        zone.removeMember(member);
        if (CoreBridge.PRESENT) {
            CoreDelegate.removeMember(zone.world(), zone.name(), member);
        }
        save();
    }

    // --- Focus Management ---

    public void setFocus(final UUID playerId, final String zoneName) {
        this.playerFocus.put(playerId, zoneName.toLowerCase(Locale.ROOT));
        if (CoreBridge.PRESENT) {
            CoreDelegate.setFocus(playerId, zoneName);
        }
    }

    public void clearFocus(final UUID playerId) {
        this.playerFocus.remove(playerId);
        if (CoreBridge.PRESENT) {
            CoreDelegate.clearFocus(playerId);
        }
    }

    public String getFocus(final UUID playerId) {
        return this.playerFocus.get(playerId);
    }

    public ZoneModel getFocusedZone(final UUID playerId) {
        final String name = this.playerFocus.get(playerId);
        return name != null ? getZone(name) : null;
    }

    // --- Selection Wand / Sessions ---

    public SelectionSession getSession(final UUID playerId) {
        return this.sessions.get(playerId);
    }

    public boolean startSelection(final Player player, final ZoneModel zone) {
        final UUID uuid = player.getUniqueId();
        if (this.sessions.containsKey(uuid)) {
            player.sendMessage(Component.text("[Zone] You already have an active selection session. Abort with /tick zone box cancelselect", NamedTextColor.RED));
            return false;
        }

        final int slot = player.getInventory().getHeldItemSlot();
        final ItemStack originalItem = player.getInventory().getItem(slot);

        final ItemStack wand = new ItemStack(Material.SNOWBALL);
        final ItemMeta meta = wand.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Zone Selector", NamedTextColor.AQUA));
            meta.lore(List.of(
                Component.text("Left-Click: Set Pos1", NamedTextColor.GRAY),
                Component.text("Right-Click: Set Pos2", NamedTextColor.GRAY),
                Component.text("/tick zone box cancelselect to abort", NamedTextColor.DARK_GRAY)
            ));
            meta.getPersistentDataContainer().set(this.wandKey, PersistentDataType.BYTE, (byte) 1);
            wand.setItemMeta(meta);
        }

        final SelectionSession session = new SelectionSession(uuid, zone.name(), slot, originalItem);
        this.sessions.put(uuid, session);
        player.getInventory().setItem(slot, wand);

        player.sendMessage(Component.text("[Zone " + zone.name() + "] Selection wand given. Left-click for pos1, right-click for pos2.", NamedTextColor.YELLOW));
        return true;
    }

    public boolean cancelSelection(final Player player, final boolean sendMessage) {
        final SelectionSession session = this.sessions.remove(player.getUniqueId());
        if (session != null) {
            stripWandAndRestore(player, session);
            if (sendMessage) {
                player.sendMessage(Component.text("[Zone] Selection cancelled.", NamedTextColor.YELLOW));
            }
            return true;
        }
        return false;
    }

    public void completeSelection(final Player player, final SelectionSession session) {
        final ZoneModel zone = getZone(session.zoneName());
        if (zone != null) {
            final ZoneBox box = session.createBox();
            addBoxToZone(zone, box);
            final int index = zone.boxes().size();
            player.sendMessage(Component.text("[Zone " + zone.name() + "] Box #" + index + " added ("
                + box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ() + ")", NamedTextColor.GREEN));
        }

        stripWandAndRestore(player, session);
        this.sessions.remove(player.getUniqueId());
    }

    private void stripWandAndRestore(final Player player, final SelectionSession session) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            final ItemStack it = player.getInventory().getItem(i);
            if (isWand(it)) {
                player.getInventory().setItem(i, null);
            }
        }
        if (isWand(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }

        final ItemStack original = session.originalItem();
        if (original != null && !original.getType().isAir()) {
            final ItemStack current = player.getInventory().getItem(session.slot());
            if (current == null || current.getType().isAir()) {
                player.getInventory().setItem(session.slot(), original);
            } else {
                final Map<Integer, ItemStack> leftover = player.getInventory().addItem(original);
                for (final ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
        }
    }

    public boolean isWand(final ItemStack item) {
        if (item == null || item.getType() != Material.SNOWBALL) {
            return false;
        }
        final ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(this.wandKey, PersistentDataType.BYTE);
    }

    // --- Highlighting ---

    public void setHighlightZone(final UUID playerId, final String zoneName) {
        this.highlights.put(playerId, new HighlightTarget(zoneName, true, Collections.emptySet()));
    }

    public void setHighlightBoxes(final UUID playerId, final String zoneName, final Set<Integer> indices) {
        this.highlights.put(playerId, new HighlightTarget(zoneName, false, indices));
    }

    public void clearHighlight(final UUID playerId) {
        this.highlights.remove(playerId);
    }

    public HighlightTarget getHighlight(final UUID playerId) {
        return this.highlights.get(playerId);
    }

    private void renderHighlights() {
        if (this.highlights.isEmpty()) {
            return;
        }

        for (final Map.Entry<UUID, HighlightTarget> entry : this.highlights.entrySet()) {
            final Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }

            final HighlightTarget target = entry.getValue();
            final ZoneModel zone = getZone(target.zoneName());
            if (zone == null || !zone.world().equalsIgnoreCase(player.getWorld().getName())) {
                continue;
            }

            final List<ZoneBox> boxes = zone.boxes();
            if (boxes.isEmpty()) {
                continue;
            }

            final List<Integer> renderIndices = new ArrayList<>();
            if (target.allBoxes()) {
                for (int i = 0; i < boxes.size(); i++) {
                    renderIndices.add(i);
                }
            } else {
                for (final int idx : target.boxIndices()) {
                    if (idx >= 0 && idx < boxes.size()) {
                        renderIndices.add(idx);
                    }
                }
            }

            for (final int idx : renderIndices) {
                final ZoneBox box = boxes.get(idx);
                final Color color = ZoneBox.getDistinctColor(idx);
                final Particle.DustOptions dust = new Particle.DustOptions(color, 1.2f);
                renderBoxEdges(player, box, dust);
            }
        }
    }

    private void renderBoxEdges(final Player player, final ZoneBox box, final Particle.DustOptions dust) {
        final double x1 = box.minX();
        final double x2 = box.maxX() + 1.0;
        final double y1 = box.minY();
        final double y2 = box.maxY() + 1.0;
        final double z1 = box.minZ();
        final double z2 = box.maxZ() + 1.0;

        final double maxDim = Math.max(x2 - x1, Math.max(y2 - y1, z2 - z1));
        final double step = maxDim > 64 ? 2.0 : 1.0;

        // 4 edges parallel to X
        drawEdge(player, dust, x1, y1, z1, x2, y1, z1, step);
        drawEdge(player, dust, x1, y2, z1, x2, y2, z1, step);
        drawEdge(player, dust, x1, y1, z2, x2, y1, z2, step);
        drawEdge(player, dust, x1, y2, z2, x2, y2, z2, step);

        // 4 edges parallel to Y
        drawEdge(player, dust, x1, y1, z1, x1, y2, z1, step);
        drawEdge(player, dust, x2, y1, z1, x2, y2, z1, step);
        drawEdge(player, dust, x1, y1, z2, x1, y2, z2, step);
        drawEdge(player, dust, x2, y1, z2, x2, y2, z2, step);

        // 4 edges parallel to Z
        drawEdge(player, dust, x1, y1, z1, x1, y1, z2, step);
        drawEdge(player, dust, x2, y1, z1, x2, y1, z2, step);
        drawEdge(player, dust, x1, y2, z1, x1, y2, z2, step);
        drawEdge(player, dust, x2, y2, z1, x2, y2, z2, step);
    }

    private void drawEdge(final Player player, final Particle.DustOptions dust,
                          final double x1, final double y1, final double z1,
                          final double x2, final double y2, final double z2,
                          final double step) {
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double dz = z2 - z1;
        final double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= 0) {
            player.spawnParticle(Particle.DUST, x1, y1, z1, 1, 0, 0, 0, 0, dust);
            return;
        }

        final double effectiveStep = Math.max(step, dist / 25.0);
        final int count = (int) Math.ceil(dist / effectiveStep);
        for (int i = 0; i <= count; i++) {
            final double f = (double) i / count;
            player.spawnParticle(Particle.DUST, x1 + dx * f, y1 + dy * f, z1 + dz * f, 1, 0, 0, 0, 0, dust);
        }
    }

    // --- Persistence ---

    private record SerializedZone(String name, String world, String owner, List<String> members, List<ZoneBox> boxes, boolean frozen, float tickRate) {}

    private void save() {
        final List<SerializedZone> list = new ArrayList<>();
        for (final ZoneModel zone : this.zones.values()) {
            final List<String> memberList = new ArrayList<>();
            for (final UUID m : zone.members()) {
                memberList.add(m.toString());
            }
            list.add(new SerializedZone(
                zone.name(),
                zone.world(),
                zone.owner() != null ? zone.owner().toString() : null,
                memberList,
                new ArrayList<>(zone.boxes()),
                zone.isFrozen(),
                zone.tickRate()
            ));
        }

        final Runnable saveTask = () -> {
            synchronized (this.storagePath) {
                try {
                    Files.createDirectories(this.storagePath.getParent());
                    try (final Writer writer = Files.newBufferedWriter(this.storagePath)) {
                        this.gson.toJson(list, writer);
                    }
                } catch (final Exception e) {
                    this.plugin.getLogger().log(Level.WARNING, "Failed to save zones to " + this.storagePath, e);
                }
            }
        };

        if (!this.plugin.isEnabled()) {
            saveTask.run();
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, saveTask);
        }
    }

    private synchronized void load() {
        if (!Files.exists(this.storagePath)) {
            return;
        }
        try (final Reader reader = Files.newBufferedReader(this.storagePath)) {
            final Type type = new TypeToken<List<SerializedZone>>() {}.getType();
            final List<SerializedZone> list = this.gson.fromJson(reader, type);
            if (list != null) {
                for (final SerializedZone s : list) {
                    final UUID owner = s.owner() != null ? UUID.fromString(s.owner()) : null;
                    final ZoneModel zone = new ZoneModel(s.name(), s.world(), owner);
                    if (s.members() != null) {
                        for (final String m : s.members()) {
                            zone.addMember(UUID.fromString(m));
                        }
                    }
                    if (s.boxes() != null) {
                        for (final ZoneBox b : s.boxes()) {
                            zone.addBox(b);
                        }
                    }
                    zone.setFrozen(s.frozen());
                    if (s.tickRate() > 0) {
                        zone.setTickRate(s.tickRate());
                    }
                    this.zones.put(zone.name().toLowerCase(Locale.ROOT), zone);
                }
            }
        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.WARNING, "Failed to load zones from " + this.storagePath, e);
        }
    }

    /**
     * Isolated delegate class to access core LabTickZones safely without class loading errors on plain Paper.
     */
    private static final class CoreDelegate {

        static void enableCore(final Collection<ZoneModel> savedZones) {
            io.papermc.paper.lab.zone.LabTickZones.setEnabled(true);
            for (final ZoneModel model : savedZones) {
                final io.papermc.paper.lab.zone.LabTickZone coreZone =
                    io.papermc.paper.lab.zone.LabTickZones.createZone(model.world(), model.name(), model.owner());
                for (final UUID m : model.members()) {
                    coreZone.addMember(m);
                }
                for (final ZoneBox b : model.boxes()) {
                    coreZone.addBox(new io.papermc.paper.lab.zone.ZoneCuboid(
                        b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()
                    ));
                }
                coreZone.setFrozen(model.isFrozen());
                coreZone.setTickRate(model.tickRate());
            }
        }

        static void disableCore() {
            io.papermc.paper.lab.zone.LabTickZones.setEnabled(false);
        }

        static void createZone(final String world, final String name, final UUID owner) {
            io.papermc.paper.lab.zone.LabTickZones.createZone(world, name, owner);
        }

        static void removeZone(final String world, final String name) {
            io.papermc.paper.lab.zone.LabTickZones.removeZone(world, name);
        }

        static void addBox(final String world, final String name, final ZoneBox box) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.addBox(new io.papermc.paper.lab.zone.ZoneCuboid(
                    box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()
                ));
            }
        }

        static void removeBox(final String world, final String name, final int index) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.removeBox(index);
            }
        }

        static void clearBoxes(final String world, final String name) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.clearBoxes();
            }
        }

        static void setFocus(final UUID playerId, final String zoneName) {
            io.papermc.paper.lab.zone.LabTickZones.setFocus(playerId, zoneName);
        }

        static void clearFocus(final UUID playerId) {
            io.papermc.paper.lab.zone.LabTickZones.clearFocus(playerId);
        }

        static void setFrozen(final String world, final String name, final boolean frozen) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.setFrozen(frozen);
            }
        }

        static void setTickRate(final String world, final String name, final float tickRate) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.setTickRate(tickRate);
            }
        }

        static void addMember(final String world, final String name, final UUID member) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.addMember(member);
            }
        }

        static void removeMember(final String world, final String name, final UUID member) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.removeMember(member);
            }
        }
    }
}
