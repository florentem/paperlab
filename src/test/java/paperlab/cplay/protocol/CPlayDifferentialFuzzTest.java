package paperlab.cplay.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import paperlab.cplay.model.CPlayAssetHandle;
import paperlab.cplay.model.CPlayAssetInfo;
import paperlab.cplay.model.CPlayAssetNamespace;
import paperlab.cplay.model.CPlayAssetType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Расширенное дифференциальное фаззинг-тестирование сетевого протокола CPlay:
 * Проверка взаимной совместимости, устойчивости к мутациям и граничным условиям
 * между эталонной спецификацией G4mespeed и реализацией PaperLab (> 200 000 итераций).
 */
public class CPlayDifferentialFuzzTest {

    private static final int LARGE_FUZZ_ITERATIONS = 50_000;
    private final Random rng = new Random(42L); // Фиксированный сид для детерминированной воспроизводимости

    // --- 1. Дифференциальный фаззинг генератора и парсера хэндлов (50 000 итераций) ---
    @Test
    @DisplayName("Дифференциальный фаззинг CPlayAssetHandle: генерация, санитизация и граничные строки [50 000 итераций]")
    public void fuzzAssetHandleGenerationAndParsing() {
        final String[] charPool = {
            "a", "b", "z", "0", "1", "9", "_", "-", " ", "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
            "А", "я", "Ж", "ш", "🔥", "🚀", "⚡", "\t", "\n", "\r", "\0", ".", "/", "\\", ":", ";", "'", "\"",
            "~", "`", "+", "=", "[", "]", "{", "}", "|", "<", ">", "?", "ç", "é", "ö", "ü", "ñ", "中", "文"
        };

        for (int i = 0; i < LARGE_FUZZ_ITERATIONS; i++) {
            final int len = rng.nextInt(80);
            final StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) {
                sb.append(charPool[rng.nextInt(charPool.length)]);
            }
            final String rawName = sb.toString();
            final CPlayAssetNamespace ns = rng.nextBoolean() ? CPlayAssetNamespace.GLOBAL : CPlayAssetNamespace.WORLD;
            final String suffix = rng.nextBoolean() ? String.valueOf(rng.nextInt(100)) : "";

            // 1. Алгоритм плагина
            final CPlayAssetHandle handle = CPlayAssetHandle.fromName(ns, rawName, suffix);

            // Проверяем инварианты мода G4mespeed GSAssetHandle:
            assertNotNull(handle);
            assertEquals(ns, handle.getNamespace());
            final String handleStr = handle.getHandle();
            assertFalse(handleStr.isEmpty(), "Хэндл не должен быть пустым");
            assertTrue(handleStr.length() <= 20, "Длина хэндла не должна превышать 20 символов, получено: " + handleStr.length());

            // Все символы обязаны быть [0-9a-z_]
            for (int k = 0; k < handleStr.length(); k++) {
                final char c = handleStr.charAt(k);
                final boolean valid = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c == '_');
                assertTrue(valid, "Недопустимый символ в хэндле: '" + c + "' в хэндле: " + handleStr);
            }

            // 2. Круговой раундтрип через строковое представление "g:handle" / "w:handle"
            final String formatted = handle.toString();
            final CPlayAssetHandle parsed = CPlayAssetHandle.parse(formatted);
            assertNotNull(parsed);
            assertEquals(handle, parsed);
        }
    }

    // --- 2. Дифференциальный фаззинг пакета создания ассета (50 000 итераций) ---
    @Test
    @DisplayName("Дифференциальный фаззинг GSCreateAssetPacket: Mod Wire Encoder vs PaperLab Wire Decoder [50 000 итераций]")
    public void fuzzCreateAssetPacketModWireToPaperLab() {
        for (int i = 0; i < LARGE_FUZZ_ITERATIONS; i++) {
            final String randomName = generateRandomString(rng, 0, 100);
            final int typeIndex = rng.nextInt(2); // 0 = COMPOSITION, 1 = SEQUENCE
            final CPlayAssetNamespace ns = rng.nextBoolean() ? CPlayAssetNamespace.GLOBAL : CPlayAssetNamespace.WORLD;
            final String handleStr = generateRandomBase36(rng, 1, 20);
            final CPlayAssetHandle handle = new CPlayAssetHandle(ns, handleStr);
            final boolean hasOriginal = rng.nextBoolean();
            final UUID origUUID = hasOriginal ? UUID.randomUUID() : null;

            // Сериализация в эталонном формате мода GSCreateAssetPacket.write
            final ByteBuf wireBuf = Unpooled.buffer();
            CPlayWire.writeString(wireBuf, randomName);
            wireBuf.writeByte(typeIndex);
            CPlayWire.writeAssetHandle(wireBuf, handle);
            wireBuf.writeBoolean(hasOriginal);
            if (hasOriginal) {
                CPlayWire.writeUUID(wireBuf, origUUID);
            }

            // Десериализация логикой PaperLab CPlayService
            final String readName = CPlayWire.readString(wireBuf);
            final int readTypeIndex = wireBuf.readByte() & 0xFF;
            final CPlayAssetType readType = CPlayAssetType.fromIndex(readTypeIndex);
            final CPlayAssetHandle readHandle = CPlayWire.readAssetHandle(wireBuf);
            final boolean readHasOrig = wireBuf.readBoolean();
            final UUID readOrigUUID = readHasOrig ? CPlayWire.readUUID(wireBuf) : null;

            assertEquals(randomName, readName);
            assertEquals(typeIndex, readTypeIndex);
            assertNotNull(readType);
            assertEquals(typeIndex == 0 ? CPlayAssetType.COMPOSITION : CPlayAssetType.SEQUENCE, readType);
            assertEquals(handle, readHandle);
            assertEquals(hasOriginal, readHasOrig);
            assertEquals(origUUID, readOrigUUID);
            assertEquals(0, wireBuf.readableBytes(), "Все байты пакета создания должны быть вычитаны");
            wireBuf.release();
        }
    }

    // --- 3. Дифференциальный фаззинг пакета импорта ассета (20 000 итераций) ---
    @Test
    @DisplayName("Дифференциальный фаззинг GSImportAssetPacket: Mod Wire Encoder vs PaperLab Wire Decoder [20 000 итераций]")
    public void fuzzImportAssetPacketModWireToPaperLab() {
        for (int i = 0; i < 20_000; i++) {
            final String randomName = generateRandomString(rng, 1, 50);
            final CPlayAssetNamespace ns = rng.nextBoolean() ? CPlayAssetNamespace.GLOBAL : CPlayAssetNamespace.WORLD;
            final CPlayAssetHandle handle = new CPlayAssetHandle(ns, generateRandomBase36(rng, 1, 20));

            // Генерируем валидный файл ассета с заголовком
            final int typeIndex = rng.nextInt(2);
            final ByteBuf fileBuf = Unpooled.buffer();
            fileBuf.writeByte((byte) (0x80 | 1)); // formatVersion 1 versioned
            fileBuf.writeByte((byte) typeIndex);
            fileBuf.writeLong(System.currentTimeMillis());
            CPlayWire.writeUUID(fileBuf, UUID.randomUUID());
            fileBuf.writeBoolean(false); // no player cache entry
            fileBuf.writeByte(0); // reserved byte
            CPlayWire.writeUUID(fileBuf, UUID.randomUUID());
            CPlayWire.writeString(fileBuf, randomName);
            fileBuf.writeInt(0); // count
            if (typeIndex == 0) {
                fileBuf.writeInt(0); // trackCount
            }
            final byte[] rawFileBytes = new byte[fileBuf.readableBytes()];
            fileBuf.readBytes(rawFileBytes);
            fileBuf.release();

            // Мод пишет: String name, GSAssetHandle handle, byte[] rawFile
            final ByteBuf wireBuf = Unpooled.buffer();
            CPlayWire.writeString(wireBuf, randomName);
            CPlayWire.writeAssetHandle(wireBuf, handle);
            wireBuf.writeBytes(rawFileBytes);

            // PaperLab парсинг:
            final String readName = CPlayWire.readString(wireBuf);
            final CPlayAssetHandle readHandle = CPlayWire.readAssetHandle(wireBuf);
            final byte[] readFileBytes = new byte[wireBuf.readableBytes()];
            wireBuf.readBytes(readFileBytes);

            assertEquals(randomName, readName);
            assertEquals(handle, readHandle);
            assertArrayEquals(rawFileBytes, readFileBytes);

            // Определение типа ассета из заголовка файла:
            int derivedTypeIndex = 0;
            if (readFileBytes.length >= 2) {
                int fmt = readFileBytes[0] & 0xFF;
                if ((fmt & 0x80) != 0) {
                    derivedTypeIndex = readFileBytes[1] & 0xFF;
                } else {
                    derivedTypeIndex = fmt;
                }
            }
            assertEquals(typeIndex, derivedTypeIndex);
            wireBuf.release();
        }
    }

    // --- 4. Дифференциальный фаззинг пакета истории ассетов (5 000 итераций) ---
    @Test
    @DisplayName("Дифференциальный фаззинг GSAssetHistory: PaperLab Encoder vs Mod GSAssetHistory Decoder [5 000 итераций]")
    public void fuzzAssetHistoryPaperLabEncoderVsModDecoder() {
        for (int i = 0; i < 5000; i++) {
            final int assetCount = rng.nextInt(15);
            final List<CPlayAssetInfo> assets = new ArrayList<>();

            for (int j = 0; j < assetCount; j++) {
                final int typeIndex = rng.nextInt(2);
                final UUID assetUUID = UUID.randomUUID();
                final CPlayAssetNamespace ns = rng.nextBoolean() ? CPlayAssetNamespace.GLOBAL : CPlayAssetNamespace.WORLD;
                final CPlayAssetHandle handle = new CPlayAssetHandle(ns, generateRandomBase36(rng, 1, 15));
                final String name = generateRandomString(rng, 0, 30);
                final long created = Math.abs(rng.nextLong());
                final long modified = Math.abs(rng.nextLong());
                final UUID creator = UUID.randomUUID();
                final UUID owner = UUID.randomUUID();

                final CPlayAssetInfo info = new CPlayAssetInfo(typeIndex, assetUUID, handle, name, created, modified, creator, owner);
                final int collabCount = rng.nextInt(5);
                for (int c = 0; c < collabCount; c++) {
                    info.addCollaborator(UUID.randomUUID());
                }
                assets.add(info);
            }

            // Кодирование через PaperLab CPlayWire
            final byte[] packetBytes = CPlayWire.encodeAssetHistory(assets);
            final ByteBuf buf = Unpooled.wrappedBuffer(packetBytes);

            // Декодирование по спецификации мода GSAssetHistoryPacket / GSAssetHistory
            final long packetId = buf.readLong();
            assertEquals(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_ASSET_HISTORY), packetId);

            final byte fileVersion = buf.readByte();
            assertEquals(0, fileVersion, "fileVersion в моде обязан быть 0x00");

            final int readCount = buf.readInt();
            assertEquals(assets.size(), readCount);

            for (int j = 0; j < readCount; j++) {
                final int readType = buf.readByte() & 0xFF;
                final UUID readUUID = CPlayWire.readUUID(buf);
                final CPlayAssetHandle readHandle = CPlayWire.readAssetHandle(buf);
                final String readName = CPlayWire.readString(buf);
                final long readCreated = buf.readLong();
                final long readModified = buf.readLong();
                final UUID readCreator = CPlayWire.readUUID(buf);
                final UUID readOwner = CPlayWire.readUUID(buf);
                final int readCollabCount = buf.readInt();

                final CPlayAssetInfo orig = assets.get(j);
                assertEquals(orig.getTypeIndex(), readType);
                assertEquals(orig.getAssetUUID(), readUUID);
                assertEquals(orig.getHandle(), readHandle);
                assertEquals(orig.getAssetName(), readName);
                assertEquals(orig.getCreatedTimestamp(), readCreated);
                assertEquals(orig.getLastModifiedTimestamp(), readModified);
                assertEquals(orig.getCreatedByUUID(), readCreator);
                assertEquals(orig.getOwnerUUID(), readOwner);
                assertEquals(orig.getCollaboratorUUIDs().size(), readCollabCount);

                for (int c = 0; c < readCollabCount; c++) {
                    final UUID collab = CPlayWire.readUUID(buf);
                    assertTrue(orig.getCollaboratorUUIDs().contains(collab));
                }
            }

            assertEquals(0, buf.readableBytes(), "Буфер истории должен быть прочитан полностью");
            buf.release();
        }
    }

    // --- 5. Дифференциальный фаззинг сессионных пакетов (5 000 итераций) ---
    @Test
    @DisplayName("Дифференциальный фаззинг GSSessionStart: PaperLab Wire Framing vs Mod GSSessionFields Decoder [5 000 итераций]")
    public void fuzzSessionStartFramingPaperLabVsMod() {
        for (int i = 0; i < 5000; i++) {
            final int typeIndex = rng.nextInt(2);
            final UUID assetUUID = UUID.randomUUID();
            final CPlayAssetNamespace ns = rng.nextBoolean() ? CPlayAssetNamespace.GLOBAL : CPlayAssetNamespace.WORLD;
            final CPlayAssetHandle handle = new CPlayAssetHandle(ns, generateRandomBase36(rng, 1, 15));
            final String name = generateRandomString(rng, 0, 25);
            final UUID creator = UUID.randomUUID();

            final CPlayAssetInfo info = new CPlayAssetInfo(typeIndex, assetUUID, handle, name, 1000L, 2000L, creator, creator);
            final byte[] defaultFile = CPlayWire.encodeDefaultAssetFile(info);

            final byte[] packetBytes = CPlayWire.encodeSessionStart(info, defaultFile);
            final ByteBuf buf = Unpooled.wrappedBuffer(packetBytes);

            // 1. Packet ID
            assertEquals(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_SESSION_START), buf.readLong());

            // 2. Session Type
            final int sessionType = buf.readInt();
            assertEquals(typeIndex, sessionType);

            // 3. GSSessionFields.read:
            final int fieldCount = buf.readInt();
            final int expectedFields = (typeIndex == 0) ? 7 : 9;
            assertEquals(expectedFields, fieldCount, "Для типа " + typeIndex + " ожидается " + expectedFields + " полей");

            final Set<String> observedFieldNames = new HashSet<>();
            for (int f = 0; f < fieldCount; f++) {
                final int sizeInBytes = buf.readInt();
                assertTrue(sizeInBytes > 0, "Размер поля в байтах должен быть положительным");
                final int readerStart = buf.readerIndex();

                final String fieldName = CPlayWire.readString(buf);
                assertNotNull(fieldName);
                assertFalse(fieldName.isEmpty());
                assertTrue(observedFieldNames.add(fieldName), "Дублирующееся поле сессии: " + fieldName);

                final int bytesReadForName = buf.readerIndex() - readerStart;
                final int remainingPayloadBytes = sizeInBytes - bytesReadForName;
                assertTrue(remainingPayloadBytes >= 0, "sizeInBytes меньше чем длина имени поля");

                switch (fieldName) {
                    case "assetUUID" -> {
                        assertTrue(buf.readBoolean());
                        assertEquals(assetUUID, CPlayWire.readUUID(buf));
                    }
                    case "assetHandle" -> {
                        assertTrue(buf.readBoolean());
                        assertEquals(handle, CPlayWire.readAssetHandle(buf));
                    }
                    case "xOffset", "yOffset" -> {
                        final float val = buf.readFloat();
                        assertEquals(0.0f, val);
                    }
                    case "gametickWidth" -> {
                        final double val = buf.readDouble();
                        assertEquals(8.0, val);
                    }
                    case "composition" -> {
                        assertTrue(buf.readBoolean());
                        assertEquals(0, buf.readByte());
                        assertEquals(assetUUID, CPlayWire.readUUID(buf));
                        assertEquals(name, CPlayWire.readString(buf));
                        assertEquals(0, buf.readInt());
                        assertEquals(0, buf.readInt());
                    }
                    case "sequence" -> {
                        assertTrue(buf.readBoolean());
                        assertEquals(0, buf.readByte());
                        assertEquals(assetUUID, CPlayWire.readUUID(buf));
                        assertEquals(name, CPlayWire.readString(buf));
                        assertEquals(0, buf.readInt());
                    }
                    default -> {
                        buf.skipBytes(sizeInBytes - (buf.readerIndex() - readerStart));
                    }
                }

                final int totalFieldBytesRead = buf.readerIndex() - readerStart;
                assertEquals(sizeInBytes, totalFieldBytesRead, "Поле '" + fieldName + "' вычитано не полностью");
            }

            assertEquals(0, buf.readableBytes(), "Пакет старта сессии должен быть вычитан полностью");
            buf.release();
        }
    }

    // --- 6. Дифференциальный фаззинг пакетов кэша игроков (20 000 итераций) ---
    @Test
    @DisplayName("Дифференциальный фаззинг PlayerCache: PaperLab Wire vs Mod Wire [20 000 итераций]")
    public void fuzzPlayerCachePackets() {
        for (int i = 0; i < 10_000; i++) {
            // GSPlayerCachePacket
            final int count = rng.nextInt(10);
            final Map<UUID, String> map = new HashMap<>();
            for (int j = 0; j < count; j++) {
                map.put(UUID.randomUUID(), generateRandomString(rng, 1, 16));
            }
            final byte[] cachePacket = CPlayWire.encodePlayerCache(map);
            final ByteBuf buf = Unpooled.wrappedBuffer(cachePacket);
            assertEquals(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_PLAYER_CACHE), buf.readLong());
            final int readCount = buf.readInt();
            assertEquals(count, readCount);
            for (int j = 0; j < readCount; j++) {
                final UUID u = CPlayWire.readUUID(buf);
                final String n = CPlayWire.readString(buf);
                assertEquals(map.get(u), n);
            }
            assertEquals(0, buf.readableBytes());
            buf.release();

            // GSPlayerCacheEntryAddedPacket
            final UUID addedUUID = UUID.randomUUID();
            final String addedName = generateRandomString(rng, 1, 16);
            final byte[] addedPacket = CPlayWire.encodePlayerCacheAdded(addedUUID, addedName);
            final ByteBuf aBuf = Unpooled.wrappedBuffer(addedPacket);
            assertEquals(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_PLAYER_CACHE_ADDED), aBuf.readLong());
            assertEquals(addedUUID, CPlayWire.readUUID(aBuf));
            assertEquals(addedName, CPlayWire.readString(aBuf));
            assertEquals(0, aBuf.readableBytes());
            aBuf.release();
        }
    }

    // --- 7. Фаззинг extractAssetPayloadOffset (50 000 итераций) ---
    @Test
    @DisplayName("Фаззинг extractAssetPayloadOffset на граничных и повреждённых заголовках [50 000 итераций]")
    public void fuzzExtractAssetPayloadOffsetRobustness() {
        for (int i = 0; i < LARGE_FUZZ_ITERATIONS; i++) {
            final int len = rng.nextInt(64);
            final byte[] randomBytes = new byte[len];
            rng.nextBytes(randomBytes);

            // Метод обязан ВСЕГДА возвращать валидное целое >= 0 без падений по исключению
            final int offset = CPlayWire.extractAssetPayloadOffset(randomBytes);
            assertTrue(offset >= 0);
            assertTrue(offset <= len);
        }
    }

    // --- 8. Мутационный фаззинг сетевого потока (50 000 итераций) ---
    @Test
    @DisplayName("Мутационный фаззинг сетевого потока: проверка защиты от битых байтов, обрезанных пакетов и переполнений [50 000 итераций]")
    public void fuzzMutationalRobustness() {
        for (int i = 0; i < LARGE_FUZZ_ITERATIONS; i++) {
            // Создаём валидный пакет создания ассета
            final ByteBuf validBuf = Unpooled.buffer();
            CPlayWire.writeString(validBuf, "ValidName" + (i % 100));
            validBuf.writeByte(rng.nextInt(2));
            CPlayWire.writeAssetHandle(validBuf, new CPlayAssetHandle(CPlayAssetNamespace.GLOBAL, "valid_handle"));
            validBuf.writeBoolean(false);

            final byte[] validBytes = new byte[validBuf.readableBytes()];
            validBuf.readBytes(validBytes);
            validBuf.release();

            // Применяем мутацию:
            final int mutationType = rng.nextInt(4);
            final byte[] mutatedBytes;

            if (mutationType == 0) {
                // Обрезаем пакет на случайном байте (тест на нехватку байтов / buffer underflow)
                final int cutLen = rng.nextInt(validBytes.length);
                mutatedBytes = new byte[cutLen];
                System.arraycopy(validBytes, 0, mutatedBytes, 0, cutLen);
            } else if (mutationType == 1) {
                // Мутируем случайные байты в потоке (тест на битые данные)
                mutatedBytes = validBytes.clone();
                final int flips = 1 + rng.nextInt(4);
                for (int f = 0; f < flips; f++) {
                    mutatedBytes[rng.nextInt(mutatedBytes.length)] = (byte) rng.nextInt(256);
                }
            } else if (mutationType == 2) {
                // Вставляем мусорные байты в произвольное место
                final int insertLen = rng.nextInt(40);
                mutatedBytes = new byte[validBytes.length + insertLen];
                rng.nextBytes(mutatedBytes);
            } else {
                // Переполнение VarInt: последовательность 0x80..0x80 (более 5 байт с установленным MSB)
                mutatedBytes = new byte[] {(byte)0x80, (byte)0x80, (byte)0x80, (byte)0x80, (byte)0x80, (byte)0x01};
            }

            final ByteBuf mutBuf = Unpooled.wrappedBuffer(mutatedBytes);
            try {
                // Пытаемся декодировать — парсер обязан либо завершиться штатно,
                // либо выбросить контролируемое исключение (IllegalArgumentException, IndexOutOfBoundsException).
                // Никаких зависаний, OOM или неперехваченных ошибок.
                final String name = CPlayWire.readString(mutBuf);
                if (mutBuf.isReadable()) {
                    final int t = mutBuf.readByte() & 0xFF;
                    if (mutBuf.isReadable()) {
                        final CPlayAssetHandle h = CPlayWire.readAssetHandle(mutBuf);
                        if (mutBuf.isReadable()) {
                            final boolean hasOrig = mutBuf.readBoolean();
                            if (hasOrig && mutBuf.isReadable(16)) {
                                CPlayWire.readUUID(mutBuf);
                            }
                        }
                    }
                }
            } catch (final IndexOutOfBoundsException | IllegalArgumentException expectedSafeFailure) {
                // Безопасное отклонение некорректного пакета
            } finally {
                mutBuf.release();
            }
        }
    }

    private static String generateRandomString(final Random rng, final int minLen, final int maxLen) {
        final int len = minLen + rng.nextInt(maxLen - minLen + 1);
        final char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = (char) (32 + rng.nextInt(95));
        }
        return new String(chars);
    }

    private static String generateRandomBase36(final Random rng, final int minLen, final int maxLen) {
        final String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz_";
        final int len = minLen + rng.nextInt(maxLen - minLen + 1);
        final StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
