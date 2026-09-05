package paperlab.servux;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import paperlab.command.LabPermissions;

/**
 * Канал {@code servux:structures} — рамки структур в MiniHUD.
 *
 * <p>Пока реализовано <b>только рукопожатие</b>: клиент регистрируется, сервер отвечает
 * метаданными, и MiniHUD перестаёт считать канал мёртвым. Сами данные структур ещё не
 * шлются.
 *
 * <p><b>Почему разделено.</b> Данные структур ходят не одним пакетом, а через «разрезатель»
 * Servux: {@code STRUCTURE_DATA_START} с размером, затем куски {@code STRUCTURE_DATA}
 * сырыми байтами. Тот же разрезатель нужен и каналу {@code servux:litematics}, поэтому его
 * стоит написать один раз и аккуратно, а не по разу на канал. Рукопожатие от этого не
 * зависит и полезно уже сейчас: без него клиент не покажет даже то, что сервер на связи.
 *
 * <p>Версия протокола и строка {@code servux} проверяются клиентом ровно так же, как
 * в {@link ServuxHud} — при расхождении он молча выключает оверлей.
 */
public final class ServuxStructures implements PluginMessageListener {

    public static final String CHANNEL = "servux:structures";

    /** Версия протокола Servux для этого канала. */
    public static final int PROTOCOL_VERSION = 3;

    private static final int S2C_METADATA = 1;
    private static final int S2C_STRUCTURE_DATA = 2;
    private static final int C2S_REGISTER = 3;
    private static final int C2S_UNREGISTER = 4;

    /**
     * Радиус в чанках, в котором ищем структуры вокруг игрока.
     *
     * <p>Обходим только уже загруженные чанки: подгружать мир ради рамок нельзя, иначе
     * инструмент наблюдения начнёт менять то, за чем наблюдает.
     */
    private static final int SEARCH_RADIUS_CHUNKS = 12;

    /** Как часто пересылать структуры. Чаще незачем: рамки почти не меняются. */
    private static final int SEND_PERIOD_TICKS = 20 * 10;

    private static int tickCounter;

    /**
     * Сколько секунд клиент держит полученные структуры, не получая обновлений.
     * Значение по умолчанию у Servux; клиент читает его из метаданных.
     */
    private static final int TIMEOUT_SECONDS = 300;

    private static final boolean DEBUG = Boolean.getBoolean("paperlab.servux.debug");

    private static final Set<UUID> REGISTERED = new HashSet<>();
    private static Plugin plugin;

    public static void enable(final Plugin owner) {
        plugin = owner;
        Bukkit.getMessenger().registerIncomingPluginChannel(owner, CHANNEL, new ServuxStructures());
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
            final int type = ServuxWire.readType(message);
            if (DEBUG) {
                plugin.getLogger().info("Servux structures: in type " + type
                    + " from " + player.getName());
            }
            switch (type) {
                case C2S_REGISTER -> onRegister(player);
                case C2S_UNREGISTER -> REGISTERED.remove(player.getUniqueId());
                default -> {
                }
            }
        } catch (final Throwable t) {
            plugin.getLogger().warning("Servux structures: malformed packet from "
                + player.getName() + ": " + t);
        }
    }

    private void onRegister(final Player player) {
        if (!player.hasPermission(LabPermissions.SERVUX_STRUCTURES)) {
            if (DEBUG) {
                plugin.getLogger().info("Servux structures: " + player.getName()
                    + " has no " + LabPermissions.SERVUX_STRUCTURES);
            }
            return;
        }
        REGISTERED.add(player.getUniqueId());

        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "structure_bounding_boxes");
        tag.putString("id", CHANNEL);
        tag.putInt("version", PROTOCOL_VERSION);
        tag.putString("servux", ServuxHud.versionString());
        tag.putInt("timeout", TIMEOUT_SECONDS);

        send(player, ServuxWire.metadata(S2C_METADATA, tag));
        if (DEBUG) {
            plugin.getLogger().info("Servux structures: metadata → " + player.getName());
        }
        sendStructures(player);
    }

    /** Периодическая рассылка. Вызывается из общего тика плагина. */
    public static void tick() {
        if (REGISTERED.isEmpty() || ++tickCounter % SEND_PERIOD_TICKS != 0) {
            return;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (REGISTERED.contains(player.getUniqueId())) {
                sendStructures(player);
            }
        }
    }

    /**
     * Собрать структуры вокруг игрока и отправить.
     *
     * <p>Формат подсмотрен у клиента, а не у Servux — после истории с кадрированием сверяюсь
     * только с тем кодом, который эти байты читает:
     *
     * <pre>
     * { Structures: [ { id: строка, ExpandBox: bool, Children: [ { BB: int[6] } ] } ] }
     * </pre>
     *
     * {@code BB} — {@code minX minY minZ maxX maxY maxZ}, как читает
     * {@code IntBoundingBox.fromArray}.
     */
    private static void sendStructures(final Player player) {
        try {
            final ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
            final ChunkPos centre = ((CraftPlayer) player).getHandle().chunkPosition();
            final ListTag structures = new ListTag();

            for (int x = centre.x() - SEARCH_RADIUS_CHUNKS; x <= centre.x() + SEARCH_RADIUS_CHUNKS; x++) {
                for (int z = centre.z() - SEARCH_RADIUS_CHUNKS; z <= centre.z() + SEARCH_RADIUS_CHUNKS; z++) {
                    // Только уже загруженные чанки: см. комментарий у SEARCH_RADIUS_CHUNKS.
                    final LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                    if (chunk == null) {
                        continue;
                    }
                    for (final Map.Entry<Structure, StructureStart> entry
                        : chunk.getAllStarts().entrySet()) {
                        final CompoundTag tag = describe(level, entry.getKey(), entry.getValue());
                        if (tag != null) {
                            structures.add(tag);
                        }
                    }
                }
            }

            if (structures.isEmpty()) {
                return;
            }

            final CompoundTag root = new CompoundTag();
            root.put("Structures", structures);
            ServuxSplitter.send(player, CHANNEL, S2C_STRUCTURE_DATA, encode(root));

            if (DEBUG) {
                plugin.getLogger().info("Servux structures: " + structures.size()
                    + " → " + player.getName());
            }
        } catch (final Throwable t) {
            plugin.getLogger().warning("Servux structures: failed to send to "
                + player.getName() + ": " + t);
        }
    }

    private static CompoundTag describe(final ServerLevel level,
                                        final Structure structure,
                                        final StructureStart start) {
        if (!start.isValid()) {
            return null;
        }
        final var key = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure);
        if (key == null || "minecraft:buried_treasure".equals(key.toString())) {
            return null;
        }

        final ListTag children = new ListTag();
        for (final StructurePiece piece : start.getPieces()) {
            final CompoundTag child = new CompoundTag();
            child.putIntArray("BB", box(piece.getBoundingBox()));
            children.add(child);
        }
        if (children.isEmpty()) {
            // Без частей клиент не построит рамку и просто отбросит запись.
            final CompoundTag child = new CompoundTag();
            child.putIntArray("BB", box(start.getBoundingBox()));
            children.add(child);
        }

        final CompoundTag tag = new CompoundTag();
        tag.putString("id", key.toString());
        tag.put("Children", children);
        return tag;
    }

    private static int[] box(final BoundingBox box) {
        return new int[] {
            box.minX(), box.minY(), box.minZ(),
            box.maxX(), box.maxY(), box.maxZ()
        };
    }

    /**
     * Тело для разрезателя — сетевой NBT.
     *
     * <p>Servux пишет здесь «длина + GZIP», но клиент читает кодеком malilib, а он, как
     * выяснилось на канале HUD, работает с сетевым NBT. Повторять за Servux второй раз
     * не будем: именно это разорвало соединение в прошлый раз.
     */
    private static byte[] encode(final CompoundTag tag) throws IOException {
        final io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
        final net.minecraft.network.FriendlyByteBuf buf =
            new net.minecraft.network.FriendlyByteBuf(buffer);
        buf.writeNbt(tag);
        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    /** Отправка в обход {@code sendPluginMessage}: причина та же, что в {@link ServuxHud}. */
    private static void send(final Player player, final byte[] body) {
        final var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null) {
            connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(Identifier.parse(CHANNEL), body)));
        }
    }
}
