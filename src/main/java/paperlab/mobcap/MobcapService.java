package paperlab.mobcap;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import paperlab.ghost.LabGhost;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.craftbukkit.util.CraftSpawnCategory;
import org.bukkit.entity.SpawnCategory;
import org.jetbrains.annotations.Nullable;

/**
 * Чтение локального мобкапа Paper без изменения состояния.
 *
 * <p>Из плагина это возможно целиком: {@code ServerPlayer.mobCounts} и
 * {@code mobBackoffCounts} — публичные поля, {@code ChunkMap.getMobCountNear} —
 * публичный метод, а лимит берётся тем же способом, что и в
 * {@code NaturalSpawner.spawnForChunk}.
 *
 * <p>Не добавляет chunk tickets, не грузит чанки, не вызывает RNG. Все запросы
 * выполняются на главном потоке сервера.
 */
public final class MobcapService {

    private MobcapService() {
    }

    /**
     * Кап монстров — единственная категория, которая нужна для ферм.
     *
     * @param counted учтённые движком мобы
     * @param backoff штраф за неудачные попытки спавна; это не живые мобы
     * @param limit   действующий лимит из bukkit.yml
     */
    public record MonsterCap(int counted, int backoff, int limit) {

        /** Ровно то, что движок вычитает из лимита: {@code getMobCountNear}. */
        public int effective() {
            return this.counted + this.backoff;
        }
    }

    public static MonsterCap monsterCap(final ServerPlayer player,
                                        final ServerLevel level,
                                        final boolean local) {
        final MobCategory category = MobCategory.MONSTER;
        final SpawnCategory spawnCategory = CraftSpawnCategory.toBukkit(category);
        final int limit = CraftSpawnCategory.isValidForLimits(spawnCategory)
            ? level.getWorld().getSpawnLimit(spawnCategory)
            : category.getMaxInstancesPerChunk();

        if (local) {
            final int index = category.ordinal();
            return new MonsterCap(player.mobCounts[index], player.mobBackoffCounts[index], limit);
        }
        final net.minecraft.world.level.NaturalSpawner.SpawnState state =
            level.getChunkSource().getLastSpawnState();
        final int counted = state == null
            ? 0
            : Math.max(0, state.getMobCategoryCounts().getOrDefault(category, 0));
        return new MonsterCap(counted, 0, limit);
    }

    /**
     * Кто именно ограничивает бюджет конкретного чанка.
     *
     * <p>Движок берёт <b>минимальный</b> остаток среди игроков, у которых чанк попадает
     * в {@code TICK_VIEW_DISTANCE}. Второй игрок рядом бюджет не увеличивает — он может
     * только урезать. Именно этого не показывает штатный {@code /paper playermobcaps}.
     *
     * <p><b>Оговорка для плагина.</b> Наблюдателя мы здесь пропускаем ради вывода, но
     * движок его всё равно учитывает: исключить игрока из переписи без правки ядра нельзя.
     * То есть строка покажет «не ограничивает», а на деле ограничение будет.
     */
    public static LimitingPlayer limitingPlayer(final ServerLevel level,
                                                final ChunkPos chunkPos,
                                                final MobCategory category) {
        int limit = category.getMaxInstancesPerChunk();
        final SpawnCategory spawnCategory = CraftSpawnCategory.toBukkit(category);
        if (CraftSpawnCategory.isValidForLimits(spawnCategory)) {
            limit = level.getWorld().getSpawnLimit(spawnCategory);
        }

        final NearbyPlayers nearbyPlayers = level.moonrise$getNearbyPlayers();
        final ReferenceList<ServerPlayer> inRange =
            nearbyPlayers.getPlayers(chunkPos, NearbyPlayers.NearbyMapType.TICK_VIEW_DISTANCE);
        if (inRange == null || inRange.size() == 0) {
            return new LimitingPlayer(null, 0, 0, limit);
        }

        final ServerPlayer[] raw = inRange.getRawDataUnchecked();
        final int len = inRange.size();

        int minDiff = Integer.MAX_VALUE;
        ServerPlayer worst = null;
        for (int i = 0; i < len; i++) {
            final ServerPlayer candidate = raw[i];
            final int diff = limit - level.getChunkSource().chunkMap.getMobCountNear(candidate, category);
            if (diff < minDiff) {
                minDiff = diff;
                worst = candidate;
            }
        }

        final int maxSpawns = minDiff == Integer.MAX_VALUE ? 0 : minDiff;
        return new LimitingPlayer(
            worst == null ? null : worst.getScoreboardName(), len, maxSpawns, limit);
    }

    /**
     * @param playerName     ограничивающий игрок; {@code null}, если в области нет игроков
     * @param playersInRange сколько игроков держат чанк в simulation distance
     * @param maxSpawns      бюджет чанка; {@code <= 0} — спавна нет
     * @param limit          действующий лимит категории
     */
    public record LimitingPlayer(@Nullable String playerName, int playersInRange, int maxSpawns, int limit) {

        public boolean canSpawn() {
            return this.maxSpawns > 0;
        }
    }

    public static boolean perPlayerEnabled(final ServerLevel level) {
        return level.paperConfig().entities.spawning.perPlayerMobSpawns;
    }

    public static boolean countAllMobs(final ServerLevel level) {
        return level.paperConfig().entities.spawning.countAllMobsForSpawning;
    }
}
