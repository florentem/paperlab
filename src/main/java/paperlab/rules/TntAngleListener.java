package paperlab.rules;

import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.util.Vector;

/**
 * The {@code hardcodeTNTangle} rule: a fixed horizontal launch angle for TNT.
 *
 * <p>Vanilla primed TNT gets a random angle, so the same cannon fires slightly differently every
 * time and two runs cannot be compared. With a fixed angle the contraption behaves identically —
 * which is what debugging TNT machinery needs.
 *
 * <p><b>Why this works from a plugin even though Carpet patches the constructor.</b> The velocity
 * is set in the {@code PrimedTnt} constructor and is read by nobody before the entity's first
 * tick. Overwriting it right after the spawn gives the same result as substituting it in the
 * constructor. The values are Carpet's: {@code (-sin(a) * 0.02, 0.2, -cos(a) * 0.02)}.
 */
public final class TntAngleListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(final EntitySpawnEvent event) {
        final double angle = LabRules.HARDCODE_TNT_ANGLE.value();
        if (angle < 0.0D || !(event.getEntity() instanceof final TNTPrimed tnt)) {
            return;
        }
        tnt.setVelocity(new Vector(-Math.sin(angle) * 0.02D, 0.2D, -Math.cos(angle) * 0.02D));
    }
}
