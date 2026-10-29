package dev.thorshelf.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.util.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
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
import java.util.Locale;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class GbaDownloadStoreTest {
    private static final String DETAIL = "gba://catalog/title/f3ae088181bf583e55daf962a92bb46f4f1d07b7";
    private Context context;
    private DownloadManager manager;
    private ShadowDownloadManager queue;
    private DownloadStore store;
    private GbaCatalog.Rom rom;
    private ExportServiceTest.FileProvider documents;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(DownloadManager.class);
        queue = shadowOf(manager);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        store = new DownloadStore(context);
        rom = GbaCatalog.lookup(DETAIL);
        assertNotNull(rom);
        documents = new ExportServiceTest.FileProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "dev.thorshelf.test.saved-gba";
        info.exported = true;
        info.grantUriPermissions = true;
        documents.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, documents);
    }

    @Test public void queuePersistsPlatformAndIdentityWithoutDownloadAddressOrBrowserHeaders() {
        long id = store.enqueueGba(rom.downloadUrl, "Emerald.gba", "ThorShelf test", DETAIL, true, "",
                rom.sha1.toUpperCase(Locale.ROOT), rom.sizeBytes);
        ShadowDownloadManager.ShadowRequest request = shadowOf(queue.getRequest(id));
        assertEquals(DownloadManager.Request.NETWORK_WIFI, request.getAllowedNetworkTypes());
        assertEquals("ThorShelf · Game Boy Advance", request.getDescription());
        assertEquals(1, request.getRequestHeaders().size());
        assertEquals(new Pair<>("User-Agent", "ThorShelf test"), request.getRequestHeaders().get(0));
        DownloadStore.Entry entry = entry(id);
        assertEquals(GamePlatform.GBA, entry.platform);
        assertEquals(DETAIL, entry.sourcePage);
        assertEquals(rom.sha1, entry.expectedSha1);
        assertEquals(rom.sizeBytes, entry.expectedSize);
        assertNull(entry.savedRomDestination);
        assertFalse(preferences().getString("records", "").contains("archive.org"));
        assertTrue(preferences().getString("records", "").contains("\"platform\":\"gba\""));
    }

    @Test public void acceptsApprovedMirrorOfExactEdition() {
        String mirror = rom.downloadUrl.replace("https://archive.org/download/",
                "https://ia800603.us.archive.org/2/items/");
        long id = store.enqueueGba(mirror, "Emerald.gba", null, DETAIL, false, "", rom.sha1, rom.sizeBytes);
        assertEquals(mirror, shadowOf(queue.getRequest(id)).getUri().toString());
        assertTrue(shadowOf(queue.getRequest(id)).getRequestHeaders().isEmpty());
    }

    @Test public void rejectsMismatchedEditionIdentitySourceAndFilenameBeforeQueueing() {
        assertThrows(IllegalArgumentException.class, () -> store.enqueueGba(rom.downloadUrl, "Game.gba", null,
                DETAIL, false, "", "0".repeat(40), rom.sizeBytes));
        assertThrows(IllegalArgumentException.class, () -> store.enqueueGba(rom.downloadUrl, "Game.gba", null,
                DETAIL, false, "", rom.sha1, rom.sizeBytes + 1));
        assertThrows(IllegalArgumentException.class, () -> store.enqueueGba(GbaCatalog.DOWNLOAD_BASE + "Wrong.gba",
                "Game.gba", null, DETAIL, false, "", rom.sha1, rom.sizeBytes));
        assertThrows(IllegalArgumentException.class, () -> store.enqueueGba(rom.downloadUrl, "Game.gba", null,
                GbaCatalog.HOME, false, "", rom.sha1, rom.sizeBytes));
        for (String filename : new String[]{null, "", "Game.zip", "Game.nds", "Game.gba.exe"}) {
            assertThrows(IllegalArgumentException.class, () -> store.enqueueGba(rom.downloadUrl, filename, null,
                    DETAIL, false, "", rom.sha1, rom.sizeBytes));
        }
        assertTrue(store.list().isEmpty());
        assertEquals(1, queue.getRequestCount());
    }

    @Test public void legacyDsAndThreeDsHistorySurvivesPlatformMigrationAndRewrite() throws Exception {
        JSONArray old = new JSONArray()
                .put(new JSONObject().put("id", 101).put("name", "Direct.nds").put("sourcePage", DownloadPolicy.HOME))
                .put(new JSONObject().put("id", 102).put("name", "Bundle.zip").put("sourcePage", NdsCatalog.HOME)
                        .put("savedDsDestination", "content://saved/document/ds"))
                .put(new JSONObject().put("id", 103).put("name", "Prepared.cia").put("sourcePage", DownloadPolicy.HOME)
                        .put("preparedDestination", "content://saved/document/3ds").put("preparedSize", 100)
                        .put("preparedDigest", "a".repeat(64)).put("requiresInstall", false).put("originalDeleted", true))
                .put(new JSONObject().put("id", 104).put("name", "Unknown.zip").put("sourcePage", DownloadPolicy.HOME));
        preferences().edit().putString("records", old.toString()).commit();
        assertEquals(GamePlatform.DS, entry(101).platform);
        assertEquals(GamePlatform.DS, entry(102).platform);
        assertEquals(GamePlatform.THREE_DS, entry(103).platform);
        assertEquals(GamePlatform.THREE_DS, entry(104).platform);
        enqueue(); // Rewrites old entries alongside a new GBA record.
        assertEquals(Uri.parse("content://saved/document/ds"), entry(102).savedDsDestination);
        assertEquals(entry(102).savedDsDestination, entry(102).savedRomDestination);
        assertEquals(NdsCatalog.HOME, entry(102).sourcePage);
        assertTrue(entry(103).isPrepared());
        assertTrue(entry(103).hasVerifiedCopy);
        assertTrue(entry(103).originalDeleted);
        assertFalse(entry(103).requiresInstall);
    }

    @Test public void savedMarkerRequiresCompletedGbaAndMatchingVerifiedIdentity() throws Exception {
        long id = enqueue();
        Uri saved = savedCopy();
        assertFalse(store.markGbaSaved(id, saved, rom.sizeBytes, rom.sha1));
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        assertFalse(store.markGbaSaved(id, saved, rom.sizeBytes + 1, rom.sha1));
        assertFalse(store.markGbaSaved(id, saved, rom.sizeBytes, "0".repeat(40)));
        assertFalse(store.markGbaSaved(id, manager.getUriForDownloadedFile(id), rom.sizeBytes, rom.sha1));
        assertFalse(store.markGbaSaved(id, Uri.parse("file:///tmp/Game.gba"), rom.sizeBytes, rom.sha1));
        assertTrue(store.markGbaSaved(id, saved, rom.sizeBytes, rom.sha1));
        assertEquals(saved, store.savedGbaUri(id));
        assertFalse(store.markDsSaved(id, saved));

        assertTrue(store.updateArtwork(id, "https://db-nds-shop.fr/api/v1/assets/boxart/Demo.nds.png"));
        long other = store.enqueue("https://download2.erista.me/content", "Other.cia", null, null, DownloadPolicy.HOME, false);
        store.cancel(other);
        DownloadStore.Entry restored = entry(id);
        assertEquals(GamePlatform.GBA, restored.platform);
        assertEquals(rom.sha1, restored.expectedSha1);
        assertEquals(rom.sizeBytes, restored.expectedSize);
        assertEquals(saved, restored.savedGbaDestination);
        assertEquals(saved, restored.savedRomDestination);
        assertFalse(restored.isPrepared());
    }

    @Test public void missingEmptyOrRevokedSavedCopiesAreNotReturned() throws Exception {
        long id = enqueue();
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        Uri saved = savedCopy();
        assertTrue(store.markGbaSaved(id, saved, rom.sizeBytes, rom.sha1));
        File output = documents.files.remove(saved);
        assertNull(store.savedGbaUri(id));
        documents.files.put(saved, output);
        Files.write(output.toPath(), new byte[0]);
        assertNull(store.savedGbaUri(id));
        Files.write(output.toPath(), new byte[]{1});
        shadowOf(context.getContentResolver()).registerInputStreamSupplier(saved, () -> {
            throw new SecurityException("Synthetic revoked grant");
        });
        assertNull(store.savedGbaUri(id));
        assertNull(store.savedRomUri(id));
        assertTrue(documents.deleted.isEmpty());
    }

    @Test public void failedGbaMarkerCommitRestoresPreviousCopyAndIdentity() throws Exception {
        long id = enqueue();
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        Uri previous = savedCopy(), replacement = savedCopy();
        assertTrue(store.markGbaSaved(id, previous, rom.sizeBytes, rom.sha1));
        SharedPreferences real = preferences();
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
        assertFalse(new DownloadStore(failingContext).markGbaSaved(id, replacement, rom.sizeBytes, rom.sha1));
        assertEquals(before, real.getString("records", ""));
        assertEquals(previous, new DownloadStore(context).savedGbaUri(id));
    }

    private long enqueue() {
        return store.enqueueGba(rom.downloadUrl, "Emerald.gba", null, DETAIL, false, "", rom.sha1, rom.sizeBytes);
    }

    private DownloadStore.Entry entry(long id) {
        return new DownloadStore(context).list().stream().filter(entry -> entry.id == id).findFirst().orElseThrow();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE);
    }

    private Uri savedCopy() throws Exception {
        File file = File.createTempFile("saved-gba-", ".gba", context.getCacheDir());
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        Uri uri = Uri.parse("content://dev.thorshelf.test.saved-gba/document/" + file.getName());
        documents.files.put(uri, file);
        return uri;
    }
}
