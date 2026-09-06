package paperlab.servux;

import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ServuxWireTest {

    @Test
    @DisplayName("Metadata packet encoding and decoding roundtrip")
    public void testMetadataRoundtrip() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "hud_data");
        tag.putInt("version", 3);
        tag.putBoolean("active", true);

        final byte[] wire = ServuxWire.metadata(1, tag);
        assertEquals(1, ServuxWire.readType(wire));

        final CompoundTag read = ServuxWire.readNetworkNbt(wire);
        assertEquals(tag, read);
    }

    @Test
    @DisplayName("Data packet encoding and decoding roundtrip")
    public void testDataRoundtrip() throws IOException {
        final CompoundTag tag = new CompoundTag();
        tag.putString("type", "weather");
        tag.putLong("seed", 1234567890L);
        tag.putDouble("tps", 20.0);

        final byte[] wire = ServuxWire.data(5, tag);
        assertEquals(5, ServuxWire.readType(wire));

        final CompoundTag read = ServuxWire.readCompressedNbt(wire);
        assertEquals(tag, read);
    }

    @Test
    @DisplayName("ServuxReassembler multi-slice reassembly test")
    public void testReassemblerMultiSlice() throws IOException {
        final UUID player = UUID.randomUUID();
        final String channel = "servux:litematics";

        final CompoundTag tag = new CompoundTag();
        tag.putString("schematic", "test_schematic_payload");
        tag.putInt("size", 100);

        // Готовим тело
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(-1);
        buf.writeNbt(tag);
        final byte[] body = new byte[buf.readableBytes()];
        buf.readBytes(body);
        buf.release();

        // Разбиваем на 2 слайса
        final int half = body.length / 2;

        // Первый слайс: varint(type=12), varint(total=body.length), body[0..half]
        final FriendlyByteBuf slice1Buf = new FriendlyByteBuf(Unpooled.buffer());
        slice1Buf.writeVarInt(12);
        slice1Buf.writeVarInt(body.length);
        slice1Buf.writeBytes(body, 0, half);
        final byte[] slice1 = new byte[slice1Buf.readableBytes()];
        slice1Buf.readBytes(slice1);
        slice1Buf.release();

        final byte[] result1 = ServuxReassembler.accept(player, channel, slice1);
        assertNull(result1, "После первого куска сборка должна ожидать продолжения");

        // Второй слайс: varint(type=13), body[half..end]
        final FriendlyByteBuf slice2Buf = new FriendlyByteBuf(Unpooled.buffer());
        slice2Buf.writeVarInt(13);
        slice2Buf.writeBytes(body, half, body.length - half);
        final byte[] slice2 = new byte[slice2Buf.readableBytes()];
        slice2Buf.readBytes(slice2);
        slice2Buf.release();

        final byte[] result2 = ServuxReassembler.accept(player, channel, slice2);
        assertNotNull(result2, "После второго куска сборка должна быть завершена");
        assertArrayEquals(body, result2);

        final CompoundTag parsedTag = ServuxReassembler.toNbt(result2, "test");
        assertEquals(tag, parsedTag);
    }
}
