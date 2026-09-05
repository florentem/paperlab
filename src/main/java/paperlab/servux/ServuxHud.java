package paperlab.servux;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import paperlab.command.LabPermissions;

/**
 * Канал {@code servux:hud_metadata} — то, что MiniHUD ждёт от серверной части.
 *
 * <p><b>Зачем свой, а не поставить Servux.</b> Servux — фабричный мод с миксинами;
 * на Paper он не запустится. Клиенту при этом безразлично, кто отвечает на канале, лишь бы
 * формат совпадал. Поэтому отвечаем сами — и заодно можем раздавать это по правам, а не
 * по одному общему уровню оператора, как в оригинале.
 *
 * <h2>Рукопожатие — единственная точка, где нельзя ошибиться</h2>
 * MiniHUD сверяет два поля метаданных: {@code version} должно совпасть с версией протокола
 * (3), а {@code servux} — начинаться с {@code servux-fabric-<версия MC>}. При несовпадении
 * он <b>молча</b> считает сервер неподходящим: ни ошибки, ни строки в логе на клиенте.
 * Отсюда и строка версии ниже, собранная из {@code SharedConstants}.
 *
 * <p>Слово {@code fabric} в строке — не описка: MiniHUD сверяет с собственным
 * {@code MOD_TYPE}, а он у клиента всегда {@code fabric}. К нашей платформе это отношения
 * не имеет.
 *
 * <h2>Что уже отвечаем</h2>
 * <ul>
 *   <li>{@code METADATA} — рукопожатие, точка мирового спавна, при желании сид;</li>
 *   <li>{@code SPAWN_DATA} — то же по отдельному запросу, когда спавн меняется.</li>
 * </ul>
 *
 * <p>Остальные типы ({@code weather}, {@code data logger} с TPS и мобкапами, рецепты)
 * пока принимаются и игнорируются — но именно принимаются, чтобы клиент не считал канал
 * сломанным.
 */
public final class ServuxHud implements PluginMessageListener {

    public static final String CHANNEL = "servux:hud_metadata";

    /** Версия протокола Servux для этого канала. Обязана совпасть с клиентской. */
    public static final int PROTOCOL_VERSION = 3;

    // Типы пакетов — по ServuxHudPacket.Type.
    private static final int S2C_METADATA = 1;
    private static final int C2S_METADATA_REQUEST = 2;
    private static final int S2C_SPAWN_DATA = 3;
    private static final int S2C_WEATHER_TICK = 5;
    private static final int S2C_DATA_LOGGER_TICK = 7;
    private static final int C2S_SPAWN_DATA_REQUEST = 4;
    private static final int C2S_RECIPE_MANAGER_REQUEST = 6;
    private static final int C2S_DATA_LOGGER_REQUEST = 8;
    private static final int C2S_UNREGISTER_REPLY = 9;

    private static final boolean DEBUG = Boolean.getBoolean("paperlab.servux.debug");

    private static final Set<UUID> REGISTERED = ConcurrentHashMap.newKeySet();

    /** Кто на какие логгеры подписан. Имена — как в протоколе Servux. */
    private static final Map<UUID, Set<String>> LOGGERS = new ConcurrentHashMap<>();

    private static final String LOGGER_TPS = "tps";
    private static final String LOGGER_MOB_CAPS = "mob_caps";

    /** Как часто уходит тик логгеров. Столько же у Servux. */
    private static final int LOGGER_PERIOD_TICKS = 15;

    /** Как часто уходит тик погоды (40 тиков = 2 секунды). */
    private static final int WEATHER_PERIOD_TICKS = 40;

    /** Делитель площади в ванильной формуле глобального капа: 17 x 17 чанков. */
    private static final int SPAWN_AREA_CHUNKS = 17 * 17;

    private static long tickCounter;
    private static Plugin plugin;

    /**
     * Отдавать ли сид мира. По умолчанию нет: сид — это знание о мире, которое обычный
     * игрок иначе не получит, и раздавать его молча неправильно.
     */
    private static volatile boolean shareSeed;

    public static void enable(final Plugin owner) {
        plugin = owner;
        Bukkit.getMessenger().registerIncomingPluginChannel(owner, CHANNEL, new ServuxHud());
        Bukkit.getMessenger().registerOutgoingPluginChannel(owner, CHANNEL);
    }

    public static void disable() {
        REGISTERED.clear();
        LOGGERS.clear();
    }

    public static void onQuit(final Player player) {
        REGISTERED.remove(player.getUniqueId());
        LOGGERS.remove(player.getUniqueId());
    }

    public static void setShareSeed(final boolean value) {
        shareSeed = value;
    }

    public static boolean shareSeed() {
        return shareSeed;
    }

    public static int registeredCount() {
        return REGISTERED.size();
    }

    @Override
    public void onPluginMessageReceived(final @NotNull String channel,
                                        final @NotNull Player player,
                                        final byte @NotNull [] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try {
            final int type = ServuxWire.readType(message);
            if (DEBUG) {
                plugin.getLogger().info("Servux hud: in type " + type + " from " + player.getName());
            }
            switch (type) {
                case C2S_METADATA_REQUEST -> onMetadataRequest(player);
                case C2S_SPAWN_DATA_REQUEST -> sendSpawnData(player);
                case C2S_DATA_LOGGER_REQUEST -> onLoggerRequest(player, message);
                case C2S_UNREGISTER_REPLY -> {
                    REGISTERED.remove(player.getUniqueId());
                    LOGGERS.remove(player.getUniqueId());
                }
                // Приняли и промолчали: клиенту важно, что канал жив.
                case C2S_RECIPE_MANAGER_REQUEST -> {
                }
                default -> {
                    if (DEBUG) {
                        plugin.getLogger().info("Servux hud: unhandled type " + type);
                    }
                }
            }
        } catch (final Throwable t) {
            plugin.getLogger().warning("Servux hud: malformed packet from "
                + player.getName() + ": " + t);
        }
    }

    private void onMetadataRequest(final Player player) throws IOException {
        if (!player.hasPermission(LabPermissions.SERVUX_HUD)) {
            if (DEBUG) {
                plugin.getLogger().info("Servux hud: " + player.getName()
                    + " has no " + LabPermissions.SERVUX_HUD);
            }
            return;
        }
        REGISTERED.add(player.getUniqueId());

        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "hud_data");
        tag.putString("id", CHANNEL);
        tag.putInt("version", PROTOCOL_VERSION);
        tag.putString("servux", versionString());
        putSpawn(tag, player);
        putSeed(tag, player);
        putLoggers(tag);

        send(player, ServuxWire.metadata(S2C_METADATA, tag));
        if (DEBUG) {
            plugin.getLogger().info("Servux hud: metadata → " + player.getName());
        }
        sendWeather(player);
    }

    private void sendSpawnData(final Player player) throws IOException {
        if (!REGISTERED.contains(player.getUniqueId())) {
            return;
        }
        final CompoundTag tag = new CompoundTag();
        putSpawn(tag, player);
        putSeed(tag, player);
        send(player, ServuxWire.data(S2C_SPAWN_DATA, tag));
    }

    /** Какие логгеры этот сервер вообще умеет. Клиент по этому списку строит запрос. */
    private static void putLoggers(final CompoundTag tag) {
        final CompoundTag loggers = new CompoundTag();
        loggers.putBoolean(LOGGER_TPS, true);
        loggers.putBoolean(LOGGER_MOB_CAPS, true);
        tag.put("Loggers", loggers);
    }

    /**
     * Клиент прислал, какие логгеры ему нужны: компаунд {@code имя -> включён}.
     */
    private void onLoggerRequest(final Player player, final byte[] message) throws IOException {
        if (!REGISTERED.contains(player.getUniqueId())) {
            return;
        }
        final CompoundTag request = ServuxWire.readCompressedNbt(message);
        final Set<String> wanted = new LinkedHashSet<>();
        for (final String key : request.keySet()) {
            if ("version".equals(key)) {
                continue;
            }
            // Клиент присылает ИМЯ КОНСТАНТЫ enum: "TPS" и "MOB_CAPS", в верхнем регистре
            // (MiniHUD зовёт .name(), а не .getSerializedName()). А ответ разбирает уже по
            // сериализованному имени — "tps", "mob_caps". Читаем в любом регистре,
            // отвечаем строчными: так же, как это делает сам Servux.
            final String normalised = key.toLowerCase(java.util.Locale.ROOT);
            if (request.getBooleanOr(key, false)
                && (LOGGER_TPS.equals(normalised) || LOGGER_MOB_CAPS.equals(normalised))) {
                wanted.add(normalised);
            }
        }
        if (DEBUG) {
            // Пустой список законен: значит в MiniHUD выключены сами строки HUD
            // (SERVER_TPS и MOB_CAPS). Отличить это от неверного ключа можно только так.
            final StringBuilder dump = new StringBuilder();
            request.keySet().forEach(key ->
                dump.append(key).append('=').append(request.getBooleanOr(key, false)).append(' '));
            plugin.getLogger().info("Servux hud: logger request { " + dump + "}");
        }
        if (wanted.isEmpty()) {
            LOGGERS.remove(player.getUniqueId());
        } else {
            LOGGERS.put(player.getUniqueId(), wanted);
            sendLoggerData(player, wanted);
        }
        if (DEBUG) {
            plugin.getLogger().info("Servux hud: " + player.getName() + " loggers " + wanted);
        }
    }

    /** Тик логгеров и погоды. Вызывается из общего тика плагина. */
    public static void tick() {
        tickCounter++;
        if (!REGISTERED.isEmpty() && tickCounter % WEATHER_PERIOD_TICKS == 0) {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                if (REGISTERED.contains(player.getUniqueId())) {
                    sendWeather(player);
                }
            }
        }
        if (LOGGERS.isEmpty() || tickCounter % LOGGER_PERIOD_TICKS != 0) {
            return;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final Set<String> wanted = LOGGERS.get(player.getUniqueId());
            if (wanted == null || wanted.isEmpty()) {
                continue;
            }
            sendLoggerData(player, wanted);
        }
    }

    private static void sendLoggerData(final Player player, final Set<String> wanted) {
        try {
            final CompoundTag tag = new CompoundTag();
            if (wanted.contains(LOGGER_TPS)) {
                tag.put(LOGGER_TPS, tpsData());
            }
            if (wanted.contains(LOGGER_MOB_CAPS)) {
                tag.put(LOGGER_MOB_CAPS, mobCapData());
            }
            send(player, ServuxWire.data(S2C_DATA_LOGGER_TICK, tag));
        } catch (final Throwable t) {
            plugin.getLogger().warning("Servux hud: logger send failed for "
                + player.getName() + ": " + t);
            LOGGERS.remove(player.getUniqueId());
        }
    }

    private static void sendWeather(final Player player) {
        if (!REGISTERED.contains(player.getUniqueId())) {
            return;
        }
        try {
            send(player, ServuxWire.data(S2C_WEATHER_TICK, weatherData(player)));
        } catch (final Throwable t) {
            if (DEBUG) {
                plugin.getLogger().warning("Servux hud: failed to send weather to "
                    + player.getName() + ": " + t);
            }
        }
    }

    public static CompoundTag weatherData(final Player player) {
        final org.bukkit.World world = Bukkit.getWorlds().isEmpty()
            ? (player != null ? player.getWorld() : null)
            : Bukkit.getWorlds().get(0);
        final CompoundTag tag = new CompoundTag();
        if (world == null) {
            tag.putBoolean("isRaining", false);
            tag.putBoolean("isThundering", false);
            tag.putInt("SetClear", 24000);
            return tag;
        }
        final ServerLevel overworld = ((CraftWorld) world).getHandle();
        final boolean raining = overworld.isRaining();
        final boolean thundering = overworld.isThundering();
        final var nmsWeather = overworld.getWeatherData();
        final int clearTime = nmsWeather.getClearWeatherTime();
        final int rainTime = nmsWeather.getRainTime();
        final int thunderTime = nmsWeather.getThunderTime();

        if (raining && rainTime > -1) {
            tag.putInt("SetRaining", rainTime);
            tag.putBoolean("isRaining", true);
        } else {
            tag.putBoolean("isRaining", false);
            final int clearRemaining = clearTime > 0 ? clearTime : (rainTime > 0 ? rainTime : 0);
            tag.putInt("SetClear", clearRemaining);
        }

        if (thundering && thunderTime > -1) {
            tag.putInt("SetThundering", thunderTime);
            tag.putBoolean("isThundering", true);
        } else {
            tag.putBoolean("isThundering", false);
        }

        return tag;
    }

    public static CompoundTag tpsData() {
        final var manager = Bukkit.getServerTickManager();
        final CompoundTag tag = new CompoundTag();
        tag.putDouble("mspt", Bukkit.getAverageTickTime());
        tag.putDouble("tps", Math.min(Bukkit.getTPS()[0], 20.0D));
        // Сколько тиков осталось спринту, наружу не отдаётся; ноль честнее выдумки.
        tag.putLong("sprintTicks", 0L);
        tag.putBoolean("frozen", manager.isFrozen());
        tag.putBoolean("sprinting", manager.isSprinting());
        tag.putBoolean("stepping", manager.isStepping());
        return tag;
    }

    /**
     * Мобкапы в том виде, в каком их ждёт MiniHUD.
     * MiniHUD ожидает компаунд сопоставлений dimKey -> { WorldTick, cap_count, cap_data: [...] }.
     * Каждая запись cap_data содержит { current, cap }.
     */
    public static CompoundTag mobCapData() {
        final CompoundTag root = new CompoundTag();
        for (final org.bukkit.World bukkitWorld : Bukkit.getWorlds()) {
            final ServerLevel world = ((CraftWorld) bukkitWorld).getHandle();
            final String dimKey = world.dimension().identifier().toString();
            final NaturalSpawner.SpawnState state = world.getChunkSource().getLastSpawnState();

            final ListTag caps = new ListTag();
            final int spawnableChunks = state != null ? state.getSpawnableChunkCount() : 0;

            for (final MobCategory category : MobCategory.values()) {
                final CompoundTag cap = new CompoundTag();
                if (state == null || spawnableChunks <= 0) {
                    cap.putInt("current", 0);
                    cap.putInt("cap", 0);
                } else {
                    final int current = state.getMobCategoryCounts().getInt(category);
                    final int capacity = category.getMaxInstancesPerChunk() * spawnableChunks / SPAWN_AREA_CHUNKS;
                    cap.putInt("current", current);
                    cap.putInt("cap", capacity);
                }
                caps.add(cap);
            }

            final CompoundTag nbtEntry = new CompoundTag();
            nbtEntry.putLong("WorldTick", world.getGameTime());
            nbtEntry.putInt("cap_count", MobCategory.values().length);
            nbtEntry.put("cap_data", caps);

            root.put(dimKey, nbtEntry);
        }
        return root;
    }

    /**
     * Точка мирового спавна.
     *
     * <p>Берётся из мира <b>овера</b>, а не из мира игрока: MiniHUD показывает именно
     * мировой спавн, и в незере он не меняется.
     */
    private static void putSpawn(final CompoundTag tag, final Player player) {
        final ServerLevel overworld = ((CraftWorld) Bukkit.getWorlds().get(0)).getHandle();
        final var spawn = overworld.serverLevelData.getRespawnData().pos();
        tag.putString("spawnDimension", overworld.dimension().identifier().toString());
        tag.putInt("spawnPosX", spawn.getX());
        tag.putInt("spawnPosY", spawn.getY());
        tag.putInt("spawnPosZ", spawn.getZ());
    }

    private static void putSeed(final CompoundTag tag, final Player player) {
        if (shareSeed && player.hasPermission(LabPermissions.SERVUX_SEED)) {
            tag.putLong("worldSeed", ((CraftWorld) Bukkit.getWorlds().get(0)).getHandle().getSeed());
        }
    }

    /**
     * Строка версии в том виде, в каком её ждёт MiniHUD:
     * {@code servux-fabric-<версия MC>-<что угодно>}.
     */
    public static String versionString() {
        return "servux-fabric-" + SharedConstants.getCurrentVersion().id() + "-paperlab";
    }

    /**
     * Отправка в обход {@code sendPluginMessage}: тот молча ничего не делает, пока клиент
     * не объявил канал через {@code minecraft:register}, а объявление приходит своим темпом.
     * На этом мы уже обожглись с ChunkDebug.
     */
    private static void send(final Player player, final byte[] body) {
        final var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null) {
            connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(Identifier.parse(CHANNEL), body)));
        }
    }
}
