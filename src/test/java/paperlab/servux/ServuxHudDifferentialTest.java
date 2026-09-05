package paperlab.servux;

import fi.dy.masa.servux.network.packet.ServuxHudPacket;
import fi.dy.masa.servux.util.data.Constants;
import fi.dy.masa.servux.util.data.tag.CompoundData;
import fi.dy.masa.servux.util.data.tag.util.DataByteBufUtils;
import fi.dy.masa.servux.util.data.tag.converter.DataConverterNbt;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.MobCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Полноценный дифференциальный тест сетевого протокола {@code servux:hud_metadata}.
 *
 * <p>Сравнивает оригинальный код мода Servux / MiniHUD ({@link ServuxHudPacket},
 * {@link DataByteBufUtils}, {@link CompoundData}) с реализацией плагина PaperLab
 * ({@link ServuxWire}, {@link ServuxHud}).
 */
public class ServuxHudDifferentialTest {

    private static final int S2C_SPAWN_DATA = 3;
    private static final int S2C_WEATHER_TICK = 5;
    private static final int S2C_DATA_LOGGER_TICK = 7;

    @Test
    @DisplayName("Дифференциальный тест Weather: PaperLab Wire Encoder -> Оригинальный парсер ServuxHudPacket.fromPacket")
    public void testWeatherPacketOriginalParserIntegration() throws IOException {
        final CompoundTag weatherTag = new CompoundTag();
        weatherTag.putBoolean("isRaining", false);
        weatherTag.putBoolean("isThundering", false);
        weatherTag.putInt("SetClear", 18500);

        // 1. Кодируем пакет проводом PaperLab:
        final byte[] wire = ServuxWire.data(S2C_WEATHER_TICK, weatherTag);

        // 2. Декодируем НАПРЯМУЮ ОРИГИНАЛЬНЫМ КЛАССОМ ServuxHudPacket.fromPacket (код MiniHUD / Servux):
        final FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final ServuxHudPacket parsed = ServuxHudPacket.fromPacket(input);

        assertNotNull(parsed, "Оригинальный ServuxHudPacket.fromPacket не должен возвращать null!");
        assertEquals(ServuxHudPacket.Type.PACKET_S2C_WEATHER_TICK, parsed.getType());
        assertFalse(input.isReadable(), "Все байты пакета обязаны быть вычитаны (защита от Netty extra bytes exception)");

        // 3. Проверяем поля через оригинальный CompoundData мода:
        final CompoundData data = parsed.getCompound();
        assertNotNull(data);
        assertFalse(data.getBooleanOrDefault("isRaining", true));
        assertFalse(data.getBooleanOrDefault("isThundering", true));
        assertEquals(18500, data.getIntOrDefault("SetClear", -1));

        // 4. Проверяем валидацию цикла MiniHUD:
        final int clearTimer = data.getIntOrDefault("SetClear", -1);
        final boolean hasValidWeatherCycle = clearTimer >= 0;
        assertTrue(hasValidWeatherCycle, "MiniHUD должен считать цикл погоды валидным!");
    }

    @Test
    @DisplayName("Дифференциальный тест Weather: Оригинальный энкодер Servux -> Парсер PaperLab ServuxWire.readCompressedNbt")
    public void testWeatherPacketReverseCrossWire() throws IOException {
        // 1. Формируем пакет оригинальными классами Servux:
        final CompoundData originalData = new CompoundData();
        originalData.putBoolean("isRaining", true);
        originalData.putBoolean("isThundering", false);
        originalData.putInt("SetRaining", 6000);

        final ServuxHudPacket packet = ServuxHudPacket.WeatherTick(originalData);
        final FriendlyByteBuf origBuf = new FriendlyByteBuf(Unpooled.buffer());
        packet.toPacket(origBuf);

        final byte[] origBytes = new byte[origBuf.readableBytes()];
        origBuf.readBytes(origBytes);
        origBuf.release();

        // 2. Читаем парсером PaperLab:
        assertEquals(S2C_WEATHER_TICK, ServuxWire.readType(origBytes));
        final CompoundTag decodedByPaperLab = ServuxWire.readCompressedNbt(origBytes);

        assertTrue(decodedByPaperLab.getBooleanOr("isRaining", false));
        assertFalse(decodedByPaperLab.getBooleanOr("isThundering", true));
        assertEquals(6000, decodedByPaperLab.getIntOr("SetRaining", -1));
    }

    @Test
    @DisplayName("Дифференциальный тест MobCaps и TPS: PaperLab -> Оригинальный ServuxHudPacket.fromPacket")
    public void testDataLoggerMobCapsOriginalParserIntegration() throws IOException {
        final CompoundTag dataLogger = new CompoundTag();

        // TPS
        final CompoundTag tps = new CompoundTag();
        tps.putDouble("mspt", 12.5);
        tps.putDouble("tps", 20.0);
        tps.putLong("sprintTicks", 0L);
        tps.putBoolean("frozen", false);
        tps.putBoolean("sprinting", false);
        tps.putBoolean("stepping", false);
        dataLogger.put("tps", tps);

        // MobCaps
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

        // Кодируем через PaperLab:
        final byte[] wire = ServuxWire.data(S2C_DATA_LOGGER_TICK, dataLogger);

        // Декодируем через оригинальный Servux:
        final FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final ServuxHudPacket parsed = ServuxHudPacket.fromPacket(input);

        assertNotNull(parsed);
        assertEquals(ServuxHudPacket.Type.PACKET_S2C_DATA_LOGGER_TICK, parsed.getType());
        assertFalse(input.isReadable());

        final CompoundData compound = parsed.getCompound();
        assertTrue(compound.contains("tps", Constants.NBT.TAG_COMPOUND));
        assertTrue(compound.contains("mob_caps", Constants.NBT.TAG_COMPOUND));

        final CompoundData mobCapsData = compound.getCompoundOrDefault("mob_caps", new CompoundData());
        assertTrue(mobCapsData.contains("minecraft:overworld", Constants.NBT.TAG_COMPOUND),
            "Оригинальный клиент MiniHUD ищет dimKey в mob_caps");

        final CompoundData owData = mobCapsData.getCompoundOrDefault("minecraft:overworld", new CompoundData());
        assertEquals(45000L, owData.getLongOrDefault("WorldTick", 0L));
        assertEquals(8, owData.getIntOrDefault("cap_count", 0));
        assertTrue(owData.containsList("cap_data", Constants.NBT.TAG_COMPOUND));
    }

    @Test
    @DisplayName("Дифференциальный тест Spawn Data: PaperLab -> Оригинальный ServuxHudPacket")
    public void testSpawnDataOriginalParserIntegration() throws IOException {
        final CompoundTag spawnTag = new CompoundTag();
        spawnTag.putString("spawnDimension", "minecraft:overworld");
        spawnTag.putInt("spawnPosX", 100);
        spawnTag.putInt("spawnPosY", 64);
        spawnTag.putInt("spawnPosZ", -200);

        final byte[] wire = ServuxWire.data(S2C_SPAWN_DATA, spawnTag);

        final FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final ServuxHudPacket parsed = ServuxHudPacket.fromPacket(input);

        assertNotNull(parsed);
        assertEquals(ServuxHudPacket.Type.PACKET_S2C_SPAWN_DATA, parsed.getType());
        assertFalse(input.isReadable());

        final CompoundData compound = parsed.getCompound();
        assertEquals("minecraft:overworld", compound.getStringOrDefault("spawnDimension", ""));
        assertEquals(100, compound.getIntOrDefault("spawnPosX", 0));
        assertEquals(64, compound.getIntOrDefault("spawnPosY", 0));
        assertEquals(-200, compound.getIntOrDefault("spawnPosZ", 0));
    }
}
