package paperlab.cplay.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import paperlab.cplay.model.CPlayAssetHandle;
import paperlab.cplay.model.CPlayAssetInfo;
import paperlab.cplay.model.CPlayAssetNamespace;

import static org.junit.jupiter.api.Assertions.*;

public class CPlayAssetStoreTest {

    private Path tempDir;
    private CPlayAssetStore store;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("cplay_test");
        store = new CPlayAssetStore(tempDir.toFile(), Logger.getLogger("CPlayTest"));
        store.init();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    @Test
    public void testAssetRegisterAndLookup() {
        final UUID assetUUID = UUID.randomUUID();
        final UUID ownerUUID = UUID.randomUUID();
        final CPlayAssetHandle handle = new CPlayAssetHandle(CPlayAssetNamespace.GLOBAL, "mycomp");
        final CPlayAssetInfo info = new CPlayAssetInfo(0, assetUUID, handle, "TestComp", 100L, 100L, ownerUUID, ownerUUID);

        store.registerAsset(info);

        assertSame(info, store.getAsset(assetUUID));
        assertSame(info, store.getAsset(handle));
        assertSame(info, store.getAssetByName("TestComp"));
        assertSame(info, store.getAssetByName("testcomp")); // case-insensitive check
        assertEquals(1, store.getAllAssets().size());
    }

    @Test
    public void testDeleteAsset() {
        final UUID assetUUID = UUID.randomUUID();
        final CPlayAssetHandle handle = new CPlayAssetHandle(CPlayAssetNamespace.WORLD, "myseq");
        final CPlayAssetInfo info = new CPlayAssetInfo(1, assetUUID, handle, "TestSeq", 100L, 100L, UUID.randomUUID(), UUID.randomUUID());

        store.registerAsset(info);
        assertNotNull(store.getAsset(assetUUID));

        final boolean deleted = store.deleteAsset(assetUUID);
        assertTrue(deleted);
        assertNull(store.getAsset(assetUUID));
        assertNull(store.getAsset(handle));
        assertNull(store.getAssetByName("TestSeq"));
    }

    @Test
    public void testAtomicSaveAndLoadData() {
        final UUID assetUUID = UUID.randomUUID();
        final CPlayAssetHandle handle = new CPlayAssetHandle(CPlayAssetNamespace.GLOBAL, "cdata");
        final CPlayAssetInfo info = new CPlayAssetInfo(0, assetUUID, handle, "DataComp", 100L, 100L, UUID.randomUUID(), UUID.randomUUID());
        store.registerAsset(info);

        final byte[] dummyData = new byte[]{10, 20, 30, 40, 50};
        store.saveAssetData(assetUUID, dummyData);

        final byte[] loaded = store.getAssetData(assetUUID);
        assertNotNull(loaded);
        assertArrayEquals(dummyData, loaded);
    }

    @Test
    public void testPersistenceAcrossReload() {
        final UUID assetUUID = UUID.randomUUID();
        final UUID ownerUUID = UUID.randomUUID();
        final UUID collabUUID = UUID.randomUUID();
        final CPlayAssetHandle handle = new CPlayAssetHandle(CPlayAssetNamespace.WORLD, "persist");
        final CPlayAssetInfo info = new CPlayAssetInfo(1, assetUUID, handle, "PersistSeq", 100L, 200L, ownerUUID, ownerUUID);
        info.addCollaborator(collabUUID);
        store.registerAsset(info);
        store.saveHistory();

        store.getPlayerCache().put(ownerUUID, "Alice");
        store.savePlayers();

        // Create new store instance pointing to same folder
        final CPlayAssetStore store2 = new CPlayAssetStore(tempDir.toFile(), Logger.getLogger("CPlayTest2"));
        store2.init();

        final CPlayAssetInfo reloaded = store2.getAsset(assetUUID);
        assertNotNull(reloaded);
        assertEquals("PersistSeq", reloaded.getAssetName());
        assertEquals(handle.toString(), reloaded.getHandle().toString());
        assertTrue(reloaded.getCollaboratorUUIDs().contains(collabUUID));

        assertEquals("Alice", store2.getPlayerCache().get(ownerUUID));
    }
}
