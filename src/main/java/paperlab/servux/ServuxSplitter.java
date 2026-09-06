package paperlab.servux;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Splitter for large Servux packets.
 *
 * <p>Structure and schematic data do not fit into a single custom payload, so Servux sends them
 * in chunks. The format is simple and identical across all of its channels:
 *
 * <pre>
 * every chunk:  varint packet type
 *               varint total size   — in the FIRST chunk only
 *               raw bytes
 * </pre>
 *
 * <p>The type is the same for every chunk and there is no separate "start" type — the client
 * recognises the first chunk by the reassembly session not being open yet. Servux's enum does
 * contain {@code ..._DATA_START} constants, but they are not used on this path: it is easy to
 * mistake them for part of the protocol and write code that is never needed.
 *
 * <p>The chunk limit is {@code 1 MiB - 5} bytes, as in Servux. The five is headroom for varints.
 */
public final class ServuxSplitter {

    /** Same as Servux's {@code MAX_PAYLOAD_PER_PACKET_S2C}. */
    private static final int MAX_SLICE = 1048576 - 5;

    private ServuxSplitter() {
    }

    /**
     * Send a body in chunks.
     *
     * @param type the packet type, identical for every chunk
     * @param body the already serialised body
     */
    public static void send(final Player player, final String channel,
                            final int type, final byte[] body) {
        final var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection == null) {
            return;
        }
        final Identifier id = Identifier.parse(channel);

        for (int offset = 0; offset < body.length; offset += MAX_SLICE) {
            final int length = Math.min(body.length - offset, MAX_SLICE);
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeVarInt(type);
            if (offset == 0) {
                buf.writeVarInt(body.length);
            }
            buf.writeBytes(body, offset, length);

            final byte[] slice = new byte[buf.readableBytes()];
            buf.readBytes(slice);
            buf.release();
            connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(id, slice)));
        }
    }
}
