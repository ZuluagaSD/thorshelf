package dev.thorshelf.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
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
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.lang.reflect.Proxy;
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
    private ExportServiceTest.FileProvider documents;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(DownloadManager.class);
        queue = shadowOf(manager);
        // Robolectric starts at ID 0; Android's SQLite download rows start at 1.
        // Retain an unrelated row as a guard against cancelling another app's work.
        unrelatedId = manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        store = new DownloadStore(context);
        documents = new ExportServiceTest.FileProvider();
        ProviderInfo provider = new ProviderInfo();
        provider.authority = "dev.thorshelf.test.prepared-store";
        provider.exported = true;
        provider.grantUriPermissions = true;
        documents.attachInfo(context, provider);
        ShadowContentResolver.registerProviderInternal(provider.authority, documents);
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
        assertFalse(entry.isPrepared());
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

    @Test public void preparedEligibilityBelongsToOneDownloadAndSurvivesOtherQueueChanges() throws Exception {
        long preparedId = complete("Prepared.cia");
        long untouchedId = complete("Untouched.cia");
        Uri destination = preparedCopy("prepared.cia", new byte[]{1, 2, 3});
        assertTrue(store.markPrepared(preparedId, destination));

        store = new DownloadStore(context);
        assertTrue(entry(preparedId).isPrepared());
        assertFalse(entry(untouchedId).isPrepared());
        long newer = enqueue("Later.cia");
        store.cancel(newer);
        assertTrue(new DownloadStore(context).list().stream()
                .filter(entry -> entry.id == preparedId).findFirst().orElseThrow().isPrepared());
        assertNotNull(queue.getRequest(untouchedId));
    }

    @Test public void markingRequiresTrackedCompletedOriginalAndSeparateContentDestination() throws Exception {
        long id = enqueue("Sample.cia");
        Uri destination = preparedCopy("prepared.cia", new byte[]{1});
        assertFalse(store.markPrepared(id, destination));
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        assertFalse(store.markPrepared(id + 1000, destination));
        assertFalse(store.markPrepared(id, manager.getUriForDownloadedFile(id)));
        assertFalse(store.markPrepared(id, Uri.parse("file:///tmp/prepared.cia")));
        assertFalse(store.markPrepared(id, null));
        assertFalse(entry(id).isPrepared());
        assertNotNull(queue.getRequest(id));
    }

    @Test public void failedPreparationMarkerCommitRestoresMemoryAndDoesNotUnlockDeletion() throws Exception {
        long id = complete("Sample.cia");
        Uri destination = preparedCopy("prepared.cia", new byte[]{1});
        SharedPreferences real = context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE);
        String before = real.getString("records", "");
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    Object result = method.invoke(real, args);
                    if (!"edit".equals(method.getName())) return result;
                    SharedPreferences.Editor editor = (SharedPreferences.Editor) result;
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, editMethod, editArgs) -> {
                                Object editResult = editMethod.invoke(editor, editArgs);
                                if ("commit".equals(editMethod.getName())) return false;
                                return editResult == editor ? editorProxy : editResult;
                            });
                });
        Context failingContext = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return "thorshelf_downloads_v1".equals(name) ? failing : super.getSharedPreferences(name, mode);
            }
        };

        assertFalse(new DownloadStore(failingContext).markPrepared(id, destination));
        assertEquals(before, real.getString("records", ""));
        assertFalse(new DownloadStore(context).list().get(0).isPrepared());
        assertThrows(IllegalStateException.class, () -> store.deletePreparedOriginal(id));
        assertNotNull(queue.getRequest(id));
    }

    @Test public void deletingUnpreparedOriginalIsRejectedWithoutRemovingAnyDownload() {
        long id = complete("Sample.cia");
        assertThrows(IllegalStateException.class, () -> store.deletePreparedOriginal(id));
        assertNotNull(queue.getRequest(id));
        assertNotNull(queue.getRequest(unrelatedId));
        assertEquals(1, store.list().size());
    }

    @Test public void missingOrEmptyPreparedCopyKeepsOriginalAvailable() throws Exception {
        long id = complete("Sample.cia");
        Uri destination = preparedCopy("prepared.cia", new byte[]{1, 2, 3});
        assertTrue(store.markPrepared(id, destination));
        File prepared = documents.files.remove(destination);
        assertThrows(IllegalStateException.class, () -> store.deletePreparedOriginal(id));
        assertNotNull(queue.getRequest(id));

        Files.write(prepared.toPath(), new byte[0]);
        documents.files.put(destination, prepared);
        assertThrows(IllegalStateException.class, () -> store.deletePreparedOriginal(id));
        assertNotNull(queue.getRequest(id));
        assertEquals(1, store.list().size());
        assertTrue(documents.deleted.isEmpty());
    }

    @Test public void deletionRechecksOriginalCompletion() throws Exception {
        long id = complete("Sample.cia");
        assertTrue(store.markPrepared(id, preparedCopy("prepared.cia", new byte[]{1})));
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_RUNNING);
        assertThrows(IllegalStateException.class, () -> store.deletePreparedOriginal(id));
        assertNotNull(queue.getRequest(id));
        assertTrue(documents.deleted.isEmpty());
    }

    @Test public void deletingPreparedOriginalKeepsPreparedFileAndOtherDownloads() throws Exception {
        long id = complete("Sample.cia");
        long otherId = complete("Other.cia");
        byte[] preparedBytes = {5, 4, 3, 2, 1};
        Uri destination = preparedCopy("prepared.cia", preparedBytes);
        Uri otherDestination = preparedCopy("other-prepared.cia", new byte[]{9, 8});
        assertTrue(store.markPrepared(id, destination));
        assertTrue(store.markPrepared(otherId, otherDestination));

        new DownloadStore(context).deletePreparedOriginal(id);

        assertNull(queue.getRequest(id));
        assertNotNull(queue.getRequest(otherId));
        assertNotNull(queue.getRequest(unrelatedId));
        assertArrayEquals(preparedBytes, Files.readAllBytes(documents.files.get(destination).toPath()));
        assertTrue(documents.deleted.isEmpty());
        List<DownloadStore.Entry> restored = new DownloadStore(context).list();
        assertEquals(1, restored.size());
        assertEquals(otherId, restored.get(0).id);
        assertTrue(restored.get(0).isPrepared());
    }

    private DownloadStore.Entry entry(long id) {
        return store.list().stream().filter(entry -> entry.id == id).findFirst().orElseThrow();
    }

    private long complete(String name) {
        long id = enqueue(name);
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        return id;
    }

    private Uri preparedCopy(String name, byte[] contents) throws Exception {
        File file = File.createTempFile("prepared-store-", ".cia", context.getCacheDir());
        Files.write(file.toPath(), contents);
        Uri uri = Uri.parse("content://dev.thorshelf.test.prepared-store/document/" + name);
        documents.files.put(uri, file);
        return uri;
    }

    private long enqueue(String name) {
        long id = store.enqueue("https://download2.erista.me/content", name, null, null,
                "https://hshop.erista.me/t/42", false);
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_PENDING);
        return id;
    }
}
