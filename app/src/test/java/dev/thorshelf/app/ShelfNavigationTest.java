package dev.thorshelf.app;

import android.content.res.Configuration;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.function.Predicate;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises shelf-to-catalog navigation without making network requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, qualifiers = "w411dp-h891dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
public class ShelfNavigationTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private View root;

    @Before public void launch() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        bindActivity();
        // The shadow delivers normal page callbacks, with no network access.
        shadowOf(browser()).performSuccessfulPageLoadClientCallbacks();
    }

    @After public void close() {
        if (controller != null) controller.pause().stop().destroy();
    }

    @Test public void coldLaunchShowsIllustratedShelfWhileCatalogIsPreloaded() {
        assertTrue(shelf().isShown());
        assertFalse(browser().isShown());
        assertEquals("https://hshop.erista.me/", shadowOf(browser()).getLastLoadedUrl());
        assertTrue(button("Browse").isSelected());
        assertTrue(button("View game").isShown());
        assertTrue(button("All games").isShown());
    }

    @Test public void categoryButtonsOpenTheirRealCatalogSections() {
        String[] labels = {"All games", "Updates", "DLC", "Virtual Console"};
        String[] paths = {"games", "updates", "dlc", "virtual-console"};
        for (int i = 0; i < labels.length; i++) {
            click(labels[i]);
            assertWebsiteVisible("https://hshop.erista.me/c/" + paths[i]);
            click("Shelf");
            assertTrue(shelf().isShown());
        }
    }

    @Test public void featuredAndGameTilesOpenTitleSearchesInsteadOfInventedListings() {
        click("View game");
        assertWebsiteVisible("https://hshop.erista.me/search/results?q=Pokemon+Omega+Ruby&qt=Text&lgy=false");
        click("Shelf");
        View title = find(View.class, view -> "Professor Layton and the Miracle Mask. Find in the hShop catalog."
                .contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()));
        assertTrue(title.isFocusable());
        assertTrue(title.performClick());
        idle();
        assertWebsiteVisible("https://hshop.erista.me/search/results?q=Professor+Layton+and+the+Miracle+Mask&qt=Text&lgy=false");
    }

    @Test public void shelfButtonKeepsTheExistingBrowserAndItsHistory() {
        click("All games");
        commitCurrentPageHistory();
        WebView existing = browser();
        int historySize = existing.copyBackForwardList().getSize();
        assertTrue(historySize > 0);
        click("Shelf");

        assertTrue(shelf().isShown());
        assertFalse(existing.isShown());
        assertSame(existing, browser());
        assertEquals("https://hshop.erista.me/c/games", existing.getUrl());
        assertEquals(historySize, existing.copyBackForwardList().getSize());
        assertFalse(shadowOf(existing).wasDestroyCalled());
        assertFalse(shadowOf(existing).wasClearHistoryCalled());
        click("Downloads");
        click("Browse");
        assertTrue(shelf().isShown());
        assertSame(existing, browser());
    }

    @Test public void rotationRestoresWebsiteSessionAndSelectedDownloadsTab() {
        click("Updates");
        commitCurrentPageHistory();
        click("Downloads");
        rotateToLandscape();

        assertTrue(button("Downloads").isSelected());
        assertFalse(button("Browse").isSelected());
        assertFalse(shelf().isShown());
        click("Browse");
        assertWebsiteVisible("https://hshop.erista.me/c/updates");
        assertEquals("https://hshop.erista.me/c/updates",
                browser().copyBackForwardList().getCurrentItem().getUrl());
    }

    @Test public void rotationRestoresShelfAndSelectedSetupTab() {
        click("DLC");
        commitCurrentPageHistory();
        click("Shelf");
        click("Setup");
        rotateToLandscape();

        assertTrue(button("Setup").isSelected());
        assertFalse(shelf().isShown());
        click("Browse");
        assertTrue(shelf().isShown());
        assertFalse(browser().isShown());
        assertEquals("https://hshop.erista.me/c/dlc", browser().getUrl());
    }

    @Test public void controllerBackReturnsToShelfBeforeFinishingTheActivity() {
        click("All games");
        browser().clearHistory();
        assertFalse(browser().canGoBack());
        press(KeyEvent.KEYCODE_BUTTON_B);
        assertTrue(shelf().isShown());
        assertFalse(browser().isShown());
        assertFalse(activity.isFinishing());

        press(KeyEvent.KEYCODE_BUTTON_B);
        assertTrue(activity.isFinishing());
    }

    @Test public void controllerSearchReturnsToBrowseAndFocusesSearch() {
        click("Setup");
        press(KeyEvent.KEYCODE_BUTTON_Y);
        assertTrue(button("Browse").isSelected());
        EditText search = find(EditText.class, view -> true);
        assertTrue(search.isShown());
        assertTrue(search.hasFocus());
        search.setText("Mario Kart 7");
        click("Search");
        assertWebsiteVisible("https://hshop.erista.me/search/results?q=Mario+Kart+7&qt=Text&lgy=false");
    }

    private void rotateToLandscape() {
        MainActivity previous = activity;
        Configuration landscape = new Configuration(activity.getResources().getConfiguration());
        landscape.orientation = Configuration.ORIENTATION_LANDSCAPE;
        landscape.screenWidthDp = 960;
        landscape.screenHeightDp = 540;
        controller.configurationChange(landscape).visible();
        bindActivity();
        assertNotSame("Rotation must recreate this activity", previous, activity);
        assertEquals(Configuration.ORIENTATION_LANDSCAPE,
                activity.getResources().getConfiguration().orientation);
    }

    private void bindActivity() {
        activity = controller.get();
        root = activity.findViewById(android.R.id.content);
    }

    private void commitCurrentPageHistory() {
        // ShadowWebView's successful callbacks do not populate its back/forward list.
        shadowOf(browser()).pushEntryToHistory(browser().getUrl());
    }

    private void assertWebsiteVisible(String expectedUrl) {
        assertTrue(button("Browse").isSelected());
        assertFalse(shelf().isShown());
        assertTrue(browser().isShown());
        assertEquals(expectedUrl, browser().getUrl());
    }

    private void click(String label) {
        assertTrue(button(label).performClick());
        idle();
    }

    private void press(int keyCode) {
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode)));
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode)));
        idle();
    }

    private void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private BrowseHomeView shelf() { return find(BrowseHomeView.class, view -> true); }
    private WebView browser() { return find(WebView.class, view -> true); }
    private Button button(String label) { return find(Button.class, view -> label.contentEquals(view.getText())); }

    private <T extends View> T find(Class<T> type, Predicate<T> predicate) {
        T result = findIn(root, type, predicate);
        assertNotNull("Expected view: " + type.getSimpleName(), result);
        return result;
    }

    private static <T extends View> T findIn(View view, Class<T> type, Predicate<T> predicate) {
        if (type.isInstance(view) && predicate.test(type.cast(view))) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T result = findIn(group.getChildAt(i), type, predicate);
                if (result != null) return result;
            }
        }
        return null;
    }
}
