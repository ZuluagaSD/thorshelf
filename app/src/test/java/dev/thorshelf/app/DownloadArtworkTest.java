package dev.thorshelf.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
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
import org.robolectric.shadows.ShadowDownloadManager;
import org.robolectric.shadows.ShadowDownloadManager.ShadowRequest;

import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Queue metadata tests use synthetic DownloadManager requests without network transfers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class DownloadArtworkTest {
    private static final String RELEASE = "https://github.com/owner/game/releases/download/v1/Game.nds";
    private static final String TITLE = "nds://catalog/title/0123456789abcdef01234567";
    private static final String ARTWORK = "https://db.universal-team.net/assets/images/icons/colors.png";
    private Context context;
    private DownloadManager manager;
    private ShadowDownloadManager queue;
    private DownloadStore store;
    private SharedPreferences preferences;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(DownloadManager.class);
        queue = shadowOf(manager);
        // Android starts at ID 1; reserve Robolectric's artificial zero row.
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        store = new DownloadStore(context);
        preferences = context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE);
    }

    @Test public void queuedArtworkSurvivesRecreationWithoutChangingGameRequest() throws Exception {
        long id = store.enqueueNds(RELEASE, "Game.nds", "ThorShelf test", TITLE, true, ARTWORK);
        ShadowRequest request = shadowOf(queue.getRequest(id));
        request.setStatus(DownloadManager.STATUS_PENDING);

        DownloadStore.Entry restored = new DownloadStore(context).list().get(0);
        assertEquals(id, restored.id);
        assertEquals(ARTWORK, restored.artworkUrl);
        assertEquals(TITLE, restored.sourcePage);
        assertEquals("Game.nds", restored.name);
        assertEquals(DownloadManager.Request.NETWORK_WIFI, request.getAllowedNetworkTypes());
        assertEquals(1, request.getRequestHeaders().size());
        assertEquals(new Pair<>("User-Agent", "ThorShelf test"), request.getRequestHeaders().get(0));
        JSONObject saved = new JSONArray(preferences.getString("records", "[]")).getJSONObject(0);
        assertEquals(ARTWORK, saved.getString("artworkUrl"));
        assertFalse(saved.toString().contains(RELEASE));
    }

    @Test public void savedArtworkIsRestoredAlongsideLegacyRecordsWithoutArtwork() throws Exception {
        JSONObject withArtwork = savedRecord(41).put("artworkUrl", ARTWORK);
        JSONObject legacy = savedRecord(42);
        save(new JSONArray().put(withArtwork).put(legacy));

        List<DownloadStore.Entry> entries = new DownloadStore(context).list();
        assertEquals(2, entries.size());
        assertEquals(42, entries.get(0).id);
        assertEquals("", entries.get(0).artworkUrl);
        assertEquals(41, entries.get(1).id);
        assertEquals(ARTWORK, entries.get(1).artworkUrl);
    }

    @Test public void olderEnqueueMethodsKeepArtworkOptional() {
        store.enqueueNds(RELEASE, "Game.nds", null, TITLE, false);
        store.enqueue("https://download2.erista.me/content", "Game.cia", null, null,
                "https://hshop.erista.me/t/42", false);

        for (DownloadStore.Entry entry : new DownloadStore(context).list()) {
            assertEquals("", entry.artworkUrl);
        }
        assertFalse(preferences.getString("records", "").contains("artworkUrl"));
    }

    @Test public void threeDsArtworkDoesNotChangeTransferHeadersOrPreparedCopy() throws Exception {
        String source = "https://hshop.erista.me/t/42";
        long id = store.enqueue("https://download2.erista.me/content", "Game.cia", "ThorShelf test",
                "session=synthetic", source, true, ARTWORK);
        ShadowRequest request = shadowOf(queue.getRequest(id));
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL);
        Uri prepared = Uri.parse("content://dev.thorshelf.test.artwork/document/Game.3ds");
        assertTrue(store.markPrepared(id, prepared));

        DownloadStore.Entry restored = new DownloadStore(context).list().get(0);
        assertEquals(id, restored.id);
        assertEquals(source, restored.sourcePage);
        assertEquals("Game.cia", restored.name);
        assertEquals(ARTWORK, restored.artworkUrl);
        assertTrue(restored.isPrepared());
        assertEquals(DownloadManager.Request.NETWORK_WIFI, request.getAllowedNetworkTypes());
        assertEquals(3, request.getRequestHeaders().size());
        assertTrue(request.getRequestHeaders().contains(new Pair<>("User-Agent", "ThorShelf test")));
        assertTrue(request.getRequestHeaders().contains(new Pair<>("Cookie", "session=synthetic")));
        assertTrue(request.getRequestHeaders().contains(new Pair<>("Referer", source)));
        assertFalse(preferences.getString("records", "").contains("session=synthetic"));
    }

    @Test public void backfillPreservesPreparedMetadataAndCannotOverwriteNewerArtworkOrDeletedRecords() throws Exception {
        String source = "https://hshop.erista.me/t/42";
        long id = store.enqueue("https://download2.erista.me/content", "Game.cia", null, null, source, false);
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        Uri prepared = Uri.parse("content://dev.thorshelf.test.artwork/document/Game.3ds");
        assertTrue(store.markPrepared(id, prepared));
        assertFalse(store.updateArtwork(id, "https://evil.example/cover.png"));
        assertEquals("", store.list().get(0).artworkUrl);

        assertTrue(store.updateArtwork(id, ARTWORK));
        assertFalse(store.updateArtwork(id, "https://raw.githubusercontent.com/owner/game/main/different.png"));
        DownloadStore.Entry restored = new DownloadStore(context).list().get(0);
        assertEquals(id, restored.id);
        assertEquals("Game.cia", restored.name);
        assertEquals(source, restored.sourcePage);
        assertEquals(ARTWORK, restored.artworkUrl);
        JSONObject saved = new JSONArray(preferences.getString("records", "[]")).getJSONObject(0);
        assertEquals(prepared.toString(), saved.getString("preparedDestination"));
        assertTrue(restored.isPrepared());
        assertNotNull(queue.getRequest(id));

        store.cancel(id);
        assertFalse(store.updateArtwork(id, ARTWORK));
        assertTrue(new DownloadStore(context).list().isEmpty());
    }

    @Test public void hostileArtworkIsDiscardedOnEnqueueAndRestore() throws Exception {
        for (String artwork : new String[]{null, "https://evil.example/cover.png",
                "https://db.universal-team.net.evil.example/assets/images/icons/colors.png",
                "file:///private/cover.png", "content://private/cover.png",
                "https://db.universal-team.net/assets/files/Game.zip"}) {
            long id = store.enqueueNds(RELEASE, "Game.nds", null, TITLE, false, artwork);
            assertEquals("", new DownloadStore(context).list().get(0).artworkUrl);
            assertTrue(shadowOf(queue.getRequest(id)).getRequestHeaders().isEmpty());
            assertFalse(preferences.getString("records", "").contains("artworkUrl"));
            store.cancel(id);

            save(new JSONArray().put(savedRecord(1000).put("artworkUrl", artwork)));
            assertEquals("", new DownloadStore(context).list().get(0).artworkUrl);
            save(new JSONArray());
        }
    }

    @Test public void markingPreparedPreservesArtworkAfterAnotherQueueWrite() throws Exception {
        long id = store.enqueueNds(RELEASE, "Game.nds", null, TITLE, false, ARTWORK);
        shadowOf(queue.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        Uri prepared = Uri.parse("content://dev.thorshelf.test.artwork/document/Game.nds");

        assertTrue(store.markPrepared(id, prepared));
        long other = store.enqueueNds(RELEASE, "Other.nds", null, TITLE, false);
        store.cancel(other);

        DownloadStore.Entry restored = new DownloadStore(context).list().get(0);
        assertEquals(id, restored.id);
        assertTrue(restored.isPrepared());
        assertEquals(ARTWORK, restored.artworkUrl);
        JSONObject saved = new JSONArray(preferences.getString("records", "[]")).getJSONObject(0);
        assertEquals(ARTWORK, saved.getString("artworkUrl"));
        assertEquals(prepared.toString(), saved.getString("preparedDestination"));
        assertNotNull(queue.getRequest(id));
    }

    private static JSONObject savedRecord(long id) throws Exception {
        return new JSONObject().put("id", id).put("name", "Game.nds").put("sourcePage", TITLE);
    }

    private void save(JSONArray records) {
        assertTrue(preferences.edit().putString("records", records.toString()).commit());
    }
}
