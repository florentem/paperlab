package paperlab.log;

import java.lang.reflect.Method;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Работа с плагином TAB, если он стоит.
 *
 * <p><b>Зачем.</b> У TAB есть anti-override: он блокирует header/footer, приходящие от
 * других плагинов, и переустанавливает свой. Наш {@code sendPlayerListFooter} на сервере
 * с TAB просто не доживёт до следующего тика TAB — подписки {@code /log} будут мигать
 * или не покажутся вовсе. Драться с этим бессмысленно и вредно: anti-override нужен
 * именно затем, чтобы таб-лист не рвали на части несколько плагинов сразу.
 *
 * <p><b>Как правильно.</b> У TAB есть свой API для того же самого:
 * {@code TabAPI.getInstance().getHeaderFooterManager().setFooter(tabPlayer, text)}.
 * Значение, поставленное через него, TAB считает своим и не перетирает. Так наш футер
 * оказывается «важнее» без всякой борьбы.
 *
 * <p><b>Почему через рефлексию, а не зависимостью.</b> TAB-API живёт на JitPack, а сборка
 * стенда не должна зависеть от доступности стороннего репозитория ради трёх вызовов.
 * Плюс интеграция мягкая: без TAB код просто не используется, и версия TAB нам безразлична,
 * пока метод называется {@code setFooter}.
 *
 * <p>Текст отдаём в legacy-формате с секциями: его понимают все версии TAB, а MiniMessage
 * появился не во всех.
 */
public final class TabBridge {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static boolean checked;
    private static boolean available;

    private static Object headerFooterManager;
    private static Method getPlayer;
    private static Object tabApi;
    private static Method setFooter;

    private TabBridge() {
    }

    /**
     * Есть ли TAB и удалось ли к нему подключиться.
     *
     * <p>Проверка ленивая и одноразовая: на момент включения нашего плагина TAB может быть
     * ещё не загружен, а порядок загрузки плагинов задавать ради этого не хочется.
     */
    public static boolean available() {
        if (!checked) {
            checked = true;
            available = connect();
        }
        return available;
    }

    private static boolean connect() {
        if (!Bukkit.getPluginManager().isPluginEnabled("TAB")) {
            return false;
        }
        try {
            final Class<?> apiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            tabApi = apiClass.getMethod("getInstance").invoke(null);
            headerFooterManager = apiClass.getMethod("getHeaderFooterManager").invoke(tabApi);
            getPlayer = apiClass.getMethod("getPlayer", UUID.class);

            for (final Method method : headerFooterManager.getClass().getMethods()) {
                if ("setFooter".equals(method.getName()) && method.getParameterCount() == 2) {
                    setFooter = method;
                    break;
                }
            }
            if (setFooter == null) {
                Bukkit.getLogger().warning("[PaperLab] TAB found, but setFooter is missing: "
                    + "falling back to the plain tab-list footer");
                return false;
            }
            Bukkit.getLogger().info("[PaperLab] TAB detected, /log will use its footer API");
            return true;
        } catch (final Throwable t) {
            Bukkit.getLogger().warning("[PaperLab] TAB found, but the API did not connect: " + t);
            return false;
        }
    }

    /**
     * Поставить футер игроку.
     *
     * @return {@code true}, если это сделал TAB; {@code false} — вызывающий должен
     *         поставить футер обычным путём
     */
    public static boolean setFooter(final Player player, final Component footer) {
        if (!available()) {
            return false;
        }
        try {
            final Object tabPlayer = getPlayer.invoke(tabApi, player.getUniqueId());
            if (tabPlayer == null) {
                // Игрок ещё не загружен в TAB. Это нормальное состояние сразу после входа:
                // просто пропускаем такт, следующий пройдёт.
                return true;
            }
            setFooter.invoke(headerFooterManager, tabPlayer, LEGACY.serialize(footer));
            return true;
        } catch (final Throwable t) {
            Bukkit.getLogger().warning("[PaperLab] TAB setFooter failed, falling back: " + t);
            available = false;
            return false;
        }
    }

    /** Вернуть футер под управление TAB. */
    public static boolean clear(final Player player) {
        if (!available()) {
            return false;
        }
        try {
            final Object tabPlayer = getPlayer.invoke(tabApi, player.getUniqueId());
            if (tabPlayer != null) {
                setFooter.invoke(headerFooterManager, tabPlayer, (Object) null);
            }
            return true;
        } catch (final Throwable t) {
            Bukkit.getLogger().warning("[PaperLab] TAB clear failed, falling back: " + t);
            available = false;
            return false;
        }
    }
}
