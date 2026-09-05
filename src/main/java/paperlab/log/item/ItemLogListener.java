package paperlab.log.item;

import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;

/**
 * Слушатель событий Bukkit для /log item.
 */
public final class ItemLogListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(final ItemSpawnEvent event) {
        if (ItemLogger.hasSubscribers()) {
            ItemLogger.onItemCreated(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(final ItemDespawnEvent event) {
        if (ItemLogger.hasSubscribers()) {
            ItemLogger.onItemDespawned(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(final EntityDamageEvent event) {
        if (!ItemLogger.hasSubscribers()) return;
        if (event.getEntity() instanceof final Item item) {
            final double remaining = item.getHealth() - event.getFinalDamage();
            if (remaining <= 0) {
                final String causeName = event.getCause().name().toLowerCase();
                ItemLogger.onItemDied(item, causeName, (float) event.getFinalDamage());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(final EntityRemoveEvent event) {
        if (!ItemLogger.hasSubscribers()) return;
        if (event.getEntity() instanceof final Item item) {
            if (event.getCause() == EntityRemoveEvent.Cause.DEATH) {
                ItemLogger.onItemDied(item, "death", 0.0f);
            }
        }
    }
}
