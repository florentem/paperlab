package paperlab.cplay.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CPlayAssetHandleTest {

    @Test
    public void testHandleToStringAndParse() {
        final CPlayAssetHandle globalHandle = new CPlayAssetHandle(CPlayAssetNamespace.GLOBAL, "abc123");
        assertEquals("g:abc123", globalHandle.toString());

        final CPlayAssetHandle parsedGlobal = CPlayAssetHandle.parse("g:abc123");
        assertEquals(CPlayAssetNamespace.GLOBAL, parsedGlobal.getNamespace());
        assertEquals("abc123", parsedGlobal.getHandle());
        assertEquals(globalHandle, parsedGlobal);

        final CPlayAssetHandle parsedGlobalUpper = CPlayAssetHandle.parse("G:abc123");
        assertEquals(globalHandle, parsedGlobalUpper);

        final CPlayAssetHandle worldHandle = new CPlayAssetHandle(CPlayAssetNamespace.WORLD, "xyz789");
        assertEquals("w:xyz789", worldHandle.toString());

        final CPlayAssetHandle parsedWorld = CPlayAssetHandle.parse("w:xyz789");
        assertEquals(CPlayAssetNamespace.WORLD, parsedWorld.getNamespace());
        assertEquals("xyz789", parsedWorld.getHandle());
        assertEquals(worldHandle, parsedWorld);

        final CPlayAssetHandle parsedWorldUpper = CPlayAssetHandle.parse("W:xyz789");
        assertEquals(worldHandle, parsedWorldUpper);
    }

    @Test
    public void testParseInvalidHandleThrows() {
        assertThrows(IllegalArgumentException.class, () -> CPlayAssetHandle.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CPlayAssetHandle.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CPlayAssetHandle.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> CPlayAssetHandle.parse("X:12345"));
        assertThrows(IllegalArgumentException.class, () -> CPlayAssetHandle.parse("g:"));
    }

    @Test
    public void testRandomHandleGeneration() {
        final CPlayAssetHandle randomGlobal = CPlayAssetHandle.random(CPlayAssetNamespace.GLOBAL, 6);
        assertEquals(CPlayAssetNamespace.GLOBAL, randomGlobal.getNamespace());
        assertEquals(6, randomGlobal.getHandle().length());
        assertTrue(randomGlobal.toString().startsWith("g:"));

        final CPlayAssetHandle randomWorld = CPlayAssetHandle.random(CPlayAssetNamespace.WORLD, 8);
        assertEquals(CPlayAssetNamespace.WORLD, randomWorld.getNamespace());
        assertEquals(8, randomWorld.getHandle().length());
        assertTrue(randomWorld.toString().startsWith("w:"));
    }

    @Test
    public void testFromNameUnique() {
        final java.util.Set<CPlayAssetHandle> existing = new java.util.HashSet<>();
        final CPlayAssetHandle h1 = CPlayAssetHandle.fromNameUnique(CPlayAssetNamespace.GLOBAL, "Test Comp", existing::contains);
        assertEquals("g:test_comp", h1.toString());
        existing.add(h1);

        final CPlayAssetHandle h2 = CPlayAssetHandle.fromNameUnique(CPlayAssetNamespace.GLOBAL, "Test Comp", existing::contains);
        assertEquals("g:test_comp2", h2.toString());
    }

    @Test
    public void testEqualityAndHashCode() {
        final CPlayAssetHandle h1 = new CPlayAssetHandle(CPlayAssetNamespace.GLOBAL, "same");
        final CPlayAssetHandle h2 = new CPlayAssetHandle(CPlayAssetNamespace.GLOBAL, "same");
        final CPlayAssetHandle h3 = new CPlayAssetHandle(CPlayAssetNamespace.WORLD, "same");

        assertEquals(h1, h2);
        assertEquals(h1.hashCode(), h2.hashCode());
        assertNotEquals(h1, h3);
    }
}
