package paperlab.cplay.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
 * Дифференциальное фаззинг-тестирование сетевого протокола CPlay:
 * Проверка взаимной совместимости, устойчивости к мутациям и граничным условиям
 * между эталонной спецификацией G4mespeed и реализацией PaperLab.
 */
public class CPlayDifferentialFuzzTest {

    private static final int FUZZ_ITERATIONS = 5000;
    private final Random rng = new Random(42L); // Фиксированный сид для воспроизводимости

    // --- 1. Дифференциальный фаззинг генератора и парсера хэндлов ---
    @Test
    @DisplayName("Дифференциальный фаззинг CPlayAssetHandle: генерация, санитизация и граничные строки")
    public void fuzzAssetHandleGenerationAndParsing() {
        final String[] charPool = {
            "a", "z", "0", "9", "_", "-", " ", "!", "@", "#", "$", "%", "^", "&", "*",
            "А", "я", "Ж", "ш", "🔥", "🚀", "\t", "\n", "\0", ".", "/", "\\"
        };

        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            // Генерируем случайную строку произвольной длины от 0 до 60 символов
            final int len = rng.nextInt(60);
            final StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) {
                sb.append(charPool[rng.nextInt(charPool.length)]);
            }
            final String rawName = sb.toString();
            final CPlayAssetNamespace ns = rng.nextBoolean() ? CPlayAssetNamespace.GLOBAL : CPlayAssetNamespace.WORLD;

            // 1. Алгоритм плагина
            final CPlayAssetHandle handle = CPlayAssetHandle.fromName(ns, rawName, "");

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

    // --- 2. Дифференциальный фаззинг пакета создания ассета (GSCreateAssetPacket) ---
    @Test
    @DisplayName("Дифференциальный фаззинг GSCreateAssetPacket: Mod Wire Encoder vs PaperLab Wire Decoder")
    public void fuzzCreateAssetPacketModWireToPaperLab() {
        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            final String randomName = generateRandomString(rng, 1, 50);
            final int typeIndex = rng.nextInt(2); // 0 = COMPOSITION, 1 = SEQUENCE
            final CPlayAssetNamespace ns = rng.nextBoolean() ? CPlayAssetNamespace.GLOBAL : CPlayAssetNamespace.WORLD;
            final String handleStr = generateRandomBase36(rng, 1, 20);
            final CPlayAssetHandle handle = new CPlayAssetHandle(ns, handleStr);
            final boolean hasOriginal = rng.nextBoolean();
            final UUID origUUID = hasOriginal ? UUID.randomUUID() : null;

            // Сериализация в эталонном формате мода GSCreateAssetPacket.write:
            // 1. name (String)
            // 2. type (unsigned byte)
            // 3. handle (GSAssetHandle: ns byte + handle string)
            // 4. hasOriginal (boolean)
            // 5. origUUID (если hasOriginal)
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

    // --- 3. Дифференциальный фаззинг пакета истории ассетов (GSAssetHistoryPacket) ---
    @Test
    @DisplayName("Дифференциальный фаззинг GSAssetHistory: PaperLab Encoder vs Mod GSAssetHistory Decoder")
    public void fuzzAssetHistoryPaperLabEncoderVsModDecoder() {
        for (int i = 0; i < 500; i++) {
            final int assetCount = rng.nextInt(20);
            final List<CPlayAssetInfo> assets = new ArrayList<>();

            for (int j = 0; j < assetCount; j++) {
                final int typeIndex = rng.nextInt(2);
                final UUID assetUUID = UUID.randomUUID();
                final CPlayAssetNamespace ns = rng.nextBoolean() ? CPlayAssetNamespace.GLOBAL : CPlayAssetNamespace.WORLD;
                final CPlayAssetHandle handle = new CPlayAssetHandle(ns, generateRandomBase36(rng, 1, 15));
                final String name = generateRandomString(rng, 1, 30);
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

    // --- 4. Дифференциальный фаззинг сессионных пакетов (GSSessionStartPacket) ---
    @Test
    @DisplayName("Дифференциальный фаззинг GSSessionStart: PaperLab Wire Framing vs Mod GSSessionFields Decoder")
    public void fuzzSessionStartFramingPaperLabVsMod() {
        for (int i = 0; i < 500; i++) {
            final int typeIndex = rng.nextInt(2);
            final UUID assetUUID = UUID.randomUUID();
            final CPlayAssetNamespace ns = rng.nextBoolean() ? CPlayAssetNamespace.GLOBAL : CPlayAssetNamespace.WORLD;
            final CPlayAssetHandle handle = new CPlayAssetHandle(ns, generateRandomBase36(rng, 1, 15));
            final String name = generateRandomString(rng, 1, 25);
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

                // Проверяем, что размер sizeInBytes покрывает fieldName + payload
                final int bytesReadForName = buf.readerIndex() - readerStart;
                final int remainingPayloadBytes = sizeInBytes - bytesReadForName;
                assertTrue(remainingPayloadBytes >= 0, "sizeInBytes меньше чем длина имени поля");

                // Проверяем декодирование конкретных полей
                switch (fieldName) {
                    case "assetUUID" -> {
                        assertTrue(buf.readBoolean()); // Basic codec non-null flag
                        assertEquals(assetUUID, CPlayWire.readUUID(buf));
                    }
                    case "assetHandle" -> {
                        assertTrue(buf.readBoolean()); // Basic codec non-null flag
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
                        assertTrue(buf.readBoolean()); // Basic codec non-null flag
                        assertEquals(0, buf.readByte()); // reserved byte
                        assertEquals(assetUUID, CPlayWire.readUUID(buf));
                        assertEquals(name, CPlayWire.readString(buf));
                        assertEquals(0, buf.readInt()); // groups
                        assertEquals(0, buf.readInt()); // tracks
                    }
                    case "sequence" -> {
                        assertTrue(buf.readBoolean()); // Basic codec non-null flag
                        assertEquals(0, buf.readByte()); // reserved byte
                        assertEquals(assetUUID, CPlayWire.readUUID(buf));
                        assertEquals(name, CPlayWire.readString(buf));
                        assertEquals(0, buf.readInt()); // channels
                    }
                    default -> {
                        buf.skipBytes(sizeInBytes - (buf.readerIndex() - readerStart));
                    }
                }

                // Гарантируем, что вычитано ровно sizeInBytes
                final int totalFieldBytesRead = buf.readerIndex() - readerStart;
                assertEquals(sizeInBytes, totalFieldBytesRead, "Поле '" + fieldName + "' вычитано не полностью");
            }

            assertEquals(0, buf.readableBytes(), "Пакет старта сессии должен быть вычитан полностью");
            buf.release();
        }
    }

    // --- 5. Мутационный фаззинг (Fuzzing с повреждением данных и проверкой защиты) ---
    @Test
    @DisplayName("Мутационный фаззинг: проверка защиты от битых байтов, обрезанных пакетов и переполнений")
    public void fuzzMutationalRobustness() {
        for (int i = 0; i < 2000; i++) {
            // Создаём валидный пакет создания ассета
            final ByteBuf validBuf = Unpooled.buffer();
            CPlayWire.writeString(validBuf, "TestAsset");
            validBuf.writeByte(0);
            CPlayWire.writeAssetHandle(validBuf, new CPlayAssetHandle(CPlayAssetNamespace.GLOBAL, "valid"));
            validBuf.writeBoolean(false);

            final byte[] validBytes = new byte[validBuf.readableBytes()];
            validBuf.readBytes(validBytes);
            validBuf.release();

            // Применяем мутацию:
            final int mutationType = rng.nextInt(3);
            final byte[] mutatedBytes;

            if (mutationType == 0) {
                // Обрезаем пакет на случайном байте (тест на нехватку байтов / buffer underflow)
                final int cutLen = rng.nextInt(validBytes.length);
                mutatedBytes = new byte[cutLen];
                System.arraycopy(validBytes, 0, mutatedBytes, 0, cutLen);
            } else if (mutationType == 1) {
                // Мутируем случайные байты в потоке (тест на мусор)
                mutatedBytes = validBytes.clone();
                final int flips = 1 + rng.nextInt(3);
                for (int f = 0; f < flips; f++) {
                    mutatedBytes[rng.nextInt(mutatedBytes.length)] = (byte) rng.nextInt(256);
                }
            } else {
                // Вставляем мусорные байты в произвольное место
                final int insertLen = rng.nextInt(30);
                mutatedBytes = new byte[validBytes.length + insertLen];
                rng.nextBytes(mutatedBytes);
            }

            final ByteBuf mutBuf = Unpooled.wrappedBuffer(mutatedBytes);
            try {
                // Пытаемся декодировать — парсер может либо успешно распарсить (если мутация допустима),
                // либо выбросить контролируемое исключение (IndexOutOfBoundsException, IllegalArgumentException).
                // КРИТИЧНО: парсер НЕ ДОЛЖЕН зависать в бесконечном цикле или падать по Fatal Error / OOM.
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
            // Случайный printable ASCII символ
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
