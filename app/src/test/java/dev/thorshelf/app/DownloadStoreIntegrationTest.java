package dev.thorshelf.app;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDownloadManager;
import org.robolectric.shadows.ShadowDownloadManager.ShadowRequest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises persistence and Android queue integration without downloading any file. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class DownloadStoreIntegrationTest {
    private Context context;
    private DownloadManager manager;
    private ShadowDownloadManager queue;
    private DownloadStore store;
    private long unrelatedId;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(DownloadManager.class);
        queue = shadowOf(manager);
        // Robolectric starts at ID 0; Android's SQLite download rows start at 1.
        // Retain an unrelated row as a guard against cancelling another app's work.
        unrelatedId = manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        store = new DownloadStore(context);
    }

    @Test public void queueSurvivesStoreRecreationWithoutPersistingSessionSecrets() {
        long id = store.enqueue("https://download2.erista.me/content?token=temporary-secret", "sample.cia",
                "ThorShelf test", "session=private-cookie", "https://hshop.erista.me/t/42?token=page-secret#fragment", true);
        ShadowRequest request = shadowOf(queue.getRequest(id));
        request.setStatus(DownloadManager.STATUS_PENDING);
        assertEquals(DownloadManager.Request.NETWORK_WIFI, request.getAllowedNetworkTypes());
        assertTrue(request.getDestination().getPath().contains("/Download/ThorShelf/"));

        List<DownloadStore.Entry> restored = new DownloadStore(context).list();
        assertEquals(1, restored.size());
        assertEquals(id, restored.get(0).id);
        assertEquals("https://hshop.erista.me/t/42", restored.get(0).sourcePage);
        assertTrue(restored.get(0).isActive());
        assertEquals("sample.cia", restored.get(0).name);
        assertEquals("sample.cia", restored.get(0).displayName);
        assertEquals(restored.get(0).name, request.getDestination().getLastPathSegment());
        assertEquals(restored.get(0).name, request.getTitle());

        String saved = context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE)
                .getString("records", "");
        assertFalse(saved.contains("temporary-secret"));
        assertFalse(saved.contains("private-cookie"));
        assertFalse(saved.contains("page-secret"));
        assertFalse(saved.contains("download2.erista.me"));
    }

    @Test public void cancelledTransferStaysRemovedAfterRecreation() {
        long first = enqueue("sample.cia");
        long second = enqueue("sample.cia");
        assertNotNull(queue.getRequest(first));
        assertNotEquals(shadowOf(queue.getRequest(first)).getDestination(),
                shadowOf(queue.getRequest(second)).getDestination());

        new DownloadStore(context).cancel(first);
        assertNull(queue.getRequest(first));
        assertNotNull(queue.getRequest(unrelatedId));
        assertNotNull(queue.getRequest(second));
        List<DownloadStore.Entry> restored = new DownloadStore(context).list();
        assertEquals(1, restored.size());
        assertEquals(second, restored.get(0).id);
    }

    @Test public void pendingDownloadsReserveReadableNamesAcrossStoreRecreation() {
        long first = enqueue("Game.cia");
        long second = enqueue("Game.cia");
        store = new DownloadStore(context);
        long third = enqueue("Game.cia");
        assertEquals("Game.cia", shadowOf(queue.getRequest(first)).getDestination().getLastPathSegment());
        assertEquals("Game (2).cia", shadowOf(queue.getRequest(second)).getDestination().getLastPathSegment());
        assertEquals("Game (3).cia", shadowOf(queue.getRequest(third)).getDestination().getLastPathSegment());
        assertEquals("Game (3).cia", store.list().get(0).name);
        assertEquals("Game (3).cia", store.list().get(0).displayName);
    }

    @Test public void visibleExistingFileIsPreservedAndItsNameIsSkipped() throws Exception {
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ThorShelf");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File existing = new File(directory, "Existing game.cia");
        assertTrue(existing.createNewFile());
        byte[] original = "existing user file".getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(existing.toPath(), original);
            long id = enqueue("Existing game.cia");
            assertEquals("Existing game (2).cia", shadowOf(queue.getRequest(id)).getDestination().getLastPathSegment());
            assertArrayEquals(original, Files.readAllBytes(existing.toPath()));
        } finally {
            assertTrue(existing.delete());
        }
    }

    @Test public void androidQueueStillReservesNamesWhenSavedRecordsAreLost() {
        long first = enqueue("Game.cia");
        context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE).edit().remove("records").commit();
        store = new DownloadStore(context);
        long second = enqueue("Game.cia");
        assertEquals("Game (2).cia", shadowOf(queue.getRequest(second)).getDestination().getLastPathSegment());
        assertNotNull(queue.getRequest(first));
    }

    @Test public void legacyOriginalNameAndDownloadIdentityRemainIntactWithReadableLabel() throws Exception {
        long id = enqueue("placeholder.cia");
        String legacy = "000400000011C400 Pokemon Omega Ruby (CTR-P-ECRA) (v3.3.0) (W).legit-a3bfbe6f17f0.cia";
        org.json.JSONObject record = new org.json.JSONObject().put("id", id).put("name", legacy)
                .put("sourcePage", "https://hshop.erista.me/t/2476");
        String saved = new org.json.JSONArray().put(record).toString();
        context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE).edit().putString("records", saved).commit();
        Uri destination = shadowOf(queue.getRequest(id)).getDestination();

        DownloadStore.Entry entry = new DownloadStore(context).list().get(0);
        assertEquals(id, entry.id);
        assertEquals(legacy, entry.name);
        assertEquals(GameFilename.clean(legacy), entry.displayName);
        assertFalse(entry.displayName.contains("000400000011C400"));
        assertFalse(entry.displayName.contains("a3bfbe6f17f0"));
        assertEquals(destination, shadowOf(queue.getRequest(id)).getDestination());
        assertEquals(saved, context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE)
                .getString("records", ""));
    }

    @Test public void progressAndExternalRemovalAreReflectedInTheSavedQueue() {
        long id = enqueue("sample.cia");
        ShadowRequest request = shadowOf(queue.getRequest(id));
        request.setStatus(DownloadManager.STATUS_RUNNING);
        request.setBytesSoFar(512);
        request.setTotalSize(2048);
        DownloadStore.Entry running = store.list().get(0);
        assertEquals(512, running.downloaded);
        assertEquals(2048, running.total);
        assertEquals("Downloading", running.statusLabel);

        manager.remove(id);
        DownloadStore.Entry removed = new DownloadStore(context).list().get(0);
        assertEquals(DownloadManager.STATUS_FAILED, removed.status);
        assertFalse(removed.isActive());
        assertNull(store.completedUri(id));
        store.cancel(id);
        assertTrue(new DownloadStore(context).list().isEmpty());
    }

    private long enqueue(String name) {
        long id = store.enqueue("https://download2.erista.me/content", name, null, null,
                "https://hshop.erista.me/t/42", false);
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_PENDING);
        return id;
    }
}
