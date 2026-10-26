package dev.thorshelf.app;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDownloadManager;
import org.robolectric.shadows.ShadowDownloadManager.ShadowRequest;

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
        assertTrue(restored.get(0).name.endsWith(".cia"));

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
