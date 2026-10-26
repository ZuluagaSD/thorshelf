package dev.thorshelf.app;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.util.Pair;

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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class NdsDownloadStoreTest {
    private static final String RELEASE = "https://github.com/owner/game/releases/download/v1/Game.nds";
    private static final String TITLE = "nds://catalog/title/0123456789abcdef01234567";
    private Context context;
    private DownloadManager manager;
    private ShadowDownloadManager queue;
    private DownloadStore store;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(DownloadManager.class);
        queue = shadowOf(manager);
        // Android's real queue starts at 1; reserve Robolectric's artificial zero row.
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        store = new DownloadStore(context);
    }

    @Test public void ndsQueuePreservesSourceAndNeverStoresSignedLinkOrBrowserHeaders() {
        String url = "https://release-assets.githubusercontent.com/github-production-release-asset/123/abc?token=temporary-secret";
        long id = store.enqueueNds(url, "Game.nds", "ThorShelf test", TITLE, true);
        ShadowRequest request = shadowOf(queue.getRequest(id));
        request.setStatus(DownloadManager.STATUS_PENDING);
        assertEquals(DownloadManager.Request.NETWORK_WIFI, request.getAllowedNetworkTypes());
        assertEquals("ThorShelf · Nintendo DS", request.getDescription());
        assertEquals("Game.nds", request.getTitle());
        assertTrue(request.getDestination().getPath().endsWith("/Download/ThorShelf/Game.nds"));
        assertEquals(1, request.getRequestHeaders().size());
        assertEquals(new Pair<>("User-Agent", "ThorShelf test"), request.getRequestHeaders().get(0));

        List<DownloadStore.Entry> restored = new DownloadStore(context).list();
        assertEquals(1, restored.size());
        assertEquals(id, restored.get(0).id);
        assertEquals(TITLE, restored.get(0).sourcePage);
        assertEquals("Game.nds", restored.get(0).name);
        assertTrue(restored.get(0).isActive());
        String saved = context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE)
                .getString("records", "");
        assertFalse(saved.contains("temporary-secret"));
        assertFalse(saved.contains("release-assets.githubusercontent.com"));
    }

    @Test public void ndsShopQueueRetainsArtworkAndEncodedUrlWithoutBrowserHeaders() {
        String encoded = "Pok%C3%A9mon%20(France)%20%5Bb%5D.nds";
        String url = "https://db-nds-shop.fr/api/v1/download/" + encoded;
        String artwork = "https://db-nds-shop.fr/api/v1/assets/boxart/" + encoded + ".png";
        long id = store.enqueueNds(url, "Pokémon (France) [b].nds", "ThorShelf test", TITLE, true, artwork);
        ShadowRequest request = shadowOf(queue.getRequest(id));
        assertEquals(url, request.getUri().toString());
        assertEquals("Pokémon (France) [b].nds", request.getTitle());
        assertEquals(DownloadManager.Request.NETWORK_WIFI, request.getAllowedNetworkTypes());
        assertEquals(1, request.getRequestHeaders().size());
        assertEquals(new Pair<>("User-Agent", "ThorShelf test"), request.getRequestHeaders().get(0));
        DownloadStore.Entry restored = new DownloadStore(context).list().get(0);
        assertEquals(TITLE, restored.sourcePage);
        assertEquals(artwork, restored.artworkUrl);
        assertEquals("Pokémon (France) [b].nds", restored.name);
        assertThrows(IllegalArgumentException.class,
                () -> store.enqueueNds(url.replace(".nds", ".cia"), "Launcher.nds", null, TITLE, false));
        assertThrows(IllegalArgumentException.class,
                () -> store.enqueue(url, "Game.nds", null, "cookie=secret", TITLE, false));
        assertEquals(1, store.list().size());
    }

    @Test public void archiveMirrorZipQueuesWithoutHshopHeadersAndCancelsWithoutASecondTransfer() {
        String url = "https://ia800603.us.archive.org/2/items/pack-roms-nintendo-ds-eu-usa-jap-rabbits-games/"
                + "Pokemon%20-%20Black%20Version%20(USA%2C%20Europe)%20(NDSi%20Enhanced).zip";
        long id = store.enqueueNds(url, "Pokemon - Black Version (USA, Europe).zip", "ThorShelf test", TITLE, true);
        ShadowRequest request = shadowOf(queue.getRequest(id));
        request.setStatus(DownloadManager.STATUS_PENDING);
        assertEquals(url, request.getUri().toString());
        assertTrue(request.getDestination().getPath().endsWith(".zip"));
        assertEquals(1, request.getRequestHeaders().size());
        assertEquals(new Pair<>("User-Agent", "ThorShelf test"), request.getRequestHeaders().get(0));
        DownloadStore.Entry restored = new DownloadStore(context).list().get(0);
        assertEquals(TITLE, restored.sourcePage);
        assertEquals("Pokemon - Black Version (USA, Europe).zip", restored.name);
        assertTrue(restored.isActive());
        assertThrows(IllegalArgumentException.class,
                () -> store.enqueue(url, "Game.zip", null, "session=secret", TITLE, true));

        store.cancel(id);

        assertNull(queue.getRequest(id));
        assertTrue(new DownloadStore(context).list().isEmpty());
    }

    @Test public void dsSourceFallbackStaysInDsCatalogAndNeverLeaksIntoReferer() {
        for (String source : new String[]{null, "https://hshop.erista.me/t/42?session=secret",
                "https://untrusted.example/game", TITLE + "?token=secret", TITLE + "#fragment"}) {
            long id = store.enqueueNds(RELEASE, "Game.zip", null, source, false);
            assertTrue(shadowOf(queue.getRequest(id)).getRequestHeaders().isEmpty());
            DownloadStore.Entry entry = new DownloadStore(context).list().get(0);
            assertEquals(NdsCatalog.HOME, entry.sourcePage);
            store.cancel(id);
        }
    }

    @Test public void dsQueueRejectsOtherHostsAndTypesWithoutCreatingRecords() {
        for (String name : new String[]{null, "Game.cia", "Game.3ds", "Game.7z", "Game.nds.exe", ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.enqueueNds(RELEASE, name, null, TITLE, false));
        }
        assertThrows(IllegalArgumentException.class, () -> store.enqueueNds(
                "https://hshop.erista.me/Game.nds", "Game.nds", null, TITLE, false));
        assertThrows(IllegalArgumentException.class, () -> store.enqueueNds(
                "https://evil.example/Game.nds", "Game.nds", null, TITLE, false));
        assertThrows(IllegalArgumentException.class, () -> store.enqueueNds(
                RELEASE, "Game.nds", "agent\nCookie: injected", TITLE, false));
        assertTrue(store.list().isEmpty());
        assertEquals(1, queue.getRequestCount());
    }

    @Test public void hshopQueueStillUsesOnlyItsOwnSourcesAndSessionHeaders() {
        assertThrows(IllegalArgumentException.class,
                () -> store.enqueue(RELEASE, "Game.nds", null, "cookie=secret", TITLE, false));
        long id = store.enqueue("https://download2.erista.me/content", "Game.cia",
                "agent", "cookie=secret", "https://hshop.erista.me/t/42?token=private", false);
        List<Pair<String, String>> headers = shadowOf(queue.getRequest(id)).getRequestHeaders();
        assertTrue(headers.contains(new Pair<>("Cookie", "cookie=secret")));
        assertTrue(headers.contains(new Pair<>("Referer", "https://hshop.erista.me/t/42")));
        assertEquals("https://hshop.erista.me/t/42", new DownloadStore(context).list().get(0).sourcePage);

        long other = store.enqueue("https://download2.erista.me/content", "Other.cia",
                null, null, TITLE, false);
        assertTrue(shadowOf(queue.getRequest(other)).getRequestHeaders()
                .contains(new Pair<>("Referer", DownloadPolicy.HOME)));
    }
}
