package paperlab;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import paperlab.chunkmap.ChunkMapService;
import paperlab.command.LabCommands;
import paperlab.core.CoreBridge;
import paperlab.counter.LabCounters;
import paperlab.ghost.LabGhost;
import paperlab.log.LabHud;
import paperlab.log.LabLoggers;

/**
 * Технический инструментарий Paper 26.2.
 *
 * <h2>Как поделена работа</h2>
 * <ul>
 *   <li><b>Плагин</b> — всё, что можно сделать плагином: команды, HUD в таб-листе,
 *       счётчики воронок, чтение локального мобкапа, карта чанков для ChunkDebug.
 *       Пересобирается за секунды.</li>
 *   <li><b>Ядро</b> — только то, чего плагином сделать нельзя: полный режим наблюдателя
 *       и трасса спавна. Один патч, шесть точек, см. {@link CoreBridge}.</li>
 *   <li><b>Боты</b> — своя реализация в ядре, {@code /player}. Готовый FakePlayer-CE
 *       пробовали и отказались: бот не наследует от вызывающего позицию и взгляд.
 *       Плагин только подвешивает готовый узел под {@code /carpet}.</li>
 * </ul>
 *
 * <p>Без нашего ядра плагин продолжает работать в урезанном виде — это нужно для
 * прогонов на нетронутом Paper, с которыми сравниваются результаты.
 */
public final class PaperLabPlugin extends JavaPlugin implements Listener {

    private static PaperLabPlugin instance;

    private paperlab.rules.RuleDefaults ruleDefaults;

    public static PaperLabPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        // Порядок важен: значения по умолчанию применяются до регистрации команд,
        // чтобы /carpet сразу показывал действующее состояние, а не ванильное.
        this.ruleDefaults = new paperlab.rules.RuleDefaults(
            this.getDataFolder().toPath().resolve("rules.conf"), this.getLogger());
        this.ruleDefaults.load();
        paperlab.command.RuleCommands.bind(this.ruleDefaults);
        paperlab.rules.LabRules.applyDefaults(this.ruleDefaults, this.getLogger()::info);
        paperlab.rules.LabRules.applyAll();

        // Права регистрируем до команд: их requires уже спрашивают эти узлы.
        paperlab.command.LabPermissions.register();
        LabCommands.register(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new LabCounters.Listener(), this);
        Bukkit.getPluginManager().registerEvents(new paperlab.rules.TntAngleListener(), this);
        Bukkit.getPluginManager().registerEvents(new paperlab.log.item.ItemLogListener(), this);
        paperlab.log.movement.MovementLogListener.init();
        Bukkit.getPluginManager().registerEvents(new paperlab.log.movement.MovementLogListener(), this);
        paperlab.log.microtiming.MicroTimingLogListener.init();
        Bukkit.getPluginManager().registerEvents(new paperlab.log.microtiming.MicroTimingLogListener(), this);
        if (!CoreBridge.PRESENT) {
            // Урезанная трасса спавна нужна только там, где нет полной из ядра.
            Bukkit.getPluginManager().registerEvents(new paperlab.spawn.SpawnCounters(), this);
        }
        ChunkMapService.enable(this);
        paperlab.servux.ServuxHud.enable(this);
        paperlab.servux.ServuxStructures.enable(this);
        paperlab.servux.ServuxLitematica.enable(this);
        paperlab.servux.ServuxEntities.enable(this);
        paperlab.servux.ServuxTweaks.enable(this);
        paperlab.cplay.CPlayService.enable(this);

        // Один общий тик: счётчики каждый тик, HUD раз в секунду (решает сам LabHud).
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            LabCounters.tick();
            LabHud.tick();
            paperlab.log.microtiming.MicroTimingLogger.flushTick();
            ChunkMapService.tick();
            paperlab.servux.ServuxHud.tick();
            paperlab.servux.ServuxStructures.tick();
        }, 1L, 1L);

        this.getLogger().info("PaperLab enabled - " + CoreBridge.describe());
        this.getLogger().info("PaperLab CPlay - " + paperlab.core.CPlayBridge.describe());
    }

    @Override
    public void onDisable() {
        ChunkMapService.disable();
        paperlab.servux.ServuxHud.disable();
        paperlab.servux.ServuxStructures.disable();
        paperlab.servux.ServuxLitematica.disable();
        paperlab.servux.ServuxEntities.disable();
        paperlab.servux.ServuxTweaks.disable();
        paperlab.cplay.CPlayService.disable();
        LabGhost.restoreAll();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            paperlab.log.LabHud.clear(player);
        }
        // Правила меняют поведение мира: снимать их обязательно, иначе выключенный
        // плагин оставит после себя изменённый сервер.
        paperlab.rules.LabRules.resetAll();
        // Оставленный бот продолжал бы держать чанки и занимать мобкап.
        final int bots = paperlab.core.BotBridge.removeAll();
        if (bots > 0) {
            this.getLogger().info("bots removed: " + bots);
        }
    }

    /**
     * Вход: начать рукопожатие ChunkDebug и спрятать тех, кто в режиме наблюдателя.
     */
    @EventHandler
    public void onJoin(final org.bukkit.event.player.PlayerJoinEvent event) {
        ChunkMapService.onJoin(event.getPlayer());
        paperlab.cplay.CPlayService.onJoin(event.getPlayer());
        LabGhost.hideGhostsFrom(event.getPlayer());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        LabLoggers.unsubscribeAll(event.getPlayer().getName());
        ChunkMapService.onDisconnect(event.getPlayer());
        paperlab.servux.ServuxHud.onQuit(event.getPlayer());
        paperlab.servux.ServuxStructures.onQuit(event.getPlayer());
        paperlab.servux.ServuxLitematica.onQuit(event.getPlayer());
        paperlab.servux.ServuxEntities.onQuit(event.getPlayer());
        paperlab.servux.ServuxTweaks.onQuit(event.getPlayer());
        paperlab.cplay.CPlayService.onQuit(event.getPlayer());
        LabGhost.onDisconnect(event.getPlayer());
    }
}
