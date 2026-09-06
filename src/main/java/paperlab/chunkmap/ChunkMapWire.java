package paperlab.chunkmap;

import io.netty.buffer.Unpooled;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;

/**
 * Turning chunk map packets into raw bytes and back.
 *
 * <p>In a plugin, channels are addressed by string, because that is what Bukkit's plugin message
 * API takes. A side benefit: Bukkit announces the registered channels to the client through
 * {@code minecraft:register} itself, so none of that has to be done by hand as it did in the core
 * version.
 */
public final class ChunkMapWire {

    public static final String HELLO = ChunkMapProtocol.NAMESPACE + ":hello";
    public static final String BYE = ChunkMapProtocol.NAMESPACE + ":bye";
    public static final String START_WATCHING = ChunkMapProtocol.NAMESPACE + ":start_watching";
    public static final String STOP_WATCHING = ChunkMapProtocol.NAMESPACE + ":stop_watching";
    public static final String CHUNK_DATA = ChunkMapProtocol.NAMESPACE + ":chunk_data";
    public static final String CHUNK_UNLOAD = ChunkMapProtocol.NAMESPACE + ":chunk_unload";
    /**
     * Note: the channel is called {@code refresh}, not {@code chunk_refresh}. In the mod it is
     * {@code ChunkDebug.id("refresh")} while the class is {@code ChunkRefreshPayload} — the class
     * name and the channel name differ, and it is easy to be caught out by that.
     */
    public static final String CHUNK_REFRESH = ChunkMapProtocol.NAMESPACE + ":refresh";

    /**
     * Channels the <b>client</b> speaks on. Those and only those need registering as incoming:
     * Paper announces the incoming channel list to the client, while the mod sends to the server
     * without checking — all it needs is for the server to accept the message.
     */
    public static final List<String> INCOMING = List.of(
        START_WATCHING, STOP_WATCHING, CHUNK_REFRESH);

    /**
     * Channels the <b>server</b> speaks on.
     *
     * <p>{@code hello} is here for a reason: in the protocol it is clientbound only. The client
     * never sends it — it merely waits for it after joining. The first version waited for a
     * client {@code hello} to answer, which is exactly why the mod said "ChunkDebug is
     * unavailable": the handshake never started.
     */
    public static final List<String> OUTGOING = List.of(
        HELLO, BYE, CHUNK_DATA, CHUNK_UNLOAD);

    private ChunkMapWire() {
    }

    private static RegistryAccess registries() {
        return ((CraftServer) Bukkit.getServer()).getServer().registryAccess();
    }

    private static RegistryFriendlyByteBuf buf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), registries());
    }

    private static RegistryFriendlyByteBuf wrap(final byte[] data) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registries());
    }

    private static byte[] drain(final RegistryFriendlyByteBuf buf) {
        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    // --- outgoing ---

    public static byte[] encodeHello(final int version) {
        final RegistryFriendlyByteBuf buf = buf();
        buf.writeInt(version);
        return drain(buf);
    }

    public static byte[] encodeChunkData(final ResourceKey<Level> dimension,
                                         final Collection<ChunkMapProtocol.ChunkInfo> chunks,
                                         final int tick,
                                         final boolean initial) {
        final RegistryFriendlyByteBuf buf = buf();
        ChunkMapProtocol.DIMENSION.encode(buf, dimension);
        ChunkMapProtocol.CHUNK_INFO_LIST.encode(buf, chunks);
        buf.writeInt(tick);
        buf.writeBoolean(initial);
        return drain(buf);
    }

    /**
     * Chunk unloading: a dimension and an array of packed positions.
     *
     * <p>Without this packet the client's map only grows: its {@code updateChunks} merely appends
     * entries, and {@code unloadChunks} is what removes them.
     */
    public static byte[] encodeChunkUnload(final ResourceKey<Level> dimension,
                                           final long[] chunks) {
        final RegistryFriendlyByteBuf buf = buf();
        ChunkMapProtocol.DIMENSION.encode(buf, dimension);
        buf.writeLongArray(chunks);
        return drain(buf);
    }

    // --- incoming ---

    public static int decodeHello(final byte[] data) {
        return wrap(data).readInt();
    }

    /**
     * {@code start_watching} and {@code stop_watching} carry a <b>list</b> of dimensions, not one.
     * An empty list on {@code stop_watching} means "stop everything".
     */
    public static List<ResourceKey<Level>> decodeDimensions(final byte[] data) {
        return ChunkMapProtocol.DIMENSIONS.decode(wrap(data));
    }
}
