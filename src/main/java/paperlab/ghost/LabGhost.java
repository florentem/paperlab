package paperlab.ghost;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import paperlab.core.CoreBridge;

/**
 * Режим наблюдателя: игрок перестаёт влиять на симуляцию, но продолжает
 * взаимодействовать с миром.
 *
 * <p>Задача — летать вдоль границы чанков и разбирать конструкцию фермы, пока её
 * обслуживает бот, и при этом не искажать то, что измеряешь.
 *
 * <p><b>Это не spectator.</b> Блоки ставятся и ломаются, контейнеры открываются,
 * инвентарь работает. Отключено только влияние на серверную симуляцию.
 *
 * <h2>Полный режим — на нашем ядре</h2>
 * Шесть точек исключения, из них три без всякого API:
 * <ol>
 *   <li>перепись мобкапа {@code ChunkMap.updatePlayerMobTypeMap} и начисление backoff
 *       {@code updateFailurePlayerMobTypeMap} — наблюдатель не занимает кап;</li>
 *   <li>{@code NaturalSpawner.spawnForChunk} — не режет бюджет чанка соседу;</li>
 *   <li>{@code ChunkMap.isChunkNearPlayer} — не расширяет область спавна;</li>
 *   <li>{@code ChunkMap.skipPlayer} — не участвует в загрузке чанков;</li>
 *   <li>{@code ActivationRange.activateEntities} — не будит мобов (EAR);</li>
 *   <li>{@code LivingEntity.canBeSeenByAnyone} — мобы не выбирают его целью.</li>
 * </ol>
 * Плюс два штатных механизма, патча не требующих: {@code affectsSpawning} (деспавн,
 * выбор позиции спавна, trial spawner) и персональная дистанция симуляции.
 *
 * <h2>Урезанный режим — на чистом Paper</h2>
 * Остаются только {@code affectsSpawning}, дистанция симуляции и невидимость.
 * Наблюдатель <b>по-прежнему занимает мобкап, будит мобов через EAR и замечается ими</b>.
 * Пролетать рядом с работающей фермой в таком режиме всё ещё искажает измерение.
 *
 * <h2>Что важно при проверке</h2>
 * Включение действует <b>не мгновенно</b>. Значение {@code sim=0} применяется сразу,
 * но снятие уже выданных ticking-тикетов у Moonrise отложенное и с ограничением
 * скорости — на стенде с 121 чанка до 1 сходится примерно за 30 секунд. Выключение,
 * наоборот, срабатывает за секунды. Принудительная переустановка игрока в загрузчике
 * задержку не убирает, поэтому лишней логики здесь нет.
 *
 * <p>Полного нуля Moonrise не поддерживает: отрицательное значение означает
 * «наследовать мировое», поэтому под самим наблюдателем остаётся один тикающий чанк.
 * Известное ограничение, а не недосмотр.
 *
 * <p>Состояние держится в памяти и сбрасывается при перезапуске: это режим отладки,
 * а не свойство игрока.
 */
public final class LabGhost {

    /** Используется только в урезанном режиме: на ядре источник истины — оно само. */
    private static final Set<UUID> FALLBACK = new HashSet<>();

    private LabGhost() {
    }

    private static ServerPlayer nms(final Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    public static boolean isGhost(final Player player) {
        return CoreBridge.PRESENT
            ? Core.isGhost(nms(player))
            : FALLBACK.contains(player.getUniqueId());
    }

    public static boolean toggle(final Player player) {
        return set(player, !isGhost(player));
    }

    public static boolean set(final Player player, final boolean ghost) {
        applyVisibility(player, ghost);
        if (CoreBridge.PRESENT) {
            return Core.set(nms(player), ghost);
        }
        if (ghost) {
            FALLBACK.add(player.getUniqueId());
        } else {
            FALLBACK.remove(player.getUniqueId());
        }
        player.setAffectsSpawning(!ghost);
        player.setSimulationDistance(ghost ? 0 : player.getWorld().getSimulationDistance());
        player.setInvisible(ghost);
        return ghost;
    }

    public static void onDisconnect(final Player player) {
        if (CoreBridge.PRESENT) {
            // Ядро снимает режим само в PlayerList.remove; здесь ничего не нужно.
            return;
        }
        if (FALLBACK.remove(player.getUniqueId())) {
            player.setAffectsSpawning(true);
            player.setInvisible(false);
            if (player.getWorld() != null) {
                player.setSimulationDistance(player.getWorld().getSimulationDistance());
            }
        }
    }

    /**
     * Полностью убрать наблюдателя с чужих экранов: не только модель, но и таб-лист,
     * и трекинг сущности.
     *
     * <p>{@code setInvisible} одного мало — невидимая модель всё равно остаётся в списке
     * игроков и в трекере, её видно по нику над головой и по строке в табе. Наблюдателя
     * не должно быть видно вообще: он инструмент, а не участник.
     */
    private static void applyVisibility(final Player player, final boolean ghost) {
        final var plugin = paperlab.PaperLabPlugin.get();
        if (plugin == null) {
            return;
        }
        for (final Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player)) {
                continue;
            }
            if (ghost) {
                other.hidePlayer(plugin, player);
            } else {
                other.showPlayer(plugin, player);
            }
        }
    }

    /** Вошедший не должен видеть тех, кто уже в режиме наблюдателя. */
    public static void hideGhostsFrom(final Player viewer) {
        final var plugin = paperlab.PaperLabPlugin.get();
        if (plugin == null) {
            return;
        }
        for (final Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(viewer) && isGhost(other)) {
                viewer.hidePlayer(plugin, other);
            }
        }
    }

    /** При выключении плагина вернуть всех в обычное состояние. */
    public static void restoreAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (isGhost(player)) {
                set(player, false);
            }
        }
        FALLBACK.clear();
    }

    public static int count() {
        return CoreBridge.PRESENT ? Core.count() : FALLBACK.size();
    }

    /** Полный ли режим. Показывается в подсказках, чтобы числа не читали как точные. */
    public static boolean full() {
        return CoreBridge.PRESENT;
    }

    /**
     * Делегат к ядру. Отдельный класс: на чистом Paper он никогда не загружается,
     * поэтому отсутствие классов ядра не приводит к ошибке разрешения.
     */
    private static final class Core {

        static boolean isGhost(final ServerPlayer player) {
            return io.papermc.paper.lab.ghost.LabGhost.isGhost(player);
        }

        static boolean set(final ServerPlayer player, final boolean ghost) {
            return io.papermc.paper.lab.ghost.LabGhost.set(player, ghost);
        }

        static int count() {
            return io.papermc.paper.lab.ghost.LabGhost.count();
        }
    }
}
