package paperlab.servux;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Разрезатель больших пакетов Servux.
 *
 * <p>Данные структур и схематик не помещаются в один custom payload, поэтому Servux шлёт их
 * кусками. Формат простой и одинаковый для всех его каналов:
 *
 * <pre>
 * каждый кусок:  varint тип пакета
 *                varint общий размер   — только в ПЕРВОМ куске
 *                сырые байты
 * </pre>
 *
 * <p>Тип у всех кусков один и тот же, отдельного «начального» типа нет — клиент отличает
 * первый кусок по тому, что сессия сборки ещё не открыта. В перечислении Servux есть
 * константы {@code ..._DATA_START}, но на этом пути они не используются: легко принять их
 * за часть протокола и написать лишнее.
 *
 * <p>Предел куска — {@code 1 МиБ − 5} байт, как у Servux. Пятёрка — запас под varint'ы.
 */
public final class ServuxSplitter {

    /** Столько же, сколько {@code MAX_PAYLOAD_PER_PACKET_S2C} у Servux. */
    private static final int MAX_SLICE = 1048576 - 5;

    private ServuxSplitter() {
    }

    /**
     * Отправить тело кусками.
     *
     * @param type тип пакета, одинаковый у всех кусков
     * @param body уже сериализованное тело
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
