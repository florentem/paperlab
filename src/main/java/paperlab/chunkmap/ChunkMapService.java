package paperlab.chunkmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import paperlab.command.LabPermissions;
import org.jetbrains.annotations.NotNull;

/**
 * Серверная сторона протокола ChunkDebug под готовый клиентский мод.
 *
 * <h2>Рукопожатие</h2>
 * Инициатива <b>серверная</b>: после входа игрока сервер сам шлёт {@code hello} с версией
 * протокола, и только получив его, мод включает карту. Клиент {@code hello} не отправляет
 * никогда — в моде этот канал зарегистрирован только как clientbound.
 *
 * <p>Это и была причина надписи «ChunkDebug is unavailable»: первая версия ждала
 * клиентского {@code hello}, чтобы ответить на него, и потому не отвечала никогда.
 * Объявление каналов через {@code minecraft:register} здесь ни при чём — Bukkit делает
 * это сам.
 *
 * <p>Отправка отложена на тик после входа: LuckPerms грузит права в том же событии, а нам
 * нужно проверить право до отправки. Так же поступает и сам мод.
 *
 * <h2>Дельты, а не полный снимок</h2>
 * Первая версия слала полный снимок раз в секунду. Это было не только дорого — в незере
 * выходило больше двух тысяч чанков в секунду, — но и <b>неверно</b>: клиентский
 * {@code updateChunks} только дописывает в свою карту, ничего не удаляя. Выгруженный чанк
 * так и оставался на карте навсегда, пока не придёт {@code chunk_unload}.
 *
 * <p>Поэтому храним последнее отправленное состояние по каждому наблюдателю и шлём только
 * разницу: изменившиеся чанки в {@code chunk_data}, исчезнувшие — в {@code chunk_unload}.
 * Полный снимок уходит один раз, при подписке.
 */
public final class ChunkMapService implements PluginMessageListener {

    private static final int PERIOD_TICKS = 20;
    private static int tickCounter;

    private static final Map<UUID, Set<ResourceKey<Level>>> WATCHERS = new HashMap<>();

    /**
     * Что уже отправлено наблюдателю: измерение → упакованная позиция чанка → его состояние.
     * Нужен, чтобы считать разницу; чистится вместе с подпиской.
     */
    private static final Map<UUID, Map<ResourceKey<Level>, Map<Long, ChunkMapProtocol.ChunkInfo>>> SENT =
        new HashMap<>();

    private static Plugin plugin;

    public static void enable(final Plugin owner) {
        plugin = owner;
        final ChunkMapService service = new ChunkMapService();
        for (final String channel : ChunkMapWire.INCOMING) {
            Bukkit.getMessenger().registerIncomingPluginChannel(owner, channel, service);
        }
        for (final String channel : ChunkMapWire.OUTGOING) {
            Bukkit.getMessenger().registerOutgoingPluginChannel(owner, channel);
        }
    }

    /**
     * Начать рукопожатие с вошедшим игроком.
     *
     * <p>Через тик после входа: право читаем после того, как его успел загрузить LuckPerms.
     */
    public static void onJoin(final Player player) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (player.isOnline() && player.hasPermission(LabPermissions.CHUNKMAP)) {
                sendHello(player);
            }
        }, 1L);
    }

    /** Повторить рукопожатие — например, после выдачи прав или если карта не открылась. */
    public static void sendHello(final Player player) {
        send(player, ChunkMapWire.HELLO, ChunkMapWire.encodeHello(ChunkMapProtocol.PROTOCOL_VERSION));
        // Диагностика отложена: список объявленных каналов приходит от клиента своим
        // темпом, и сразу после входа он ещё пуст. На саму отправку это не влияет —
        // hello уже ушёл, — но без этой строки «карта не работает» не отличить от
        // «мода нет».
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline()) {
                return;
            }
            final boolean announced = player.getListeningPluginChannels()
                .contains(ChunkMapWire.HELLO);
            plugin.getLogger().info("ChunkDebug: hello → " + player.getName()
                + (announced ? " (mod present)" : " (channel not announced, mod likely missing)"));
        }, 60L);
    }

    /** Отозвать доступ: мод погасит карту. */
    public static void sendBye(final Player player) {
        WATCHERS.remove(player.getUniqueId());
        send(player, ChunkMapWire.BYE, new byte[0]);
    }

    public static void disable() {
        if (plugin != null) {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin);
        }
        WATCHERS.clear();
        SENT.clear();
    }

    @Override
    public void onPluginMessageReceived(final @NotNull String channel,
                                        final @NotNull Player player,
                                        final byte @NotNull [] message) {
        try {
            if (DEBUG) {
                plugin.getLogger().info("ChunkDebug: in " + channel + " from " + player.getName()
                    + ", " + message.length + " bytes");
            }
            if (ChunkMapWire.CHUNK_REFRESH.equals(channel)) {
                onRefresh(player);
            } else if (ChunkMapWire.START_WATCHING.equals(channel)) {
                onStartWatching(player, ChunkMapWire.decodeDimensions(message));
            } else if (ChunkMapWire.STOP_WATCHING.equals(channel)) {
                onStopWatching(player, ChunkMapWire.decodeDimensions(message));
            }
        } catch (final Throwable t) {
            // Испорченное тело от клиента не должно ронять обработку пакетов.
            plugin.getLogger().warning("ChunkDebug: malformed packet " + channel
                + " from " + player.getName() + ": " + t);
        }
    }

    /** Клиент нажал «обновить»: шлём полный снимок по всем его измерениям. */
    private void onRefresh(final Player player) {
        final Set<ResourceKey<Level>> dims = WATCHERS.get(player.getUniqueId());
        if (dims == null) {
            return;
        }
        for (final ResourceKey<Level> dimension : dims) {
            final ServerLevel level = levelOf(dimension);
            if (level != null) {
                sendFull(player, level);
            }
        }
    }

    private void onStartWatching(final Player player, final List<ResourceKey<Level>> dimensions) {
        for (final ResourceKey<Level> dimension : dimensions) {
            final ServerLevel level = levelOf(dimension);
            if (level == null) {
                plugin.getLogger().warning("ChunkDebug: " + player.getName()
                    + " requested unknown dimension " + dimension.identifier());
                continue;
            }
            WATCHERS.computeIfAbsent(player.getUniqueId(), key -> new HashSet<>()).add(dimension);
            sendFull(player, level);
        }
    }

    /** Пустой список означает «прекратить всё» — так это и задумано в протоколе. */
    private void onStopWatching(final Player player, final List<ResourceKey<Level>> dimensions) {
        if (dimensions.isEmpty()) {
            WATCHERS.remove(player.getUniqueId());
            SENT.remove(player.getUniqueId());
            return;
        }
        final Set<ResourceKey<Level>> dims = WATCHERS.get(player.getUniqueId());
        if (dims != null) {
            dimensions.forEach(dims::remove);
            if (dims.isEmpty()) {
                WATCHERS.remove(player.getUniqueId());
            }
        }
        final var sent = SENT.get(player.getUniqueId());
        if (sent != null) {
            dimensions.forEach(sent::remove);
        }
    }

    public static void onDisconnect(final Player player) {
        WATCHERS.remove(player.getUniqueId());
        SENT.remove(player.getUniqueId());
    }

    public static void tick() {
        if (WATCHERS.isEmpty() || ++tickCounter % PERIOD_TICKS != 0) {
            return;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final Set<ResourceKey<Level>> dims = WATCHERS.get(player.getUniqueId());
            if (dims == null) {
                continue;
            }
            for (final ResourceKey<Level> dimension : dims) {
                final ServerLevel level = levelOf(dimension);
                if (level != null) {
                    sendDelta(player, level);
                }
            }
        }
    }

    /** Полный снимок: при подписке и по кнопке «обновить». */
    private static void sendFull(final Player player, final ServerLevel level) {
        final List<ChunkMapProtocol.ChunkInfo> chunks = ChunkMapTracker.snapshot(level, true);
        send(player, ChunkMapWire.CHUNK_DATA, ChunkMapWire.encodeChunkData(
            level.dimension(), chunks, (int) level.getGameTime(), true));
        remember(player, level, chunks);
        if (DEBUG) {
            plugin.getLogger().info("ChunkDebug: full " + chunks.size() + " chunks to "
                + player.getName() + " in " + level.dimension().identifier().getPath());
        }
    }

    /** Разница с прошлым разом: что изменилось и что исчезло. */
    private static void sendDelta(final Player player, final ServerLevel level) {
        final Map<Long, ChunkMapProtocol.ChunkInfo> previous = SENT
            .computeIfAbsent(player.getUniqueId(), key -> new HashMap<>())
            .computeIfAbsent(level.dimension(), key -> new HashMap<>());

        final List<ChunkMapProtocol.ChunkInfo> chunks = ChunkMapTracker.snapshot(level, true);
        final Map<Long, ChunkMapProtocol.ChunkInfo> current = new HashMap<>(chunks.size());
        final List<ChunkMapProtocol.ChunkInfo> changed = new ArrayList<>();

        for (final ChunkMapProtocol.ChunkInfo info : chunks) {
            final long key = info.position().pack();
            current.put(key, info);
            if (!same(info, previous.get(key))) {
                changed.add(info);
            }
        }

        final List<Long> gone = new ArrayList<>();
        for (final Long key : previous.keySet()) {
            if (!current.containsKey(key)) {
                gone.add(key);
            }
        }

        if (!changed.isEmpty()) {
            send(player, ChunkMapWire.CHUNK_DATA, ChunkMapWire.encodeChunkData(
                level.dimension(), changed, (int) level.getGameTime(), false));
        }
        if (!gone.isEmpty()) {
            final long[] positions = new long[gone.size()];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = gone.get(i);
            }
            send(player, ChunkMapWire.CHUNK_UNLOAD,
                ChunkMapWire.encodeChunkUnload(level.dimension(), positions));
        }

        SENT.get(player.getUniqueId()).put(level.dimension(), current);

        if (DEBUG && (!changed.isEmpty() || !gone.isEmpty())) {
            plugin.getLogger().info("ChunkDebug: delta +" + changed.size() + " -" + gone.size()
                + " to " + player.getName() + " in " + level.dimension().identifier().getPath());
        }
    }

    /**
     * Считается ли чанк изменившимся.
     *
     * <p>Обратный отсчёт ticket'а (<i>ticksLeft</i>) намеренно <b>не</b> учитывается.
     * У отложенных тикетов он уменьшается каждый тик, и при сравнении «целиком» в дельту
     * попадала тысяча чанков в секунду — то есть дельта переставала быть дельтой.
     * Донорский сервер поступает так же: его трекер помечает чанк грязным при смене
     * статуса или набора тикетов, а не при тиканье счётчика.
     *
     * <p>Плата: цифра обратного отсчёта на карте обновляется не каждую секунду, а когда
     * у чанка меняется что-то ещё. Для чтения карты это несущественно, а разница в
     * трафике — на два порядка.
     */
    private static boolean same(final ChunkMapProtocol.ChunkInfo now,
                                final ChunkMapProtocol.ChunkInfo before) {
        if (before == null) {
            return false;
        }
        if (now.statusLevel() != before.statusLevel()
            || now.tickingStatusLevel() != before.tickingStatusLevel()
            || now.unloading() != before.unloading()
            || !java.util.Objects.equals(now.stage(), before.stage())
            || now.tickets().size() != before.tickets().size()) {
            return false;
        }
        for (int i = 0; i < now.tickets().size(); i++) {
            final var a = now.tickets().get(i);
            final var b = before.tickets().get(i);
            if (!a.type().equals(b.type()) || a.level() != b.level()) {
                return false;
            }
        }
        return true;
    }

    private static void remember(final Player player, final ServerLevel level,
                                 final List<ChunkMapProtocol.ChunkInfo> chunks) {
        final Map<Long, ChunkMapProtocol.ChunkInfo> map = new HashMap<>(chunks.size());
        for (final ChunkMapProtocol.ChunkInfo info : chunks) {
            map.put(info.position().pack(), info);
        }
        SENT.computeIfAbsent(player.getUniqueId(), key -> new HashMap<>())
            .put(level.dimension(), map);
    }

    /**
     * Подробный лог обмена. Включается системным свойством
     * {@code -Dpaperlab.chunkdebug.debug=true}: без него в норме молчим, а при разборе
     * «карта пустая» без него не обойтись — клиент об ошибках не сообщает вообще.
     */
    private static final boolean DEBUG =
        Boolean.getBoolean("paperlab.chunkdebug.debug");

    private static @org.jetbrains.annotations.Nullable ServerLevel levelOf(final ResourceKey<Level> dimension) {
        for (final org.bukkit.World world : Bukkit.getWorlds()) {
            final ServerLevel level = ((CraftWorld) world).getHandle();
            if (level.dimension().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Отправка в обход {@code Player#sendPluginMessage}.
     *
     * <p>Штатный путь Bukkit молча <b>ничего не делает</b>, если клиент ещё не объявил канал
     * через {@code minecraft:register} ({@code CraftPlayer.sendPluginMessage}: проверка
     * {@code channels().contains(channel)}). У мода ChunkDebug объявление приходит своим
     * темпом, и рукопожатие через тик после входа в эту проверку не укладывается —
     * первый {@code hello} просто исчезал.
     *
     * <p>Сам мод шлёт и принимает без всяких проверок регистрации, поэтому кладём пакет
     * в соединение напрямую. Тело для неизвестного серверу канала Paper хранит как есть
     * ({@code DiscardedPayload}), кодеки регистрировать не нужно.
     */
    private static void send(final Player player, final String channel, final byte[] body) {
        final var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection == null) {
            return;
        }
        connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
            new net.minecraft.network.protocol.common.custom.DiscardedPayload(
                net.minecraft.resources.Identifier.parse(channel), body)));
    }

    public static int watcherCount() {
        return WATCHERS.size();
    }
}
