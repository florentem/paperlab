package paperlab.log;

import paperlab.counter.LabCounter;
import paperlab.counter.LabCounters;
import paperlab.counter.WoolColors;
import paperlab.ghost.LabGhost;
import paperlab.mobcap.MobcapService;
import paperlab.spawn.SpawnView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Рисует подписки {@code /log} в футер таб-листа.
 *
 * <p>Модель Carpet {@code HUDController}: обновление раз в секунду, по одной короткой
 * строке на подписку. Из плагина это делается штатным {@code Player#sendPlayerListFooter},
 * поэтому пакеты вручную собирать не нужно.
 */
public final class LabHud {

    private static final int PERIOD_TICKS = 20;
    private static int tickCounter;

    private LabHud() {
    }

    public static void tick() {
        if (++tickCounter % PERIOD_TICKS != 0) {
            return;
        }
        // Трасса спавна включается ровно на время подписки: без подписчиков в горячем
        // пути движка остаётся чтение одного volatile поля.
        SpawnView.setSubscribed(LabLoggers.SPAWN.hasSubscribers());
        if (!LabLoggers.anySubscribers()) {
            return;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final List<Component> lines = linesFor(player);
            if (lines.isEmpty()) {
                continue;
            }
            Component footer = Component.empty();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    footer = footer.append(Component.newline());
                }
                footer = footer.append(lines.get(i));
            }
            // Если стоит TAB, ставим футер его же API: иначе anti-override перетрёт
            // наш через тик, и подписки будут мигать. Подробности в TabBridge.
            if (!TabBridge.setFooter(player, footer)) {
                player.sendPlayerListFooter(footer);
            }
        }
    }

    /** Сбросить футер — при снятии последней подписки. */
    public static void clear(final Player player) {
        if (!TabBridge.clear(player)) {
            player.sendPlayerListFooter(Component.empty());
        }
    }

    private static List<Component> linesFor(final Player player) {
        final String name = player.getName();
        final List<Component> lines = new ArrayList<>(4);

        if (LabLoggers.TPS.subscribed(name)) {
            lines.add(tpsLine());
        }
        for (final String option : LabLoggers.MOBCAPS.optionsFor(name)) {
            lines.addAll(mobcapLines(player, option));
        }
        for (final String option : LabLoggers.COUNTER.optionsFor(name)) {
            lines.addAll(counterLines(player, option));
        }
        for (final String option : LabLoggers.SPAWN.optionsFor(name)) {
            lines.add(spawnLine(player, option));
        }
        return lines;
    }

    private static Component tpsLine() {
        final double tps = Bukkit.getTPS()[0];
        final double mspt = Bukkit.getAverageTickTime();
        final NamedTextColor colour = heat(mspt, 50.0D);
        return Component.text("TPS ", NamedTextColor.GRAY)
            .append(Component.text(String.format(Locale.ROOT, "%.1f", Math.min(tps, 20.0D)), colour))
            .append(Component.text("  MSPT ", NamedTextColor.GRAY))
            .append(Component.text(String.format(Locale.ROOT, "%.1f", mspt), colour));
    }

    /**
     * Локальный мобкап. По умолчанию одна строка: ник и кап монстров.
     * Опция {@code full} добавляет строку с неудачными попытками.
     */
    private static List<Component> mobcapLines(final Player viewer, final String option) {
        boolean full = false;
        String targetName = null;
        for (final String token : option.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("full")) {
                full = true;
            } else {
                targetName = token;
            }
        }

        final Player target = targetName == null ? viewer : Bukkit.getPlayerExact(targetName);
        if (target == null) {
            return List.of(Component.text("cap " + targetName + " offline", NamedTextColor.DARK_GRAY));
        }

        final ServerPlayer handle = ((CraftPlayer) target).getHandle();
        final ServerLevel level = handle.level();
        final boolean local = MobcapService.perPlayerEnabled(level);
        final MobcapService.MonsterCap cap = MobcapService.monsterCap(handle, level, local);

        TextComponent head = Component.text(target.getName() + "  ", NamedTextColor.GRAY)
            .append(Component.text(cap.counted(), heat(cap.effective(), cap.limit())))
            .append(Component.text("/" + cap.limit(), NamedTextColor.DARK_GRAY));
        if (!local) {
            head = head.append(Component.text("  global", NamedTextColor.DARK_GRAY));
        }
        if (LabGhost.isGhost(target)) {
            // На нашем ядре наблюдатель действительно вне переписи — число ниже честное.
            // На чистом Paper он в перепись попадает, и это надо видеть в строке.
            head = LabGhost.full()
                ? head.append(Component.text("  ghost", NamedTextColor.AQUA))
                : head.append(Component.text("  ghost(partial)", NamedTextColor.RED));
        }
        if (!full) {
            return List.of(head);
        }

        // Строка full короткая намеренно: она висит в табе постоянно, рядом с другими
        // подписками, и каждое лишнее слово вытесняет полезное. «+12 backoff → 17/5»
        // читается не хуже фразы, а места занимает втрое меньше.
        TextComponent extra = Component.text("  +", NamedTextColor.DARK_GRAY)
            .append(Component.text(cap.backoff(),
                cap.backoff() > 0 ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY))
            .append(Component.text(" backoff", NamedTextColor.DARK_GRAY));
        if (cap.backoff() > 0) {
            extra = extra.append(Component.text(" → ", NamedTextColor.DARK_GRAY))
                .append(Component.text(cap.effective() + "/" + cap.limit(),
                    heat(cap.effective(), cap.limit())));
        }
        if (local) {
            final MobcapService.LimitingPlayer limiting = MobcapService.limitingPlayer(
                level, handle.chunkPosition(), net.minecraft.world.entity.MobCategory.MONSTER);
            final String limiter = limiting.playerName();
            if (limiter != null && !limiter.equals(target.getName())) {
                extra = extra.append(Component.text("  by ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(limiter, NamedTextColor.RED));
            }
        }
        return List.of(head, extra);
    }

    /** Счётчик воронки. Одна строка на цвет; {@code full} добавляет разбивку. */
    private static List<Component> counterLines(final Player viewer, final String option) {
        boolean full = false;
        String colourName = null;
        for (final String token : option.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("full")) {
                full = true;
            } else {
                colourName = token;
            }
        }
        if (colourName == null) {
            return List.of();
        }
        final DyeColor colour = WoolColors.byName(colourName);
        if (colour == null) {
            return List.of();
        }

        final LabCounter counter = LabCounters.existing(viewer.getWorld(), colour);
        if (counter == null || !counter.started()) {
            return List.of(Component.text(colour.getName(), LabCounters.chatColour(colour))
                .append(Component.text("  -", NamedTextColor.DARK_GRAY)));
        }

        final long gameTime = viewer.getWorld().getGameTime();
        final List<Component> out = new ArrayList<>();
        out.add(LabCounters.summary(counter, gameTime, false));
        if (full) {
            for (final LabCounter.Entry entry : counter.entries()) {
                out.add(Component.text("  " + entry.count() + " ", NamedTextColor.WHITE)
                    .append(entry.name().color(NamedTextColor.DARK_GRAY)));
            }
        }
        return out;
    }

    /** Спавн: где останавливаются попытки. Подробности — {@link SpawnView}. */
    private static Component spawnLine(final Player viewer, final String option) {
        return SpawnView.line(viewer.getWorld(), option);
    }

    /** Зелёный → жёлтый → красный по заполненности. */
    public static NamedTextColor heat(final double value, final double max) {
        if (max <= 0.0D) {
            return NamedTextColor.DARK_GRAY;
        }
        final double ratio = value / max;
        if (ratio >= 1.0D) {
            return NamedTextColor.RED;
        }
        if (ratio >= 0.75D) {
            return NamedTextColor.GOLD;
        }
        if (ratio >= 0.4D) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.GREEN;
    }
}
