package paperlab.rules;

import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.util.Vector;

/**
 * Правило {@code hardcodeTNTangle}: фиксированный горизонтальный угол разлёта TNT.
 *
 * <p>Ванильный зажжённый динамит получает случайный угол, поэтому одна и та же пушка
 * каждый раз стреляет чуть иначе, и два прогона нельзя сравнивать. С фиксированным углом
 * конструкция ведёт себя одинаково — это и нужно для отладки TNT-механизмов.
 *
 * <p><b>Почему это работает из плагина, хотя Carpet правит конструктор.</b> Скорость
 * задаётся в конструкторе {@code PrimedTnt} и до первого тика сущности никем не читается.
 * Перезапись сразу после появления даёт тот же результат, что и подмена в конструкторе.
 * Значения те же, что у Carpet: {@code (-sin(a) * 0.02, 0.2, -cos(a) * 0.02)}.
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
