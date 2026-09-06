package paperlab.servux;

import io.netty.buffer.Unpooled;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Servux packet framing.
 *
 * <p>Recovered from the Servux sources. The layout is the same across all of its channels
 * and, importantly, <b>not the same for different packet types</b> — an easy place to get
 * it wrong:
 *
 * <pre>
 * varint packetType
 * metadata types (1 and 2):  network NBT, as FriendlyByteBuf.writeNbt
 * every other type:          int length + a GZIP stream of named NBT (root name "")
 * </pre>
 *
 * <p>The second variant is {@code DataByteBufUtils.toByteBuf}: it writes a length and then
 * gzipped NBT in the classic (named) format. Vanilla {@code NbtIo.writeCompressed} does
 * exactly the same: type, empty name, body, all under GZIP.
 *
 * <p>The client never reports a parse error: on a byte mismatch MiniHUD simply stops
 * treating the server as "server-side". That is why framing lives here, in one place.
 */
public final class ServuxWire {

    /** NBT read quota: how many bytes a client may send in a single packet. */
    private static final long READ_LIMIT = 2 * 1024 * 1024L;

    private ServuxWire() {
    }

    /** Packet type — the first field of every frame. */
    public static int readType(final byte[] data) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(data)).readVarInt();
    }

    /** Body of a metadata packet: network NBT right after the type. */
    public static CompoundTag readNetworkNbt(final byte[] data) {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        buf.readVarInt();
        final CompoundTag tag = buf.readNbt();
        return tag == null ? new CompoundTag() : tag;
    }

    /**
     * Body of an ordinary packet — read leniently, trying the known variants.
     *
     * <p>This is not laziness. Servux writes "length + GZIP of named NBT" and reads the
     * same, but the <b>client</b> takes its writer from malilib, and MiniHUD's bytes do not
     * fit that format: the length reads as 167837699 for a 22-byte packet. Guessing which
     * malilib variant is in play costs more than trying three — especially as trying is
     * harmless: a wrong variant fails on the very first byte.
     *
     * <p>Order: length + GZIP (as Servux writes), length + uncompressed NBT (Servux's own
     * fallback), then network NBT (as vanilla {@code writeNbt}).
     *
     * <p>If nothing fits we throw, with a hex dump. Silently returning an empty compound
     * would be worse: the channel would appear to work while the data went missing.
     */
    public static CompoundTag readCompressedNbt(final byte[] data) throws IOException {
        final int prefix = varIntLength(data);

        // 1. Network NBT right after the type (what MiniHUD / Litematica send).
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            buf.readVarInt();
            final CompoundTag tag = buf.readNbt();
            if (tag != null) {
                return tag;
            }
        } catch (final Throwable ignored) {
            // try the next variant
        }

        // 2. Length + GZIP.
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            buf.readVarInt();
            final int length = buf.readInt();
            if (length >= 0 && length <= buf.readableBytes()) {
                final byte[] body = new byte[length];
                buf.readBytes(body);
                return NbtIo.readCompressed(new ByteArrayInputStream(body),
                    NbtAccounter.create(READ_LIMIT));
            }
        } catch (final Throwable ignored) {
            // try the next variant
        }

        // 3. Length + uncompressed named NBT.
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            buf.readVarInt();
            final int length = buf.readInt();
            if (length >= 0 && length <= buf.readableBytes()) {
                final byte[] body = new byte[length];
                buf.readBytes(body);
                return NbtIo.read(new DataInputStream(new ByteArrayInputStream(body)));
            }
        } catch (final Throwable ignored) {
            // nothing matched
        }

        throw new IOException("unknown NBT framing, prefix " + prefix
            + " bytes, body: " + hex(data));
    }

    /**
     * Parse a body <b>without</b> the type prefix — what the reassembler produced.
     *
     * <p>The same fallback chain, for the same reason: malilib has two write overloads and
     * they produce different formats. Small HUD-channel packets arrive as network NBT, but
     * a large Litematica body does not: {@code readNbt} fails on it with
     * {@code ReportedNbtException}. Hence "length + GZIP" as the first candidate.
     *
     * @param label what is being parsed — appears in the log on failure
     * @return the parsed compound; which variant worked is visible via {@link #lastVariant()}
     */
    public static CompoundTag readBody(final byte[] body, final String label) throws IOException {
        // 0. varint + network NBT — what malilib actually sends.
        //
        // A dump of a real Litematica request began like this:
        //   ff ff ff ff 0f | 0a | 0a 00 10 "RenderLayerRange" ...
        // The first five bytes are a varint holding -1 (apparently "length unknown"),
        // followed by ordinary network NBT: 0x0a is TAG_Compound, then the first field.
        // Without the dump this variant was unguessable: it matches no overload in the
        // Servux sources.
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(body));
            buf.readVarInt();
            final CompoundTag tag = buf.readNbt();
            if (tag != null && !tag.isEmpty()) {
                lastVariant = "varint+network";
                return tag;
            }
        } catch (final Throwable ignored) {
            // try the next variant
        }

        // 1. Length + GZIP.
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(body));
            final int length = buf.readInt();
            if (length >= 0 && length <= buf.readableBytes()) {
                final byte[] inner = new byte[length];
                buf.readBytes(inner);
                final CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(inner),
                    NbtAccounter.create(READ_LIMIT));
                lastVariant = "length+gzip";
                return tag;
            }
        } catch (final Throwable ignored) {
            // try the next variant
        }

        // 2. Length + uncompressed named NBT.
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(body));
            final int length = buf.readInt();
            if (length >= 0 && length <= buf.readableBytes()) {
                final byte[] inner = new byte[length];
                buf.readBytes(inner);
                final CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(inner)));
                lastVariant = "length+named";
                return tag;
            }
        } catch (final Throwable ignored) {
            // try the next variant
        }

        // 3. Network NBT from the start of the body.
        try {
            final CompoundTag tag = new FriendlyByteBuf(Unpooled.wrappedBuffer(body)).readNbt();
            if (tag != null) {
                lastVariant = "network";
                return tag;
            }
        } catch (final Throwable ignored) {
            // try the next variant
        }

        // 4. GZIP with no length prefix.
        try {
            final CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(body),
                NbtAccounter.create(READ_LIMIT));
            lastVariant = "gzip";
            return tag;
        } catch (final Throwable ignored) {
            // nothing matched
        }

        throw new IOException(label + ": unknown body framing, " + body.length
            + " bytes, head: " + hex(body));
    }

    private static volatile String lastVariant = "?";

    /** Which parse variant worked last. Used for logging only. */
    public static String lastVariant() {
        return lastVariant;
    }

    /** How many bytes the type varint took: needed only for the error message. */
    private static int varIntLength(final byte[] data) {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        final int before = buf.readableBytes();
        buf.readVarInt();
        return before - buf.readableBytes();
    }

    /** A dump for working out an unfamiliar format. Truncated: nobody reads a long one. */
    private static String hex(final byte[] data) {
        final StringBuilder out = new StringBuilder();
        final int limit = Math.min(data.length, 48);
        for (int i = 0; i < limit; i++) {
            out.append(String.format("%02x ", data[i]));
        }
        if (data.length > limit) {
            out.append("... (").append(data.length).append(" bytes)");
        }
        return out.toString().trim();
    }

    /**
     * Append network NBT to a buffer.
     */
    public static void appendNbtBody(final FriendlyByteBuf buf, final CompoundTag tag) {
        buf.writeNbt(tag);
    }

    /** Build a metadata frame. */
    public static byte[] metadata(final int type, final CompoundTag tag) {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(type);
        buf.writeNbt(tag);
        return drain(buf);
    }

    /**
     * Build a Servux data frame: varint(type) + network NBT.
     *
     * <p>The MiniHUD client reads every packet type (SPAWN_DATA, WEATHER_TICK,
     * DATA_LOGGER_TICK) through {@code FriendlyByteBuf.readNbt()}. Any length prefix or GZIP
     * leaves unread bytes in the Netty buffer and gets the client kicked with a
     * DecoderException.
     */
    public static byte[] data(final int type, final CompoundTag tag) {
        return metadata(type, tag);
    }

    private static byte[] drain(final FriendlyByteBuf buf) {
        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }
}
