package paperlab.zone;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ZoneModelTest {

    @Test
    void testZoneModelBoxes() {
        final UUID owner = UUID.randomUUID();
        final ZoneModel zone = new ZoneModel("test_zone", "world", owner);

        assertEquals("test_zone", zone.name());
        assertEquals("world", zone.world());
        assertEquals(owner, zone.owner());
        assertTrue(zone.boxes().isEmpty());

        final ZoneBox b1 = ZoneBox.of(0, 0, 0, 10, 10, 10);
        final ZoneBox b2 = ZoneBox.of(20, 20, 20, 30, 30, 30);
        zone.addBox(b1);
        zone.addBox(b2);

        assertEquals(2, zone.boxes().size());
        assertEquals(b1, zone.boxes().get(0));
        assertEquals(b2, zone.boxes().get(1));

        assertTrue(zone.removeBox(0));
        assertEquals(1, zone.boxes().size());
        assertEquals(b2, zone.boxes().get(0));

        assertFalse(zone.removeBox(5));

        zone.clearBoxes();
        assertTrue(zone.boxes().isEmpty());
    }

    @Test
    void testZoneMembership() {
        final UUID owner = UUID.randomUUID();
        final UUID member1 = UUID.randomUUID();
        final UUID stranger = UUID.randomUUID();

        final ZoneModel zone = new ZoneModel("test_zone", "world", owner);

        assertTrue(zone.isMember(owner));
        assertFalse(zone.isMember(member1));
        assertFalse(zone.isMember(stranger));

        zone.addMember(member1);
        assertTrue(zone.isMember(member1));

        zone.removeMember(member1);
        assertFalse(zone.isMember(member1));
    }

    @Test
    void testFreezeAndRate() {
        final ZoneModel zone = new ZoneModel("test", "world", UUID.randomUUID());
        assertFalse(zone.isFrozen());
        assertEquals(20.0f, zone.tickRate());

        zone.setFrozen(true);
        assertTrue(zone.isFrozen());

        zone.setTickRate(50.0f);
        assertEquals(50.0f, zone.tickRate());
    }
}
