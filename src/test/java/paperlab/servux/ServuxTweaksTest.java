package paperlab.servux;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import paperlab.command.LabPermissions;

import static org.junit.jupiter.api.Assertions.*;

public class ServuxTweaksTest {

    @Test
    public void testTweaksChannelAndVersion() {
        assertEquals("servux:tweaks", ServuxTweaks.CHANNEL);
        assertEquals(2, ServuxTweaks.PROTOCOL_VERSION);
    }

    @Test
    public void testPermissionsRegistered() {
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.SERVUX_TWEAKS),
            "LabPermissions should contain SERVUX_TWEAKS");
        assertTrue(LabPermissions.nodes().containsKey(LabPermissions.SERVUX_ENTITIES_PLAYERS),
            "LabPermissions should contain SERVUX_ENTITIES_PLAYERS");
        assertEquals("paperlab.servux.tweaks", LabPermissions.SERVUX_TWEAKS);
        assertEquals("paperlab.servux.entities.players", LabPermissions.SERVUX_ENTITIES_PLAYERS);
    }

    @Test
    public void testWireMetadataEncodingAndDecoding() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "tweaks_data");
        tag.putString("id", ServuxTweaks.CHANNEL);
        tag.putInt("version", ServuxTweaks.PROTOCOL_VERSION);

        final byte[] packet = ServuxWire.metadata(1, tag);
        assertEquals(1, ServuxWire.readType(packet));

        final CompoundTag decoded = ServuxWire.readNetworkNbt(packet);
        assertEquals("tweaks_data", decoded.getStringOr("name", ""));
        assertEquals(ServuxTweaks.CHANNEL, decoded.getStringOr("id", ""));
        assertEquals(2, decoded.getIntOr("version", 0));
    }
}
