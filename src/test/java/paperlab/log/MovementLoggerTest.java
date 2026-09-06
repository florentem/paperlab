package paperlab.log;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import paperlab.log.movement.MovementLogger;

import static org.junit.jupiter.api.Assertions.*;

public class MovementLoggerTest {

    @Test
    public void testNonZeroFilter() {
        // Zero movement length should be rejected if option starts with non_zero:
        final boolean zeroFiltered = MovementLogger.shouldReportFor(null, "non_zero:@s", "Player1", null, 0.0, true, "player");
        assertFalse(zeroFiltered);

        // Near-zero movement
        final boolean epsilonFiltered = MovementLogger.shouldReportFor(null, "non_zero:@s", "Player1", null, 1e-13, true, "player");
        assertFalse(epsilonFiltered);

        // Non-zero movement
        final boolean nonZeroAllowed = MovementLogger.shouldReportFor(null, "non_zero:@s", "Player1", null, 0.05, true, "player");
        assertTrue(nonZeroAllowed);
    }

    @Test
    public void testSelfSelector() {
        assertTrue(MovementLogger.shouldReportFor(null, "@s", "Player1", null, 0.1, true, "player"));
        assertFalse(MovementLogger.shouldReportFor(null, "@s", "Creeper1", null, 0.1, false, "creeper"));

        assertTrue(MovementLogger.shouldReportFor(null, "me", "Player1", null, 0.1, true, "player"));
        assertFalse(MovementLogger.shouldReportFor(null, "me", "Zombie1", null, 0.1, false, "zombie"));
    }

    @Test
    public void testSpecificEntityName() {
        assertTrue(MovementLogger.shouldReportFor(null, "Creeper1", "Creeper1", null, 0.1, false, "creeper"));
        assertTrue(MovementLogger.shouldReportFor(null, "creeper1", "Creeper1", null, 0.1, false, "creeper"));
        assertFalse(MovementLogger.shouldReportFor(null, "Creeper1", "Skeleton1", null, 0.1, false, "skeleton"));
    }

    @Test
    public void testWildcardSelectors() {
        assertTrue(MovementLogger.shouldReportFor(null, "all", "AnyEntity", null, 0.1, false, "any"));
        assertTrue(MovementLogger.shouldReportFor(null, "*", "AnyEntity", null, 0.1, false, "any"));
    }

    @Test
    public void testStepRecord() {
        final MovementLogger.StepRecord step = new MovementLogger.StepRecord(
            0.0, 1.0, 0.0,
            0.0, 0.5, 0.0,
            "Collision"
        );
        assertEquals(0.0, step.oldX());
        assertEquals(1.0, step.oldY());
        assertEquals(0.5, step.newY());
        assertEquals("Collision", step.reason());
    }
}
