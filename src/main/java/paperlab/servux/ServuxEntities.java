package paperlab.servux;

import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import paperlab.command.LabPermissions;

/**
 * Канал {@code servux:entity_data} — данные сущности и блока под прицелом.
 *
 * <p>Ради этого канала MiniHUD показывает содержимое сундука на расстоянии, инвентарь моба
 * и полный NBT того, на что смотришь. Без серверной части клиенту эти данные просто негде
 * взять: ваниль их не рассылает.
 *
 * <p><b>Это чтение чужого NBT.</b> Поэтому у канала своё право
 * {@code paperlab.servux.entities} и оно не выдаётся заодно с HUD: видеть содержимое любого
 * сундука в зоне видимости — заметно больше, чем видеть TPS.
 *
 * <h2>Протокол</h2>
 * <pre>
 * C2S 2  запрос метаданных            → S2C 1  метаданные (version=2)
 * C2S 3  varint тип + BlockPos        → S2C 5  varint тип + BlockPos + NBT
 * C2S 4  varint тип + varint id       → S2C 6  varint тип + varint id + NBT
 * C2S 7  отписка
 * </pre>
 *
 * <p>NBT в ответах кодируется так же, как везде у malilib: {@code varint(-1)} и следом
 * сетевой NBT. Это выяснилось по дампу настоящего пакета Litematica, см. {@link ServuxWire}.
 */
public final class ServuxEntities implements PluginMessageListener {

    public static final String CHANNEL = "servux:entity_data";

    /** У этого канала версия 2, как у litematics. */
    public static final int PROTOCOL_VERSION = 2;

    private static final int S2C_METADATA = 1;
    private static final int C2S_METADATA_REQUEST = 2;
    private static final int C2S_BLOCK_ENTITY_REQUEST = 3;
    private static final int C2S_ENTITY_REQUEST = 4;
    private static final int S2C_BLOCK_NBT_RESPONSE = 5;
    private static final int S2C_ENTITY_NBT_RESPONSE = 6;
    private static final int C2S_UNREGISTER_REPLY = 7;

    /**
     * Насколько далеко разрешено спрашивать.
     *
     * <p>Ограничение наше, у Servux его нет. Без него запрос — это чтение любого блока мира
     * по координате, и право на подсказки в HUD незаметно превращается в право осмотреть
     * чужую базу.
     */
    private static final double MAX_DISTANCE = 128.0D;

    private static final boolean DEBUG = Boolean.getBoolean("paperlab.servux.debug");

    private static final Set<UUID> REGISTERED = ConcurrentHashMap.newKeySet();
    private static Plugin plugin;

    public static void enable(final Plugin owner) {
        plugin = owner;
        Bukkit.getMessenger().registerIncomingPluginChannel(owner, CHANNEL, new ServuxEntities());
        Bukkit.getMessenger().registerOutgoingPluginChannel(owner, CHANNEL);
    }

    public static void disable() {
        REGISTERED.clear();
    }

    public static void onQuit(final Player player) {
        REGISTERED.remove(player.getUniqueId());
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
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(message));
            final int type = buf.readVarInt();
            if (DEBUG) {
                plugin.getLogger().info("Servux entities: in type " + type
                    + " from " + player.getName());
            }
            switch (type) {
                case C2S_METADATA_REQUEST -> onRegister(player);
                case C2S_UNREGISTER_REPLY -> REGISTERED.remove(player.getUniqueId());
                case C2S_BLOCK_ENTITY_REQUEST -> {
                    // MiniHUD 0.40.4 шлёт varint transactionId перед BlockPos (8 байт).
                    if (buf.readableBytes() > 8) {
                        buf.readVarInt();
                    }
                    onBlockRequest(player, buf.readBlockPos());
                }
                case C2S_ENTITY_REQUEST -> {
                    // MiniHUD 0.40.4 шлёт varint transactionId перед varint entityId.
                    final int first = buf.readVarInt();
                    final int entityId = buf.isReadable() ? buf.readVarInt() : first;
                    onEntityRequest(player, entityId);
                }
                default -> {
                }
            }
        } catch (final Throwable t) {
            plugin.getLogger().warning("Servux entities: bad packet from "
                + player.getName() + ": " + t);
        }
    }

    private void onRegister(final Player player) {
        if (!player.hasPermission(LabPermissions.SERVUX_ENTITIES)) {
            if (DEBUG) {
                plugin.getLogger().info("Servux entities: " + player.getName()
                    + " has no " + LabPermissions.SERVUX_ENTITIES);
            }
            return;
        }
        REGISTERED.add(player.getUniqueId());

        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "entity_data");
        tag.putString("id", CHANNEL);
        tag.putInt("version", PROTOCOL_VERSION);
        tag.putString("servux", ServuxHud.versionString());

        send(player, ServuxWire.metadata(S2C_METADATA, tag));
        if (DEBUG) {
            plugin.getLogger().info("Servux entities: metadata → " + player.getName());
        }
    }

    private void onBlockRequest(final Player player, final BlockPos pos) {
        if (!allowed(player)) {
            return;
        }
        final ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
        if (!withinReach(player, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)) {
            return;
        }
        // Только уже загруженный чанк: подсказка в HUD не повод грузить мир.
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return;
        }
        final BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) {
            return;
        }
        final CompoundTag data = capture(level, entity.problemPath(),
            output -> entity.saveWithId(output));

        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(S2C_BLOCK_NBT_RESPONSE);
        buf.writeBlockPos(pos);
        ServuxWire.appendNbtBody(buf, data);
        send(player, drain(buf));
    }

    private void onEntityRequest(final Player player, final int entityId) {
        if (!allowed(player)) {
            return;
        }
        final ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
        final Entity entity = level.getEntity(entityId);
        if (entity == null || !withinReach(player, entity.getX(), entity.getY(), entity.getZ())) {
            return;
        }
        if (entity instanceof net.minecraft.server.level.ServerPlayer targetPlayer
            && !targetPlayer.getUUID().equals(player.getUniqueId())
            && !player.hasPermission(LabPermissions.SERVUX_ENTITIES_PLAYERS)) {
            return;
        }
        final CompoundTag data = capture(level, entity.problemPath(),
            output -> entity.saveWithoutId(output));
        final var typeKey = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (typeKey != null) {
            data.putString("id", typeKey.toString());
        }

        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(S2C_ENTITY_NBT_RESPONSE);
        buf.writeVarInt(entityId);
        ServuxWire.appendNbtBody(buf, data);
        send(player, drain(buf));
    }

    /**
     * Снять NBT через {@code ValueOutput} и вернуть обычный компаунд.
     *
     * <p>В 26.2 сущности и тайл-энтити пишутся не в {@code CompoundTag} напрямую, а через
     * {@code ValueOutput}; для отправки по проводу нужен именно компаунд.
     */
    static CompoundTag capture(final ServerLevel level,
                               final net.minecraft.util.ProblemReporter.PathElement path,
                               final java.util.function.Consumer<
                                   net.minecraft.world.level.storage.ValueOutput> writer) {
        try (final net.minecraft.util.ProblemReporter.ScopedCollector reporter =
                 new net.minecraft.util.ProblemReporter.ScopedCollector(
                     path, org.slf4j.LoggerFactory.getLogger("PaperLab"))) {
            final var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                reporter, level.registryAccess());
            writer.accept(output);
            return output.buildResult();
        }
    }

    private static boolean allowed(final Player player) {
        return REGISTERED.contains(player.getUniqueId())
            && player.hasPermission(LabPermissions.SERVUX_ENTITIES);
    }

    private static boolean withinReach(final Player player, final double x, final double y, final double z) {
        final var location = player.getLocation();
        final double dx = location.getX() - x;
        final double dy = location.getY() - y;
        final double dz = location.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= MAX_DISTANCE * MAX_DISTANCE;
    }

    private static byte[] drain(final FriendlyByteBuf buf) {
        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    private static void send(final Player player, final byte[] body) {
        final var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null) {
            connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(Identifier.parse(CHANNEL), body)));
        }
    }
}
