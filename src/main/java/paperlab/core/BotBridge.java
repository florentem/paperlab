package paperlab.core;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.Nullable;

/**
 * A soft link to the bots, which live in the core.
 *
 * <p>Bots are {@code ServerPlayer}s without a client, and they need {@code doTick()} in the
 * connection phase, exactly as a live player does. A plugin's scheduler runs at the start of
 * {@code tickChildren}, before the level phase, so a plugin cannot do it — hence the one-line
 * hook in {@code MinecraftServer}.
 *
 * <p>The {@code /player} tree is registered by the core as well: the command needs vanilla
 * argument types ({@code Vec3Argument}, {@code RotationArgument}, {@code DimensionArgument},
 * {@code GameModeArgument}), and keeping those in two places cannot be justified. The plugin
 * merely attaches the finished node as the {@code /carpet player} subcommand.
 *
 * <p>On stock Paper there are simply no bots, and {@code /carpet} does not show that line.
 */
public final class BotBridge {

    public static final boolean PRESENT = detect();

    private BotBridge() {
    }

    private static boolean detect() {
        try {
            Class.forName("io.papermc.paper.lab.bot.LabBotRegistry", false,
                BotBridge.class.getClassLoader());
            return true;
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /**
     * The {@code /player} node registered by the core, or {@code null} if there are no bots or
     * the core has not registered it yet.
     */
    public static @Nullable LiteralCommandNode<CommandSourceStack> node() {
        return PRESENT ? Core.node() : null;
    }

    /**
     * Remove every bot. Mandatory on disable: a bot left behind would keep holding chunks and
     * taking mobcap.
     */
    public static int removeAll() {
        return PRESENT ? Core.removeAll() : 0;
    }

    public static int count() {
        return PRESENT ? Core.count() : 0;
    }

    /** Delegate to the core: on stock Paper this class is never loaded. */
    private static final class Core {

        /**
         * The cast is justified: {@code net.minecraft.commands.CommandSourceStack} implements
         * {@code io.papermc.paper.command.brigadier.CommandSourceStack} (through
         * {@code PaperCommandSourceStack}), and vanilla and plugin commands share one dispatcher.
         * They differ only to the compiler.
         */
        @SuppressWarnings("unchecked")
        static @Nullable LiteralCommandNode<CommandSourceStack> node() {
            return (LiteralCommandNode<CommandSourceStack>) (Object)
                io.papermc.paper.lab.command.LabPlayerCommand.node;
        }

        static int removeAll() {
            return io.papermc.paper.lab.bot.LabBotRegistry.removeAll();
        }

        static int count() {
            return io.papermc.paper.lab.bot.LabBotRegistry.count();
        }
    }
}
