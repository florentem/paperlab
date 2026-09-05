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
 * Кадрирование пакетов Servux.
 *
 * <p>Разобрано по исходникам Servux. Устройство одинаково у всех его каналов и, что важно,
 * <b>не одно и то же для разных типов пакетов</b> — на этом легко ошибиться:
 *
 * <pre>
 * varint packetType
 * если тип — metadata (1 и 2):  сетевой NBT, как FriendlyByteBuf.writeNbt
 * все остальные типы:           int длина + GZIP-поток именованного NBT (имя корня "")
 * </pre>
 *
 * <p>Второй вариант — это {@code DataByteBufUtils.toByteBuf}: он пишет длину, а потом
 * gzip'ованный NBT в классическом (именованном) формате. Ванильный
 * {@code NbtIo.writeCompressed} делает ровно это же: тип, пустое имя, тело, всё под GZIP.
 *
 * <p>Клиент об ошибках разбора не сообщает вообще: при расхождении в байтах MiniHUD просто
 * не считает сервер «серверсайдным». Поэтому кадрирование здесь и в одном месте.
 */
public final class ServuxWire {

    /** Порог NBT при чтении: сколько байт клиенту позволено прислать в одном пакете. */
    private static final long READ_LIMIT = 2 * 1024 * 1024L;

    private ServuxWire() {
    }

    /** Тип пакета — первое поле в любом кадре. */
    public static int readType(final byte[] data) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(data)).readVarInt();
    }

    /** Тело metadata-пакета: сетевой NBT сразу после типа. */
    public static CompoundTag readNetworkNbt(final byte[] data) {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        buf.readVarInt();
        final CompoundTag tag = buf.readNbt();
        return tag == null ? new CompoundTag() : tag;
    }

    /**
     * Тело обычного пакета — читаем терпимо, перебирая известные варианты.
     *
     * <p>Так пришлось сделать не от лени. Servux пишет «длина + GZIP именованного NBT»
     * и читает его же, но <b>клиент</b> берёт writer из malilib, и байты от MiniHUD в этот
     * формат не укладываются: длина читается как 167837699 при пакете в 22 байта. Гадать,
     * какой именно из вариантов у malilib, дороже, чем перебрать три — тем более что
     * перебор безвреден: неверный вариант падает на первом же байте.
     *
     * <p>Порядок: длина + GZIP (как у Servux), длина + несжатый NBT (запасной путь самого
     * Servux), затем сетевой NBT (как у ванильного {@code writeNbt}).
     *
     * <p>Если не подошло ничего — бросаем с шестнадцатеричным дампом. Молча вернуть пустой
     * компаунд было бы хуже: канал бы «работал», а данные терялись.
     */
    public static CompoundTag readCompressedNbt(final byte[] data) throws IOException {
        final int prefix = varIntLength(data);

        // 1. Длина + GZIP.
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
            // следующий вариант
        }

        // 2. Длина + несжатый именованный NBT.
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
            // следующий вариант
        }

        // 3. Сетевой NBT сразу после типа.
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            buf.readVarInt();
            final CompoundTag tag = buf.readNbt();
            if (tag != null) {
                return tag;
            }
        } catch (final Throwable ignored) {
            // не подошло ничего
        }

        throw new IOException("unknown NBT framing, prefix " + prefix
            + " bytes, body: " + hex(data));
    }

    /**
     * Разобрать тело <b>без</b> префикса типа — то, что собрал разрезатель.
     *
     * <p>Перебор тот же и по той же причине: у malilib две перегрузки записи, и они
     * дают разные форматы. Мелкие пакеты канала HUD приходят сетевым NBT, а вот большое
     * тело от Litematica — нет: {@code readNbt} на нём падает с
     * {@code ReportedNbtException}. Отсюда «длина + GZIP» первым кандидатом.
     *
     * @param label что разбираем — попадёт в лог при неудаче
     * @return разобранный компаунд; какой вариант сработал, видно по {@link #lastVariant()}
     */
    public static CompoundTag readBody(final byte[] body, final String label) throws IOException {
        // 0. varint + сетевой NBT — то, что на самом деле шлёт malilib.
        //
        // Дамп настоящего запроса Litematica начинался так:
        //   ff ff ff ff 0f | 0a | 0a 00 10 "RenderLayerRange" ...
        // Первые пять байт — varint со значением -1 (видимо, «длина неизвестна»),
        // дальше обычный сетевой NBT: 0x0a — TAG_Compound, затем первое поле.
        // Без дампа этот вариант было не угадать: он не совпадает ни с одной
        // перегрузкой в исходниках Servux.
        try {
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(body));
            buf.readVarInt();
            final CompoundTag tag = buf.readNbt();
            if (tag != null && !tag.isEmpty()) {
                lastVariant = "varint+network";
                return tag;
            }
        } catch (final Throwable ignored) {
            // следующий вариант
        }

        // 1. Длина + GZIP.
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
            // следующий вариант
        }

        // 2. Длина + несжатый именованный NBT.
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
            // следующий вариант
        }

        // 3. Сетевой NBT с начала тела.
        try {
            final CompoundTag tag = new FriendlyByteBuf(Unpooled.wrappedBuffer(body)).readNbt();
            if (tag != null) {
                lastVariant = "network";
                return tag;
            }
        } catch (final Throwable ignored) {
            // следующий вариант
        }

        // 4. GZIP без префикса длины.
        try {
            final CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(body),
                NbtAccounter.create(READ_LIMIT));
            lastVariant = "gzip";
            return tag;
        } catch (final Throwable ignored) {
            // не подошло ничего
        }

        throw new IOException(label + ": unknown body framing, " + body.length
            + " bytes, head: " + hex(body));
    }

    private static volatile String lastVariant = "?";

    /** Какой вариант разбора сработал последним. Нужно только для лога. */
    public static String lastVariant() {
        return lastVariant;
    }

    /** Сколько байт занял varint типа: нужно только для сообщения об ошибке. */
    private static int varIntLength(final byte[] data) {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        final int before = buf.readableBytes();
        buf.readVarInt();
        return before - buf.readableBytes();
    }

    /** Дамп для разбора незнакомого формата. Обрезаем: длинный дамп никто не читает. */
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
     * Дописать тело в кадре malilib: {@code varint(-1)} и следом сетевой NBT.
     *
     * <p>Минус единица — то, что malilib пишет вместо длины. Установлено по дампу
     * настоящего пакета Litematica: {@code ff ff ff ff 0f | 0a | ...}.
     */
    public static void appendNbtBody(final FriendlyByteBuf buf, final CompoundTag tag) {
        buf.writeVarInt(-1);
        buf.writeNbt(tag);
    }

    /** Собрать metadata-кадр. */
    public static byte[] metadata(final int type, final CompoundTag tag) {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(type);
        buf.writeNbt(tag);
        return drain(buf);
    }

    /**
     * Собрать обычный кадр.
     *
     * <p><b>Кадр тот же, что у metadata: сетевой NBT.</b> Это выяснилось дорого. Servux
     * пишет «длина + GZIP» и читает его же, и я повторил за ним — но клиент берёт кодек
     * из malilib, а там сетевой NBT. Клиент прочитал наш NBT-заголовок, остаток не тронул,
     * и ванильный декодер убил соединение:
     *
     * <pre>
     * Packet play/clientbound/minecraft:custom_payload was larger than I expected,
     * found 104 bytes extra
     * </pre>
     *
     * <p>Урок общий: сверять кадрирование нужно с <b>клиентским</b> кодом, а не с серверным.
     * Мод и его серверная часть писались не совсем согласованно, и расходятся они молча —
     * до того момента, когда рвут соединение.
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
