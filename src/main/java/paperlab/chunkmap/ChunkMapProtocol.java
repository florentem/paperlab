package paperlab.chunkmap;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Серверная сторона протокола ChunkDebug — под уже существующий клиентский мод.
 *
 * <p>Пространство имён {@code chunk-debug}, версия протокола {@code 4}.
 *
 * <p><b>Формат воспроизводится по исходникам мода буквально.</b> Это не стилистическое
 * требование: любое расхождение в байтах ломает разбор всего пакета, а клиент при этом
 * ничего не сообщает — просто показывает пустую карту. Первая версия здесь разошлась
 * в двух местах и именно так и выглядела:
 *
 * <ul>
 *   <li>ticket писался как {@code utf, int level, varlong ticksLeft}, а мод читает
 *       {@code identifier, int ticksLeft, int level} — и порядок другой, и varlong вместо int;</li>
 *   <li>стадия генерации писалась как {@code boolean + identifier}, а мод использует
 *       {@code ByteBufCodecs.fromCodec}, то есть <b>NBT-тег</b>, а не строку.</li>
 * </ul>
 *
 * <p>Поэтому кодеки ниже собраны из тех же кирпичей, что у мода ({@code ChunkStatus.CODEC},
 * {@code Unit.CODEC}, {@code Codec.either}, {@code ByteBufCodecs.fromCodec}) — так
 * совпадение байтов следует из конструкции, а не из аккуратности.
 *
 * <p><b>Почему сервер приходится писать заново.</b> Серверная часть ChunkDebug сделана
 * миксинами в ванильные {@code ChunkMap}, {@code DistanceManager}, {@code TicketStorage}
 * и {@code TickingTracker}. Paper заменяет всю эту подсистему на Moonrise, поэтому
 * донорские миксины неприменимы: данные берутся из
 * {@code ChunkHolderManager}/{@code NewChunkHolder} (см. {@link ChunkMapTracker}).
 */
public final class ChunkMapProtocol {

    public static final String NAMESPACE = "chunk-debug";
    public static final int PROTOCOL_VERSION = 4;

    /** Тип ticket'а, которого нет в реестре. То же имя, что у мода. */
    private static final Identifier UNREGISTERED = id("unregistered");

    private ChunkMapProtocol() {
    }

    public static Identifier id(final String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    // --- вспомогательные кодеки ---

    /** {@code writeResourceKey} пишет идентификатор ключа — ровно как у мода. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ResourceKey<Level>> DIMENSION =
        StreamCodec.of(
            (buf, key) -> buf.writeResourceKey(key),
            buf -> buf.readResourceKey(Registries.DIMENSION)
        );

    /** Список измерений: именно так устроены start_watching и stop_watching. */
    public static final StreamCodec<RegistryFriendlyByteBuf, List<ResourceKey<Level>>> DIMENSIONS =
        ByteBufCodecs.<RegistryFriendlyByteBuf, ResourceKey<Level>>list().apply(DIMENSION);

    /**
     * Стадия генерации, {@code null} — «чанк ещё не сгенерирован».
     *
     * <p>Собрано так же, как в моде: {@code either(ChunkStatus.CODEC, Unit.CODEC)} через
     * {@code fromCodec}, то есть на проводе это NBT-тег.
     */
    private static final Codec<Optional<ChunkStatus>> OPTIONAL_CHUNK_STATUS_CODEC =
        Codec.either(ChunkStatus.CODEC, Unit.CODEC)
            .xmap(
                either -> either.map(Optional::of, unit -> Optional.<ChunkStatus>empty()),
                optional -> optional.<Either<ChunkStatus, Unit>>map(Either::left)
                    .orElseGet(() -> Either.right(Unit.INSTANCE))
            )
            .orElse(Optional.empty());

    public static final StreamCodec<ByteBuf, Optional<ChunkStatus>> OPTIONAL_CHUNK_STATUS =
        ByteBufCodecs.fromCodec(OPTIONAL_CHUNK_STATUS_CODEC);

    /**
     * Ticket: тип, сколько тиков осталось, уровень — <b>именно в этом порядке</b>.
     *
     * <p>Тип передаётся идентификатором из реестра. У Paper есть собственные типы,
     * которых нет в ваниле; клиент показывает незнакомые как есть, поэтому подмена или
     * фильтрация здесь недопустима — иначе картина tickets перестанет быть настоящей.
     */
    public record TicketInfo(Identifier type, int level, int ticksLeft) {
    }

    public static final StreamCodec<FriendlyByteBuf, TicketInfo> TICKET = StreamCodec.of(
        (buf, ticket) -> {
            buf.writeIdentifier(ticket.type());
            buf.writeInt(ticket.ticksLeft());
            buf.writeInt(ticket.level());
        },
        buf -> {
            final Identifier type = buf.readIdentifier();
            final int ticksLeft = buf.readInt();
            final int level = buf.readInt();
            return new TicketInfo(type, level, ticksLeft);
        }
    );

    public static final StreamCodec<FriendlyByteBuf, List<TicketInfo>> TICKETS =
        ByteBufCodecs.<FriendlyByteBuf, TicketInfo>list().apply(TICKET);

    public static Identifier ticketTypeId(final TicketType type) {
        final Identifier key = BuiltInRegistries.TICKET_TYPE.getKey(type);
        return key == null ? UNREGISTERED : key;
    }

    /** Имя типа для текстовых сводок; в протоколе не участвует. */
    public static String ticketTypeName(final TicketType type) {
        return Util.getRegisteredName(BuiltInRegistries.TICKET_TYPE, type);
    }

    // --- модель чанка ---

    /**
     * @param position            координаты чанка
     * @param stage               стадия генерации; {@code null} — не сгенерирован
     * @param tickets             tickets, удерживающие чанк
     * @param statusLevel         уровень ticket'а
     * @param tickingStatusLevel  уровень, учитывающий ticking-распространение
     * @param unloading           чанк помечен на выгрузку
     */
    public record ChunkInfo(
        ChunkPos position,
        @Nullable ChunkStatus stage,
        List<TicketInfo> tickets,
        int statusLevel,
        int tickingStatusLevel,
        boolean unloading
    ) {
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkInfo> CHUNK_INFO = StreamCodec.of(
        (buf, data) -> {
            buf.writeChunkPos(data.position());
            buf.writeInt(data.statusLevel());
            buf.writeInt(data.tickingStatusLevel());
            buf.writeBoolean(data.unloading());
            OPTIONAL_CHUNK_STATUS.encode(buf, Optional.ofNullable(data.stage()));
            TICKETS.encode(buf, data.tickets());
        },
        buf -> {
            final ChunkPos pos = buf.readChunkPos();
            final int statusLevel = buf.readInt();
            final int tickingStatusLevel = buf.readInt();
            final boolean unloading = buf.readBoolean();
            final ChunkStatus stage = OPTIONAL_CHUNK_STATUS.decode(buf).orElse(null);
            final List<TicketInfo> tickets = TICKETS.decode(buf);
            return new ChunkInfo(pos, stage, tickets, statusLevel, tickingStatusLevel, unloading);
        }
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, Collection<ChunkInfo>> CHUNK_INFO_LIST =
        ByteBufCodecs.collection(ArrayList::new, CHUNK_INFO);

    // Записи-пакеты здесь не объявляются: тело кодируется напрямую в ChunkMapWire
    // через DiscardedPayload, поэтому регистрировать типы в реестре протокола не нужно.
}
