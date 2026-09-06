package paperlab.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LabPermissionsTest {

    @Test
    void testWildcardGroupsExist() {
        assertEquals("paperlab.log.*", LabPermissions.LOG_ALL);
        assertEquals("paperlab.servux.*", LabPermissions.SERVUX_ALL);
        assertEquals("paperlab.cplay.*", LabPermissions.CPLAY_ALL);
        assertEquals("paperlab.rule.*", LabPermissions.RULE_ALL);
        assertEquals("paperlab.counter.*", LabPermissions.COUNTER_ALL);
        assertEquals("paperlab.ghost.*", LabPermissions.GHOST_ALL);
        assertEquals("paperlab.*", LabPermissions.ROOT);

        assertTrue(LabPermissions.groups().containsKey(LabPermissions.LOG_ALL));
        assertTrue(LabPermissions.groups().containsKey(LabPermissions.SERVUX_ALL));
        assertTrue(LabPermissions.groups().containsKey(LabPermissions.CPLAY_ALL));
        assertTrue(LabPermissions.groups().containsKey(LabPermissions.RULE_ALL));
        assertTrue(LabPermissions.groups().containsKey(LabPermissions.COUNTER_ALL));
        assertTrue(LabPermissions.groups().containsKey(LabPermissions.GHOST_ALL));
    }

    @Test
    void testNodesContainAllCategories() {
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.LOG));
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.LOG_TPS));
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.LOG_MOBCAPS));
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.LOG_COUNTER));
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.LOG_SPAWN));
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.LOG_ITEM));
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.LOG_MICROTIMING));
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.LOG_MOVEMENT));

        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.SERVUX_HUD));
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.SERVUX_LITEMATICS));
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.SERVUX_TWEAKS));

        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.CPLAY));
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.CPLAY_PLAYBACK));
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.CPLAY_MANAGE));

        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.RULE_DEFAULT));
        assertTrue(LabPermissions.nodes().containsKey("paperlab.rule.fillupdates"));
        assertTrue(LabPermissions.nodes().containsKey("paperlab.rule.perworldtick"));
    }
}
