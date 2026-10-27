package dev.thorshelf.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowDownloadManager;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class NdsSavedDownloadTest {
    private Context context;
    private DownloadManager manager;
    private ShadowDownloadManager queue;
    private DownloadStore store;
    private ExportServiceTest.FileProvider documents;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(DownloadManager.class);
        queue = shadowOf(manager);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        store = new DownloadStore(context);
        documents = new ExportServiceTest.FileProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "dev.thorshelf.test.saved-ds";
        info.exported = true;
        info.grantUriPermissions = true;
        documents.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, documents);
    }

    @Test public void savedRomSurvivesRecreationArtworkAndPreparedUpdates() throws Exception {
        long id = completeDs("Game.zip");
        Uri destination = savedCopy("Game.nds");
        assertNull(entry(id).savedDsDestination);
        assertNull(store.savedDsUri(id));
        assertTrue(store.markDsSaved(id, destination));
        assertFalse(entry(id).isPrepared());
        assertThrows(IllegalStateException.class, () -> store.deletePreparedOriginal(id));

        String artwork = "https://db-nds-shop.fr/api/v1/assets/boxart/Game.nds.png";
        assertTrue(store.updateArtwork(id, artwork));
        assertTrue(store.markPrepared(id, savedCopy("Other.cia")));
        long other = completeDs("Another.nds");
        store.cancel(other);

        DownloadStore restored = new DownloadStore(context);
        assertEquals(destination, restored.list().get(0).savedDsDestination);
        assertEquals(destination, restored.savedDsUri(id));
        assertEquals(artwork, restored.list().get(0).artworkUrl);
        assertTrue(restored.list().get(0).isPrepared());
    }

    @Test public void markerRequiresCompletedDsSourceAndSeparateContentDestination() throws Exception {
        long id = completeDs("Game.nds");
        Uri destination = savedCopy("Game.nds");
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_RUNNING);
        assertFalse(store.markDsSaved(id, destination));
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        assertFalse(store.markDsSaved(id + 1000, destination));
        assertFalse(store.markDsSaved(id, manager.getUriForDownloadedFile(id)));
        assertFalse(store.markDsSaved(id, Uri.parse("file:///tmp/Game.nds")));
        assertFalse(store.markDsSaved(id, Uri.parse("content:///Game.nds")));
        assertFalse(store.markDsSaved(id, null));
        for (String name : new String[]{"Game.cia", "Unknown.zip"}) {
            long wrongType = store.enqueue("https://download2.erista.me/content", name,
                    null, null, DownloadPolicy.HOME, false);
            shadowOf(queue.getRequest(wrongType)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
            assertFalse(store.markDsSaved(wrongType, destination));
            assertNull(entry(wrongType).savedDsDestination);
        }
        assertNull(entry(id).savedDsDestination);
        assertTrue(store.markDsSaved(id, destination));
        assertTrue(store.markDsSaved(completeDs("Known.zip"), destination));
    }

    @Test public void missingEmptyAndRevokedSavedCopiesAreNotReturned() throws Exception {
        long id = completeDs("Game.nds");
        Uri destination = savedCopy("Game.nds");
        assertTrue(store.markDsSaved(id, destination));
        assertEquals(destination, store.savedDsUri(id));
        File output = documents.files.remove(destination);
        assertNull(store.savedDsUri(id));
        documents.files.put(destination, output);
        Files.write(output.toPath(), new byte[0]);
        assertNull(store.savedDsUri(id));
        Files.write(output.toPath(), new byte[]{1});
        shadowOf(context.getContentResolver()).registerInputStreamSupplier(destination, () -> {
            throw new SecurityException("Synthetic revoked permission");
        });
        assertNull(store.savedDsUri(id));
        assertNotNull(queue.getRequest(id));
        assertTrue(documents.deleted.isEmpty());
    }

    @Test public void failedMarkerCommitRestoresPreviousCopy() throws Exception {
        long id = completeDs("Game.nds");
        Uri previous = savedCopy("Previous.nds");
        Uri replacement = savedCopy("Replacement.nds");
        assertTrue(store.markDsSaved(id, previous));
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

        assertFalse(new DownloadStore(failingContext).markDsSaved(id, replacement));
        assertEquals(before, real.getString("records", ""));
        assertEquals(previous, new DownloadStore(context).savedDsUri(id));
        assertTrue(documents.deleted.isEmpty());
    }

    private DownloadStore.Entry entry(long id) {
        return new DownloadStore(context).list().stream()
                .filter(entry -> entry.id == id).findFirst().orElseThrow();
    }

    private long completeDs(String name) {
        long id = store.enqueueNds("https://github.com/owner/game/releases/download/v1/" + name,
                name, null, NdsCatalog.HOME, false);
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        return id;
    }

    private Uri savedCopy(String name) throws Exception {
        File file = File.createTempFile("saved-ds-", ".nds", context.getCacheDir());
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        Uri uri = Uri.parse("content://dev.thorshelf.test.saved-ds/document/" + name);
        documents.files.put(uri, file);
        return uri;
    }
}
