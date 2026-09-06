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
 * The {@code servux:hud_metadata} channel — what MiniHUD expects from a server side.
 *
 * <p><b>Why our own rather than installing Servux.</b> Servux is a Fabric mod with mixins;
 * it will not start on Paper. The client, meanwhile, does not care who answers on a channel
 * as long as the format matches. So we answer ourselves — and can hand this out by
 * permission rather than by a single blanket operator level, as the original does.
 *
 * <h2>The handshake is the one place you cannot get wrong</h2>
 * MiniHUD checks two metadata fields: {@code version} must equal the protocol version (3),
 * and {@code servux} must start with {@code servux-fabric-<MC version>}. On a mismatch it
 * <b>silently</b> decides the server is unsuitable: no error, no client log line. Hence the
 * version string below, assembled from {@code SharedConstants}.
 *
 * <p>The word {@code fabric} in that string is not a typo: MiniHUD compares against its own
 * {@code MOD_TYPE}, which on a client is always {@code fabric}. It says nothing about our
 * platform.
 *
 * <h2>What we already answer</h2>
 * <ul>
 *   <li>{@code METADATA} — handshake, world spawn point, and the seed if allowed;</li>
 *   <li>{@code SPAWN_DATA} — the same on a separate request, when the spawn changes.</li>
 * </ul>
 *
 * <p>The remaining types ({@code weather}, the {@code data logger} carrying TPS and mobcaps,
 * recipes) are accepted and ignored for now — accepted specifically so the client does not
 * consider the channel broken.
 */
public final class ServuxHud implements PluginMessageListener {

    public static final String CHANNEL = "servux:hud_metadata";

    /** Servux protocol version for this channel. Must match the client's. */
    public static final int PROTOCOL_VERSION = 3;

    // Packet types, from ServuxHudPacket.Type.
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

    /** Who is subscribed to which loggers. The names follow the Servux protocol. */
    private static final Map<UUID, Set<String>> LOGGERS = new ConcurrentHashMap<>();

    private static final String LOGGER_TPS = "tps";
    private static final String LOGGER_MOB_CAPS = "mob_caps";

    /** How often the logger tick goes out. Same value as Servux. */
    private static final int LOGGER_PERIOD_TICKS = 15;

    /** How often the weather tick goes out (40 ticks = 2 seconds). */
    private static final int WEATHER_PERIOD_TICKS = 40;

    /** Area divisor in the vanilla global cap formula: 17 x 17 chunks. */
    private static final int SPAWN_AREA_CHUNKS = 17 * 17;

    private static long tickCounter;
    private static Plugin plugin;

    /**
     * Whether to hand out the world seed. Off by default: a seed is knowledge about the world
     * an ordinary player could not otherwise obtain, and giving it away silently would be
     * wrong.
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
                // Accepted and ignored: what matters to the client is that the channel is alive.
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

    /** Which loggers this server supports at all. The client builds its request from this list. */
    private static void putLoggers(final CompoundTag tag) {
        final CompoundTag loggers = new CompoundTag();
        loggers.putBoolean(LOGGER_TPS, true);
        loggers.putBoolean(LOGGER_MOB_CAPS, true);
        tag.put("Loggers", loggers);
    }

    /**
     * The client has sent which loggers it wants: a compound of {@code name -> enabled}.
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
            // The client sends the ENUM CONSTANT NAME: "TPS" and "MOB_CAPS", upper case
            // (MiniHUD calls .name(), not .getSerializedName()). But it parses the response by
            // the serialized name — "tps", "mob_caps". So we read case-insensitively and answer
            // in lower case, exactly as Servux itself does.
            final String normalised = key.toLowerCase(java.util.Locale.ROOT);
            if (request.getBooleanOr(key, false)
                && (LOGGER_TPS.equals(normalised) || LOGGER_MOB_CAPS.equals(normalised))) {
                wanted.add(normalised);
            }
        }
        if (DEBUG) {
            // An empty list is legitimate: it means the HUD lines themselves (SERVER_TPS and
            // MOB_CAPS) are off in MiniHUD. This is the only way to tell that from a bad key.
            final StringBuilder dump = new StringBuilder();
            request.keySet().forEach(key ->
                dump.append(key).append('=').append(request.getBooleanOr(key, false)).append(' '));
            plugin.getLogger().info("Servux hud: logger request { " + dump + "}");
        }
        if (wanted.isEmpty()) {
            LOGGERS.remove(player.getUniqueId());
        } else {
            final Set<String> copy = Set.copyOf(wanted);
            LOGGERS.put(player.getUniqueId(), copy);
            sendLoggerData(player, copy);
        }
        if (DEBUG) {
            plugin.getLogger().info("Servux hud: " + player.getName() + " loggers " + wanted);
        }
    }

    /** Logger and weather tick. Called from the plugin's shared tick. */
    public static void tick() {
        tickCounter++;
        if (tickCounter >= 1_000_000) {
            tickCounter = 0;
        }
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
        // How many sprint ticks remain is not exposed; zero is honester than a guess.
        tag.putLong("sprintTicks", 0L);
        tag.putBoolean("frozen", manager.isFrozen());
        tag.putBoolean("sprinting", manager.isSprinting());
        tag.putBoolean("stepping", manager.isStepping());
        return tag;
    }

    /**
     * Mobcaps in the shape MiniHUD expects: a compound mapping
     * dimKey -&gt; { WorldTick, cap_count, cap_data: [...] }, where each cap_data entry holds
     * { current, cap }.
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
     * The world spawn point.
     *
     * <p>Taken from the <b>overworld</b> rather than the player's world: MiniHUD shows the
     * world spawn, and it does not change in the nether.
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
     * The version string in the shape MiniHUD expects:
     * {@code servux-fabric-<MC version>-<anything>}.
     */
    public static String versionString() {
        return "servux-fabric-" + SharedConstants.getCurrentVersion().id() + "-paperlab";
    }

    /**
     * Sending that bypasses {@code sendPluginMessage}: that method silently does nothing until
     * the client has announced the channel via {@code minecraft:register}, and the announcement
     * arrives at its own pace. We already got burned by this with ChunkDebug.
     */
    private static void send(final Player player, final byte[] body) {
        final var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null) {
            connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(Identifier.parse(CHANNEL), body)));
        }
    }
}
