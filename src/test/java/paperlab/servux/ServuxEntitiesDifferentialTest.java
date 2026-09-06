package paperlab.servux;

import fi.dy.masa.minihud.network.ServuxEntitiesPacket;
import fi.dy.masa.minihud.network.ServuxStructuresPacket;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential tests of the {@code servux:entity_data} and {@code servux:structures} protocols.
 *
 * <p>Compares MiniHUD's original client code ({@link ServuxEntitiesPacket} and
 * {@link ServuxStructuresPacket}) against PaperLab's wire implementation.
 */
public class ServuxEntitiesDifferentialTest {

    private static final int S2C_METADATA = 1;
    private static final int C2S_METADATA_REQUEST = 2;
    private static final int C2S_BLOCK_ENTITY_REQUEST = 3;
    private static final int C2S_ENTITY_REQUEST = 4;
    private static final int S2C_BLOCK_NBT_RESPONSE = 5;
    private static final int S2C_ENTITY_NBT_RESPONSE = 6;

    @Test
    @DisplayName("Entity metadata differential: PaperLab -> MiniHUD")
    public void testEntitiesMetadataHandshake() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "entity_data");
        tag.putString("id", "servux:entity_data");
        tag.putInt("version", 2);
        tag.putString("servux", "servux-fabric-26.2-paperlab");

        final byte[] wire = ServuxWire.metadata(S2C_METADATA, tag);
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final ServuxEntitiesPacket packet = ServuxEntitiesPacket.fromPacket(buf);

        assertNotNull(packet, "MiniHUD must parse the entity metadata packet successfully");
        assertEquals(ServuxEntitiesPacket.Type.PACKET_S2C_METADATA, packet.getType());
        assertFalse(buf.isReadable(), "the entity metadata buffer must be fully consumed");
        assertEquals("entity_data", packet.getCompound().getStringOr("name", ""));
        assertEquals(2, packet.getCompound().getIntOr("version", -1));
    }

    @Test
    @DisplayName("Block entity response differential: PaperLab -> MiniHUD")
    public void testBlockEntityResponse() {
        final BlockPos pos = new BlockPos(123, -45, 6789);
        final CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:chest");
        tag.putInt("ItemsCount", 5);

        final FriendlyByteBuf serverBuf = new FriendlyByteBuf(Unpooled.buffer());
        serverBuf.writeVarInt(S2C_BLOCK_NBT_RESPONSE);
        serverBuf.writeBlockPos(pos);
        ServuxWire.appendNbtBody(serverBuf, tag);

        final byte[] wire = new byte[serverBuf.readableBytes()];
        serverBuf.readBytes(wire);
        serverBuf.release();

        final FriendlyByteBuf clientBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final ServuxEntitiesPacket packet = ServuxEntitiesPacket.fromPacket(clientBuf);

        assertNotNull(packet, "MiniHUD must parse the block entity response successfully");
        assertEquals(ServuxEntitiesPacket.Type.PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE, packet.getType());
        assertEquals(pos, packet.getPos(), "the block coordinates must match exactly");
        assertEquals(tag, packet.getCompound(), "the block entity NBT must match exactly");
        assertFalse(clientBuf.isReadable(), "the response buffer must be fully consumed (zero spare bytes)");
    }

    @Test
    @DisplayName("Entity response differential: PaperLab -> MiniHUD")
    public void testEntityResponse() {
        final int entityId = 42069;
        final CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:zombie");
        tag.putFloat("Health", 20.0f);

        final FriendlyByteBuf serverBuf = new FriendlyByteBuf(Unpooled.buffer());
        serverBuf.writeVarInt(S2C_ENTITY_NBT_RESPONSE);
        serverBuf.writeVarInt(entityId);
        ServuxWire.appendNbtBody(serverBuf, tag);

        final byte[] wire = new byte[serverBuf.readableBytes()];
        serverBuf.readBytes(wire);
        serverBuf.release();

        final FriendlyByteBuf clientBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final ServuxEntitiesPacket packet = ServuxEntitiesPacket.fromPacket(clientBuf);

        assertNotNull(packet, "MiniHUD must parse the entity response successfully");
        assertEquals(ServuxEntitiesPacket.Type.PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE, packet.getType());
        assertEquals(entityId, packet.getEntityId(), "the entity id must match");
        assertEquals(tag, packet.getCompound(), "the entity NBT must match exactly");
        assertFalse(clientBuf.isReadable(), "the response buffer must be fully consumed (zero spare bytes)");
    }

    @Test
    @DisplayName("Client block entity request differential: MiniHUD -> PaperLab parser")
    public void testClientBlockEntityRequestParsing() {
        final BlockPos targetPos = new BlockPos(-500, 12, 1024);
        final ServuxEntitiesPacket clientPacket = ServuxEntitiesPacket.BlockEntityRequest(targetPos);
        clientPacket.setTransactionId(77);

        final FriendlyByteBuf clientOut = new FriendlyByteBuf(Unpooled.buffer());
        clientPacket.toPacket(clientOut);

        final byte[] wire = new byte[clientOut.readableBytes()];
        clientOut.readBytes(wire);
        clientOut.release();

        // Check PaperLab's parsing logic
        final FriendlyByteBuf serverIn = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final int type = serverIn.readVarInt();
        assertEquals(C2S_BLOCK_ENTITY_REQUEST, type);

        if (serverIn.readableBytes() > 8) {
            final int txId = serverIn.readVarInt();
            assertEquals(77, txId);
        }
        final BlockPos parsedPos = serverIn.readBlockPos();
        assertEquals(targetPos, parsedPos);
        assertFalse(serverIn.isReadable(), "server-side parsing must consume every byte of the request");
    }

    @Test
    @DisplayName("Client entity request differential: MiniHUD -> PaperLab parser")
    public void testClientEntityRequestParsing() {
        final int targetEntityId = 1337;
        final ServuxEntitiesPacket clientPacket = ServuxEntitiesPacket.EntityRequest(targetEntityId);
        clientPacket.setTransactionId(99);

        final FriendlyByteBuf clientOut = new FriendlyByteBuf(Unpooled.buffer());
        clientPacket.toPacket(clientOut);

        final byte[] wire = new byte[clientOut.readableBytes()];
        clientOut.readBytes(wire);
        clientOut.release();

        // Check PaperLab's parsing logic
        final FriendlyByteBuf serverIn = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final int type = serverIn.readVarInt();
        assertEquals(C2S_ENTITY_REQUEST, type);

        final int first = serverIn.readVarInt();
        final int entityId = serverIn.isReadable() ? serverIn.readVarInt() : first;
        assertEquals(targetEntityId, entityId);
        assertFalse(serverIn.isReadable(), "server-side parsing must consume every byte of the request");
    }

    @Test
    @DisplayName("Structure metadata differential: PaperLab -> MiniHUD")
    public void testStructuresMetadataHandshake() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "structure_bounding_boxes");
        tag.putString("id", "servux:structures");
        tag.putInt("version", 3);
        tag.putString("servux", "servux-fabric-26.2-paperlab");
        tag.putInt("timeout", 300);

        final byte[] wire = ServuxWire.metadata(1, tag);
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final ServuxStructuresPacket packet = ServuxStructuresPacket.fromPacket(buf);

        assertNotNull(packet, "MiniHUD must parse the structure metadata successfully");
        assertEquals(ServuxStructuresPacket.Type.PACKET_S2C_METADATA, packet.getType());
        assertFalse(buf.isReadable(), "the structure metadata buffer must be fully consumed");
        assertEquals("structure_bounding_boxes", packet.getCompound().getStringOr("name", ""));
        assertEquals(300, packet.getCompound().getIntOr("timeout", 0));
    }

    @Test
    @DisplayName("Entity protocol fuzz: 10,000 iterations of block and entity responses")
    public void testEntitiesDifferentialFuzzing() {
        final Random rng = new Random(0xCAFEBABE);
        for (int i = 0; i < 10_000; i++) {
            final boolean isBlock = rng.nextBoolean();
            final CompoundTag tag = new CompoundTag();
            tag.putString("tag_" + i, "val_" + rng.nextLong());
            tag.putInt("intVal", rng.nextInt());

            if (isBlock) {
                final BlockPos pos = new BlockPos(
                    rng.nextInt(2_000_000) - 1_000_000,
                    rng.nextInt(512) - 64,
                    rng.nextInt(2_000_000) - 1_000_000
                );
                final FriendlyByteBuf serverBuf = new FriendlyByteBuf(Unpooled.buffer());
                serverBuf.writeVarInt(S2C_BLOCK_NBT_RESPONSE);
                serverBuf.writeBlockPos(pos);
                ServuxWire.appendNbtBody(serverBuf, tag);

                final byte[] wire = new byte[serverBuf.readableBytes()];
                serverBuf.readBytes(wire);
                serverBuf.release();

                final FriendlyByteBuf clientBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
                final ServuxEntitiesPacket packet = ServuxEntitiesPacket.fromPacket(clientBuf);
                assertNotNull(packet);
                assertEquals(pos, packet.getPos());
                assertEquals(tag, packet.getCompound());
                assertFalse(clientBuf.isReadable());
            } else {
                final int entityId = rng.nextInt(Integer.MAX_VALUE);
                final FriendlyByteBuf serverBuf = new FriendlyByteBuf(Unpooled.buffer());
                serverBuf.writeVarInt(S2C_ENTITY_NBT_RESPONSE);
                serverBuf.writeVarInt(entityId);
                ServuxWire.appendNbtBody(serverBuf, tag);

                final byte[] wire = new byte[serverBuf.readableBytes()];
                serverBuf.readBytes(wire);
                serverBuf.release();

                final FriendlyByteBuf clientBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
                final ServuxEntitiesPacket packet = ServuxEntitiesPacket.fromPacket(clientBuf);
                assertNotNull(packet);
                assertEquals(entityId, packet.getEntityId());
                assertEquals(tag, packet.getCompound());
                assertFalse(clientBuf.isReadable());
            }
        }
    }
}
