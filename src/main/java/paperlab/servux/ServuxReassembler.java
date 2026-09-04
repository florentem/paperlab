package paperlab.servux;

import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/**
 * Приёмная сторона разрезателя Servux.
 *
 * <p>Зеркало {@link ServuxSplitter}: клиент шлёт большое тело кусками, у всех кусков один
 * и тот же тип пакета, и только первый несёт {@code varint общего размера}.
 *
 * <pre>
 * первый кусок:  varint тип, varint общий размер, байты
 * остальные:     varint тип, байты
 * </pre>
 *
 * <p>Сессия сборки — на игрока и канал. Начало новой сессии узнаём по тому, что прошлая
 * закрыта: отдельного «стартового» типа в протоколе нет.
 *
 * <p><b>Ограничение размера обязательно.</b> Тело приходит от клиента, и без предела
 * достаточно одного испорченного varint'а, чтобы сервер попытался выделить гигабайты.
 */
public final class ServuxReassembler {

    /** Столько же, сколько {@code DEFAULT_MAX_RECEIVE_SIZE_C2S} у Servux: 16 МиБ. */
    private static final int MAX_TOTAL = 16 * 1024 * 1024;

    private static final Map<String, Session> SESSIONS = new HashMap<>();

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
     * Принять кусок.
     *
     * @return полностью собранное тело, либо {@code null}, если ждём продолжения
     * @throws IllegalArgumentException если заявленный размер вне допустимого
     */
    public static @Nullable byte[] accept(final UUID player, final String channel,
                                          final byte[] slice) {
        final String key = player + "|" + channel;
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(slice));
        buf.readVarInt(); // тип пакета, уже разобран вызывающим

        Session session = SESSIONS.get(key);
        if (session == null) {
            final int total = buf.readVarInt();
            if (total <= 0 || total > MAX_TOTAL) {
                throw new IllegalArgumentException("declared size " + total + " out of range");
            }
            session = new Session(total);
            SESSIONS.put(key, session);
        }

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

    /**
     * Разобрать собранное тело.
     *
     * <p>Формат тут <b>не тот</b>, что у мелких пакетов канала: у malilib две перегрузки
     * записи, и большое тело приходит не сетевым NBT. Поэтому разбор терпимый,
     * см. {@link ServuxWire#readBody}.
     */
    public static CompoundTag toNbt(final byte[] body, final String label) throws java.io.IOException {
        return ServuxWire.readBody(body, label);
    }

    /** Забыть незавершённую сборку: при выходе игрока и при ошибке. */
    public static void forget(final UUID player) {
        SESSIONS.keySet().removeIf(key -> key.startsWith(player + "|"));
    }
}
