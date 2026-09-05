package paperlab.cplay.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CPlayAssetHandleTest {

    @Test
    public void testHandleToStringAndParse() {
        final CPlayAssetHandle compHandle = new CPlayAssetHandle(CPlayAssetNamespace.COMPOSITION, "abc123");
        assertEquals("c:abc123", compHandle.toString());

        final CPlayAssetHandle parsedComp = CPlayAssetHandle.parse("c:abc123");
        assertEquals(CPlayAssetNamespace.COMPOSITION, parsedComp.getNamespace());
        assertEquals("abc123", parsedComp.getHandle());
        assertEquals(compHandle, parsedComp);

        final CPlayAssetHandle parsedCompUpper = CPlayAssetHandle.parse("C:abc123");
        assertEquals(compHandle, parsedCompUpper);

        final CPlayAssetHandle seqHandle = new CPlayAssetHandle(CPlayAssetNamespace.SEQUENCE, "xyz789");
        assertEquals("s:xyz789", seqHandle.toString());

        final CPlayAssetHandle parsedSeq = CPlayAssetHandle.parse("s:xyz789");
        assertEquals(CPlayAssetNamespace.SEQUENCE, parsedSeq.getNamespace());
        assertEquals("xyz789", parsedSeq.getHandle());
        assertEquals(seqHandle, parsedSeq);

        final CPlayAssetHandle parsedSeqUpper = CPlayAssetHandle.parse("S:xyz789");
        assertEquals(seqHandle, parsedSeqUpper);
    }

    @Test
    public void testParseInvalidHandleThrows() {
        assertThrows(IllegalArgumentException.class, () -> CPlayAssetHandle.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CPlayAssetHandle.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CPlayAssetHandle.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> CPlayAssetHandle.parse("X:12345"));
        assertThrows(IllegalArgumentException.class, () -> CPlayAssetHandle.parse("c:"));
    }

    @Test
    public void testRandomHandleGeneration() {
        final CPlayAssetHandle randomComp = CPlayAssetHandle.random(CPlayAssetNamespace.COMPOSITION, 6);
        assertEquals(CPlayAssetNamespace.COMPOSITION, randomComp.getNamespace());
        assertEquals(6, randomComp.getHandle().length());
        assertTrue(randomComp.toString().startsWith("c:"));

        final CPlayAssetHandle randomSeq = CPlayAssetHandle.random(CPlayAssetNamespace.SEQUENCE, 8);
        assertEquals(CPlayAssetNamespace.SEQUENCE, randomSeq.getNamespace());
        assertEquals(8, randomSeq.getHandle().length());
        assertTrue(randomSeq.toString().startsWith("s:"));
    }

    @Test
    public void testEqualityAndHashCode() {
        final CPlayAssetHandle h1 = new CPlayAssetHandle(CPlayAssetNamespace.COMPOSITION, "same");
        final CPlayAssetHandle h2 = new CPlayAssetHandle(CPlayAssetNamespace.COMPOSITION, "same");
        final CPlayAssetHandle h3 = new CPlayAssetHandle(CPlayAssetNamespace.SEQUENCE, "same");

        assertEquals(h1, h2);
        assertEquals(h1.hashCode(), h2.hashCode());
        assertNotEquals(h1, h3);
    }
}
