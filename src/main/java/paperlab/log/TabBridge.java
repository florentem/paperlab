package paperlab.log;

import java.lang.reflect.Method;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Working with the TAB plugin, when it is installed.
 *
 * <p><b>Why.</b> TAB has an anti-override: it blocks headers and footers coming from other
 * plugins and reinstates its own. On a server with TAB, our {@code sendPlayerListFooter} does
 * not survive to TAB's next tick — the {@code /log} subscriptions would flicker or never show.
 * Fighting that is pointless and harmful: the anti-override exists precisely so that several
 * plugins do not tear the tab list apart at once.
 *
 * <p><b>The right way.</b> TAB has its own API for the same thing:
 * {@code TabAPI.getInstance().getHeaderFooterManager().setFooter(tabPlayer, text)}. A value set
 * through it is treated by TAB as its own (forcedFooter) and is not overwritten.
 *
 * <p><b>Class loader isolation.</b> PaperLab is loaded through PaperPluginLoader, which has an
 * isolated classpath. To call TAB's API we take TAB's own loader
 * ({@code tabPlugin.getClass().getClassLoader()}) and reach the interface through reflection.
 * The footer manager is fetched dynamically so that {@code /tab reload} is survived correctly.
 */
public final class TabBridge {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static volatile boolean initialized;
    private static volatile boolean available;

    private static Method getInstance;
    private static Method getHeaderFooterManager;
    private static Method getPlayer;
    private static Method setFooter;

    private TabBridge() {
    }

    /**
     * Whether TAB is present and we managed to connect to it.
     */
    public static boolean available() {
        final Plugin tabPlugin = Bukkit.getPluginManager().getPlugin("TAB");
        if (tabPlugin == null || !tabPlugin.isEnabled()) {
            return false;
        }
        if (!initialized) {
            init(tabPlugin);
        }
        return available;
    }

    private static synchronized void init(final Plugin tabPlugin) {
        if (initialized) {
            return;
        }
        try {
            final ClassLoader cl = tabPlugin.getClass().getClassLoader();
            Class<?> apiClass;
            try {
                apiClass = Class.forName("me.neznamy.tab.api.TabAPI", true, cl);
            } catch (final ClassNotFoundException e) {
                apiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            }
            getInstance = apiClass.getMethod("getInstance");
            getHeaderFooterManager = apiClass.getMethod("getHeaderFooterManager");
            getPlayer = apiClass.getMethod("getPlayer", UUID.class);

            getInstance.setAccessible(true);
            getHeaderFooterManager.setAccessible(true);
            getPlayer.setAccessible(true);

            Method setFooterMethod = null;
            try {
                final Class<?> hfClass = Class.forName("me.neznamy.tab.api.tablist.HeaderFooterManager", true, cl);
                final Class<?> tpClass = Class.forName("me.neznamy.tab.api.TabPlayer", true, cl);
                setFooterMethod = hfClass.getMethod("setFooter", tpClass, String.class);
            } catch (final Throwable ignored) {
            }

            if (setFooterMethod == null) {
                final Object api = getInstance.invoke(null);
                final Object mgr = api != null ? getHeaderFooterManager.invoke(api) : null;
                if (mgr != null) {
                    for (final Method m : mgr.getClass().getMethods()) {
                        if ("setFooter".equals(m.getName()) && m.getParameterCount() == 2) {
                            setFooterMethod = m;
                            break;
                        }
                    }
                }
            }

            if (setFooterMethod == null) {
                Bukkit.getLogger().warning("[PaperLab] TAB found, but setFooter method could not be resolved");
                available = false;
                initialized = true;
                return;
            }

            setFooterMethod.setAccessible(true);
            setFooter = setFooterMethod;
            available = true;
            initialized = true;
            Bukkit.getLogger().info("[PaperLab] TAB detected, /log will use its footer API");
        } catch (final Throwable t) {
            Bukkit.getLogger().warning("[PaperLab] TAB found, but the API did not connect: " + t);
            available = false;
            initialized = true;
        }
    }

    /**
     * Set a player's footer through TAB's API.
     *
     * @return {@code true} if TAB did it; {@code false} means the caller should set the footer
     *         the ordinary way
     */
    public static boolean setFooter(final Player player, final Component footer) {
        if (!available()) {
            return false;
        }
        try {
            final Object tabApi = getInstance.invoke(null);
            if (tabApi == null) {
                return false;
            }
            final Object manager = getHeaderFooterManager.invoke(tabApi);
            if (manager == null) {
                // The header-footer section is disabled in TAB's configuration.
                return false;
            }
            final Object tabPlayer = getPlayer.invoke(tabApi, player.getUniqueId());
            if (tabPlayer == null) {
                // The player is not loaded into TAB yet (right after joining, say). We return
                // true so as not to cut across TAB with a vanilla packet: on the next tick TAB
                // will have added the player and the subscriptions will show.
                return true;
            }
            setFooter.invoke(manager, tabPlayer, LEGACY.serialize(footer));
            return true;
        } catch (final Throwable t) {
            final Throwable cause = t.getCause() != null ? t.getCause() : t;
            Bukkit.getLogger().warning("[PaperLab] TAB setFooter failed: " + cause);
            return false;
        }
    }

    /** Hand the footer back to TAB (clear forcedFooter). */
    public static boolean clear(final Player player) {
        if (!available()) {
            return false;
        }
        try {
            final Object tabApi = getInstance.invoke(null);
            if (tabApi == null) {
                return false;
            }
            final Object manager = getHeaderFooterManager.invoke(tabApi);
            if (manager == null) {
                return false;
            }
            final Object tabPlayer = getPlayer.invoke(tabApi, player.getUniqueId());
            if (tabPlayer != null) {
                setFooter.invoke(manager, tabPlayer, (String) null);
            }
            return true;
        } catch (final Throwable t) {
            final Throwable cause = t.getCause() != null ? t.getCause() : t;
            Bukkit.getLogger().warning("[PaperLab] TAB clear failed: " + cause);
            return false;
        }
    }
}

