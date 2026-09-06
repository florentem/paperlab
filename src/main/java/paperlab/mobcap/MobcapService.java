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
 * Reading Paper's local mobcap without changing any state.
 *
 * <p>A plugin can do this in full: {@code ServerPlayer.mobCounts} and
 * {@code mobBackoffCounts} are public fields, {@code ChunkMap.getMobCountNear} is a public
 * method, and the limit is obtained the same way {@code NaturalSpawner.spawnForChunk} does it.
 *
 * <p>Adds no chunk tickets, loads no chunks, consumes no RNG. Every query runs on the main
 * server thread.
 */
public final class MobcapService {

    private MobcapService() {
    }

    /**
     * The monster cap — the only category farms care about.
     *
     * @param counted mobs counted by the engine
     * @param backoff penalty for failed spawn attempts; these are not live mobs
     * @param limit   the effective limit from bukkit.yml
     */
    public record MonsterCap(int counted, int backoff, int limit) {

        /** Exactly what the engine subtracts from the limit: {@code getMobCountNear}. */
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
     * Who exactly is limiting a given chunk's budget.
     *
     * <p>The engine takes the <b>smallest</b> headroom among the players whose
     * {@code TICK_VIEW_DISTANCE} covers the chunk. A second player nearby does not increase the
     * budget — they can only cut it. That is precisely what the stock
     * {@code /paper playermobcaps} does not show.
     *
     * <p><b>A plugin caveat.</b> We skip an observer here for the sake of the output, but the
     * engine still counts them: a player cannot be excluded from the census without a core
     * patch. So the line will say "not limiting" while a limit is in fact applied.
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
     * @param playerName     the limiting player; {@code null} if no player is in range
     * @param playersInRange how many players hold the chunk within simulation distance
     * @param maxSpawns      the chunk budget; {@code <= 0} means no spawning
     * @param limit          the effective category limit
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
