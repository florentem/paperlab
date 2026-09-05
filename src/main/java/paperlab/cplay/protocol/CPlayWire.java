package paperlab.cplay.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import paperlab.cplay.model.CPlayAssetHandle;
import paperlab.cplay.model.CPlayAssetInfo;
import paperlab.cplay.model.CPlayAssetNamespace;

public final class CPlayWire {

    private static final int BIT_SIZE_X = 26;
    private static final int BIT_SIZE_Z = 26;
    private static final int BIT_SIZE_Y = 64 - BIT_SIZE_X - BIT_SIZE_Z;
    private static final long BIT_MASK_X = (1L << BIT_SIZE_X) - 1L;
    private static final long BIT_MASK_Y = (1L << BIT_SIZE_Y) - 1L;
    private static final long BIT_MASK_Z = (1L << BIT_SIZE_Z) - 1L;

    private CPlayWire() {
    }

    // --- Базовые операции сериализации Netty ByteBuf ---

    public static void writeVarInt(final ByteBuf buf, int value) {
        while (true) {
            if ((value & 0xFFFFFF80) == 0) {
                buf.writeByte((byte) value);
                return;
            }
            buf.writeByte((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    public static int readVarInt(final ByteBuf buf) {
        byte data;
        int value = 0;
        int count = 0;
        do {
            if (count >= 5) {
                throw new RuntimeException("VarInt too big");
            }
            data = buf.readByte();
            value |= (data & 0x7F) << (7 * count);
            count++;
        } while ((data & 0x80) != 0);
        return value;
    }

    public static void writeString(final ByteBuf buf, final String s) {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public static String readString(final ByteBuf buf) {
        final int len = readVarInt(buf);
        if (len < 0 || len > 32767) {
            throw new IllegalArgumentException("String length " + len + " out of bounds (0-32767)");
        }
        if (buf.readableBytes() < len) {
            throw new IllegalArgumentException("Buffer underflow reading string of length " + len + ", readable: " + buf.readableBytes());
        }
        final byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeUUID(final ByteBuf buf, final UUID u) {
        buf.writeLong(u.getMostSignificantBits());
        buf.writeLong(u.getLeastSignificantBits());
    }

    public static UUID readUUID(final ByteBuf buf) {
        final long most = buf.readLong();
        final long least = buf.readLong();
        return new UUID(most, least);
    }

    public static void writeBlockPos(final ByteBuf buf, final BlockPos pos) {
        long val = 0L;
        val |= ((long) pos.getX() & BIT_MASK_X) << (BIT_SIZE_Z + BIT_SIZE_Y);
        val |= ((long) pos.getY() & BIT_MASK_Y);
        val |= ((long) pos.getZ() & BIT_MASK_Z) << BIT_SIZE_Y;
        buf.writeLong(val);
    }

    public static BlockPos readBlockPos(final ByteBuf buf) {
        final long val = buf.readLong();
        final int x = (int) ((val << (64 - BIT_SIZE_X - BIT_SIZE_Z - BIT_SIZE_Y)) >> (64 - BIT_SIZE_X));
        final int y = (int) ((val << (64 - BIT_SIZE_Y)) >> (64 - BIT_SIZE_Y));
        final int z = (int) ((val << (64 - BIT_SIZE_Z - BIT_SIZE_Y)) >> (64 - BIT_SIZE_Z));
        return new BlockPos(x, y, z);
    }

    // --- Пакеты рукопожатия и ассетов ---

    public static byte[] encodeConnectionPacket() {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CORE_UID, CPlayProtocol.PACKET_CORE_CONNECTION));

        // 2 расширения: Core 1.6.1 и Capture & Playback 0.8.0
        buf.writeInt(2);

        // Core
        writeString(buf, "Core");
        buf.writeInt(CPlayProtocol.CORE_UID);
        buf.writeShort(1); // major
        buf.writeShort(6); // minor
        buf.writeShort(1); // patch

        // Capture & Playback
        writeString(buf, "Capture & Playback");
        buf.writeInt(CPlayProtocol.CAPL_UID);
        buf.writeShort(0); // major
        buf.writeShort(8); // minor
        buf.writeShort(0); // patch

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static byte[] encodeAssetHistory(final Collection<CPlayAssetInfo> assets) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_ASSET_HISTORY));

        buf.writeByte(0); // fileVersion
        buf.writeInt(assets.size());

        for (final CPlayAssetInfo info : assets) {
            writeAssetInfo(buf, info);
        }

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static byte[] encodeAssetInfoChanged(final CPlayAssetInfo info) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_ASSET_INFO_CHANGED));
        writeAssetInfo(buf, info);

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static byte[] encodeAssetInfoRemoved(final UUID assetUUID) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_ASSET_INFO_REMOVED));
        writeUUID(buf, assetUUID);

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static void writeAssetHandle(final ByteBuf buf, final CPlayAssetHandle handle) {
        buf.writeByte((byte) handle.getNamespace().getIndex());
        writeString(buf, handle.getHandle());
    }

    public static CPlayAssetHandle readAssetHandle(final ByteBuf buf) {
        final int nsIndex = buf.readByte() & 0xFF;
        final CPlayAssetNamespace ns = CPlayAssetNamespace.fromIndex(nsIndex);
        if (ns == null) {
            throw new IllegalArgumentException("Unknown namespace index: " + nsIndex);
        }
        final String handleStr = readString(buf);
        return new CPlayAssetHandle(ns, handleStr);
    }

    public static void writeAssetInfo(final ByteBuf buf, final CPlayAssetInfo info) {
        buf.writeByte((byte) info.getTypeIndex());
        writeUUID(buf, info.getAssetUUID());

        // GSAssetHandle
        if (info.getHandle() != null) {
            writeAssetHandle(buf, info.getHandle());
        } else {
            writeAssetHandle(buf, new CPlayAssetHandle(CPlayAssetNamespace.GLOBAL, "default"));
        }

        writeString(buf, info.getAssetName());
        buf.writeLong(info.getCreatedTimestamp());
        buf.writeLong(info.getLastModifiedTimestamp());
        writeUUID(buf, info.getCreatedByUUID());
        writeUUID(buf, info.getOwnerUUID());

        final Set<UUID> collabs = info.getCollaboratorUUIDs();
        buf.writeInt(collabs.size());
        for (final UUID collab : collabs) {
            writeUUID(buf, collab);
        }
    }

    public static CPlayAssetInfo readAssetInfo(final ByteBuf buf) {
        final int typeIndex = buf.readByte() & 0xFF;
        final UUID assetUUID = readUUID(buf);
        final CPlayAssetHandle handle = readAssetHandle(buf);
        final String name = readString(buf);
        final long created = buf.readLong();
        final long modified = buf.readLong();
        final UUID createdBy = readUUID(buf);
        final UUID owner = readUUID(buf);

        final CPlayAssetInfo info = new CPlayAssetInfo(typeIndex, assetUUID, handle, name, created, modified, createdBy, owner);
        int collabCount = buf.readInt();
        if (collabCount < 0 || collabCount > 10000 || buf.readableBytes() < collabCount * 16) {
            throw new IllegalArgumentException("Invalid collaborator count: " + collabCount);
        }
        while (collabCount-- > 0) {
            info.addCollaborator(readUUID(buf));
        }
        return info;
    }

    public static byte[] encodePlayerCache(final Map<UUID, String> players) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_PLAYER_CACHE));

        buf.writeInt(players.size());
        for (final Map.Entry<UUID, String> entry : players.entrySet()) {
            writeUUID(buf, entry.getKey());
            writeString(buf, entry.getValue());
        }

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static byte[] encodePlayerCacheAdded(final UUID uuid, final String name) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_PLAYER_CACHE_ADDED));
        writeUUID(buf, uuid);
        writeString(buf, name);

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static byte[] encodePlayerCacheRemoved(final UUID uuid) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_PLAYER_CACHE_REMOVED));
        writeUUID(buf, uuid);

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static byte[] encodeAssetRequestResponseSuccess(final byte[] fileContent) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_ASSET_REQUEST_RESPONSE));
        buf.writeByte(1); // SUCCESS
        buf.writeBytes(fileContent);

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static byte[] encodeAssetRequestResponseDenied(final UUID assetUUID) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_ASSET_REQUEST_RESPONSE));
        buf.writeByte(0); // DENIED
        writeUUID(buf, assetUUID);

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static int extractAssetPayloadOffset(final byte[] fileData) {
        if (fileData == null || fileData.length < 2) return 0;
        final ByteBuf buf = Unpooled.wrappedBuffer(fileData);
        try {
            int formatVersion = buf.readByte() & 0xFF;
            if ((formatVersion & 0x80) != 0) {
                formatVersion &= ~0x80;
                buf.readByte(); // typeIndex
            }
            buf.readLong(); // createdTimestamp
            readUUID(buf); // createdByUUID
            if (formatVersion >= 1) {
                final boolean hasPlayerEntry = buf.readBoolean();
                if (hasPlayerEntry) {
                    readString(buf); // entry name
                }
            }
            return buf.readerIndex();
        } catch (final Exception e) {
            return 0;
        } finally {
            buf.release();
        }
    }

    public static byte[] encodeDefaultAssetFile(final CPlayAssetInfo info) {
        final ByteBuf buf = Unpooled.buffer();
        // GSAssetFileHeader: version 0x81 (0x80 | 1)
        buf.writeByte((byte) (0x80 | 1));
        buf.writeByte((byte) info.getTypeIndex());
        buf.writeLong(info.getCreatedTimestamp());
        writeUUID(buf, info.getCreatedByUUID());
        buf.writeBoolean(false); // no player cache entry

        // Asset payload
        if (info.getTypeIndex() == 0) {
            // Empty GSComposition
            buf.writeByte(0); // reserved byte
            writeUUID(buf, info.getAssetUUID());
            writeString(buf, info.getAssetName());
            buf.writeInt(0); // groupCount
            buf.writeInt(0); // trackCount
        } else {
            // Empty GSSequence
            buf.writeByte(0); // reserved byte
            writeUUID(buf, info.getAssetUUID());
            writeString(buf, info.getAssetName());
            buf.writeInt(0); // channelCount
        }

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static byte[] encodeSessionStart(final CPlayAssetInfo info, final byte[] existingAssetData) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_SESSION_START));

        final int typeIndex = info.getTypeIndex();
        buf.writeInt(typeIndex);

        final UUID assetUUID = info.getAssetUUID();
        final CPlayAssetHandle handle = (info.getHandle() != null) ? info.getHandle() : new CPlayAssetHandle(CPlayAssetNamespace.GLOBAL, "default");
        final String name = info.getAssetName();

        if (typeIndex == 0) {
            // COMPOSITION (7 fields)
            buf.writeInt(7);

            // 1. assetUUID
            writeSessionField(buf, "assetUUID", fBuf -> {
                fBuf.writeBoolean(true);
                writeUUID(fBuf, assetUUID);
            });

            // 2. assetHandle
            writeSessionField(buf, "assetHandle", fBuf -> {
                fBuf.writeBoolean(true);
                writeAssetHandle(fBuf, handle);
            });

            // 3. xOffset
            writeSessionField(buf, "xOffset", fBuf -> fBuf.writeFloat(0.0f));

            // 4. yOffset
            writeSessionField(buf, "yOffset", fBuf -> fBuf.writeFloat(0.0f));

            // 5. undoRedoHistory
            writeSessionField(buf, "undoRedoHistory", fBuf -> {
                fBuf.writeBoolean(true);
                fBuf.writeInt(0);
                fBuf.writeInt(0);
            });

            // 6. composition
            writeSessionField(buf, "composition", fBuf -> {
                fBuf.writeBoolean(true);
                if (existingAssetData != null && existingAssetData.length > 27) {
                    final int payloadOffset = extractAssetPayloadOffset(existingAssetData);
                    fBuf.writeBytes(existingAssetData, payloadOffset, existingAssetData.length - payloadOffset);
                } else {
                    fBuf.writeByte(0); // reserved byte
                    writeUUID(fBuf, assetUUID);
                    writeString(fBuf, name);
                    fBuf.writeInt(0); // groupCount
                    fBuf.writeInt(0); // trackCount
                }
            });

            // 7. gametickWidth
            writeSessionField(buf, "gametickWidth", fBuf -> fBuf.writeDouble(8.0));
        } else {
            // SEQUENCE (9 fields)
            buf.writeInt(9);

            // 1. assetUUID
            writeSessionField(buf, "assetUUID", fBuf -> {
                fBuf.writeBoolean(true);
                writeUUID(fBuf, assetUUID);
            });

            // 2. assetHandle
            writeSessionField(buf, "assetHandle", fBuf -> {
                fBuf.writeBoolean(true);
                writeAssetHandle(fBuf, handle);
            });

            // 3. xOffset
            writeSessionField(buf, "xOffset", fBuf -> fBuf.writeFloat(0.0f));

            // 4. yOffset
            writeSessionField(buf, "yOffset", fBuf -> fBuf.writeFloat(0.0f));

            // 5. undoRedoHistory
            writeSessionField(buf, "undoRedoHistory", fBuf -> {
                fBuf.writeBoolean(true);
                fBuf.writeInt(0);
                fBuf.writeInt(0);
            });

            // 6. sequence
            writeSessionField(buf, "sequence", fBuf -> {
                fBuf.writeBoolean(true);
                if (existingAssetData != null && existingAssetData.length > 27) {
                    final int payloadOffset = extractAssetPayloadOffset(existingAssetData);
                    fBuf.writeBytes(existingAssetData, payloadOffset, existingAssetData.length - payloadOffset);
                } else {
                    fBuf.writeByte(0); // reserved byte
                    writeUUID(fBuf, assetUUID);
                    writeString(fBuf, name);
                    fBuf.writeInt(0); // channelCount
                }
            });

            // 7. selectedChannel
            writeSessionField(buf, "selectedChannel", fBuf -> fBuf.writeBoolean(false)); // null

            // 8. minExpandedColumn
            writeSessionField(buf, "minExpandedColumn", fBuf -> fBuf.writeInt(-1));

            // 9. maxExpandedColumn
            writeSessionField(buf, "maxExpandedColumn", fBuf -> fBuf.writeInt(-1));
        }

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static byte[] encodeSessionStop(final UUID assetUUID) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_SESSION_STOP));
        writeUUID(buf, assetUUID);

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static byte[] encodeSessionDeltas(final UUID assetUUID, final byte[] rawDeltas) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeLong(CPlayProtocol.makePacketId(CPlayProtocol.CAPL_UID, CPlayProtocol.PACKET_CAPL_SESSION_DELTAS));
        writeUUID(buf, assetUUID);
        buf.writeBytes(rawDeltas);

        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static void writeSessionField(final ByteBuf parentBuf, final String name, final java.util.function.Consumer<ByteBuf> writer) {
        final ByteBuf fieldBuf = Unpooled.buffer();
        writeString(fieldBuf, name);
        writer.accept(fieldBuf);

        parentBuf.writeInt(fieldBuf.readableBytes());
        parentBuf.writeBytes(fieldBuf);
        fieldBuf.release();
    }
}
