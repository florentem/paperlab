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
 * Technical toolset for Paper 26.2.
 *
 * <h2>How the work is split</h2>
 * <ul>
 *   <li><b>Plugin</b> — everything a plugin can do: commands, the tab-list HUD, hopper
 *       counters, reading the local mobcap, the chunk map for ChunkDebug. Rebuilds in
 *       seconds.</li>
 *   <li><b>Core</b> — only what a plugin cannot do: full observer mode and the spawn trace.
 *       One patch, six sites, see {@link CoreBridge}.</li>
 *   <li><b>Bots</b> — our own implementation in the core, {@code /player}. The ready-made
 *       FakePlayer-CE was tried and rejected: its bots do not inherit the caller's position
 *       and look. The plugin only attaches the existing node under {@code /carpet}.</li>
 * </ul>
 *
 * <p>Without our core the plugin keeps working in reduced form — that is what control runs on
 * untouched Paper need, and results are compared against them.
 */
public final class PaperLabPlugin extends JavaPlugin implements Listener {

    private static PaperLabPlugin instance;

    private paperlab.rules.RuleDefaults ruleDefaults;
    private paperlab.zone.ZoneService zoneService;

    public static PaperLabPlugin get() {
        return instance;
    }

    public paperlab.zone.ZoneService zoneService() {
        return this.zoneService;
    }

    @Override
    public void onEnable() {
        instance = this;

        // Order matters: the defaults are applied before command registration, so that
        // /carpet shows the state in force straight away rather than the vanilla one.
        this.ruleDefaults = new paperlab.rules.RuleDefaults(
            this.getDataFolder().toPath().resolve("rules.conf"), this.getLogger());
        this.ruleDefaults.load();
        paperlab.command.RuleCommands.bind(this.ruleDefaults);
        paperlab.rules.LabRules.applyDefaults(this.ruleDefaults, this.getLogger()::info);
        paperlab.rules.LabRules.applyAll();

        this.zoneService = new paperlab.zone.ZoneService(this);
        this.zoneService.enable();
        paperlab.zone.ZoneCommands.attachToVanillaTick(this.zoneService);

        // Permissions are registered before commands: their requires already ask for these nodes.
        paperlab.command.LabPermissions.register();
        LabCommands.register(this, this.zoneService);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new LabCounters.Listener(), this);
        Bukkit.getPluginManager().registerEvents(new paperlab.rules.TntAngleListener(), this);
        Bukkit.getPluginManager().registerEvents(new paperlab.log.item.ItemLogListener(), this);
        paperlab.log.movement.MovementLogListener.init();
        Bukkit.getPluginManager().registerEvents(new paperlab.log.movement.MovementLogListener(), this);
        paperlab.log.microtiming.MicroTimingLogListener.init();
        Bukkit.getPluginManager().registerEvents(new paperlab.log.microtiming.MicroTimingLogListener(), this);
        if (!CoreBridge.PRESENT) {
            // The reduced spawn trace is only needed where the full one from the core is absent.
            Bukkit.getPluginManager().registerEvents(new paperlab.spawn.SpawnCounters(), this);
        }
        ChunkMapService.enable(this);
        paperlab.servux.ServuxHud.enable(this);
        paperlab.servux.ServuxStructures.enable(this);
        paperlab.servux.ServuxLitematica.enable(this);
        paperlab.servux.ServuxEntities.enable(this);
        paperlab.servux.ServuxTweaks.enable(this);
        paperlab.cplay.CPlayService.enable(this);

        // One shared tick: counters every tick, HUD once a second (LabHud decides that itself).
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
        if (this.zoneService != null) {
            this.zoneService.disable();
            this.zoneService = null;
        }
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
        // Rules change how the world behaves: they must be cleared, or a disabled plugin would
        // leave a modified server behind.
        paperlab.rules.LabRules.resetAll();
        // A bot left behind would keep holding chunks and taking mobcap.
        final int bots = paperlab.core.BotBridge.removeAll();
        if (bots > 0) {
            this.getLogger().info("bots removed: " + bots);
        }
    }

    /**
     * On join: start the ChunkDebug handshake and hide anyone in observer mode.
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
