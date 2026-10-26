package dev.thorshelf.app;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;

import static dev.thorshelf.app.CatalogUiTestSupport.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real Android controls and queue integration with deterministic catalog responses. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivitySmokeTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private View root;
    private FakeClient client;
    private DownloadManager manager;

    @Before public void launch() {
        client = new FakeClient();
        MainActivity.catalogClientFactory = () -> client;
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        root = activity.findViewById(android.R.id.content);
        manager = activity.getSystemService(DownloadManager.class);
        // Android database IDs start at 1; Robolectric starts at 0.
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
    }

    @After public void close() {
        client.releaseAll();
        if (controller != null) controller.pause().stop().destroy();
        MainActivity.catalogClientFactory = CatalogClient::new;
    }

    @Test public void coldLaunchIsNativeAndDoesNotFetchUntilTheUserBrowses() {
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(client.fetched.isEmpty());
        assertNull(CatalogUiTestSupport.find(root, WebView.class, view -> true));
        assertTrue(button("Browse").isSelected());
        assertFalse(button("Downloads").isSelected());
        assertFalse(button("Settings").isSelected());
    }

    @Test public void searchEncodesTextAndRendersResultsAsNativeDownloadCards() {
        search("  A & B? # + é  ");
        await("native results", () -> optionalButton("Download") != null);
        assertEquals("https://hshop.erista.me/search/results?q=A+%26+B%3F+%23+%2B+%C3%A9&qt=Text&lgy=false",
                client.fetched.get(0));
        assertTrue(button("Browse").isSelected());
        assertNotNull(CatalogUiTestSupport.find(root, TextView.class, view -> GAME.contentEquals(view.getText()) && view.isShown()));
        assertNull(CatalogUiTestSupport.find(root, WebView.class, view -> true));
    }

    @Test public void blankSearchKeepsTheShelfAndFocusesTheInput() {
        search("   ");
        assertTrue(client.fetched.isEmpty());
        assertTrue(find(BrowseHomeView.class, view -> true).isShown());
        assertTrue(find(EditText.class, view -> true).hasFocus());
    }

    @Test public void shoulderButtonsNavigateAndWrapNativeTabs() {
        press(KeyEvent.KEYCODE_BUTTON_R1);
        assertTrue(button("Downloads").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_R1);
        assertTrue(button("Settings").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_R1);
        assertTrue(button("Browse").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_L1);
        assertTrue(button("Settings").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_L1);
        assertTrue(button("Downloads").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_Y);
        assertTrue(button("Browse").isSelected());
        assertTrue(find(EditText.class, view -> true).hasFocus());
    }

    @Test public void downloadResolvesAFreshLinkInsteadOfReusingCachedDetails() {
        client.pages.put(TITLE, detail("https://download4.erista.me/content/2476?token=old-fixture"));
        openResults();
        Button details = find(Button.class, view -> view.getContentDescription() != null
                && ("View details for " + GAME).contentEquals(view.getContentDescription()));
        details.performClick();
        await("native title details", () -> client.fetchCount(TITLE) == 1 && optionalButton("Download") != null
                && CatalogUiTestSupport.find(root, TextView.class, view -> "Title ID: 000400000011C400".contentEquals(view.getText())) != null);
        client.pages.put(TITLE, detail(DOWNLOAD));
        button("Download").performClick();
        await("queued download", () -> queue().size() == 1);
        assertEquals(2, client.fetchCount(TITLE));
        assertEquals(1, client.inspected.size());
        assertEquals(DOWNLOAD, client.inspected.get(0));
        assertEquals(TITLE, client.inspectedReferer);
        assertNotNull(client.inspectedAgent);
        DownloadStore.Entry entry = queue().get(0);
        assertEquals(GAME + ".cia", entry.name);
        assertEquals(TITLE, entry.sourcePage);
        assertEquals(GAME + ".cia", shadowOf(shadowOf(manager).getRequest(entry.id)).getDestination().getLastPathSegment());
        assertEquals(DownloadManager.Request.NETWORK_WIFI, shadowOf(shadowOf(manager).getRequest(entry.id)).getAllowedNetworkTypes());
        assertTrue(button("Browse").isSelected());
        assertNull(CatalogUiTestSupport.find(root, WebView.class, view -> true));
        button("Queued ✓").performClick();
        assertTrue(button("Downloads").isSelected());
        assertEquals(1, queue().size());
        assertEquals(2, client.fetchCount(TITLE));
    }

    @Test public void repeatedClicksWhileResolvingAndInspectingProduceOneQueueEntry() {
        Gate fetchGate = new Gate(false);
        Gate headGate = new Gate(false);
        client.gates.put(TITLE, fetchGate);
        client.inspectionGate = headGate;
        client.pages.put(TITLE, detail(DOWNLOAD));
        openResults();
        Button download = button("Download");
        download.performClick();
        await("title request blocked", () -> fetchGate.entered.getCount() == 0);
        assertFalse(download.isEnabled());
        download.performClick();
        assertEquals(1, client.fetchCount(TITLE));
        fetchGate.release.countDown();
        await("HEAD request blocked", () -> headGate.entered.getCount() == 0);
        download.performClick();
        assertEquals(1, client.inspected.size());
        assertTrue(queue().isEmpty());
        headGate.release.countDown();
        await("single completed enqueue", () -> queue().size() == 1);
        assertEquals(1, client.fetchCount(TITLE));
        assertEquals(1, client.inspected.size());
    }

    @Test public void activeQueueRecordSurvivesRecreationAndPreventsAnotherDownloadRequest() {
        // A real detail response has a usable transfer link. Artwork lookup must never use it.
        client.pages.put(TITLE, detail(DOWNLOAD));
        long existingId = new DownloadStore(activity).enqueue(DOWNLOAD, GAME + ".cia",
                "ThorShelf test", null, TITLE, true);
        shadowOf(shadowOf(manager).getRequest(existingId)).setStatus(DownloadManager.STATUS_PENDING);
        assertTrue(new DownloadStore(activity).list().get(0).isActive());
        openResults();

        MainActivity previous = activity;
        Configuration landscape = new Configuration(activity.getResources().getConfiguration());
        landscape.orientation = Configuration.ORIENTATION_LANDSCAPE;
        landscape.screenWidthDp = 960;
        landscape.screenHeightDp = 540;
        controller.configurationChange(landscape).visible();
        activity = controller.get();
        root = activity.findViewById(android.R.id.content);
        manager = activity.getSystemService(DownloadManager.class);
        assertNotSame(previous, activity);
        await("recreated catalog card", () -> optionalButton("Download") != null);
        assertTrue(new DownloadStore(activity).list().get(0).isActive());
        int previousFetches = client.fetched.size();

        button("Download").performClick();
        await("existing download shown", () -> button("Downloads").isSelected());
        await("missing queue artwork checked", () -> client.fetchCount(TITLE) == 1);
        assertEquals(previousFetches + 1, client.fetched.size());
        assertEquals(1, client.fetchCount(TITLE));
        assertTrue(client.inspected.isEmpty());
        assertEquals(1, queue().size());
        assertEquals(existingId, queue().get(0).id);
        assertTrue(queue().get(0).isActive());
    }

    @Test public void downloadKeepsItsOwnSourcePageWhileTheUserSearchesElsewhere() {
        Gate headGate = new Gate(false);
        client.inspectionGate = headGate;
        client.pages.put(TITLE, detail(DOWNLOAD));
        openResults();
        button("Download").performClick();
        await("HEAD request blocked", () -> headGate.entered.getCount() == 0);
        String other = DownloadPolicy.searchUrl("Different search");
        client.pages.put(other, results(other, "Different search results"));
        search("Different search");
        await("other search visible", () -> visibleText("Different search results"));
        headGate.release.countDown();
        await("original download queued", () -> queue().size() == 1);
        assertEquals(TITLE, queue().get(0).sourcePage);
        assertEquals(TITLE, client.inspectedReferer);
        assertTrue(visibleText("Different search results"));
    }

    @Test public void failedLinkResolutionDoesNotEnqueueAndTheCardCanRetry() {
        client.failures.put(TITLE, new IOException("Fixture connection failure"));
        openResults();
        button("Download").performClick();
        await("download failure", () -> ShadowToast.getTextOfLatestToast() != null
                && ShadowToast.getTextOfLatestToast().contains("fresh download link"));
        assertTrue(queue().isEmpty());
        assertTrue(client.inspected.isEmpty());
        assertTrue(button("Download").isEnabled());
        client.failures.remove(TITLE);
        client.pages.put(TITLE, detail(DOWNLOAD));
        button("Download").performClick();
        await("successful retry", () -> queue().size() == 1);
        assertEquals(2, client.fetchCount(TITLE));
    }

    @Test public void failedDownloadHeaderCheckNeverEnqueuesTheResponse() {
        client.pages.put(TITLE, detail(DOWNLOAD));
        client.inspectionFailure = new IOException("The download response is not a supported content file.");
        openResults();
        button("Download").performClick();
        await("header failure", () -> ShadowToast.getTextOfLatestToast() != null
                && ShadowToast.getTextOfLatestToast().contains("download link could not be opened"));
        assertEquals(1, client.inspected.size());
        assertTrue(queue().isEmpty());
        assertTrue(button("Download").isEnabled());
    }

    @Test public void catalogFailureShowsANativeRetryWithoutOpeningAWebsite() {
        String url = "https://hshop.erista.me/c/games";
        client.failures.put(url, new IOException("Fixture unavailable"));
        button("All games").performClick();
        await("native error", () -> optionalButton("Try again") != null);
        assertTrue(visibleText("Couldn’t open the catalog"));
        assertNull(CatalogUiTestSupport.find(root, WebView.class, view -> true));
        assertTrue(queue().isEmpty());
        client.failures.remove(url);
        button("Try again").performClick();
        await("retry results", () -> optionalButton("Download") != null);
        assertEquals(2, client.fetchCount(url));
    }

    @Test public void anOlderResponseCannotReplaceANewerSearch() {
        String oldUrl = DownloadPolicy.searchUrl("Old search");
        String newUrl = DownloadPolicy.searchUrl("New search");
        Gate gate = new Gate(true); // Models a connection that finishes despite cancellation.
        client.gates.put(oldUrl, gate);
        client.pages.put(oldUrl, results(oldUrl, "Old results"));
        client.pages.put(newUrl, results(newUrl, "New results"));
        search("Old search");
        await("old request started", () -> gate.entered.getCount() == 0);
        search("New search");
        await("new results visible", () -> visibleText("New results"));
        gate.release.countDown();
        await("old worker finished posting its response", () -> gate.finished.getCount() == 0
                && gate.thread != null && gate.thread.getState() == Thread.State.WAITING);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(visibleText("New results"));
        assertFalse(visibleText("Old results"));
    }

    @Test public void verificationCompletionContinuesThroughNativeHeaderCheckAndEnqueuesOnce() throws Exception {
        client.failures.put(TITLE, new CatalogClient.VerificationRequiredException("Fixture check required"));
        openResults();
        button("Download").performClick();
        await("verification dialog", () -> ReflectionHelpers.getField(activity, "verification") != null);
        CatalogVerification verification = ReflectionHelpers.getField(activity, "verification");
        CatalogVerification.Listener listener = ReflectionHelpers.getField(verification, "listener");
        AlertDialog dialog = ReflectionHelpers.getField(verification, "dialog");
        WebView browser = ReflectionHelpers.getField(verification, "webView");
        assertTrue(dialog.isShowing());
        assertEquals(TITLE, shadowOf(browser).getLastLoadedUrl());
        assertTrue(queue().isEmpty());
        assertTrue(client.inspected.isEmpty());

        String completed = titleFixture();
        listener.onHtml(completed);
        listener.onHtml(completed); // Overlapping poll callbacks must not resolve twice.
        await("verified download enqueued", () -> queue().size() == 1);
        assertEquals(1, client.inspected.size());
        assertEquals(DOWNLOAD, client.inspected.get(0));
        assertEquals(TITLE, queue().get(0).sourcePage);
        assertNull(ReflectionHelpers.getField(activity, "verification"));
        assertFalse(dialog.isShowing());
        assertTrue(shadowOf(browser).wasDestroyCalled());
        assertNull(browser.getParent());
        assertTrue(button("Browse").isSelected());
        assertNotNull(optionalButton("Queued ✓"));

        listener.onHtml(completed);
        await("late completion ignored", () -> !ReflectionHelpers.<Boolean>getField(activity, "parsingVerification"));
        assertEquals(1, client.inspected.size());
        assertEquals(1, queue().size());
    }

    @Test public void verificationCancellationRestoresDownloadAndRejectsLateCompletedHtml() throws Exception {
        client.failures.put(TITLE, new CatalogClient.VerificationRequiredException("Fixture check required"));
        openResults();
        button("Download").performClick();
        await("verification dialog", () -> ReflectionHelpers.getField(activity, "verification") != null);
        CatalogVerification verification = ReflectionHelpers.getField(activity, "verification");
        CatalogVerification.Listener listener = ReflectionHelpers.getField(verification, "listener");
        AlertDialog dialog = ReflectionHelpers.getField(verification, "dialog");
        WebView browser = ReflectionHelpers.getField(verification, "webView");

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(ReflectionHelpers.getField(activity, "verification"));
        assertEquals("", ReflectionHelpers.getField(activity, "pendingDownload"));
        assertTrue(button("Download").isEnabled());
        assertFalse(dialog.isShowing());
        assertTrue(shadowOf(browser).wasDestroyCalled());
        assertTrue(queue().isEmpty());

        listener.onHtml(titleFixture());
        await("late verification callback ignored", () -> !ReflectionHelpers.<Boolean>getField(activity, "parsingVerification"));
        assertTrue(client.inspected.isEmpty());
        assertTrue(queue().isEmpty());
        assertTrue(button("Download").isEnabled());
    }

    private void openResults() {
        search(GAME);
        await("download card", () -> optionalButton("Download") != null);
    }
    private void search(String value) {
        find(EditText.class, view -> true).setText(value);
        button("Search").performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }
    private List<DownloadStore.Entry> queue() { return new DownloadStore(activity).list(); }
    private String titleFixture() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/catalog/title.html")) {
            if (input == null) throw new IOException("Missing catalog title fixture");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private boolean visibleText(String text) {
        return CatalogUiTestSupport.find(root, TextView.class, view -> text.contentEquals(view.getText()) && view.isShown()) != null;
    }
    private void press(int keyCode) {
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode)));
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode)));
        shadowOf(Looper.getMainLooper()).idle();
    }
    private Button button(String label) { return find(Button.class, view -> label.contentEquals(view.getText())); }
    private Button optionalButton(String label) { return CatalogUiTestSupport.find(root, Button.class, view -> label.contentEquals(view.getText())); }
    private <T extends View> T find(Class<T> type, Predicate<T> predicate) {
        T result = CatalogUiTestSupport.find(root, type, predicate);
        assertNotNull("Expected view: " + type.getSimpleName(), result);
        return result;
    }
}
