package paperlab.log.movement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import paperlab.log.LabLoggers;

import java.util.List;
import java.util.Locale;

/**
 * Логгер расчета движения сущностей (/log movement).
 *
 * <p>Идентичен реализации Carpet-TIS-Addition:
 * отображает попытку движения, тип (Self logic, Player action, Piston),
 * промежуточные изменения вектора (Piston Limit, Sneaking, Collision)
 * и итоговый вектор и позицию.
 */
public final class MovementLogger {

    public static final String NON_ZERO_PREFIX = "non_zero:";

    public record StepRecord(double oldX, double oldY, double oldZ,
                             double newX, double newY, double newZ,
                             String reason) {
    }

    private MovementLogger() {
    }

    public static boolean hasSubscribers() {
        return LabLoggers.MOVEMENT.hasSubscribers();
    }

    public static boolean shouldReportFor(final Player subscriber, final String rawOption,
                                          final String entityName, final Location entityLoc,
                                          final double finalLengthSqr, final boolean isSelf,
                                          final String entityTypeName) {
        String option = (rawOption == null || rawOption.isBlank())
            ? LabLoggers.MOVEMENT.defaultOption()
            : rawOption.trim();

        if (option.startsWith(NON_ZERO_PREFIX)) {
            if (finalLengthSqr <= 1e-12) {
                return false;
            }
            option = option.substring(NON_ZERO_PREFIX.length()).trim();
        }

        if (option.isEmpty() || option.equalsIgnoreCase("@s") || option.equalsIgnoreCase("me")) {
            return isSelf;
        }
        if (option.equalsIgnoreCase(entityName)) {
            return true;
        }
        if (option.equalsIgnoreCase("all") || option.equals("*")) {
            return true;
        }

        // Селекторы вида @a[distance=..10] или @e[distance=..5]
        if (option.startsWith("@a") || option.startsWith("@e")) {
            final boolean requirePlayer = option.startsWith("@a");
            if (requirePlayer && !isSelf && Bukkit.getPlayerExact(entityName) == null) {
                return false;
            }

            // Парсим distance=..N
            final int distIdx = option.indexOf("distance=..");
            if (distIdx != -1) {
                final int endIdx = option.indexOf("]", distIdx);
                final String distStr = (endIdx != -1)
                    ? option.substring(distIdx + "distance=..".length(), endIdx)
                    : option.substring(distIdx + "distance=..".length());
                try {
                    final double maxDist = Double.parseDouble(distStr.split(",")[0].trim());
                    if (subscriber.getWorld() != entityLoc.getWorld()) {
                        return false;
                    }
                    if (subscriber.getLocation().distance(entityLoc) > maxDist) {
                        return false;
                    }
                } catch (final NumberFormatException ignored) {
                }
            }

            // Парсим type=X
            final int typeIdx = option.indexOf("type=");
            if (typeIdx != -1) {
                final int endIdx = option.indexOf("]", typeIdx);
                final String typeStr = (endIdx != -1)
                    ? option.substring(typeIdx + "type=".length(), endIdx)
                    : option.substring(typeIdx + "type=".length());
                final String targetType = typeStr.split(",")[0].trim().toLowerCase(Locale.ROOT);
                if (!entityTypeName.toLowerCase(Locale.ROOT).endsWith(targetType)) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    public static void report(final String entityName, final String entityTypeName, final boolean isSelf,
                              final Location startLoc, final double startVx, final double startVy, final double startVz,
                              final String moverType, final List<StepRecord> steps,
                              final Location endLoc, final double endVx, final double endVy, final double endVz) {
        if (!hasSubscribers()) return;

        final double finalLenSqr = endVx * endVx + endVy * endVy + endVz * endVz;
        final long gameTime = startLoc.getWorld() != null ? startLoc.getWorld().getGameTime() : 0;

        for (final var entry : LabLoggers.MOVEMENT.subscribers().entrySet()) {
            final Player subscriber = Bukkit.getPlayerExact(entry.getKey());
            if (subscriber == null || !subscriber.isOnline()) continue;

            for (final String option : entry.getValue()) {
                if (shouldReportFor(subscriber, option, entityName, startLoc, finalLenSqr, isSelf, entityTypeName)) {
                    sendReport(subscriber, entityName, startLoc, startVx, startVy, startVz, moverType,
                        gameTime, steps, endLoc, endVx, endVy, endVz);
                    break;
                }
            }
        }
    }

    private static void sendReport(final Player subscriber, final String entityName,
                                   final Location startLoc, final double startVx, final double startVy, final double startVz,
                                   final String moverType, final long gameTime, final List<StepRecord> steps,
                                   final Location endLoc, final double endVx, final double endVy, final double endVz) {
        // Line 1: empty
        subscriber.sendMessage(Component.empty());

        // Line 2: header
        final Component header = Component.text(entityName + " tried to move for ", NamedTextColor.AQUA)
            .append(formatVector(startVx, startVy, startVz, NamedTextColor.YELLOW))
            .append(Component.text("  @ ", NamedTextColor.DARK_GRAY))
            .append(formatCoord(startLoc));
        subscriber.sendMessage(header);

        // Line 3: details
        final Component details = Component.text("Movement type: ", NamedTextColor.GRAY)
            .append(Component.text(moverType, NamedTextColor.RED))
            .append(Component.text(", Game time: ", NamedTextColor.GRAY))
            .append(Component.text(gameTime + "t", NamedTextColor.DARK_AQUA));
        subscriber.sendMessage(details);

        // Line 4+: modifications
        for (final StepRecord step : steps) {
            final double dx = step.newX() - step.oldX();
            final double dy = step.newY() - step.oldY();
            final double dz = step.newZ() - step.oldZ();
            final Component deltaHover = Component.text(String.format(Locale.ROOT, "delta: [%.4f, %.4f, %.4f]", dx, dy, dz));

            final Component mod = Component.text("  - ", NamedTextColor.DARK_GRAY)
                .append(formatVector(step.oldX(), step.oldY(), step.oldZ(), NamedTextColor.YELLOW))
                .append(Component.text(" -> ", NamedTextColor.DARK_GRAY).hoverEvent(HoverEvent.showText(deltaHover)))
                .append(formatVector(step.newX(), step.newY(), step.newZ(), NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(" due to ", NamedTextColor.GRAY))
                .append(Component.text(step.reason(), NamedTextColor.WHITE));
            subscriber.sendMessage(mod);
        }

        // Last line: footer
        final Component footer = Component.text(entityName + " actually moved for ", NamedTextColor.AQUA)
            .append(formatVector(endVx, endVy, endVz, NamedTextColor.LIGHT_PURPLE))
            .append(Component.text("  @ ", NamedTextColor.DARK_GRAY))
            .append(formatCoord(endLoc));
        subscriber.sendMessage(footer);
    }

    private static Component formatVector(final double x, final double y, final double z, final NamedTextColor color) {
        return Component.text(String.format(Locale.ROOT, "[%.4f, %.4f, %.4f]", x, y, z), color);
    }

    private static Component formatCoord(final Location loc) {
        final String dim = loc.getWorld() != null ? loc.getWorld().getName() : "world";
        final String coords = String.format(Locale.ROOT, "%.2f %.2f %.2f", loc.getX(), loc.getY(), loc.getZ());
        return Component.text(coords, NamedTextColor.GRAY)
            .hoverEvent(HoverEvent.showText(Component.text(dim, NamedTextColor.GRAY)))
            .clickEvent(ClickEvent.suggestCommand(String.format(Locale.ROOT, "/tp %.2f %.2f %.2f", loc.getX(), loc.getY(), loc.getZ())));
    }
}
