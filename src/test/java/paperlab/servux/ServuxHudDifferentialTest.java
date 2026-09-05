package paperlab.servux;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.MobCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Дифференциальные тесты совместимости канала {@code servux:hud_metadata} с клиентом MiniHUD.
 *
 * <p>Проверяют соответствие форматов сериализации PaperLab и десериализации MiniHUD
 * (классы {@code ServuxHudPacket}, {@code DataByteBufUtils} и {@code HudDataManager}).
 */
public class ServuxHudDifferentialTest {

    private static final int S2C_SPAWN_DATA = 3;
    private static final int S2C_WEATHER_TICK = 5;
    private static final int S2C_DATA_LOGGER_TICK = 7;

    /**
     * Эмуляция клиентского парсера MiniHUD (DataByteBufUtils.fromByteBuf + ServuxHudPacket.fromPacket).
     */
    private static CompoundTag decodeMiniHudPacket(final byte[] packetBytes, final int expectedType) throws IOException {
        final FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(packetBytes));
        final int type = input.readVarInt();
        assertEquals(expectedType, type, "Packet type ID must match MiniHUD protocol");

        // MiniHUD: DataByteBufUtils.fromByteBuf(input)
        final int length = input.readInt();
        assertTrue(length > 0, "Length prefix must be positive");
        assertEquals(length, input.readableBytes(),
            "Buffer slice must match readable bytes exactly: no extra trailing bytes allowed (would kill Netty)");

        final byte[] slice = new byte[length];
        input.readBytes(slice);
        assertFalse(input.isReadable(), "All bytes in custom payload must be consumed by MiniHUD");

        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(slice))) {
            return NbtIo.readCompressed(new ByteArrayInputStream(slice), NbtAccounter.unlimitedHeap());
        }
    }

    @Test
    @DisplayName("Weather packet framing and MiniHUD cycle validation (prevents 'Disabled, or unknown')")
    public void testWeatherPacketEncodingAndMiniHudDecoding() throws IOException {
        final CompoundTag weatherTag = new CompoundTag();
        weatherTag.putBoolean("isRaining", false);
        weatherTag.putBoolean("isThundering", false);
        weatherTag.putInt("SetClear", 18500);

        final byte[] wire = ServuxWire.data(S2C_WEATHER_TICK, weatherTag);
        final CompoundTag decoded = decodeMiniHudPacket(wire, S2C_WEATHER_TICK);

        // Проверяем поля NBT
        assertFalse(decoded.getBooleanOr("isRaining", true));
        assertFalse(decoded.getBooleanOr("isThundering", true));
        assertEquals(18500, decoded.getIntOr("SetClear", -1));

        // Эмуляция логики MiniHUD HudDataManager.receiveWeatherData + hasValidWeatherCycle()
        final boolean isRaining = decoded.getBooleanOr("isRaining", false);
        final boolean isThundering = decoded.getBooleanOr("isThundering", false);
        final int clearWeatherTimer = decoded.getIntOr("SetClear", -1);
        final int rainWeatherTimer = decoded.getIntOr("SetRaining", -1);
        final int thunderWeatherTimer = decoded.getIntOr("SetThundering", -1);

        final boolean isWeatherClear = !isRaining && !isThundering;
        final int clearTime = isWeatherClear ? clearWeatherTimer : -1;
        final int rainTime = isRaining ? rainWeatherTimer : -1;
        final int thunderTime = isThundering ? thunderWeatherTimer : -1;

        // MiniHUD hasValidWeatherCycle():
        final boolean hasValidWeatherCycle = clearTime >= 0 || rainTime >= 0 || thunderTime >= 0;
        assertTrue(hasValidWeatherCycle,
            "MiniHUD must recognize a valid weather cycle! If false, MiniHUD renders 'Weather: Disabled, or unknown'");
        assertEquals(18500, clearTime);
    }

    @Test
    @DisplayName("MobCaps and TPS data logger packet framing and MiniHUD dimKey lookup")
    public void testDataLoggerMobCapsAndTpsEncodingAndMiniHudDecoding() throws IOException {
        final CompoundTag dataLogger = new CompoundTag();

        // 1. TPS
        final CompoundTag tps = new CompoundTag();
        tps.putDouble("mspt", 12.5);
        tps.putDouble("tps", 20.0);
        tps.putLong("sprintTicks", 0L);
        tps.putBoolean("frozen", false);
        tps.putBoolean("sprinting", false);
        tps.putBoolean("stepping", false);
        dataLogger.put("tps", tps);

        // 2. MobCaps в формате Servux (dimKey -> { WorldTick, cap_count, cap_data })
        final CompoundTag mobCaps = new CompoundTag();
        final CompoundTag overworldEntry = new CompoundTag();
        overworldEntry.putLong("WorldTick", 45000L);
        overworldEntry.putInt("cap_count", MobCategory.values().length);

        final ListTag capsList = new ListTag();
        for (final MobCategory cat : MobCategory.values()) {
            final CompoundTag c = new CompoundTag();
            c.putInt("current", 15);
            c.putInt("cap", 70);
            capsList.add(c);
        }
        overworldEntry.put("cap_data", capsList);
        mobCaps.put("minecraft:overworld", overworldEntry);
        dataLogger.put("mob_caps", mobCaps);

        // Кодируем в провод
        final byte[] wire = ServuxWire.data(S2C_DATA_LOGGER_TICK, dataLogger);
        final CompoundTag decoded = decodeMiniHudPacket(wire, S2C_DATA_LOGGER_TICK);

        // MiniHUD: TPS
        final CompoundTag decodedTps = decoded.getCompound("tps").orElseThrow();
        assertNotNull(decodedTps);
        assertEquals(20.0, decodedTps.getDoubleOr("tps", 0.0));
        assertEquals(12.5, decodedTps.getDoubleOr("mspt", 0.0));

        // MiniHUD: MobCaps
        final CompoundTag decodedMobCaps = decoded.getCompound("mob_caps").orElseThrow();
        assertNotNull(decodedMobCaps);

        // MiniHUD: String dimKey = mc.level.dimension().identifier().toString();
        final String dimKey = "minecraft:overworld";
        assertTrue(decodedMobCaps.contains(dimKey),
            "MiniHUD looks up entry.contains(dimKey). If missing, mobcaps line is completely hidden!");

        final CompoundTag nbtEntry = decodedMobCaps.getCompound(dimKey).orElseThrow();
        assertEquals(45000L, nbtEntry.getLongOr("WorldTick", 0L));
        assertEquals(8, nbtEntry.getIntOr("cap_count", 0));

        final ListTag caps = (ListTag) nbtEntry.get("cap_data");
        assertNotNull(caps);
        assertEquals(8, caps.size());

        final CompoundTag monsterCap = (CompoundTag) caps.get(0);
        assertEquals(15, monsterCap.getIntOr("current", 0));
        assertEquals(70, monsterCap.getIntOr("cap", 0));
    }

    @Test
    @DisplayName("Spawn data packet framing and decoding")
    public void testSpawnDataEncodingAndMiniHudDecoding() throws IOException {
        final CompoundTag spawnTag = new CompoundTag();
        spawnTag.putString("spawnDimension", "minecraft:overworld");
        spawnTag.putInt("spawnPosX", 100);
        spawnTag.putInt("spawnPosY", 64);
        spawnTag.putInt("spawnPosZ", -200);

        final byte[] wire = ServuxWire.data(S2C_SPAWN_DATA, spawnTag);
        final CompoundTag decoded = decodeMiniHudPacket(wire, S2C_SPAWN_DATA);

        assertEquals("minecraft:overworld", decoded.getStringOr("spawnDimension", ""));
        assertEquals(100, decoded.getIntOr("spawnPosX", 0));
        assertEquals(64, decoded.getIntOr("spawnPosY", 0));
        assertEquals(-200, decoded.getIntOr("spawnPosZ", 0));
    }

    @Test
    @DisplayName("ServuxWire roundtrip with readCompressedNbt")
    public void testServuxWireRoundtrip() throws IOException {
        final CompoundTag tag = new CompoundTag();
        tag.putString("key", "test_value");
        tag.putInt("num", 42);

        final byte[] wire = ServuxWire.data(S2C_WEATHER_TICK, tag);
        final CompoundTag roundtrip = ServuxWire.readCompressedNbt(wire);

        assertEquals("test_value", roundtrip.getStringOr("key", ""));
        assertEquals(42, roundtrip.getIntOr("num", 0));
    }
}
