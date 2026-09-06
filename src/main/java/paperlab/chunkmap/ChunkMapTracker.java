package paperlab.chunkmap;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * A snapshot of a world's chunk state for the map — taken from Moonrise rather than from
 * vanilla {@code ChunkMap}.
 *
 * <p>The reference ChunkDebug reads its data through mixins into {@code ChunkMap},
 * {@code DistanceManager}, {@code TicketStorage} and {@code TickingTracker}. Paper replaces
 * that whole subsystem, so the source here is different:
 *
 * <ul>
 *   <li>{@code ChunkHolderManager.getChunkHolders()} — every live holder;</li>
 *   <li>{@code NewChunkHolder.getTicketLevel()} — the ticket level;</li>
 *   <li>{@code NewChunkHolder.getChunkStatus()} — the actual {@code FullChunkStatus};</li>
 *   <li>{@code NewChunkHolder.getCurrentGenStatus()} — the generation stage;</li>
 *   <li>{@code ChunkHolderManager.getTicketsAt(x, z)} — the tickets themselves.</li>
 * </ul>
 *
 * <p>Reads existing state only: loads no chunks and adds no tickets. Must be called on the main
 * server thread.
 */
public final class ChunkMapTracker {

    private ChunkMapTracker() {
    }

    /**
     * A full snapshot.
     *
     * @param withTickets whether to collect tickets per chunk. That is a separate query taking a
     *                    region lock per chunk, so on large worlds it costs more than walking the
     *                    holders does.
     */
    public static List<ChunkMapProtocol.ChunkInfo> snapshot(final ServerLevel level,
                                                            final boolean withTickets) {
        final var scheduler = level.moonrise$getChunkTaskScheduler();
        final List<NewChunkHolder> holders = scheduler.chunkHolderManager.getChunkHolders();
        final List<ChunkMapProtocol.ChunkInfo> out = new ArrayList<>(holders.size());

        for (final NewChunkHolder holder : holders) {
            final ChunkMapProtocol.ChunkInfo info = describe(level, holder, withTickets);
            if (info != null) {
                out.add(info);
            }
        }
        return out;
    }

    private static ChunkMapProtocol.@org.checkerframework.checker.nullness.qual.Nullable ChunkInfo describe(
        final ServerLevel level, final NewChunkHolder holder, final boolean withTickets) {

        final int ticketLevel = holder.getTicketLevel();
        if (ticketLevel > ChunkLevel.MAX_LEVEL) {
            // A holder exists, but its level is already past the load threshold — nothing to show.
            return null;
        }

        final ChunkPos pos = new ChunkPos(holder.chunkX, holder.chunkZ);

        final ChunkStatus stage = holder.getCurrentGenStatus();

        // The client computes FullChunkStatus as fullStatus(max(statusLevel, tickingStatusLevel)).
        // In Moonrise the ticking propagation is already folded into the ticket level, so both
        // levels are the same: there is nothing to split them by, and reporting different values
        // would be a lie.
        final int statusLevel = ticketLevel;
        final int tickingStatusLevel = ticketLevel;

        final List<ChunkMapProtocol.TicketInfo> tickets;
        if (withTickets) {
            tickets = ticketsAt(level, pos.x(), pos.z());
        } else {
            tickets = List.of();
        }

        // Moonrise exposes no public "queued for unload" flag (only the internal checkUnload and
        // isSafeToUnload(), which returns a reason). Substituting something similar is not an
        // option: the client would paint the chunk as unloading and the picture would be false.
        // So an honest false until a real signal appears.
        final boolean unloading = false;

        return new ChunkMapProtocol.ChunkInfo(
            pos, stage, tickets, statusLevel, tickingStatusLevel, unloading);
    }

    /** One chunk's tickets, in a form suitable for sending to the client. */
    public static List<ChunkMapProtocol.TicketInfo> ticketsAt(final ServerLevel level,
                                                              final int chunkX, final int chunkZ) {
        final List<Ticket> raw = level.moonrise$getChunkTaskScheduler()
            .chunkHolderManager.getTicketsAt(chunkX, chunkZ);
        if (raw.isEmpty()) {
            return List.of();
        }
        final List<ChunkMapProtocol.TicketInfo> out = new ArrayList<>(raw.size());
        for (final Ticket ticket : raw) {
            out.add(new ChunkMapProtocol.TicketInfo(
                ChunkMapProtocol.ticketTypeId(ticket.getType()),
                ticket.getTicketLevel(),
                (int) ticket.moonrise$getRemoveDelay()));
        }
        return out;
    }
}
