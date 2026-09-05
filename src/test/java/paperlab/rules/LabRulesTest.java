package paperlab.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class LabRulesTest {

    @Test
    public void testPerWorldTickRuleRegistration() {
        final LabRule<?> rule = LabRules.get("perWorldTick");
        assertNotNull(rule, "Rule perWorldTick should be registered");
        assertEquals("perWorldTick", rule.name());
        assertEquals(Boolean.FALSE, rule.vanilla());
        assertTrue(rule.options().contains("true"));
        assertTrue(rule.options().contains("false"));
        assertEquals("paperlab.rule.perworldtick", rule.permission());
    }

    @Test
    public void testPerWorldTickRuleSettingAndParsing() {
        final LabRule<Boolean> rule = LabRules.PER_WORLD_TICK;
        assertNull(rule.set("true"), "Setting valid 'true' should succeed");
        assertEquals(Boolean.TRUE, rule.value());

        assertNull(rule.set("false"), "Setting valid 'false' should succeed");
        assertEquals(Boolean.FALSE, rule.value());

        assertNotNull(rule.set("invalid_value"), "Setting invalid value should return error");
    }

    @Test
    public void testRuleDefaultsSaveAndLoad(@TempDir Path tempDir) {
        final Path conf = tempDir.resolve("rules.conf");
        final Logger logger = Logger.getLogger("RuleDefaultsTest");

        final RuleDefaults defaults = new RuleDefaults(conf, logger);
        defaults.load();
        assertFalse(defaults.has("perWorldTick"));

        defaults.set("perWorldTick", "true");
        assertTrue(defaults.has("perWorldTick"));
        assertEquals("true", defaults.get("perWorldTick"));

        // Reload from disk
        final RuleDefaults reloaded = new RuleDefaults(conf, logger);
        reloaded.load();
        assertTrue(reloaded.has("perWorldTick"));
        assertEquals("true", reloaded.get("perWorldTick"));

        // Remove
        assertTrue(reloaded.remove("perWorldTick"));
        assertFalse(reloaded.has("perWorldTick"));

        final RuleDefaults reloadedAfterRemove = new RuleDefaults(conf, logger);
        reloadedAfterRemove.load();
        assertFalse(reloadedAfterRemove.has("perWorldTick"));
    }
}
