package paperlab.command;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * Права инструментария — одним списком.
 *
 * <p><b>Зачем регистрировать, а не просто проверять.</b> Brigadier-команде достаточно
 * строки в {@code requires}, и работать всё будет и так. Но тогда права нигде не
 * перечислены: LuckPerms не подсказывает их в {@code /lp user ... permission set},
 * веб-редактор не показывает, и узнать полный список можно только из исходников.
 * Зарегистрированные в {@code PluginManager} права LuckPerms подхватывает сам.
 *
 * <p>Дерево двухуровневое: у каждой команды своё право, а подкоманды, которые меняют
 * состояние мира или чужого игрока, вынесены отдельно — чтобы можно было выдать
 * наблюдение без вмешательства.
 *
 * <p>Все по умолчанию {@link PermissionDefault#OP}: инструментарий не для обычных игроков.
 */
public final class LabPermissions {

    /** Корень. Выдача {@code paperlab.*} включает всё дерево. */
    public static final String ROOT = "paperlab.*";

    // --- наблюдение ---
    public static final String LOG = "paperlab.log";
    public static final String LOG_TPS = "paperlab.log.tps";
    public static final String LOG_MOBCAPS = "paperlab.log.mobcaps";
    public static final String LOG_COUNTER = "paperlab.log.counter";
    public static final String LOG_SPAWN = "paperlab.log.spawn";
    public static final String LOG_ITEM = "paperlab.log.item";
    public static final String LOG_MICROTIMING = "paperlab.log.microtiming";
    public static final String LOG_MOVEMENT = "paperlab.log.movement";
    public static final String CHUNKS = "paperlab.chunks";
    public static final String CHUNKMAP = "paperlab.chunkmap";
    public static final String SPAWN = "paperlab.spawn";
    public static final String COUNTER = "paperlab.counter";

    /** Инструменты из Carpet: разбор периметра, сведения о блоке, расстояние. */
    public static final String PERIMETER = "paperlab.perimeterinfo";
    public static final String INFO = "paperlab.info";
    public static final String DISTANCE = "paperlab.distance";

    /**
     * Ванильные отладочные подписки 26.2: пути мобов, обновления соседей, порядок
     * редстоуна, структуры, POI, brain. Их рисует MiniHUD.
     *
     * <p>Ваниль пускает к ним только операторов. Право — альтернатива этому: выдавать
     * полный OP ради отладочных рендеров слишком крупная монета. Проверяется в ядре,
     * в {@code ServerDebugSubscribers.hasRequiredPermissions}.
     */
    public static final String DEBUG_DATA = "paperlab.debugdata";

    /** Канал servux:hud_metadata: спавн мира, а позже TPS и мобкапы в HUD MiniHUD. */
    public static final String SERVUX_HUD = "paperlab.servux.hud";

    /**
     * Сид мира через канал Servux.
     *
     * <p>Отдельно от самого канала и по умолчанию не работает без правила
     * {@code servuxShareSeed}: сид — это знание о мире, которое обычный игрок иначе
     * не получит, и раздавать его молча неправильно.
     */
    public static final String SERVUX_SEED = "paperlab.servux.seed";

    /** Канал servux:structures: рамки структур в MiniHUD. */
    public static final String SERVUX_STRUCTURES = "paperlab.servux.structures";

    /**
     * Канал servux:litematics: серверная вставка схематик.
     *
     * <p>Отдельно от остальных Servux-прав и не выдаётся заодно с ними: это
     * единственный канал, который <b>пишет в мир</b>, а не только читает.
     */
    public static final String SERVUX_LITEMATICS = "paperlab.servux.litematics";

    /**
     * Канал servux:entity_data: NBT сущности и блока под прицелом.
     *
     * <p>Отдельно от HUD: видеть содержимое любого сундука в зоне видимости —
     * заметно больше, чем видеть TPS.
     */
    public static final String SERVUX_ENTITIES = "paperlab.servux.entities";

    /**
     * Право на просмотр NBT других игроков (инвентарь, эндер-сундук, здоровье).
     * Без него запрос NBT чужого игрока через Servux отклоняется.
     */
    public static final String SERVUX_ENTITIES_PLAYERS = "paperlab.servux.entities.players";

    /** Канал servux:tweaks: предпросмотр инвентарей мода Tweakeroo. */
    public static final String SERVUX_TWEAKS = "paperlab.servux.tweaks";

    // --- вмешательство ---
    public static final String COUNTER_EDIT = "paperlab.counter.edit";
    public static final String GHOST = "paperlab.ghost";
    public static final String GHOST_OTHER = "paperlab.ghost.other";
    public static final String TICK = "paperlab.tick";
    public static final String PLAYER = "paperlab.player";

    // --- Capture & Playback ---
    public static final String CPLAY = "paperlab.cplay";
    public static final String CPLAY_PLAYBACK = "paperlab.cplay.playback";
    public static final String CPLAY_CAPTURE = "paperlab.cplay.capture";
    public static final String CPLAY_MANAGE = "paperlab.cplay.manage";
    public static final String CPLAY_ADMIN = "paperlab.cplay.admin";

    // --- группы wildcard (родительские узлы) ---
    public static final String LOG_ALL = "paperlab.log.*";
    public static final String SERVUX_ALL = "paperlab.servux.*";
    public static final String CPLAY_ALL = "paperlab.cplay.*";
    public static final String RULE_ALL = "paperlab.rule.*";
    public static final String COUNTER_ALL = "paperlab.counter.*";
    public static final String GHOST_ALL = "paperlab.ghost.*";

    /**
     * Право сохранять значение правила между перезапусками.
     *
     * <p>Отдельно от прав на сами правила: поставить правило на сессию и закрепить его
     * навсегда — разные по цене действия. Забытое сохранённое правило портит все
     * последующие замеры молча.
     */
    public static final String RULE_DEFAULT = "paperlab.rule.setdefault";

    /** Право → описание. Порядок сохраняется, он же порядок регистрации. */
    private static final Map<String, String> NODES = new LinkedHashMap<>();

    /** Промежуточные wildcard-группы → описание. */
    private static final Map<String, String> GROUPS = new LinkedHashMap<>();

    static {
        NODES.put(LOG, "the /log command as a whole");
        NODES.put(LOG_TPS, "TPS and MSPT subscription");
        NODES.put(LOG_MOBCAPS, "local mobcap subscription, including other players");
        NODES.put(LOG_COUNTER, "hopper counter subscription");
        NODES.put(LOG_SPAWN, "spawn trace subscription");
        NODES.put(LOG_ITEM, "item lifecycle: created, despawned, died");
        NODES.put(LOG_MICROTIMING, "redstone components microtiming: merged, all, unique");
        NODES.put(LOG_MOVEMENT, "entity movement calculation breakdown");
        NODES.put(CHUNKS, "chunk status summary around a player");
        NODES.put(CHUNKMAP, "chunk map for the ChunkDebug client mod");
        NODES.put(SPAWN, "spawn trace: view and control collection");
        NODES.put(COUNTER, "view hopper counters");
        NODES.put(PERIMETER, "count spawnable spots around a point");
        NODES.put(INFO, "block state, block entity and ticking info");
        NODES.put(DISTANCE, "distance between two points");
        NODES.put(DEBUG_DATA, "vanilla debug subscriptions for MiniHUD without granting OP");
        NODES.put(SERVUX_HUD, "servux:hud_metadata channel for MiniHUD");
        NODES.put(SERVUX_SEED, "share the world seed over the Servux channel");
        NODES.put(SERVUX_STRUCTURES, "servux:structures channel for MiniHUD");
        NODES.put(SERVUX_LITEMATICS, "servux:litematics channel: server-side schematic paste");
        NODES.put(SERVUX_ENTITIES, "servux:entity_data channel: entity and block entity NBT");
        NODES.put(SERVUX_ENTITIES_PLAYERS, "inspect other players' private inventory/NBT via Servux");
        NODES.put(SERVUX_TWEAKS, "servux:tweaks channel: Tweakeroo inventory preview");
        NODES.put(COUNTER_EDIT, "reset counters and put hoppers under tracking");
        NODES.put(GHOST, "observer mode for yourself");
        NODES.put(GHOST_OTHER, "observer mode for another player or bot");
        NODES.put(TICK, "tick freeze, step and warp");
        NODES.put(PLAYER, "bots: create, act, remove");
        NODES.put(CPLAY, "Capture & Playback mod integration and asset access");
        NODES.put(CPLAY_PLAYBACK, "playback redstone captures into the world");
        NODES.put(CPLAY_CAPTURE, "capture redstone signals into assets");
        NODES.put(CPLAY_MANAGE, "manage all Capture & Playback assets");
        NODES.put(CPLAY_ADMIN, "alternative admin permission for Capture & Playback assets");
        NODES.put(RULE_DEFAULT, "persist rule values across restarts");
        // По праву на каждое правило: правила меняют поведение мира, и раздавать их
        // скопом нельзя — кому-то нужен только fillUpdates и ничего больше.
        for (final paperlab.rules.LabRule<?> rule : paperlab.rules.LabRules.all()) {
            NODES.put(rule.permission(), "rule " + rule.name() + ": " + rule.description());
        }

        GROUPS.put(LOG_ALL, "all log subscriptions");
        GROUPS.put(SERVUX_ALL, "all Servux channels and client mod features");
        GROUPS.put(CPLAY_ALL, "all Capture & Playback mod integration features");
        GROUPS.put(RULE_ALL, "all /carpet rules and persistence");
        GROUPS.put(COUNTER_ALL, "all hopper counter commands and edit permissions");
        GROUPS.put(GHOST_ALL, "observer mode for self and other players/bots");
    }

    private LabPermissions() {
    }

    /**
     * Регистрирует всё дерево, включая промежуточные wildcard-узлы с привязкой дочерних прав.
     * Вызывать один раз при включении плагина.
     *
     * <p>Повторную регистрацию Bukkit считает ошибкой, поэтому уже занятые узлы
     * пропускаем: это бывает при {@code /reload}.
     */
    public static void register() {
        final Map<String, Boolean> rootChildren = new LinkedHashMap<>();

        // 1. Листовые ноды
        for (final Map.Entry<String, String> node : NODES.entrySet()) {
            add(new Permission(node.getKey(), node.getValue(), PermissionDefault.OP));
            rootChildren.put(node.getKey(), Boolean.TRUE);
        }

        // 2. Промежуточные wildcard-ноды с явным маппингом дочерних прав (children)
        registerGroup(LOG_ALL, GROUPS.get(LOG_ALL), rootChildren,
            LOG, LOG_TPS, LOG_MOBCAPS, LOG_COUNTER, LOG_SPAWN, LOG_ITEM, LOG_MICROTIMING, LOG_MOVEMENT);

        registerGroup(SERVUX_ALL, GROUPS.get(SERVUX_ALL), rootChildren,
            SERVUX_HUD, SERVUX_SEED, SERVUX_STRUCTURES, SERVUX_LITEMATICS, SERVUX_ENTITIES,
            SERVUX_ENTITIES_PLAYERS, SERVUX_TWEAKS);

        registerGroup(CPLAY_ALL, GROUPS.get(CPLAY_ALL), rootChildren,
            CPLAY, CPLAY_PLAYBACK, CPLAY_CAPTURE, CPLAY_MANAGE, CPLAY_ADMIN);

        registerGroup(COUNTER_ALL, GROUPS.get(COUNTER_ALL), rootChildren,
            COUNTER, COUNTER_EDIT);

        registerGroup(GHOST_ALL, GROUPS.get(GHOST_ALL), rootChildren,
            GHOST, GHOST_OTHER);

        final Map<String, Boolean> ruleChildren = new LinkedHashMap<>();
        ruleChildren.put(RULE_DEFAULT, Boolean.TRUE);
        for (final paperlab.rules.LabRule<?> rule : paperlab.rules.LabRules.all()) {
            ruleChildren.put(rule.permission(), Boolean.TRUE);
        }
        add(new Permission(RULE_ALL, GROUPS.get(RULE_ALL), PermissionDefault.OP, ruleChildren));
        rootChildren.put(RULE_ALL, Boolean.TRUE);

        // 3. Корень со всеми дочерними элементами
        add(new Permission(ROOT, "the whole Technical Lab toolset", PermissionDefault.OP, rootChildren));
    }

    private static void registerGroup(final String groupName, final String description,
                                      final Map<String, Boolean> rootChildren,
                                      final String... childrenNodes) {
        final Map<String, Boolean> children = new LinkedHashMap<>();
        for (final String child : childrenNodes) {
            children.put(child, Boolean.TRUE);
        }
        add(new Permission(groupName, description, PermissionDefault.OP, children));
        rootChildren.put(groupName, Boolean.TRUE);
    }

    private static void add(final Permission permission) {
        if (Bukkit.getPluginManager().getPermission(permission.getName()) == null) {
            Bukkit.getPluginManager().addPermission(permission);
        }
    }

    /** Список для {@code /carpet perms}: право и его описание. */
    public static Map<String, String> nodes() {
        return Map.copyOf(NODES);
    }

    /** Список зарегистрированных wildcard-групп. */
    public static Map<String, String> groups() {
        return Map.copyOf(GROUPS);
    }

    /** Порядок вывода — тот же, что при регистрации. */
    public static Iterable<Map.Entry<String, String>> ordered() {
        return NODES.entrySet();
    }
}

