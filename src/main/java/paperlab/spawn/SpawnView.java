package paperlab.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import paperlab.core.CoreBridge;

/**
 * Трасса спавна: где именно останавливаются попытки естественного появления мобов.
 *
 * <p>Прямо отвечает на вопрос «почему ферма не спавнит». Движок не сообщает, на каком
 * шаге остановилась попытка, а причин несколько и они разные по смыслу.
 *
 * <h2>На нашем ядре — полная трасса</h2>
 * <pre>
 * overworld  monster  кап 133295 · проходов 184 · позиция 167 · плагин 0 · заспавнено 3
 * overworld  ambient  кап 0 · проходов 133479 · позиция 569992 · плагин 0 · заспавнено 0
 * </pre>
 * Читается сразу: у монстров всё упирается в <b>кап</b>, у ambient кап свободен, но не
 * проходит <b>позиция</b>. Ни один штатный инструмент этого не различает.
 *
 * <p><b>Единицы разные, складывать нельзя.</b> «кап» и «проходов» считаются на каждый
 * проход «чанк × категория»; «позиция», «плагин» и «заспавнено» — на каждую отдельную
 * позицию внутри прохода, а позиций за проход пробуется несколько. Поэтому «позиция»
 * законно бывает больше «проходов».
 *
 * <p>Столбец <b>«плагин»</b> — единственная причина, которой в чистом Paper быть не
 * должно. На боевом сервере это прямой ответ, режет ли что-то спавн.
 *
 * <h2>На чистом Paper — только два факта</h2>
 * «Упёрлось в кап» и «не подошла позиция» наружу не публикуются: их видно только внутри
 * {@code NaturalSpawner}. Остаются успешное появление и отмена обработчиком —
 * см. {@link SpawnCounters}.
 *
 * <p>Сбор включается только пока кто-то подписан: при выключенной трассе в горячем пути
 * спавна остаётся чтение одного {@code volatile} поля.
 */
public final class SpawnView {

    private SpawnView() {
    }

    /**
     * Два независимых источника «включено»: подписка в таб-листе и ручное включение
     * командой. Держать один флаг нельзя — тик HUD видит «подписчиков нет» и гасит сбор,
     * включённый из консоли. А консоль нужна: прогон часто идёт вообще без живого игрока.
     */
    private static volatile boolean manual;
    private static volatile boolean subscribed;

    /** Ручное включение командой {@code /carpet spawn on|off}. */
    public static void setManual(final boolean enabled) {
        manual = enabled;
        apply();
    }

    /** Есть ли подписчики в таб-листе. Вызывается из тика HUD. */
    public static void setSubscribed(final boolean value) {
        subscribed = value;
        apply();
    }

    public static boolean enabled() {
        return manual || subscribed;
    }

    private static void apply() {
        if (CoreBridge.PRESENT) {
            Core.setEnabled(manual || subscribed);
        }
    }

    public static void reset() {
        if (CoreBridge.PRESENT) {
            Core.reset();
        }
        SpawnCounters.reset();
    }

    /** Что подставлять в подсказки: категории мобов на ядре, причины спавна без него. */
    public static List<String> options() {
        final List<String> out = new ArrayList<>();
        if (CoreBridge.PRESENT) {
            for (final MobCategory category : MobCategory.values()) {
                out.add(category.getName());
            }
        } else {
            for (final org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason
                : org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.values()) {
                out.add(reason.name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** Одна строка HUD. {@code option} — категория (на ядре) либо причина спавна. */
    public static Component line(final World world, final String option) {
        final String arg = option == null || option.isBlank() ? null : option.trim();
        return CoreBridge.PRESENT ? Core.line(world, arg) : degraded(world, arg);
    }

    private static Component degraded(final World world, final String reason) {
        final SpawnCounters.Snapshot snapshot = SpawnCounters.snapshot(world, reason);
        return Component.text("spawn ", NamedTextColor.GRAY)
            .append(Component.text((reason == null ? "all" : reason) + "  ", NamedTextColor.DARK_GRAY))
            .append(Component.text("spawned ", NamedTextColor.DARK_GRAY))
            .append(Component.text(snapshot.spawned(),
                snapshot.spawned() > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY))
            .append(Component.text(" - cancelled ", NamedTextColor.DARK_GRAY))
            .append(Component.text(snapshot.cancelled(),
                snapshot.cancelled() > 0 ? NamedTextColor.RED : NamedTextColor.DARK_GRAY));
    }

    /** Делегат к ядру: на чистом Paper класс не загружается. */
    private static final class Core {

        /** Категория по умолчанию: монстры — то, ради чего трассу и писали. */
        private static final MobCategory DEFAULT = MobCategory.MONSTER;

        static void setEnabled(final boolean enabled) {
            io.papermc.paper.lab.spawn.SpawnTrace.setEnabled(enabled);
        }

        static void reset() {
            io.papermc.paper.lab.spawn.SpawnTrace.reset();
        }

        static Component line(final World world, final String option) {
            MobCategory category = DEFAULT;
            if (option != null) {
                for (final MobCategory candidate : MobCategory.values()) {
                    if (candidate.getName().equalsIgnoreCase(option)) {
                        category = candidate;
                        break;
                    }
                }
            }

            final ServerLevel level = ((CraftWorld) world).getHandle();
            final long[] counters = io.papermc.paper.lab.spawn.SpawnTrace.snapshot(level, category);

            Component line = Component.text("spawn ", NamedTextColor.GRAY)
                .append(Component.text(category.getName() + "  ", NamedTextColor.WHITE));
            if (counters == null) {
                return line.append(Component.text("no data", NamedTextColor.DARK_GRAY));
            }

            final var outcomes = io.papermc.paper.lab.spawn.SpawnTrace.Outcome.values();
            for (int i = 0; i < outcomes.length; i++) {
                if (i > 0) {
                    line = line.append(Component.text(" ", NamedTextColor.DARK_GRAY));
                }
                line = line.append(Component.text(outcomes[i].label() + " ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(counters[i], colour(outcomes[i], counters[i])));
            }
            return line;
        }

        /**
         * Цвет по смыслу исхода, а не по величине: «заспавнено» хорошо, «плагин» —
         * повод разбираться, остальное нейтрально.
         */
        private static NamedTextColor colour(
            final io.papermc.paper.lab.spawn.SpawnTrace.Outcome outcome, final long value) {
            return switch (outcome) {
                case SPAWNED -> value > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY;
                case PLUGIN -> value > 0 ? NamedTextColor.RED : NamedTextColor.DARK_GRAY;
                case CAP_FULL -> value > 0 ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY;
                default -> NamedTextColor.WHITE;
            };
        }
    }
}
