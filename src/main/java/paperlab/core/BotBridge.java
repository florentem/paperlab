package paperlab.core;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.Nullable;

/**
 * Мягкая связь с ботами, которые живут в ядре.
 *
 * <p>Боты — это {@code ServerPlayer} без клиента, и им нужен {@code doTick()} в фазе
 * соединений, ровно как живому игроку. Планировщик плагина работает в начале
 * {@code tickChildren}, до фазы уровней, поэтому сделать это плагином нельзя — отсюда
 * одна строка хука в {@code MinecraftServer}.
 *
 * <p>Само дерево {@code /player} тоже регистрирует ядро: команде нужны ванильные типы
 * аргументов ({@code Vec3Argument}, {@code RotationArgument}, {@code DimensionArgument},
 * {@code GameModeArgument}), а держать их в двух местах нечем оправдать. Плагин лишь
 * подвешивает готовый узел подкомандой {@code /carpet player}.
 *
 * <p>На чистом Paper ботов просто нет, и {@code /carpet} эту строку не показывает.
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
     * Узел {@code /player}, зарегистрированный ядром, либо {@code null}, если ботов нет
     * или ядро ещё не успело его зарегистрировать.
     */
    public static @Nullable LiteralCommandNode<CommandSourceStack> node() {
        return PRESENT ? Core.node() : null;
    }

    /**
     * Убрать всех ботов. Обязательно при выключении: оставленный бот продолжал бы
     * держать чанки и занимать мобкап.
     */
    public static int removeAll() {
        return PRESENT ? Core.removeAll() : 0;
    }

    public static int count() {
        return PRESENT ? Core.count() : 0;
    }

    /** Делегат к ядру: на чистом Paper класс не загружается. */
    private static final class Core {

        /**
         * Приведение обоснованно: {@code net.minecraft.commands.CommandSourceStack}
         * реализует {@code io.papermc.paper.command.brigadier.CommandSourceStack}
         * (через {@code PaperCommandSourceStack}), а диспетчер у ванильных и плагинных
         * команд общий. Разные они только для компилятора.
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
