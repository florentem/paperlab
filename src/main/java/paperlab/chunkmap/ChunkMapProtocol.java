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
 * Server side of the ChunkDebug protocol, for the existing client mod.
 *
 * <p>Namespace {@code chunk-debug}, protocol version {@code 4}.
 *
 * <p><b>The format is reproduced from the mod's sources literally.</b> That is not a
 * stylistic requirement: any byte-level mismatch breaks parsing of the whole packet, and the
 * client reports nothing — it simply shows an empty map. The first version diverged in two
 * places and looked exactly like that:
 *
 * <ul>
 *   <li>a ticket was written as {@code utf, int level, varlong ticksLeft}, while the mod reads
 *       {@code identifier, int ticksLeft, int level} — different order, and varlong instead of
 *       int;</li>
 *   <li>the generation stage was written as {@code boolean + identifier}, while the mod uses
 *       {@code ByteBufCodecs.fromCodec}, that is an <b>NBT tag</b> rather than a string.</li>
 * </ul>
 *
 * <p>So the codecs below are assembled from the same building blocks the mod uses
 * ({@code ChunkStatus.CODEC}, {@code Unit.CODEC}, {@code Codec.either},
 * {@code ByteBufCodecs.fromCodec}) — that way matching bytes follow from the construction
 * rather than from care.
 *
 * <p><b>Why the server has to be rewritten.</b> ChunkDebug's server side is made of mixins
 * into vanilla {@code ChunkMap}, {@code DistanceManager}, {@code TicketStorage} and
 * {@code TickingTracker}. Paper replaces that whole subsystem with Moonrise, so those mixins
 * do not apply: the data comes from {@code ChunkHolderManager}/{@code NewChunkHolder} (see
 * {@link ChunkMapTracker}).
 */
public final class ChunkMapProtocol {

    public static final String NAMESPACE = "chunk-debug";
    public static final int PROTOCOL_VERSION = 4;

    /** A ticket type absent from the registry. Same name as the mod uses. */
    private static final Identifier UNREGISTERED = id("unregistered");

    private ChunkMapProtocol() {
    }

    public static Identifier id(final String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    // --- helper codecs ---

    /** {@code writeResourceKey} writes the key's identifier — exactly as the mod does. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ResourceKey<Level>> DIMENSION =
        StreamCodec.of(
            (buf, key) -> buf.writeResourceKey(key),
            buf -> buf.readResourceKey(Registries.DIMENSION)
        );

    /** A list of dimensions: that is how start_watching and stop_watching are shaped. */
    public static final StreamCodec<RegistryFriendlyByteBuf, List<ResourceKey<Level>>> DIMENSIONS =
        ByteBufCodecs.<RegistryFriendlyByteBuf, ResourceKey<Level>>list().apply(DIMENSION);

    /**
     * Generation stage; {@code null} means "the chunk is not generated yet".
     *
     * <p>Assembled as in the mod: {@code either(ChunkStatus.CODEC, Unit.CODEC)} through
     * {@code fromCodec}, so on the wire this is an NBT tag.
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
     * A ticket: type, ticks remaining, level — <b>in exactly that order</b>.
     *
     * <p>The type travels as a registry identifier. Paper has types of its own that vanilla
     * lacks; the client shows unknown ones as they are, so substituting or filtering here is
     * not allowed — the ticket picture would stop being the real one.
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

    /** Type name for text summaries; not part of the protocol. */
    public static String ticketTypeName(final TicketType type) {
        return Util.getRegisteredName(BuiltInRegistries.TICKET_TYPE, type);
    }

    // --- chunk model ---

    /**
     * @param position            chunk coordinates
     * @param stage               generation stage; {@code null} means not generated
     * @param tickets             the tickets holding the chunk
     * @param statusLevel         the ticket level
     * @param tickingStatusLevel  the level including ticking propagation
     * @param unloading           the chunk is marked for unload
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

    // No packet records are declared here: the body is encoded directly in ChunkMapWire via
    // DiscardedPayload, so there is no need to register types in the protocol registry.
}
