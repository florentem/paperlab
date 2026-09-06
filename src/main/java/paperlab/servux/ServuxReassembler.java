package paperlab.servux;

import io.netty.buffer.Unpooled;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/**
 * The receiving side of the Servux splitter.
 *
 * <p>A mirror of {@link ServuxSplitter}: the client sends a large body in chunks, every chunk
 * carries the same packet type, and only the first one carries a {@code varint total size}.
 *
 * <pre>
 * first chunk:  varint type, varint total size, bytes
 * the rest:     varint type, bytes
 * </pre>
 *
 * <p>A reassembly session is per player and channel. A new session is recognised by the previous
 * one being closed: the protocol has no separate "start" type.
 *
 * <p><b>A size limit is mandatory.</b> The body comes from a client, and without a bound one
 * corrupt varint is enough to make the server try to allocate gigabytes.
 */
public final class ServuxReassembler {

    /** Same as Servux's {@code DEFAULT_MAX_RECEIVE_SIZE_C2S}: 16 MiB. */
    private static final int MAX_TOTAL = 16 * 1024 * 1024;

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private static final class Session {
        private final byte[] buffer;
        private int filled;

        private Session(final int size) {
            this.buffer = new byte[size];
        }
    }

    private ServuxReassembler() {
    }

    /**
     * Accept a chunk.
     *
     * @return the fully reassembled body, or {@code null} if more is expected
     * @throws IllegalArgumentException if the declared size is out of range
     */
    public static @Nullable byte[] accept(final UUID player, final String channel,
                                          final byte[] slice) {
        final String key = player + "|" + channel;
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(slice));
        buf.readVarInt(); // packet type, already parsed by the caller

        Session session = SESSIONS.get(key);
        if (session == null) {
            final int total = buf.readVarInt();
            if (total <= 0 || total > MAX_TOTAL) {
                throw new IllegalArgumentException("declared size " + total + " out of range");
            }
            session = new Session(total);
            final Session prev = SESSIONS.putIfAbsent(key, session);
            if (prev != null) {
                session = prev;
            }
        }

        synchronized (session) {
            final int available = buf.readableBytes();
            if (session.filled + available > session.buffer.length) {
                SESSIONS.remove(key);
                throw new IllegalArgumentException("slice overflows the declared size");
            }
            buf.readBytes(session.buffer, session.filled, available);
            session.filled += available;

            if (session.filled < session.buffer.length) {
                return null;
            }
            SESSIONS.remove(key);
            return session.buffer;
        }
    }

    /**
     * Parse the reassembled body.
     *
     * <p>The format here is <b>not</b> the one used by the channel's small packets: malilib has
     * two write overloads, and a large body does not arrive as network NBT. Hence the lenient
     * parsing, see {@link ServuxWire#readBody}.
     */
    public static CompoundTag toNbt(final byte[] body, final String label) throws java.io.IOException {
        return ServuxWire.readBody(body, label);
    }

    /** Drop an unfinished reassembly: on player quit and on error. */
    public static void forget(final UUID player) {
        SESSIONS.keySet().removeIf(key -> key.startsWith(player + "|"));
    }
}
