package paperlab.core;

/**
 * Whether our fork, with its minimal core patch, is underneath the plugin.
 *
 * <p>The project splits responsibilities like this: the core holds <b>only what a plugin
 * fundamentally cannot do</b> — observer mode and the spawn trace. Everything else (commands,
 * HUD, counters, the chunk map, reading the mobcap) lives here, because a plugin rebuilds in
 * seconds while a server jar takes minutes.
 *
 * <p>The plugin must also work on stock Paper: the A/B/C variants of a methodology are run on an
 * untouched server, and the instrument must not get in the way of that run. So the core
 * dependencies are soft: with the classes absent, the module in question drops to a reduced mode
 * and says so honestly.
 *
 * <p>References to core classes are moved into nested delegate classes that load only when
 * {@link #PRESENT} — otherwise constant resolution would fail on first access on stock Paper.
 */
public final class CoreBridge {

    /** Checked once at class load: the result cannot change while running. */
    public static final boolean PRESENT = detect();

    private CoreBridge() {
    }

    private static boolean detect() {
        try {
            Class.forName("io.papermc.paper.lab.ghost.LabGhost", false,
                CoreBridge.class.getClassLoader());
            return true;
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /** A line for the log and for {@code /carpet}: what we are running on. */
    public static String describe() {
        return PRESENT
            ? "Lab-patched core: observer and spawn trace are complete"
            : "plain Paper: observer is partial, spawn trace has no reason breakdown";
    }
}
