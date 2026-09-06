package paperlab.log;

import net.kyori.adventure.text.Component;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;
import paperlab.log.microtiming.MicroTimingLogger;
import paperlab.log.microtiming.MicroTimingLogger.MicroEvent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MicroTimingLoggerTest {

    @Test
    public void testEmptyEventsList() {
        final List<Component> formatted = MicroTimingLogger.formatEvents(List.of(), "merged");
        assertTrue(formatted.isEmpty());
    }

    @Test
    public void testMergedMode() {
        final MicroEvent ev1 = new MicroEvent(100L, "minecraft:overworld", 10, 64, 20, DyeColor.RED, "redstone_wire", "state change", "power: 0 -> 15", 0);
        final MicroEvent ev2 = new MicroEvent(100L, "minecraft:overworld", 10, 64, 20, DyeColor.RED, "redstone_wire", "state change", "power: 0 -> 15", 0);
        final MicroEvent ev3 = new MicroEvent(100L, "minecraft:overworld", 10, 64, 20, DyeColor.RED, "redstone_wire", "state change", "power: 0 -> 15", 0);
        final MicroEvent ev4 = new MicroEvent(100L, "minecraft:overworld", 11, 64, 20, DyeColor.BLUE, "repeater", "state change", "powered: false -> true", 1);

        final List<Component> lines = MicroTimingLogger.formatEvents(List.of(ev1, ev2, ev3, ev4), "merged");
        // Header line + 1 merged line (ev1+2+3) + 1 line (ev4) = 3 lines total
        assertEquals(3, lines.size());
    }

    @Test
    public void testAllMode() {
        final MicroEvent ev1 = new MicroEvent(100L, "minecraft:overworld", 10, 64, 20, DyeColor.RED, "redstone_wire", "state change", "power: 0 -> 15", 0);
        final MicroEvent ev2 = new MicroEvent(100L, "minecraft:overworld", 10, 64, 20, DyeColor.RED, "redstone_wire", "state change", "power: 0 -> 15", 0);
        final MicroEvent ev3 = new MicroEvent(100L, "minecraft:overworld", 11, 64, 20, DyeColor.BLUE, "repeater", "state change", "powered: false -> true", 1);

        final List<Component> lines = MicroTimingLogger.formatEvents(List.of(ev1, ev2, ev3), "all");
        // Header line + ev1 + ev2 + ev3 = 4 lines total
        assertEquals(4, lines.size());
    }

    @Test
    public void testUniqueMode() {
        final MicroEvent ev1 = new MicroEvent(100L, "minecraft:overworld", 10, 64, 20, DyeColor.RED, "redstone_wire", "state change", "power: 0 -> 15", 0);
        final MicroEvent ev2 = new MicroEvent(100L, "minecraft:overworld", 10, 64, 20, DyeColor.RED, "redstone_wire", "state change", "power: 0 -> 15", 0);
        final MicroEvent ev3 = new MicroEvent(100L, "minecraft:overworld", 11, 64, 20, DyeColor.BLUE, "repeater", "state change", "powered: false -> true", 1);

        final List<Component> lines = MicroTimingLogger.formatEvents(List.of(ev1, ev2, ev3), "unique");
        // Header line + ev1 + ev3 (ev2 discarded as duplicate) = 3 lines total
        assertEquals(3, lines.size());
    }
}
