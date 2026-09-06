package paperlab.command;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * The toolset's permissions, in one list.
 *
 * <p><b>Why register them rather than just check them.</b> A Brigadier command only needs a
 * string in {@code requires}, and everything would work. But then the permissions are listed
 * nowhere: LuckPerms does not suggest them in {@code /lp user ... permission set}, the web
 * editor does not show them, and the full list can only be found in the sources. Permissions
 * registered with {@code PluginManager} are picked up by LuckPerms on its own.
 *
 * <p>The tree has two levels: every command has its own node, and subcommands that change the
 * world or another player are split out — so that observation can be granted without
 * interference.
 *
 * <p>Everything defaults to {@link PermissionDefault#OP}: this toolset is not for ordinary
 * players.
 */
public final class LabPermissions {

    /** The root. Granting {@code paperlab.*} enables the whole tree. */
    public static final String ROOT = "paperlab.*";

    // --- observation ---
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

    /** Tools from Carpet: perimeter analysis, block information, distance. */
    public static final String PERIMETER = "paperlab.perimeterinfo";
    public static final String INFO = "paperlab.info";
    public static final String DISTANCE = "paperlab.distance";

    /**
     * Vanilla 26.2 debug subscriptions: mob paths, neighbour updates, redstone order,
     * structures, POI, brain. MiniHUD draws them.
     *
     * <p>Vanilla admits only operators. This permission is the alternative: handing out full
     * OP for debug renderers is too large a coin. Checked in the core, in
     * {@code ServerDebugSubscribers.hasRequiredPermissions}.
     */
    public static final String DEBUG_DATA = "paperlab.debugdata";

    /** The servux:hud_metadata channel: world spawn, and later TPS and mobcaps in MiniHUD. */
    public static final String SERVUX_HUD = "paperlab.servux.hud";

    /**
     * The world seed, over the Servux channel.
     *
     * <p>Separate from the channel itself: a seed is knowledge about the world an ordinary
     * player could not otherwise obtain, and handing it out silently would be wrong.
     */
    public static final String SERVUX_SEED = "paperlab.servux.seed";

    /** The servux:structures channel: structure bounding boxes in MiniHUD. */
    public static final String SERVUX_STRUCTURES = "paperlab.servux.structures";

    /**
     * The servux:litematics channel: server-side schematic pasting.
     *
     * <p>Separate from the other Servux nodes and not granted alongside them: this is the one
     * channel that <b>writes to the world</b> rather than only reading.
     */
    public static final String SERVUX_LITEMATICS = "paperlab.servux.litematics";

    /**
     * The servux:entity_data channel: NBT of the entity and block under the crosshair.
     *
     * <p>Separate from the HUD: seeing the contents of any chest in view is a good deal more
     * than seeing TPS.
     */
    public static final String SERVUX_ENTITIES = "paperlab.servux.entities";

    /**
     * Permission to view other players' NBT (inventory, ender chest, health). Without it a
     * Servux request for another player's NBT is refused.
     */
    public static final String SERVUX_ENTITIES_PLAYERS = "paperlab.servux.entities.players";

    /** The servux:tweaks channel: inventory preview for the Tweakeroo mod. */
    public static final String SERVUX_TWEAKS = "paperlab.servux.tweaks";

    // --- interference ---
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

    // --- wildcard groups (parent nodes) ---
    public static final String LOG_ALL = "paperlab.log.*";
    public static final String SERVUX_ALL = "paperlab.servux.*";
    public static final String CPLAY_ALL = "paperlab.cplay.*";
    public static final String RULE_ALL = "paperlab.rule.*";
    public static final String COUNTER_ALL = "paperlab.counter.*";
    public static final String GHOST_ALL = "paperlab.ghost.*";

    /**
     * Permission to persist a rule value across restarts.
     *
     * <p>Separate from the rule nodes themselves: setting a rule for the session and pinning
     * it forever are actions of different cost. A forgotten saved rule silently spoils every
     * later measurement.
     */
    public static final String RULE_DEFAULT = "paperlab.rule.setdefault";

    /** Permission to description. Insertion order is kept and is also the registration order. */
    private static final Map<String, String> NODES = new LinkedHashMap<>();

    /** Wildcard groups -> description. */
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
        NODES.put(RULE_DEFAULT, "persist rule values across restarts");
        // One node per rule: rules change how the world behaves and must not be handed out
        // wholesale — someone may need fillUpdates and nothing else.
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
     * Registers the whole tree, including intermediate wildcard groups with child mappings.
     * Call once when the plugin enables.
     *
     * <p>Bukkit treats re-registration as an error, so nodes that are already taken are
     * skipped: that happens on {@code /reload}.
     */
    public static void register() {
        final Map<String, Boolean> rootChildren = new LinkedHashMap<>();

        // 1. Leaf nodes
        for (final Map.Entry<String, String> node : NODES.entrySet()) {
            add(new Permission(node.getKey(), node.getValue(), PermissionDefault.OP));
            rootChildren.put(node.getKey(), Boolean.TRUE);
        }

        // 2. Intermediate wildcard nodes with explicit children mappings
        registerGroup(LOG_ALL, GROUPS.get(LOG_ALL), rootChildren,
            LOG, LOG_TPS, LOG_MOBCAPS, LOG_COUNTER, LOG_SPAWN, LOG_ITEM, LOG_MICROTIMING, LOG_MOVEMENT);

        registerGroup(SERVUX_ALL, GROUPS.get(SERVUX_ALL), rootChildren,
            SERVUX_HUD, SERVUX_SEED, SERVUX_STRUCTURES, SERVUX_LITEMATICS, SERVUX_ENTITIES,
            SERVUX_ENTITIES_PLAYERS, SERVUX_TWEAKS);

        registerGroup(CPLAY_ALL, GROUPS.get(CPLAY_ALL), rootChildren,
            CPLAY, CPLAY_PLAYBACK, CPLAY_CAPTURE, CPLAY_MANAGE);

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

        // 3. Root with all children
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

    /** The list for {@code /carpet perms}: a permission and its description. */
    public static Map<String, String> nodes() {
        return Map.copyOf(NODES);
    }

    /** List of registered wildcard groups. */
    public static Map<String, String> groups() {
        return Map.copyOf(GROUPS);
    }

    /** Output order is the registration order. */
    public static Iterable<Map.Entry<String, String>> ordered() {
        return NODES.entrySet();
    }
}
