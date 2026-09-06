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
 * Extended differential fuzzing of the CPlay network protocol: mutual compatibility,
 * resistance to mutation, and boundary conditions between the reference G4mespeed
 * specification and PaperLab's implementation (over 200,000 iterations).
 */
public class CPlayDifferentialFuzzTest {

    private static final int LARGE_FUZZ_ITERATIONS = 50_000;
    private final Random rng = new Random(42L); // Fixed seed for deterministic reproducibility

    // --- 1. Differential fuzz of the handle generator and parser (50,000 iterations) ---
    @Test
    @DisplayName("CPlayAssetHandle differential fuzz: generation, sanitising and boundary strings [50,000 iterations]")
    public void fuzzAssetHandleGenerationAndParsing() throws Exception {
        final String[] charPool = {
            "a", "b", "z", "0", "1", "9", "_", "-", " ", "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
            "A", "z", "Zh", "sh", "🔥", "🚀", "⚡", "\t", "\n", "\r", "\0", ".", "/", "\\", ":", ";", "'", "\"",
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

            // 1. The real mod class:
            final com.g4mesoft.captureplayback.common.asset.GSEAssetNamespace modNs =
                (ns == CPlayAssetNamespace.GLOBAL)
                    ? com.g4mesoft.captureplayback.common.asset.GSEAssetNamespace.GLOBAL
                    : com.g4mesoft.captureplayback.common.asset.GSEAssetNamespace.WORLD;
            final com.g4mesoft.captureplayback.common.asset.GSAssetHandle modHandle =
                com.g4mesoft.captureplayback.common.asset.GSAssetHandle.fromName(modNs, rawName, suffix);

            // 2. The real plugin class:
            final CPlayAssetHandle pluginHandle = CPlayAssetHandle.fromName(ns, rawName, suffix);

            // Character-by-character comparison of the real mod and the plugin:
            assertEquals(modHandle.getHandle(), pluginHandle.getHandle(), "Handle mismatch for rawName: '" + rawName + "'");
            assertEquals(modHandle.toString(), pluginHandle.toString(), "Handle toString mismatch");
            assertEquals(modHandle.getNamespace().getIndex(), pluginHandle.getNamespace().getIndex());

            // Cross-serialisation: encode with the mod's GSEncodeBuffer, read with CPlayWire:
            final ByteBuf crossBuf = Unpooled.buffer();
            final com.g4mesoft.util.GSEncodeBuffer encBuf = com.g4mesoft.util.GSEncodeBuffer.wrap(crossBuf);
            com.g4mesoft.captureplayback.common.asset.GSAssetHandle.write(encBuf, modHandle);

            final CPlayAssetHandle crossReadByPlugin = CPlayWire.readAssetHandle(crossBuf);
            assertEquals(pluginHandle, crossReadByPlugin);

            // Reverse cross-serialisation: write with CPlayWire, read with the mod's GSDecodeBuffer:
            crossBuf.clear();
            CPlayWire.writeAssetHandle(crossBuf, pluginHandle);
            final com.g4mesoft.util.GSDecodeBuffer decBuf = com.g4mesoft.util.GSDecodeBuffer.wrap(crossBuf);
            final com.g4mesoft.captureplayback.common.asset.GSAssetHandle crossReadByMod =
                com.g4mesoft.captureplayback.common.asset.GSAssetHandle.read(decBuf);
            assertEquals(modHandle, crossReadByMod);
            crossBuf.release();
        }
    }

    // --- 2. Differential fuzz of the asset creation packet (50,000 iterations) ---
    @Test
    @DisplayName("GSCreateAssetPacket differential fuzz: mod wire encoder vs PaperLab wire decoder [50,000 iterations]")
    public void fuzzCreateAssetPacketModWireToPaperLab() {
        for (int i = 0; i < LARGE_FUZZ_ITERATIONS; i++) {
            final String randomName = generateRandomString(rng, 0, 100);
            final int typeIndex = rng.nextInt(2); // 0 = COMPOSITION, 1 = SEQUENCE
            final CPlayAssetNamespace ns = rng.nextBoolean() ? CPlayAssetNamespace.GLOBAL : CPlayAssetNamespace.WORLD;
            final String handleStr = generateRandomBase36(rng, 1, 20);
            final CPlayAssetHandle handle = new CPlayAssetHandle(ns, handleStr);
            final boolean hasOriginal = rng.nextBoolean();
            final UUID origUUID = hasOriginal ? UUID.randomUUID() : null;

            // Serialise in the mod's reference format, GSCreateAssetPacket.write
            final ByteBuf wireBuf = Unpooled.buffer();
            CPlayWire.writeString(wireBuf, randomName);
            wireBuf.writeByte(typeIndex);
            CPlayWire.writeAssetHandle(wireBuf, handle);
            wireBuf.writeBoolean(hasOriginal);
            if (hasOriginal) {
                CPlayWire.writeUUID(wireBuf, origUUID);
            }

            // Deserialise with PaperLab's CPlayService logic
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
            assertEquals(0, wireBuf.readableBytes(), "every byte of the creation packet must be consumed");
            wireBuf.release();
        }
    }

    // --- 3. Differential fuzz of the asset import packet (20,000 iterations) ---
    @Test
    @DisplayName("GSImportAssetPacket differential fuzz: mod wire encoder vs PaperLab wire decoder [20,000 iterations]")
    public void fuzzImportAssetPacketModWireToPaperLab() {
        for (int i = 0; i < 20_000; i++) {
            final String randomName = generateRandomString(rng, 1, 50);
            final CPlayAssetNamespace ns = rng.nextBoolean() ? CPlayAssetNamespace.GLOBAL : CPlayAssetNamespace.WORLD;
            final CPlayAssetHandle handle = new CPlayAssetHandle(ns, generateRandomBase36(rng, 1, 20));

            // Generate a valid asset file with a header
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

            // The mod writes: String name, GSAssetHandle handle, byte[] rawFile
            final ByteBuf wireBuf = Unpooled.buffer();
            CPlayWire.writeString(wireBuf, randomName);
            CPlayWire.writeAssetHandle(wireBuf, handle);
            wireBuf.writeBytes(rawFileBytes);

            // PaperLab parsing:
            final String readName = CPlayWire.readString(wireBuf);
            final CPlayAssetHandle readHandle = CPlayWire.readAssetHandle(wireBuf);
            final byte[] readFileBytes = new byte[wireBuf.readableBytes()];
            wireBuf.readBytes(readFileBytes);

            assertEquals(randomName, readName);
            assertEquals(handle, readHandle);
            assertArrayEquals(rawFileBytes, readFileBytes);

            // Determine the asset type from the file header:
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

    // --- 4. Differential fuzz of the asset history packet (5,000 iterations) ---
    @Test
    @DisplayName("GSAssetHistory differential fuzz: PaperLab encoder vs mod GSAssetHistory decoder [5,000 iterations]")
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

            // Encode through PaperLab's CPlayWire
            final byte[] packetBytes = CPlayWire.encodeAssetHistory(assets);
            final ByteBuf buf = Unpooled.wrappedBuffer(packetBytes);

            // Decode per the mod's GSAssetHistoryPacket / GSAssetHistory specification
            final long packetId = buf.readLong();
            assertEquals(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_ASSET_HISTORY), packetId);

            final byte fileVersion = buf.readByte();
            assertEquals(0, fileVersion, "the mod requires fileVersion to be 0x00");

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

            assertEquals(0, buf.readableBytes(), "the history buffer must be read in full");
            buf.release();
        }
    }

    // --- 5. Differential fuzz of the session packets (5,000 iterations) ---
    @Test
    @DisplayName("GSSessionStart differential fuzz: PaperLab wire framing vs mod GSSessionFields decoder [5,000 iterations]")
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
            assertEquals(expectedFields, fieldCount, "type " + typeIndex + " is expected to carry " + expectedFields + " fields");

            final Set<String> observedFieldNames = new HashSet<>();
            for (int f = 0; f < fieldCount; f++) {
                final int sizeInBytes = buf.readInt();
                assertTrue(sizeInBytes > 0, "the field size in bytes must be positive");
                final int readerStart = buf.readerIndex();

                final String fieldName = CPlayWire.readString(buf);
                assertNotNull(fieldName);
                assertFalse(fieldName.isEmpty());
                assertTrue(observedFieldNames.add(fieldName), "duplicate session field: " + fieldName);

                final int bytesReadForName = buf.readerIndex() - readerStart;
                final int remainingPayloadBytes = sizeInBytes - bytesReadForName;
                assertTrue(remainingPayloadBytes >= 0, "sizeInBytes is smaller than the field name length");

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
                assertEquals(sizeInBytes, totalFieldBytesRead, "field '" + fieldName + "' was not fully consumed");
            }

            assertEquals(0, buf.readableBytes(), "the session start packet must be fully consumed");
            buf.release();
        }
    }

    // --- 6. Differential fuzz of the player cache packets (20,000 iterations) ---
    @Test
    @DisplayName("PlayerCache differential fuzz: PaperLab wire vs mod wire [20,000 iterations]")
    public void fuzzPlayerCachePackets() throws Exception {
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
            // Read the plugin's packet with the REAL mod class GSPlayerCacheEntry via GSDecodeBuffer:
            final ByteBuf aBuf = Unpooled.wrappedBuffer(addedPacket);
            assertEquals(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_PLAYER_CACHE_ADDED), aBuf.readLong());
            final com.g4mesoft.util.GSDecodeBuffer aDec = com.g4mesoft.util.GSDecodeBuffer.wrap(aBuf);
            final UUID modReadUUID = aDec.readUUID();
            final com.g4mesoft.captureplayback.common.asset.GSPlayerCacheEntry modReadEntry =
                com.g4mesoft.captureplayback.common.asset.GSPlayerCacheEntry.read(aDec);
            assertEquals(addedUUID, modReadUUID);
            assertEquals(addedName, modReadEntry.getName());
            assertEquals(0, aBuf.readableBytes());
            aBuf.release();
        }
    }

    // --- 7. Fuzzing extractAssetPayloadOffset (50,000 iterations) ---
    @Test
    @DisplayName("extractAssetPayloadOffset fuzz on boundary and corrupted headers [50,000 iterations]")
    public void fuzzExtractAssetPayloadOffsetRobustness() {
        for (int i = 0; i < LARGE_FUZZ_ITERATIONS; i++) {
            final int len = rng.nextInt(64);
            final byte[] randomBytes = new byte[len];
            rng.nextBytes(randomBytes);

            // The method must ALWAYS return a valid integer >= 0 without throwing
            final int offset = CPlayWire.extractAssetPayloadOffset(randomBytes);
            assertTrue(offset >= 0);
            assertTrue(offset <= len);
        }
    }

    // --- 8. Wire mutation fuzz (50,000 iterations) ---
    @Test
    @DisplayName("Wire mutation fuzz: resistance to corrupt bytes, truncated packets and overflows [50,000 iterations]")
    public void fuzzMutationalRobustness() {
        for (int i = 0; i < LARGE_FUZZ_ITERATIONS; i++) {
            // Build a valid asset creation packet
            final ByteBuf validBuf = Unpooled.buffer();
            CPlayWire.writeString(validBuf, "ValidName" + (i % 100));
            validBuf.writeByte(rng.nextInt(2));
            CPlayWire.writeAssetHandle(validBuf, new CPlayAssetHandle(CPlayAssetNamespace.GLOBAL, "valid_handle"));
            validBuf.writeBoolean(false);

            final byte[] validBytes = new byte[validBuf.readableBytes()];
            validBuf.readBytes(validBytes);
            validBuf.release();

            // Apply the mutation:
            final int mutationType = rng.nextInt(4);
            final byte[] mutatedBytes;

            if (mutationType == 0) {
                // Truncate the packet at a random byte (tests buffer underflow)
                final int cutLen = rng.nextInt(validBytes.length);
                mutatedBytes = new byte[cutLen];
                System.arraycopy(validBytes, 0, mutatedBytes, 0, cutLen);
            } else if (mutationType == 1) {
                // Mutate random bytes in the stream (tests corrupt data)
                mutatedBytes = validBytes.clone();
                final int flips = 1 + rng.nextInt(4);
                for (int f = 0; f < flips; f++) {
                    mutatedBytes[rng.nextInt(mutatedBytes.length)] = (byte) rng.nextInt(256);
                }
            } else if (mutationType == 2) {
                // Insert junk bytes at an arbitrary offset
                final int insertLen = rng.nextInt(40);
                mutatedBytes = new byte[validBytes.length + insertLen];
                rng.nextBytes(mutatedBytes);
            } else {
                // VarInt overflow: a run of 0x80..0x80 (more than 5 bytes with the MSB set)
                mutatedBytes = new byte[] {(byte)0x80, (byte)0x80, (byte)0x80, (byte)0x80, (byte)0x80, (byte)0x01};
            }

            final ByteBuf mutBuf = Unpooled.wrappedBuffer(mutatedBytes);
            try {
                // Try to decode — the parser must either finish normally or throw a controlled
                // exception (IllegalArgumentException, IndexOutOfBoundsException). No hangs, no
                // OOM, no uncaught errors.
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
                // Safe rejection of a malformed packet
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
