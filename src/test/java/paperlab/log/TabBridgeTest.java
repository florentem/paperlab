package paperlab.log;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

public class TabBridgeTest {

    @AfterEach
    public void tearDown() {
        try {
            final java.lang.reflect.Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            serverField.set(null, null);

            resetTabBridge();
        } catch (final Throwable ignored) {
        }
    }

    private static void resetTabBridge() throws Exception {
        final java.lang.reflect.Field initField = TabBridge.class.getDeclaredField("initialized");
        initField.setAccessible(true);
        initField.set(null, false);

        final java.lang.reflect.Field availField = TabBridge.class.getDeclaredField("available");
        availField.setAccessible(true);
        availField.set(null, false);

        final java.lang.reflect.Field setFooterField = TabBridge.class.getDeclaredField("setFooter");
        setFooterField.setAccessible(true);
        setFooterField.set(null, null);
    }

    @Test
    public void testTabNotInstalled() throws Exception {
        setupMockServer(null);
        assertFalse(TabBridge.available());

        final Player mockPlayer = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> null
        );
        assertFalse(TabBridge.setFooter(mockPlayer, Component.text("test")));
        assertFalse(TabBridge.clear(mockPlayer));
    }

    @Test
    public void testTabIntegrationLifecycle(@TempDir Path tempDir) throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System Java compiler must be available in test");

        final Path srcDir = tempDir.resolve("src");
        final Path binDir = tempDir.resolve("bin");
        Files.createDirectories(binDir);

        final Path tpFile = srcDir.resolve("me/neznamy/tab/api/TabPlayer.java");
        Files.createDirectories(tpFile.getParent());
        Files.writeString(tpFile, "package me.neznamy.tab.api; public interface TabPlayer {}");

        final Path hfFile = srcDir.resolve("me/neznamy/tab/api/tablist/HeaderFooterManager.java");
        Files.createDirectories(hfFile.getParent());
        Files.writeString(hfFile, "package me.neznamy.tab.api.tablist; import me.neznamy.tab.api.TabPlayer; "
            + "public interface HeaderFooterManager { void setFooter(TabPlayer p, String t); }");

        final Path apiFile = srcDir.resolve("me/neznamy/tab/api/TabAPI.java");
        Files.writeString(apiFile, "package me.neznamy.tab.api; import java.util.UUID; "
            + "import me.neznamy.tab.api.tablist.HeaderFooterManager; "
            + "public abstract class TabAPI { "
            + "  private static TabAPI instance; "
            + "  public static TabAPI getInstance() { return instance; } "
            + "  public static void setInstance(TabAPI inst) { instance = inst; } "
            + "  public abstract TabPlayer getPlayer(UUID id); "
            + "  public abstract HeaderFooterManager getHeaderFooterManager(); "
            + "}");

        final Path implFile = srcDir.resolve("me/neznamy/tab/api/TabAPIImpl.java");
        Files.writeString(implFile, "package me.neznamy.tab.api; import java.util.UUID; "
            + "import me.neznamy.tab.api.tablist.HeaderFooterManager; "
            + "public class TabAPIImpl extends TabAPI { "
            + "  public static TabPlayer staticPlayer; "
            + "  public static HeaderFooterManager staticManager; "
            + "  public TabPlayer getPlayer(UUID id) { return staticPlayer; } "
            + "  public HeaderFooterManager getHeaderFooterManager() { return staticManager; } "
            + "}");

        final int compileExit = compiler.run(null, null, null,
            "-d", binDir.toString(),
            tpFile.toString(), hfFile.toString(), apiFile.toString(), implFile.toString()
        );
        assertEquals(0, compileExit, "Compilation of mock TAB API classes should succeed");

        final URLClassLoader tabCl = new URLClassLoader(
            new URL[]{binDir.toUri().toURL()},
            TabBridgeTest.class.getClassLoader()
        );

        final Class<?> tabApiClass = tabCl.loadClass("me.neznamy.tab.api.TabAPI");
        final Class<?> tabPlayerClass = tabCl.loadClass("me.neznamy.tab.api.TabPlayer");
        final Class<?> hfManagerClass = tabCl.loadClass("me.neznamy.tab.api.tablist.HeaderFooterManager");
        final Class<?> implClass = tabCl.loadClass("me.neznamy.tab.api.TabAPIImpl");

        final AtomicReference<String> lastSetFooter = new AtomicReference<>();
        final UUID testUuid = UUID.randomUUID();

        final Object mockTabPlayer = Proxy.newProxyInstance(
            tabCl,
            new Class<?>[]{tabPlayerClass},
            (proxy, method, args) -> null
        );

        final Object mockHfManager = Proxy.newProxyInstance(
            tabCl,
            new Class<?>[]{hfManagerClass},
            (proxy, method, args) -> {
                if ("setFooter".equals(method.getName())) {
                    lastSetFooter.set((String) args[1]);
                    return null;
                }
                return null;
            }
        );

        final Object implInstance = implClass.getDeclaredConstructor().newInstance();
        implClass.getField("staticPlayer").set(null, mockTabPlayer);
        implClass.getField("staticManager").set(null, mockHfManager);
        tabApiClass.getMethod("setInstance", tabApiClass).invoke(null, implInstance);

        final Plugin mockTabPlugin = (Plugin) Proxy.newProxyInstance(
            tabCl,
            new Class<?>[]{Plugin.class},
            (proxy, method, args) -> {
                if ("isEnabled".equals(method.getName())) {
                    return true;
                }
                return null;
            }
        );

        setupMockServer(mockTabPlugin);

        assertTrue(TabBridge.available(), "TabBridge should connect to mock TAB");

        final Player mockPlayer = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> {
                if ("getUniqueId".equals(method.getName())) {
                    return testUuid;
                }
                return null;
            }
        );

        // Setting footer
        final Component footer = Component.text("TPS ", NamedTextColor.GRAY)
            .append(Component.text("20.0", NamedTextColor.GREEN));

        assertTrue(TabBridge.setFooter(mockPlayer, footer));
        assertEquals("§7TPS §a20.0", lastSetFooter.get(), "Footer should be serialized to legacy section format");

        // Clear
        assertTrue(TabBridge.clear(mockPlayer));
        assertNull(lastSetFooter.get(), "Clearing footer should pass null to TAB setFooter");

        // When manager is null (feature disabled in TAB config)
        implClass.getField("staticManager").set(null, null);
        assertFalse(TabBridge.setFooter(mockPlayer, footer), "Should return false when HeaderFooter is disabled");
    }

    private static void setupMockServer(final Plugin tabPlugin) throws Exception {
        final PluginManager mockPm = (PluginManager) Proxy.newProxyInstance(
            PluginManager.class.getClassLoader(),
            new Class<?>[]{PluginManager.class},
            (proxy, method, args) -> {
                if ("getPlugin".equals(method.getName()) && "TAB".equals(args[0])) {
                    return tabPlugin;
                }
                if ("isPluginEnabled".equals(method.getName()) && "TAB".equals(args[0])) {
                    return tabPlugin != null && tabPlugin.isEnabled();
                }
                return null;
            }
        );

        final Server mockServer = (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(),
            new Class<?>[]{Server.class},
            (proxy, method, args) -> {
                if ("getPluginManager".equals(method.getName())) {
                    return mockPm;
                }
                if ("getLogger".equals(method.getName())) {
                    return java.util.logging.Logger.getLogger("TestServer");
                }
                return null;
            }
        );

        final java.lang.reflect.Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, mockServer);
    }
}
