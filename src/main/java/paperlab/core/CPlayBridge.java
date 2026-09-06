package paperlab.core;

import io.papermc.paper.lab.cplay.CPlayCaptureStream;
import io.papermc.paper.lab.cplay.CPlayPlaybackStream;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * A soft link to the core for the Capture &amp; Playback module.
 *
 * <p>If the plugin runs on ordinary Paper without our core patch, {@link #PRESENT} is
 * {@code false}. Networking, asset storage and sharing, sessions and collaborative editing all
 * keep working, but physically replaying signals and capturing them in the world are disabled
 * with a clear message.
 */
public final class CPlayBridge {

    public static final boolean PRESENT = detect();

    private CPlayBridge() {
    }

    private static boolean detect() {
        try {
            Class.forName("io.papermc.paper.lab.cplay.CPlayManager", false,
                CPlayBridge.class.getClassLoader());
            return true;
        } catch (final Throwable ignored) {
            return false;
        }
    }

    public static String describe() {
        return PRESENT
            ? "CPlay: core hooks available"
            : "CPlay: plain Paper (playback/capture limited)";
    }

    public static void addPlaybackStream(final ServerLevel level, final CPlayPlaybackStream stream) {
        if (PRESENT) {
            Core.addPlaybackStream(level, stream);
        }
    }

    public static boolean removePlaybackStream(final ServerLevel level, final UUID assetId) {
        return PRESENT && Core.removePlaybackStream(level, assetId);
    }

    public static void addCaptureStream(final ServerLevel level, final CPlayCaptureStream stream) {
        if (PRESENT) {
            Core.addCaptureStream(level, stream);
        }
    }

    public static boolean removeCaptureStream(final ServerLevel level, final UUID assetId) {
        return PRESENT && Core.removeCaptureStream(level, assetId);
    }

    public static Collection<CPlayPlaybackStream> getPlaybackStreams(final ServerLevel level) {
        return PRESENT ? Core.getPlaybackStreams(level) : Collections.emptyList();
    }

    public static Collection<CPlayCaptureStream> getCaptureStreams(final ServerLevel level) {
        return PRESENT ? Core.getCaptureStreams(level) : Collections.emptyList();
    }

    public static void clearAll(final ServerLevel level) {
        if (PRESENT) {
            Core.clearAll(level);
        }
    }

    private static final class Core {
        static void addPlaybackStream(final ServerLevel level, final CPlayPlaybackStream stream) {
            io.papermc.paper.lab.cplay.CPlayManager.addPlaybackStream(level, stream);
        }

        static boolean removePlaybackStream(final ServerLevel level, final UUID assetId) {
            return io.papermc.paper.lab.cplay.CPlayManager.removePlaybackStream(level, assetId);
        }

        static void addCaptureStream(final ServerLevel level, final CPlayCaptureStream stream) {
            io.papermc.paper.lab.cplay.CPlayManager.addCaptureStream(level, stream);
        }

        static boolean removeCaptureStream(final ServerLevel level, final UUID assetId) {
            return io.papermc.paper.lab.cplay.CPlayManager.removeCaptureStream(level, assetId);
        }

        static Collection<CPlayPlaybackStream> getPlaybackStreams(final ServerLevel level) {
            return io.papermc.paper.lab.cplay.CPlayManager.getPlaybackStreams(level);
        }

        static Collection<CPlayCaptureStream> getCaptureStreams(final ServerLevel level) {
            return io.papermc.paper.lab.cplay.CPlayManager.getCaptureStreams(level);
        }

        static void clearAll(final ServerLevel level) {
            io.papermc.paper.lab.cplay.CPlayManager.clearAll(level);
        }
    }
}
