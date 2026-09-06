package paperlab.log.movement;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import paperlab.core.CoreBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge and event listener for /log movement.
 */
public final class MovementLogListener implements Listener {

    public static void init() {
        if (CoreBridge.PRESENT) {
            CoreDelegate.register();
        }
    }

    private static final class CoreDelegate {
        static void register() {
            io.papermc.paper.lab.movement.LabMovementTracker.addListener((entity, moverType, originalPos, originalMovement, modifications, finalMovement, finalPos) -> {
                if (!MovementLogger.hasSubscribers()) return;

                final org.bukkit.World bWorld = entity.level().getWorld();
                final Location startLoc = new Location(bWorld, originalPos.x, originalPos.y, originalPos.z);
                final Location endLoc = new Location(bWorld, finalPos.x, finalPos.y, finalPos.z);

                final List<MovementLogger.StepRecord> steps = new ArrayList<>(modifications.size());
                for (final var mod : modifications) {
                    steps.add(new MovementLogger.StepRecord(
                        mod.oldDelta().x, mod.oldDelta().y, mod.oldDelta().z,
                        mod.newDelta().x, mod.newDelta().y, mod.newDelta().z,
                        mod.modification().displayName()
                    ));
                }

                final String name = entity.getName().getString();
                final String typeName = entity.getType().getDescriptionId();
                final boolean isSelf = entity instanceof net.minecraft.world.entity.player.Player;

                MovementLogger.report(name, typeName, isSelf,
                    startLoc, originalMovement.x, originalMovement.y, originalMovement.z,
                    moverType.name().toLowerCase(), steps,
                    endLoc, finalMovement.x, finalMovement.y, finalMovement.z);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(final PlayerMoveEvent event) {
        // Fallback for stock Paper, where the core carries no patch.
        if (CoreBridge.PRESENT || !MovementLogger.hasSubscribers()) return;

        final Location from = event.getFrom();
        final Location to = event.getTo();
        final double dx = to.getX() - from.getX();
        final double dy = to.getY() - from.getY();
        final double dz = to.getZ() - from.getZ();

        final Player player = event.getPlayer();
        MovementLogger.report(player.getName(), "player", true,
            from, dx, dy, dz,
            "player", List.of(),
            to, dx, dy, dz);
    }
}
