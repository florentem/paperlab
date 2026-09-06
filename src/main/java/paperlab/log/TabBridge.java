package paperlab.log;

import java.lang.reflect.Method;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Работа с плагином TAB, если он установлен.
 *
 * <p><b>Зачем.</b> У TAB есть anti-override: он блокирует header/footer, приходящие от
 * других плагинов, и переустанавливает свой. Наш {@code sendPlayerListFooter} на сервере
 * с TAB не доживёт до следующего тика TAB — подписки {@code /log} будут мигать
 * или не покажутся вовсе. Драться с этим бессмысленно и вредно: anti-override нужен
 * именно затем, чтобы таб-лист не рвали на части несколько плагинов сразу.
 *
 * <p><b>Как правильно.</b> У TAB есть свой API для того же самого:
 * {@code TabAPI.getInstance().getHeaderFooterManager().setFooter(tabPlayer, text)}.
 * Значение, поставленное через него, TAB считает своим (forcedFooter) и не перетирает.
 *
 * <p><b>Изоляция загрузчиков.</b> PaperLab загружается через PaperPluginLoader, у которого
 * изолированный classpath. Для вызова API TAB мы берём загрузчик самого плагина TAB
 * ({@code tabPlugin.getClass().getClassLoader()}) и обращаемся к интерфейсу через рефлексию.
 * Менеджер футера получается динамически, чтобы корректно переживать {@code /tab reload}.
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
     * Есть ли TAB и удалось ли к нему подключиться.
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
     * Поставить футер игроку через TAB API.
     *
     * @return {@code true}, если это сделал TAB; {@code false} — вызывающий должен
     *         поставить футер обычным путём
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
                // В конфигурации TAB выключена секция header-footer
                return false;
            }
            final Object tabPlayer = getPlayer.invoke(tabApi, player.getUniqueId());
            if (tabPlayer == null) {
                // Игрок ещё не загружен в TAB (например, сразу после входа).
                // Возвращаем true, чтобы не перебивать TAB ванильным пакетом:
                // на следующем тике TAB уже добавит игрока и подписки отобразятся.
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

    /** Вернуть футер под управление TAB (очистить forcedFooter). */
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

