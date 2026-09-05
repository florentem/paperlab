package paperlab.cplay.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import paperlab.cplay.model.CPlayAssetHandle;
import paperlab.cplay.model.CPlayAssetInfo;
import paperlab.cplay.model.CPlayAssetNamespace;

import static org.junit.jupiter.api.Assertions.*;

public class CPlayWireTest {

    @Test
    public void testVarIntRoundTrip() {
        final int[] testValues = {0, 1, 127, 128, 255, 16384, 65535, 1000000, Integer.MAX_VALUE};
        for (final int value : testValues) {
            final ByteBuf buf = Unpooled.buffer();
            CPlayWire.writeVarInt(buf, value);
            final int read = CPlayWire.readVarInt(buf);
            assertEquals(value, read, "VarInt mismatch for value: " + value);
            buf.release();
        }
    }

    @Test
    public void testStringRoundTrip() {
        final String[] testStrings = {"", "test", "Hello World!", "Привет, мир!", "§aColored §cText"};
        for (final String s : testStrings) {
            final ByteBuf buf = Unpooled.buffer();
            CPlayWire.writeString(buf, s);
            final String read = CPlayWire.readString(buf);
            assertEquals(s, read, "String mismatch for: " + s);
            buf.release();
        }
    }

    @Test
    public void testReadStringBoundsValidation() {
        // Test negative length
        final ByteBuf negBuf = Unpooled.buffer();
        CPlayWire.writeVarInt(negBuf, -1);
        assertThrows(IllegalArgumentException.class, () -> CPlayWire.readString(negBuf));
        negBuf.release();

        // Test length exceeding maximum limit (32767)
        final ByteBuf hugeBuf = Unpooled.buffer();
        CPlayWire.writeVarInt(hugeBuf, 40000);
        assertThrows(IllegalArgumentException.class, () -> CPlayWire.readString(hugeBuf));
        hugeBuf.release();

        // Test buffer underflow (claims length 50, but buffer is empty)
        final ByteBuf underBuf = Unpooled.buffer();
        CPlayWire.writeVarInt(underBuf, 50);
        assertThrows(IllegalArgumentException.class, () -> CPlayWire.readString(underBuf));
        underBuf.release();
    }

    @Test
    public void testUUIDRoundTrip() {
        final UUID uuid = UUID.randomUUID();
        final ByteBuf buf = Unpooled.buffer();
        CPlayWire.writeUUID(buf, uuid);
        final UUID read = CPlayWire.readUUID(buf);
        assertEquals(uuid, read);
        buf.release();
    }

    @Test
    public void testBlockPosRoundTrip() {
        final BlockPos[] poses = {
            new BlockPos(0, 0, 0),
            new BlockPos(100, 64, -200),
            new BlockPos(-3000000, -64, 3000000),
            new BlockPos(1234567, 319, -7654321)
        };
        for (final BlockPos pos : poses) {
            final ByteBuf buf = Unpooled.buffer();
            CPlayWire.writeBlockPos(buf, pos);
            final BlockPos read = CPlayWire.readBlockPos(buf);
            assertEquals(pos, read, "BlockPos mismatch for: " + pos);
            buf.release();
        }
    }

    @Test
    public void testAssetInfoRoundTrip() {
        final UUID assetUUID = UUID.randomUUID();
        final UUID creatorUUID = UUID.randomUUID();
        final UUID ownerUUID = UUID.randomUUID();
        final UUID collab1 = UUID.randomUUID();
        final UUID collab2 = UUID.randomUUID();
        final CPlayAssetHandle handle = new CPlayAssetHandle(CPlayAssetNamespace.SEQUENCE, "seq123");

        final CPlayAssetInfo original = new CPlayAssetInfo(1, assetUUID, handle, "TestSequence", 1000L, 2000L, creatorUUID, ownerUUID);
        original.addCollaborator(collab1);
        original.addCollaborator(collab2);

        final ByteBuf buf = Unpooled.buffer();
        CPlayWire.writeAssetInfo(buf, original);

        final CPlayAssetInfo read = CPlayWire.readAssetInfo(buf);
        assertEquals(original.getTypeIndex(), read.getTypeIndex());
        assertEquals(original.getAssetUUID(), read.getAssetUUID());
        assertEquals(original.getHandle().toString(), read.getHandle().toString());
        assertEquals(original.getAssetName(), read.getAssetName());
        assertEquals(original.getCreatedTimestamp(), read.getCreatedTimestamp());
        assertEquals(original.getLastModifiedTimestamp(), read.getLastModifiedTimestamp());
        assertEquals(original.getCreatedByUUID(), read.getCreatedByUUID());
        assertEquals(original.getOwnerUUID(), read.getOwnerUUID());
        assertEquals(2, read.getCollaboratorUUIDs().size());
        assertTrue(read.getCollaboratorUUIDs().contains(collab1));
        assertTrue(read.getCollaboratorUUIDs().contains(collab2));
        buf.release();
    }

    @Test
    public void testConnectionPacketFormat() {
        final byte[] bytes = CPlayWire.encodeConnectionPacket();
        assertNotNull(bytes);
        assertTrue(bytes.length > 8);

        final ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        final long packetId = buf.readLong();
        assertEquals(CPlayProtocol.makePacketId(CPlayProtocol.CORE_UID, CPlayProtocol.PACKET_CORE_CONNECTION), packetId);
        final int extCount = buf.readInt();
        assertEquals(2, extCount);
        buf.release();
    }

    @Test
    public void testSessionPackets() {
        final UUID assetUUID = UUID.randomUUID();
        final UUID clientUUID = UUID.randomUUID();

        final byte[] start = CPlayWire.encodeSessionStart(assetUUID, 0, "CompAsset", true, clientUUID);
        assertNotNull(start);
        assertTrue(start.length > 8);

        final byte[] stop = CPlayWire.encodeSessionStop(assetUUID);
        assertNotNull(stop);
        final ByteBuf stopBuf = Unpooled.wrappedBuffer(stop);
        assertEquals(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_SESSION_STOP), stopBuf.readLong());
        assertEquals(assetUUID, CPlayWire.readUUID(stopBuf));
        stopBuf.release();

        final byte[] dummyDeltas = new byte[]{1, 2, 3, 4};
        final byte[] deltasPacket = CPlayWire.encodeSessionDeltas(assetUUID, dummyDeltas);
        assertNotNull(deltasPacket);
        final ByteBuf deltasBuf = Unpooled.wrappedBuffer(deltasPacket);
        assertEquals(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_SESSION_DELTAS), deltasBuf.readLong());
        assertEquals(assetUUID, CPlayWire.readUUID(deltasBuf));
        final byte[] readDeltas = new byte[deltasBuf.readableBytes()];
        deltasBuf.readBytes(readDeltas);
        assertArrayEquals(dummyDeltas, readDeltas);
        deltasBuf.release();
    }

    @Test
    public void testPlayerCachePackets() {
        final UUID u1 = UUID.randomUUID();
        final UUID u2 = UUID.randomUUID();
        final Map<UUID, String> map = new HashMap<>();
        map.put(u1, "Alice");
        map.put(u2, "Bob");

        final byte[] cacheBytes = CPlayWire.encodePlayerCache(map);
        assertNotNull(cacheBytes);

        final byte[] addBytes = CPlayWire.encodePlayerCacheAdded(u1, "Alice");
        assertNotNull(addBytes);

        final byte[] removeBytes = CPlayWire.encodePlayerCacheRemoved(u1);
        assertNotNull(removeBytes);
    }
}
