package paperlab.servux;

import fi.dy.masa.servux.network.packet.ServuxHudPacket;
import fi.dy.masa.servux.util.data.Constants;
import fi.dy.masa.servux.util.data.tag.CompoundData;
import fi.dy.masa.servux.util.data.tag.util.DataByteBufUtils;
import fi.dy.masa.servux.util.data.tag.converter.DataConverterNbt;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.Random;
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

    private static final int S2C_METADATA = 1;
    private static final int S2C_SPAWN_DATA = 3;
    private static final int S2C_WEATHER_TICK = 5;
    private static final int S2C_DATA_LOGGER_TICK = 7;

    @Test
    @DisplayName("Дифференциальный тест Handshake: PaperLab Metadata -> Оригинальный парсер и валидация MiniHUD")
    public void testMetadataPacketHandshakeValidation() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "hud_data");
        tag.putString("id", "servux:hud_metadata");
        tag.putInt("version", 3);
        tag.putString("servux", "servux-fabric-26.2-paperlab");
        tag.putString("spawnDimension", "minecraft:overworld");
        tag.putInt("spawnPosX", 0);
        tag.putInt("spawnPosY", 64);
        tag.putInt("spawnPosZ", 0);

        final CompoundTag loggers = new CompoundTag();
        loggers.putBoolean("tps", true);
        loggers.putBoolean("mob_caps", true);
        tag.put("Loggers", loggers);

        // Кодируем сетевым NBT PaperLab
        final byte[] wire = ServuxWire.metadata(S2C_METADATA, tag);

        // Декодируем оригинальным парсером ServuxHudPacket.fromPacket
        final FriendlyByteBuf inBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final ServuxHudPacket packet = ServuxHudPacket.fromPacket(inBuf);

        assertNotNull(packet);
        assertEquals(ServuxHudPacket.Type.PACKET_S2C_METADATA, packet.getType());
        assertFalse(inBuf.isReadable());

        // Точная проверка условий MiniHUD HudDataManager.receiveMetadata
        final CompoundData data = packet.getCompound();
        final int version = data.getIntOrDefault("version", -1);
        final String servux = data.getStringOrDefault("servux", "?");

        assertEquals(3, version);
        assertTrue(servux.startsWith("servux-fabric-26.2"), "MiniHUD требует префикс 'servux-fabric-26.2'");
        assertEquals("hud_data", data.getStringOrDefault("name", ""));
        assertEquals("servux:hud_metadata", data.getStringOrDefault("id", ""));

        final CompoundData readLoggers = data.getCompoundOrDefault("Loggers", new CompoundData());
        assertTrue(readLoggers.getBooleanOrDefault("tps", false));
        assertTrue(readLoggers.getBooleanOrDefault("mob_caps", false));
    }

    @Test
    @DisplayName("Дифференциальный тест Weather: Дождь и гроза (SetRaining, SetThundering) -> MiniHUD логика")
    public void testRainAndThunderWeatherIntegration() throws IOException {
        final CompoundTag thunderWeatherTag = new CompoundTag();
        thunderWeatherTag.putBoolean("isRaining", true);
        thunderWeatherTag.putBoolean("isThundering", true);
        thunderWeatherTag.putInt("SetRaining", 12500);
        thunderWeatherTag.putInt("SetThundering", 6400);

        final byte[] wire = ServuxWire.data(S2C_WEATHER_TICK, thunderWeatherTag);
        final FriendlyByteBuf inBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final ServuxHudPacket parsed = ServuxHudPacket.fromPacket(inBuf);

        assertNotNull(parsed);
        assertEquals(ServuxHudPacket.Type.PACKET_S2C_WEATHER_TICK, parsed.getType());
        assertFalse(inBuf.isReadable());

        final CompoundData data = parsed.getCompound();
        assertTrue(data.getBooleanOrDefault("isRaining", false));
        assertTrue(data.getBooleanOrDefault("isThundering", false));
        assertEquals(12500, data.getIntOrDefault("SetRaining", -1));
        assertEquals(6400, data.getIntOrDefault("SetThundering", -1));

        // Эмуляция логики MiniHUD HudDataManager.receiveWeatherData
        boolean isRaining = data.getBooleanOrDefault("isRaining", false);
        boolean isThundering = data.getBooleanOrDefault("isThundering", false);
        int rainTime = data.getIntOrDefault("SetRaining", -1);
        int thunderTime = data.getIntOrDefault("SetThundering", -1);

        assertTrue(isRaining && isThundering);
        assertEquals(12500, rainTime);
        assertEquals(6400, thunderTime);

        // Эмуляция InfoLineWeather:
        String weatherType = (isThundering && isRaining) ? "thundering" : (isRaining ? "raining" : "clear");
        int weatherTime = (isThundering && isRaining) ? thunderTime : (isRaining ? rainTime : -1);

        assertEquals("thundering", weatherType);
        assertEquals(6400, weatherTime);
    }

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

    // --- 5. Дифференциальный фаззинг Weather (10 000 итераций) ---
    @Test
    @DisplayName("Дифференциальный фаззинг Weather: PaperLab vs Mod в обе стороны [10 000 итераций]")
    public void fuzzWeatherDifferential() throws IOException {
        final Random rng = new Random(1337L);
        for (int i = 0; i < 10_000; i++) {
            final boolean raining = rng.nextBoolean();
            final boolean thundering = rng.nextBoolean();
            final int clearTimer = rng.nextInt(2_000_000);
            final int rainTimer = rng.nextInt(2_000_000);
            final int thunderTimer = rng.nextInt(2_000_000);

            // PaperLab -> Mod
            final CompoundTag weatherTag = new CompoundTag();
            weatherTag.putBoolean("isRaining", raining);
            weatherTag.putBoolean("isThundering", thundering);
            weatherTag.putInt("SetClear", clearTimer);
            weatherTag.putInt("SetRaining", rainTimer);
            weatherTag.putInt("SetThundering", thunderTimer);

            final byte[] wire = ServuxWire.data(S2C_WEATHER_TICK, weatherTag);
            final FriendlyByteBuf inBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
            final ServuxHudPacket parsed = ServuxHudPacket.fromPacket(inBuf);

            assertNotNull(parsed);
            assertEquals(ServuxHudPacket.Type.PACKET_S2C_WEATHER_TICK, parsed.getType());
            assertFalse(inBuf.isReadable());

            final CompoundData cd = parsed.getCompound();
            assertEquals(raining, cd.getBooleanOrDefault("isRaining", !raining));
            assertEquals(thundering, cd.getBooleanOrDefault("isThundering", !thundering));
            assertEquals(clearTimer, cd.getIntOrDefault("SetClear", -1));
            assertEquals(rainTimer, cd.getIntOrDefault("SetRaining", -1));
            assertEquals(thunderTimer, cd.getIntOrDefault("SetThundering", -1));

            // Mod -> PaperLab
            final CompoundData origCd = new CompoundData();
            origCd.putBoolean("isRaining", raining);
            origCd.putBoolean("isThundering", thundering);
            origCd.putInt("SetClear", clearTimer);
            origCd.putInt("SetRaining", rainTimer);
            origCd.putInt("SetThundering", thunderTimer);

            final ServuxHudPacket origPacket = ServuxHudPacket.WeatherTick(origCd);
            final FriendlyByteBuf origBuf = new FriendlyByteBuf(Unpooled.buffer());
            origPacket.toPacket(origBuf);
            final byte[] origBytes = new byte[origBuf.readableBytes()];
            origBuf.readBytes(origBytes);
            origBuf.release();

            assertEquals(S2C_WEATHER_TICK, ServuxWire.readType(origBytes));
            final CompoundTag decoded = ServuxWire.readCompressedNbt(origBytes);
            assertEquals(raining, decoded.getBooleanOr("isRaining", !raining));
            assertEquals(thundering, decoded.getBooleanOr("isThundering", !thundering));
            assertEquals(clearTimer, decoded.getIntOr("SetClear", -1));
            assertEquals(rainTimer, decoded.getIntOr("SetRaining", -1));
            assertEquals(thunderTimer, decoded.getIntOr("SetThundering", -1));
        }
    }

    // --- 6. Дифференциальный фаззинг MobCaps и TPS (5 000 итераций) ---
    @Test
    @DisplayName("Дифференциальный фаззинг MobCaps и TPS [5 000 итераций]")
    public void fuzzMobCapsDifferential() throws IOException {
        final Random rng = new Random(4242L);
        final String[] dims = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "custom:space"};

        for (int i = 0; i < 5_000; i++) {
            final CompoundTag dataLogger = new CompoundTag();

            final CompoundTag tps = new CompoundTag();
            final double mspt = rng.nextDouble() * 100.0;
            final double tpsVal = Math.min(20.0, rng.nextDouble() * 25.0);
            tps.putDouble("mspt", mspt);
            tps.putDouble("tps", tpsVal);
            tps.putLong("sprintTicks", (long) rng.nextInt(1000));
            tps.putBoolean("frozen", rng.nextBoolean());
            tps.putBoolean("sprinting", rng.nextBoolean());
            tps.putBoolean("stepping", rng.nextBoolean());
            dataLogger.put("tps", tps);

            final CompoundTag mobCaps = new CompoundTag();
            final String selectedDim = dims[rng.nextInt(dims.length)];
            final CompoundTag dimEntry = new CompoundTag();
            final long worldTick = (long) rng.nextInt(10_000_000);
            dimEntry.putLong("WorldTick", worldTick);
            dimEntry.putInt("cap_count", MobCategory.values().length);

            final ListTag capsList = new ListTag();
            final int[] currents = new int[MobCategory.values().length];
            final int[] caps = new int[MobCategory.values().length];
            for (int c = 0; c < MobCategory.values().length; c++) {
                currents[c] = rng.nextInt(500);
                caps[c] = rng.nextInt(500);
                final CompoundTag catTag = new CompoundTag();
                catTag.putInt("current", currents[c]);
                catTag.putInt("cap", caps[c]);
                capsList.add(catTag);
            }
            dimEntry.put("cap_data", capsList);
            mobCaps.put(selectedDim, dimEntry);
            dataLogger.put("mob_caps", mobCaps);

            final byte[] wire = ServuxWire.data(S2C_DATA_LOGGER_TICK, dataLogger);
            final FriendlyByteBuf inBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
            final ServuxHudPacket parsed = ServuxHudPacket.fromPacket(inBuf);

            assertNotNull(parsed);
            assertEquals(ServuxHudPacket.Type.PACKET_S2C_DATA_LOGGER_TICK, parsed.getType());
            assertFalse(inBuf.isReadable());

            final CompoundData cd = parsed.getCompound();
            final CompoundData tpsCd = cd.getCompoundOrDefault("tps", new CompoundData());
            assertEquals(mspt, tpsCd.getDoubleOrDefault("mspt", 0.0), 0.001);
            assertEquals(tpsVal, tpsCd.getDoubleOrDefault("tps", 0.0), 0.001);

            final CompoundData mobCapsCd = cd.getCompoundOrDefault("mob_caps", new CompoundData());
            final CompoundData dimCd = mobCapsCd.getCompoundOrDefault(selectedDim, new CompoundData());
            assertEquals(worldTick, dimCd.getLongOrDefault("WorldTick", -1L));
            assertEquals(MobCategory.values().length, dimCd.getIntOrDefault("cap_count", 0));
        }
    }

    // --- 7. Дифференциальный фаззинг Spawn Data (5 000 итераций) ---
    @Test
    @DisplayName("Дифференциальный фаззинг Spawn Data [5 000 итераций]")
    public void fuzzSpawnDataDifferential() throws IOException {
        final Random rng = new Random(9999L);
        final String[] dims = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "dim:void"};

        for (int i = 0; i < 5_000; i++) {
            final String dim = dims[rng.nextInt(dims.length)];
            final int x = rng.nextInt();
            final int y = rng.nextInt(512) - 64;
            final int z = rng.nextInt();

            final CompoundTag spawnTag = new CompoundTag();
            spawnTag.putString("spawnDimension", dim);
            spawnTag.putInt("spawnPosX", x);
            spawnTag.putInt("spawnPosY", y);
            spawnTag.putInt("spawnPosZ", z);

            final byte[] wire = ServuxWire.data(S2C_SPAWN_DATA, spawnTag);
            final FriendlyByteBuf inBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
            final ServuxHudPacket parsed = ServuxHudPacket.fromPacket(inBuf);

            assertNotNull(parsed);
            assertEquals(ServuxHudPacket.Type.PACKET_S2C_SPAWN_DATA, parsed.getType());
            assertFalse(inBuf.isReadable());

            final CompoundData cd = parsed.getCompound();
            assertEquals(dim, cd.getStringOrDefault("spawnDimension", ""));
            assertEquals(x, cd.getIntOrDefault("spawnPosX", 0));
            assertEquals(y, cd.getIntOrDefault("spawnPosY", 0));
            assertEquals(z, cd.getIntOrDefault("spawnPosZ", 0));
        }
    }

    // --- 8. Мутационный фаззинг сетевого потока (10 000 итераций) ---
    @Test
    @DisplayName("Мутационный фаззинг сетевого потока: устойчивость к повреждённым байтам и неполным пакетам [10 000 итераций]")
    public void fuzzMutationalRobustness() throws IOException {
        final Random rng = new Random(777L);
        final CompoundTag validTag = new CompoundTag();
        validTag.putBoolean("isRaining", true);
        validTag.putInt("SetRaining", 1234);

        final byte[] validBytes = ServuxWire.data(S2C_WEATHER_TICK, validTag);

        for (int i = 0; i < 10_000; i++) {
            final int mutationType = rng.nextInt(3);
            final byte[] mutated;

            if (mutationType == 0) {
                // Обрезаем пакет
                final int cut = rng.nextInt(validBytes.length);
                mutated = new byte[cut];
                System.arraycopy(validBytes, 0, mutated, 0, cut);
            } else if (mutationType == 1) {
                // Повреждаем случайные байты в сжатом GZIP потоке
                mutated = validBytes.clone();
                final int flips = 1 + rng.nextInt(5);
                for (int f = 0; f < flips; f++) {
                    mutated[rng.nextInt(mutated.length)] = (byte) rng.nextInt(256);
                }
            } else {
                // Полностью случайный мусор произвольной длины
                final int noiseLen = rng.nextInt(128);
                mutated = new byte[noiseLen];
                rng.nextBytes(mutated);
            }

            final FriendlyByteBuf inBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(mutated));
            try {
                // Вызываем оригинальный парсер Servux/MiniHUD:
                // Он должен либо успешно распарсить, либо безопасно вернуть null / выбросить исключение (без зависания или OOM)
                ServuxHudPacket.fromPacket(inBuf);
            } catch (final Exception expectedSafeFailure) {
                // Безопасное отклонение повреждённого пакета
            } finally {
                inBuf.release();
            }
        }
    }
}
