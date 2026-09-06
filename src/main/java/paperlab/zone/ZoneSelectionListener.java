package paperlab.zone;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Handles interactions for snowball-based zone box selection and ensures
 * players cannot lose items or drop the wand.
 */
public final class ZoneSelectionListener implements Listener {

    private final ZoneService zoneService;

    public ZoneSelectionListener(final ZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(final PlayerInteractEvent event) {
        final Player player = event.getPlayer();
        final SelectionSession session = this.zoneService.getSession(player.getUniqueId());
        if (session == null) {
            return;
        }

        // Only handle main hand interaction
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        // Check if player is holding the wand in the registered slot
        if (player.getInventory().getHeldItemSlot() != session.slot()) {
            return;
        }

        final Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            final Block block = event.getClickedBlock();
            if (block != null) {
                session.setPos1(block.getX(), block.getY(), block.getZ());
                player.sendMessage(Component.text("[Zone " + session.zoneName() + "] Pos1 set to "
                    + block.getX() + " " + block.getY() + " " + block.getZ(), NamedTextColor.AQUA));
                if (session.isComplete()) {
                    this.zoneService.completeSelection(player, session);
                }
            }
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            final Block block = event.getClickedBlock();
            if (block != null) {
                session.setPos2(block.getX(), block.getY(), block.getZ());
                player.sendMessage(Component.text("[Zone " + session.zoneName() + "] Pos2 set to "
                    + block.getX() + " " + block.getY() + " " + block.getZ(), NamedTextColor.AQUA));
                if (session.isComplete()) {
                    this.zoneService.completeSelection(player, session);
                }
            }
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.LEFT_CLICK_AIR) {
            // Prevent throwing the snowball
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        this.zoneService.cancelSelection(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onKick(final PlayerKickEvent event) {
        this.zoneService.cancelSelection(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        final Player player = event.getPlayer();
        final SelectionSession session = this.zoneService.getSession(player.getUniqueId());
        if (session != null && this.zoneService.isWand(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(final InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof final Player player) {
            final SelectionSession session = this.zoneService.getSession(player.getUniqueId());
            if (session != null) {
                if (event.getSlot() == session.slot() || this.zoneService.isWand(event.getCurrentItem())
                    || this.zoneService.isWand(event.getCursor())) {
                    event.setCancelled(true);
                }
            }
        }
    }
}
