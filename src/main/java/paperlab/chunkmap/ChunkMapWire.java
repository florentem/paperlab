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
 * Перевод пакетов карты чанков в сырые байты и обратно.
 *
 * <p>В плагине каналы адресуются строками, потому что таков API плагинных сообщений
 * Bukkit. Побочная выгода: Bukkit сам объявляет клиенту зарегистрированные каналы через
 * {@code minecraft:register}, поэтому возиться с этим вручную, как в версии для ядра,
 * не нужно.
 */
public final class ChunkMapWire {

    public static final String HELLO = ChunkMapProtocol.NAMESPACE + ":hello";
    public static final String BYE = ChunkMapProtocol.NAMESPACE + ":bye";
    public static final String START_WATCHING = ChunkMapProtocol.NAMESPACE + ":start_watching";
    public static final String STOP_WATCHING = ChunkMapProtocol.NAMESPACE + ":stop_watching";
    public static final String CHUNK_DATA = ChunkMapProtocol.NAMESPACE + ":chunk_data";
    public static final String CHUNK_UNLOAD = ChunkMapProtocol.NAMESPACE + ":chunk_unload";
    /**
     * Внимание: канал называется {@code refresh}, а не {@code chunk_refresh}.
     * В моде это {@code ChunkDebug.id("refresh")} при классе {@code ChunkRefreshPayload} —
     * имя класса и имя канала расходятся, и на это легко попасться.
     */
    public static final String CHUNK_REFRESH = ChunkMapProtocol.NAMESPACE + ":refresh";

    /**
     * Каналы, по которым говорит <b>клиент</b>. Их и только их нужно регистрировать
     * входящими: Paper объявляет клиенту именно список входящих каналов, а мод шлёт
     * серверу без проверки — ему важно лишь, чтобы сервер сообщение принял.
     */
    public static final List<String> INCOMING = List.of(
        START_WATCHING, STOP_WATCHING, CHUNK_REFRESH);

    /**
     * Каналы, по которым говорит <b>сервер</b>.
     *
     * <p>{@code hello} здесь не случайно: в протоколе он только серверный. Клиент его
     * никогда не отправляет — он лишь ждёт его после входа. Первая версия ждала клиентского
     * {@code hello} в ответ, и именно поэтому мод писал «ChunkDebug is unavailable»:
     * рукопожатие не начиналось никогда.
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

    // --- исходящие ---

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
     * Выгрузка чанков: измерение и массив упакованных позиций.
     *
     * <p>Без этого пакета карта у клиента только растёт: его {@code updateChunks} лишь
     * дописывает записи, а удаляет их именно {@code unloadChunks}.
     */
    public static byte[] encodeChunkUnload(final ResourceKey<Level> dimension,
                                           final long[] chunks) {
        final RegistryFriendlyByteBuf buf = buf();
        ChunkMapProtocol.DIMENSION.encode(buf, dimension);
        buf.writeLongArray(chunks);
        return drain(buf);
    }

    // --- входящие ---

    public static int decodeHello(final byte[] data) {
        return wrap(data).readInt();
    }

    /**
     * {@code start_watching} и {@code stop_watching} несут <b>список</b> измерений,
     * а не одно. Пустой список у {@code stop_watching} означает «прекратить всё».
     */
    public static List<ResourceKey<Level>> decodeDimensions(final byte[] data) {
        return ChunkMapProtocol.DIMENSIONS.decode(wrap(data));
    }
}
