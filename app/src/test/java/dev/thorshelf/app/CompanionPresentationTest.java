package dev.thorshelf.app;

import android.app.DownloadManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
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
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.shadows.ShadowDownloadManager;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;

import static dev.thorshelf.app.CatalogUiTestSupport.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

/** Real presentation windows with synthetic displays, catalog responses and download progress. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class CompanionPresentationTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private FakeClient client;
    private DownloadManager manager;
    private int displayId;

    @Before public void launch() {
        client = new FakeClient();
        MainActivity.catalogClientFactory = () -> client;
        MainActivity.ndsCatalogClientFactory = () -> new NdsCatalogClient() {
            @Override public CatalogPage fetch(String url, String userAgent) {
                return results(url, "Nintendo DS games");
            }
        };
        controller = Robolectric.buildActivity(MainActivity.class).create().start();
        activity = controller.get();
        displayId = addPresentationDisplay();
        controller.resume().visible();
        manager = activity.getSystemService(DownloadManager.class);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        assertNotNull(companion());
        assertTrue(companion().isShowing());
    }

    @After public void close() {
        ReflectionHelpers.setStaticField(PreparationService.class, "active", false);
        activity.getSharedPreferences("preparation_status", 0).edit().clear().commit();
        client.releaseAll();
        controller.pause().stop().destroy();
        MainActivity.catalogClientFactory = CatalogClient::new;
        MainActivity.ndsCatalogClientFactory = NdsCatalogClient::new;
    }

    @Test public void browseContextFollowsSubmittedQueryPlatformAndFilters() {
        assertText("On main screen: Browse Nintendo 3DS games");
        ReflectionHelpers.setField(activity, "catalogLanguage", "en");
        ReflectionHelpers.setField(activity, "catalogRegion", "europe");
        search("Pokemon White");
        await("3DS companion search context", () -> hasText(
                "On main screen: Nintendo 3DS · Search: Pokemon White · English · Europe"));

        input().setText("Unsubmitted text");
        update();
        assertText("On main screen: Nintendo 3DS · Search: Pokemon White · English · Europe");
        input().setText("Pokemon White");
        button("DS").performClick();
        await("DS companion search context", () -> hasText(
                "On main screen: Nintendo DS · Search: Pokemon White · English · Europe"));
        button("Settings").performClick();
        assertText("On main screen: Settings");
        button("Browse").performClick();
        assertText("On main screen: Nintendo DS · Search: Pokemon White · English · Europe");
    }

    @Test public void oldPreparationAppearsAsHistoryOnlyOnDownloadsWithoutCompletedProgress() {
        activity.getSharedPreferences("preparation_status", 0).edit()
                .putBoolean("ready", true).putString("status", "Saved Previous game.3ds. Refresh Azahar").commit();
        update();
        assertFalse(hasText("Previous game"));
        assertFalse(hasText("Game ready"));
        assertFalse(progress().isShown());

        button("Downloads").performClick();
        assertText("Last preparation complete");
        assertText("Previous game");
        assertFalse(progress().isShown());

        button("Browse").performClick();
        assertFalse(hasText("Previous game"));
        assertFalse(hasText("Last preparation complete"));
        assertText("On main screen: Browse Nintendo 3DS games");
    }

    @Test public void activeDownloadUpdatesWithoutStatusChangeWhileBrowseContextChanges() {
        long id = new DownloadStore(activity).enqueue(DOWNLOAD, "Current game.cia",
                "Test", null, TITLE, true);
        ShadowDownloadManager.ShadowRequest request = shadowOf(shadowOf(manager).getRequest(id));
        request.setStatus(DownloadManager.STATUS_RUNNING);
        request.setTotalSize(1000);
        request.setBytesSoFar(330);
        renderDownloads();
        ProgressBar original = progress();
        assertEquals(33, original.getProgress());
        assertText("33%");
        assertText("1 downloading");

        request.setBytesSoFar(890);
        renderDownloads();
        assertSame(original, progress());
        assertEquals(89, original.getProgress());
        assertText("89%");
        search("Different game");
        await("new browsing context during transfer", () -> hasText(
                "On main screen: Nintendo 3DS · Search: Different game"));
        assertText("Current game.cia\nDownloading");
        assertEquals(89, progress().getProgress());
    }

    @Test public void runningPreparationRemainsVisibleWhenBrowsingAndItsOldResultDoesNot() {
        ReflectionHelpers.setStaticField(PreparationService.class, "active", true);
        activity.getSharedPreferences("preparation_status", 0).edit()
                .putString("status", "Preparing current game").putInt("progress", 42).commit();
        update();
        assertText("Getting things ready");
        assertText("Preparing current game");
        assertEquals(42, progress().getProgress());
        assertText("1 preparing");

        ReflectionHelpers.setStaticField(PreparationService.class, "active", false);
        activity.getSharedPreferences("preparation_status", 0).edit().putBoolean("ready", true).commit();
        update();
        assertFalse(hasText("Preparing current game"));
        assertFalse(progress().isShown());
    }

    @Test public void resumeRecreatesPresentationWithCurrentContext() {
        search("Current search");
        await("search context", () -> hasText("On main screen: Nintendo 3DS · Search: Current search"));
        CompanionPresentation first = companion();
        controller.pause();
        assertFalse(first.isShowing());
        assertNull(companion());

        controller.resume();
        assertNotNull(companion());
        assertNotSame(first, companion());
        assertTrue(companion().isShowing());
        assertText("On main screen: Nintendo 3DS · Search: Current search");
    }

    @Test public void removingAndReplacingSecondaryDisplayUsesCurrentScreen() {
        button("Downloads").performClick();
        CompanionPresentation first = companion();
        ShadowDisplayManager.removeDisplay(displayId);
        assertFalse(first.isShowing());
        assertNull(companion());
        button("Settings").performClick();

        displayId = addPresentationDisplay();
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(companion());
        assertNotSame(first, companion());
        assertEquals(displayId, companion().getDisplay().getDisplayId());
        assertText("On main screen: Settings");
    }

    private CompanionPresentation companion() {
        return ReflectionHelpers.getField(activity, "companion");
    }
    private int addPresentationDisplay() {
        // addDisplay's int argument is a display type, not its presentation flags.
        int type = ReflectionHelpers.getStaticField(Display.class, "TYPE_VIRTUAL");
        int id = ShadowDisplayManager.addDisplay("w620dp-h540dp", type);
        Display display = activity.getSystemService(DisplayManager.class).getDisplay(id);
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, activity.getSystemService(DisplayManager.class)
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).length);
        return id;
    }
    private View companionRoot() { return companion().getWindow().getDecorView(); }
    private boolean hasText(String text) {
        return find(companionRoot(), TextView.class, view -> view.isShown() && text.contentEquals(view.getText())) != null;
    }
    private void assertText(String text) { assertTrue("Missing companion text: " + text, hasText(text)); }
    private ProgressBar progress() {
        return find(companionRoot(), ProgressBar.class, view -> true);
    }
    private Button button(String text) {
        Button result = find(activity.findViewById(android.R.id.content), Button.class,
                view -> text.contentEquals(view.getText()));
        assertNotNull(text, result);
        return result;
    }
    private EditText input() {
        return find(activity.findViewById(android.R.id.content), EditText.class, view -> true);
    }
    private void search(String text) { input().setText(text); button("Search").performClick(); }
    private void update() { ReflectionHelpers.callInstanceMethod(activity, "updateCompanion"); }
    private void renderDownloads() {
        ReflectionHelpers.callInstanceMethod(activity, "renderDownloads", from(List.class, new DownloadStore(activity).list()));
    }
}
