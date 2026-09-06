package paperlab.log.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import paperlab.log.LabLoggers;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Item lifecycle logger (/log item).
 *
 * <p>Matches Carpet-TIS-Addition's behaviour:
 * <ul>
 *   <li>{@code create}: an item entity appears in the world;</li>
 *   <li>{@code despawn}: natural despawn after 5 minutes;</li>
 *   <li>{@code die}: destruction by damage (fire, lava, cactus, explosion, the void).</li>
 * </ul>
 */
public final class ItemLogger {

    public static final String TYPE_CREATE = "create";
    public static final String TYPE_DESPAWN = "despawn";
    public static final String TYPE_DIE = "die";

    private ItemLogger() {
    }

    public static boolean hasSubscribers() {
        return LabLoggers.ITEM.hasSubscribers();
    }

    public static boolean shouldLog(final String option, final String eventType) {
        if (option == null || option.isBlank()) {
            return eventType.equalsIgnoreCase(TYPE_DESPAWN);
        }
        final String[] parts = option.split("[,. ]+");
        for (final String part : parts) {
            if (part.equalsIgnoreCase(eventType)) {
                return true;
            }
        }
        return false;
    }

    public static void onItemCreated(final Item item) {
        if (!hasSubscribers()) return;
        final Location loc = item.getLocation();
        final ItemStack stack = item.getItemStack();
        final long time = loc.getWorld().getGameTime();
        final String name = formatItemName(stack);
        final int count = stack.getAmount();

        final Component msg = Component.text("[" + time + "] ", NamedTextColor.GRAY)
            .append(Component.text(name, NamedTextColor.WHITE)
                .hoverEvent(HoverEvent.showText(Component.text("Item stack size: " + count, NamedTextColor.WHITE))))
            .append(Component.text("(" + count + ")", NamedTextColor.GRAY))
            .append(Component.text(" created", NamedTextColor.WHITE))
            .append(coordComponent(loc));

        dispatch(TYPE_CREATE, msg);
    }

    public static void onItemDespawned(final Item item) {
        if (!hasSubscribers()) return;
        final Location loc = item.getLocation();
        final ItemStack stack = item.getItemStack();
        final long time = loc.getWorld().getGameTime();
        final String name = formatItemName(stack);
        final int count = stack.getAmount();

        final Component msg = Component.text("[" + time + "] ", NamedTextColor.GRAY)
            .append(Component.text(name, NamedTextColor.WHITE)
                .hoverEvent(HoverEvent.showText(Component.text("Item stack size: " + count, NamedTextColor.WHITE))))
            .append(Component.text("(" + count + ")", NamedTextColor.GRAY))
            .append(Component.text(" despawned", NamedTextColor.WHITE))
            .append(coordComponent(loc));

        dispatch(TYPE_DESPAWN, msg);
    }

    public static void onItemDied(final Item item, final @Nullable String deathReason, final float damageAmount) {
        if (!hasSubscribers()) return;
        final Location loc = item.getLocation();
        final ItemStack stack = item.getItemStack();
        final long time = loc.getWorld().getGameTime();
        final String name = formatItemName(stack);
        final String reason = (deathReason != null && !deathReason.isBlank()) ? deathReason : "damage";

        final Component deathText = Component.text(name + " died (" + reason + ")", NamedTextColor.WHITE)
            .hoverEvent(HoverEvent.showText(Component.text(String.format(Locale.ROOT, "Damage amount: %.1f", damageAmount), NamedTextColor.WHITE)));

        final Component msg = Component.text("[" + time + "] ", NamedTextColor.GRAY)
            .append(deathText)
            .append(coordComponent(loc));

        dispatch(TYPE_DIE, msg);
    }

    public static Component coordComponent(final Location loc) {
        final String dim = loc.getWorld() != null ? loc.getWorld().getName() : "world";
        final String coords = String.format(Locale.ROOT, "%.1f %.1f %.1f", loc.getX(), loc.getY(), loc.getZ());
        return Component.text("  @ ", NamedTextColor.DARK_GRAY)
            .append(Component.text(coords, NamedTextColor.WHITE)
                .hoverEvent(HoverEvent.showText(Component.text(dim, NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.suggestCommand(String.format(Locale.ROOT, "/tp %.1f %.1f %.1f", loc.getX(), loc.getY(), loc.getZ()))));
    }

    private static String formatItemName(final ItemStack stack) {
        if (stack == null) return "item";
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            return stack.getItemMeta().getDisplayName();
        }
        return stack.getType().name().toLowerCase(Locale.ROOT);
    }

    private static void dispatch(final String eventType, final Component message) {
        for (final var entry : LabLoggers.ITEM.subscribers().entrySet()) {
            final Player player = Bukkit.getPlayerExact(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            for (final String option : entry.getValue()) {
                if (shouldLog(option, eventType)) {
                    player.sendMessage(message);
                    break;
                }
            }
        }
    }
}
