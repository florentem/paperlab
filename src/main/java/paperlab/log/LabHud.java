package paperlab.log;

import paperlab.counter.LabCounter;
import paperlab.counter.LabCounters;
import paperlab.counter.WoolColors;
import paperlab.ghost.LabGhost;
import paperlab.mobcap.MobcapService;
import paperlab.spawn.SpawnView;
import paperlab.text.Msg;
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
 * Renders {@code /log} subscriptions into the tab-list footer.
 *
 * <p>Carpet's {@code HUDController} model: one refresh per second, one short line per
 * subscription. From a plugin this goes through the stock
 * {@code Player#sendPlayerListFooter}, so no packets have to be assembled by hand.
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
        // The spawn trace runs exactly as long as the subscription: with no subscribers the
        // engine's hot path is left with a single volatile field read.
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
            // If TAB is installed, set the footer through its own API: otherwise its
            // anti-override overwrites ours a tick later and the subscriptions flicker. See
            // TabBridge.
            if (!TabBridge.setFooter(player, footer)) {
                player.sendPlayerListFooter(footer);
            }
        }
    }

    /** Clear the footer, when the last subscription goes away. */
    public static void clear(final Player player) {
        if (!TabBridge.clear(player)) {
            player.sendPlayerListFooter(Component.empty());
        }
    }

    private static List<Component> linesFor(final Player player) {
        final String name = player.getName();
        final List<Component> lines = new ArrayList<>(4);

        final boolean showTps = LabLoggers.TPS.subscribed(name);
        final boolean showMspt = LabLoggers.MSPT.subscribed(name);
        if (showTps || showMspt) {
            lines.add(tpsLine(player, showTps, showMspt));
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

    /**
     * The TPS line — computed and rendered exactly as in Carpet.
     *
     * <p>This used to be {@code Bukkit.getTPS()[0]}, which is a <b>different number</b>:
     * Bukkit's is a one-minute rolling average, sluggish and barely moving on a short lag
     * spike. Carpet shows the instantaneous {@code 1000 / max(target mspt, actual mspt)} —
     * how fast the server is running right now. The discrepancy was obvious to anyone
     * arriving from the mod, and it got in the way of comparing measurements.
     *
     * <p>Hence also the {@code /tick} handling: TPS is zero while frozen, and the ceiling is
     * lifted while sprinting.
     */
    private static Component tpsLine(final Player player, final boolean showTps, final boolean showMspt) {
        final net.minecraft.server.MinecraftServer server =
            ((org.bukkit.craftbukkit.CraftServer) Bukkit.getServer()).getServer();
        final double mspt = server.getAverageTickTimeNanos() / 1_000_000.0D;
        final net.minecraft.server.ServerTickRateManager ticks = server.tickRateManager();

        double serverTps = 1000.0D / Math.max(ticks.isSprinting() ? 0.0D : ticks.millisecondsPerTick(), mspt);
        if (ticks.isFrozen()) {
            serverTps = 0.0D;
        }

        // Focused zone information
        paperlab.zone.ZoneModel focusedZone = null;
        final paperlab.PaperLabPlugin plugin = paperlab.PaperLabPlugin.get();
        if (plugin != null) {
            focusedZone = plugin.zoneService().getFocusedZone(player.getUniqueId());
        }

        double displayTps = serverTps;
        double targetMspt = ticks.millisecondsPerTick();

        if (focusedZone != null) {
            boolean frozen = focusedZone.isFrozen();
            float rate = focusedZone.tickRate();
            if (paperlab.core.CoreBridge.PRESENT) {
                final io.papermc.paper.lab.zone.LabTickZone coreZone =
                    io.papermc.paper.lab.zone.LabTickZones.findZone(focusedZone.name());
                if (coreZone != null) {
                    frozen = coreZone.isFrozen();
                    rate = coreZone.tickRate();
                }
            }
            if (ticks.isFrozen() || frozen) {
                displayTps = 0.0D;
            } else {
                displayTps = rate * (Math.min(20.0D, serverTps) / 20.0D);
            }
            targetMspt = 1000.0D / Math.max(0.1D, rate);
        }

        final String colour = Msg.heatmap(mspt, targetMspt);
        final List<Object> parts = new ArrayList<>();

        if (focusedZone != null) {
            parts.add("c " + focusedZone.name() + ": ");
        }

        if (showTps && showMspt) {
            parts.add("g TPS: ");
            parts.add(String.format(Locale.US, "%s %.1f", colour, displayTps));
            parts.add("g   MSPT: ");
            parts.add(String.format(Locale.US, "%s %.1f", colour, mspt));
        } else if (showTps) {
            parts.add("g TPS: ");
            parts.add(String.format(Locale.US, "%s %.1f", colour, displayTps));
        } else if (showMspt) {
            parts.add("g MSPT: ");
            parts.add(String.format(Locale.US, "%s %.1f", colour, mspt));
        }

        return Msg.c(parts.toArray(new Object[0]));
    }

    /**
     * The local mobcap. One line by default: name and monster cap. The {@code full} option adds
     * a line with the failed attempts.
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

        // Carpet's grammar: "current / limit", the current value on the heat scale and the
        // limit in the category colour. The content is ours, though: Carpet's cap is world-wide,
        // ours is per-player, and that is the whole point of this lab.
        Component head = Msg.c(
            "w " + target.getName() + "  ",
            Msg.heatmap(cap.effective(), cap.limit()) + " " + cap.counted(),
            "g  / ",
            Msg.creatureTypeColour("monster") + " " + cap.limit());
        if (!local) {
            head = head.append(Msg.c("gi  global"));
        }
        if (LabGhost.isGhost(target)) {
            // On our core the observer really is outside the census, so the number below is
            // honest. On stock Paper they are counted, and the line has to say so.
            head = LabGhost.full()
                ? head.append(Msg.c("c  ghost"))
                : head.append(Msg.c("r  ghost(partial)"));
        }
        if (!full) {
            return List.of(head);
        }

        // The full line is deliberately short: it sits in the tab list permanently, next to
        // other subscriptions, and every extra word crowds out something useful. "+12 backoff
        // -> 17/5" reads no worse than a sentence and takes a third of the space.
        Component extra = Component.text("  +", NamedTextColor.DARK_GRAY)
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

    /** A hopper counter. One line per colour; {@code full} adds the item breakdown. */
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

    /** Spawns: where the attempts stop. Details in {@link SpawnView}. */
    private static Component spawnLine(final Player viewer, final String option) {
        return SpawnView.line(viewer.getWorld(), option);
    }

    /** Green to yellow to red, by how full it is. */
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
