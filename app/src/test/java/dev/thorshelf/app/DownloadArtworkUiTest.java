package dev.thorshelf.app;

import android.app.DownloadManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static dev.thorshelf.app.CatalogUiTestSupport.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Existing transfers receive optional metadata without blocking or rebuilding queue controls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class DownloadArtworkUiTest {
    private static final String SOURCE = "https://hshop.erista.me/t/42";
    private static final String ART = "https://raw.githubusercontent.com/owner/game/main/cover.png";
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private DownloadStore store;
    private DownloadManager manager;
    private FakeClient client;

    @Before public void launch() {
        client = new FakeClient();
        MainActivity.catalogClientFactory = () -> client;
        GameArtwork.clearRemoteCacheForTests();
        GameArtwork.clientFactory = () -> new ArtworkClient() {
            @Override public Bitmap fetch(String url, int targetPixels) {
                return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            }
        };
        controller = Robolectric.buildActivity(MainActivity.class).create().start().visible();
        activity = controller.get();
        store = new DownloadStore(activity);
        manager = activity.getSystemService(DownloadManager.class);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        // Explicit snapshots keep unrelated periodic DownloadManager refreshes out of these tests.
        ReflectionHelpers.setField(activity, "queryingDownloads", true);
    }

    @After public void close() {
        client.releaseAll();
        if (controller != null) controller.stop().destroy();
        MainActivity.catalogClientFactory = CatalogClient::new;
        GameArtwork.clientFactory = ArtworkClient::new;
        GameArtwork.clearRemoteCacheForTests();
    }

    @Test public void existingDownloadsShareOneMetadataRequestAndKeepFocusedActionsWhenArtworkArrives() throws Exception {
        long first = add("Game.cia", SOURCE);
        long second = add("Game again.cia", SOURCE);
        client.pages.put(SOURCE, artworkPage(SOURCE, SOURCE));
        Gate gate = new Gate(false);
        client.gates.put(SOURCE, gate);
        render();
        assertTrue("Browse should not start queue artwork lookups", client.fetched.isEmpty());
        showDownloads();
        render();
        await("metadata lookup", () -> gate.entered.getCount() == 0);
        ViewGroup originalCard = card(first, "root");
        FrameLayout artwork = card(first, "artwork");
        View fallback = artwork.getChildAt(0);
        Button prepare = find(originalCard, Button.class,
                view -> "Prepare for Azahar".contentEquals(view.getText()));
        assertNotNull(prepare);
        prepare.setFocusableInTouchMode(true);
        assertTrue(prepare.requestFocus());
        render();
        ExecutorService downloadQueries = ReflectionHelpers.getField(activity, "downloadQueries");
        assertEquals("available", downloadQueries.submit(() -> "available").get(1, TimeUnit.SECONDS));
        assertEquals(1, client.fetchCount(SOURCE));

        gate.release.countDown();
        await("saved and displayed artwork", () -> ART.equals(this.<String>card(first, "artworkUrl"))
                && ART.equals(this.<String>card(second, "artworkUrl")));

        for (DownloadStore.Entry entry : store.list()) assertEquals(ART, entry.artworkUrl);
        assertSame(originalCard, card(first, "root"));
        assertSame(prepare, find(originalCard, Button.class,
                view -> "Prepare for Azahar".contentEquals(view.getText())));
        assertNotSame(fallback, artwork.getChildAt(0));
        assertTrue("Replacing decoration must keep controller focus", prepare.hasFocus());
        render();
        drainMetadata();
        assertEquals(1, client.fetchCount(SOURCE));
    }

    @Test public void failedOrMismatchedMetadataIsNotRetriedAndDsTransfersAreNotQueried() throws Exception {
        String mismatch = "https://hshop.erista.me/t/43";
        add("Unavailable.cia", SOURCE);
        add("Wrong match.cia", mismatch);
        add("Legacy without details.cia", DownloadPolicy.HOME);
        store.enqueueNds("https://github.com/owner/game/releases/download/v1/Game.nds", "Game.nds",
                null, "nds://catalog/title/0123456789abcdef01234567", false);
        client.failures.put(SOURCE, new IOException("Synthetic metadata failure"));
        client.pages.put(mismatch, artworkPage(mismatch, "https://hshop.erista.me/t/999"));
        showDownloads();
        render();
        drainMetadata();
        assertEquals(2, client.fetched.size());
        for (int i = 0; i < 3; i++) render();
        drainMetadata();

        assertEquals(1, client.fetchCount(SOURCE));
        assertEquals(1, client.fetchCount(mismatch));
        assertEquals(2, client.fetched.size());
        for (DownloadStore.Entry entry : store.list()) assertEquals("", entry.artworkUrl);
    }

    private long add(String filename, String source) {
        long id = store.enqueue("https://download1.erista.me/synthetic", filename, null, null, source, false);
        shadowOf(shadowOf(manager).getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        return id;
    }

    private CatalogPage artworkPage(String source, String entryUrl) {
        return new CatalogPage(source, "Game", "", Collections.singletonList(new CatalogPage.Entry(
                entryUrl, "Game", "games", "world", "1 MiB", "Legit", "000400000011C400", ART)),
                Collections.emptyList(), "", "", "", false);
    }

    private void showDownloads() {
        ReflectionHelpers.callInstanceMethod(activity, "showTab", ReflectionHelpers.ClassParameter.from(int.class, 1));
    }

    private void render() {
        ReflectionHelpers.callInstanceMethod(activity, "renderDownloads",
                ReflectionHelpers.ClassParameter.from(List.class, store.list()));
    }

    private void drainMetadata() throws Exception {
        ExecutorService metadata = ReflectionHelpers.getField(activity, "artworkMetadataQueries");
        metadata.submit(() -> { }).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private <T> T card(long id, String field) {
        Map<Long, ?> cards = ReflectionHelpers.getField(activity, "downloadCards");
        Object card = cards.get(id);
        assertNotNull(card);
        return ReflectionHelpers.getField(card, field);
    }
}
