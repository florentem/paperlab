package paperlab.log;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import paperlab.log.item.ItemLogger;

import static org.junit.jupiter.api.Assertions.*;

public class ItemLoggerTest {

    @Test
    public void testShouldLogDefaultOption() {
        assertTrue(ItemLogger.shouldLog(null, ItemLogger.TYPE_DESPAWN));
        assertTrue(ItemLogger.shouldLog("", ItemLogger.TYPE_DESPAWN));
        assertTrue(ItemLogger.shouldLog("   ", ItemLogger.TYPE_DESPAWN));

        assertFalse(ItemLogger.shouldLog(null, ItemLogger.TYPE_CREATE));
        assertFalse(ItemLogger.shouldLog(null, ItemLogger.TYPE_DIE));
    }

    @Test
    public void testShouldLogSpecificOptions() {
        assertTrue(ItemLogger.shouldLog("create", ItemLogger.TYPE_CREATE));
        assertTrue(ItemLogger.shouldLog("CREATE", ItemLogger.TYPE_CREATE));
        assertFalse(ItemLogger.shouldLog("create", ItemLogger.TYPE_DESPAWN));
        assertFalse(ItemLogger.shouldLog("create", ItemLogger.TYPE_DIE));

        assertTrue(ItemLogger.shouldLog("die", ItemLogger.TYPE_DIE));
        assertTrue(ItemLogger.shouldLog("DIE", ItemLogger.TYPE_DIE));

        assertTrue(ItemLogger.shouldLog("despawn", ItemLogger.TYPE_DESPAWN));
    }

    @Test
    public void testShouldLogMultipleCommaOrSpaceDelimited() {
        assertTrue(ItemLogger.shouldLog("create,die", ItemLogger.TYPE_CREATE));
        assertTrue(ItemLogger.shouldLog("create,die", ItemLogger.TYPE_DIE));
        assertFalse(ItemLogger.shouldLog("create,die", ItemLogger.TYPE_DESPAWN));

        assertTrue(ItemLogger.shouldLog("create despawn die", ItemLogger.TYPE_CREATE));
        assertTrue(ItemLogger.shouldLog("create despawn die", ItemLogger.TYPE_DESPAWN));
        assertTrue(ItemLogger.shouldLog("create despawn die", ItemLogger.TYPE_DIE));
    }

    @Test
    public void testCoordComponent() {
        final Location loc = new Location(null, 10.5, 64.0, -25.5);
        final Component comp = ItemLogger.coordComponent(loc);
        assertNotNull(comp);

        final boolean hasTpAction = comp.children().stream().anyMatch(c ->
            c.clickEvent() != null && c.clickEvent().action() == ClickEvent.Action.SUGGEST_COMMAND
                && c.clickEvent().toString().contains("/tp 10.5 64.0 -25.5")
        );
        assertTrue(hasTpAction, "Coordinate component should have suggest_command click event with /tp");
    }
}
