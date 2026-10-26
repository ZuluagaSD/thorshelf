package dev.thorshelf.app;

import android.content.res.Configuration;
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

import java.util.function.Predicate;

import static dev.thorshelf.app.CatalogUiTestSupport.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Native shelf and catalog navigation without creating a browser or contacting hShop. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, qualifiers = "w411dp-h891dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
public class ShelfNavigationTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private View root;
    private FakeClient client;

    @Before public void launch() {
        client = new FakeClient();
        MainActivity.catalogClientFactory = () -> client;
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        bindActivity();
    }

    @After public void close() {
        client.releaseAll();
        if (controller != null) controller.pause().stop().destroy();
        MainActivity.catalogClientFactory = CatalogClient::new;
    }

    @Test public void coldLaunchShowsIllustratedShelfWithoutLoadingAWebsite() {
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(shelf().isShown());
        assertFalse(resultsView().isShown());
        assertNull(CatalogUiTestSupport.find(root, WebView.class, view -> true));
        assertTrue(client.fetched.isEmpty());
        assertTrue(button("Browse").isSelected());
        assertTrue(find(TextView.class, view -> "Mario Kart 7".contentEquals(view.getText())).isShown());
        assertTrue(button("All games").isShown());
    }

    @Test public void categoryButtonsFetchTheirRealCatalogSectionsAsNativeCards() {
        String[] labels = {"All games", "Updates", "DLC", "Virtual Console"};
        String[] paths = {"games", "updates", "dlc", "virtual-console"};
        for (int i = 0; i < labels.length; i++) {
            click(labels[i]);
            assertNativeCatalog("https://hshop.erista.me/c/" + paths[i]);
            click("Browse");
            assertTrue(shelf().isShown());
        }
    }

    @Test public void illustratedGameTilesSearchThePublicCatalog() {
        for (String title : new String[]{"Mario Kart 7", "Professor Layton and the Miracle Mask"}) {
            View tile = find(View.class, view -> (title + ". Find in the hShop catalog.")
                    .contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()));
            assertTrue(tile.isFocusable());
            assertTrue(tile.performClick());
            assertNativeCatalog(DownloadPolicy.searchUrl(title));
            click("Browse");
        }
    }

    @Test public void categoryDrilldownAndBackUseNativeHistoryAndCachedMetadata() {
        String games = "https://hshop.erista.me/c/games";
        client.pages.put(games, category(games));
        click("All games");
        await("region category", () -> optionalButton("Browse category") != null);
        click("Browse category");
        assertNativeCatalog(games + "/s/world");
        press(KeyEvent.KEYCODE_BUTTON_B);
        await("cached category", () -> optionalButton("Browse category") != null);
        assertEquals(1, client.fetchCount(games));
        assertFalse(shelf().isShown());
        press(KeyEvent.KEYCODE_BUTTON_B);
        assertTrue(shelf().isShown());
        assertFalse(activity.isFinishing());
    }

    @Test public void reselectingBrowseReturnsToShelfAndRetainsReusableCatalogMetadata() {
        String games = "https://hshop.erista.me/c/games";
        click("All games");
        assertNativeCatalog(games);
        click("Browse");
        assertTrue(shelf().isShown());
        click("Downloads");
        click("Browse");
        assertTrue(shelf().isShown());
        click("All games");
        await("cached result card", () -> optionalButton("Download") != null);
        assertEquals(1, client.fetchCount(games));
        press(KeyEvent.KEYCODE_BUTTON_B);
        assertTrue(shelf().isShown());
    }

    @Test public void backRestoresTheQueryThatMatchesTheCachedSearchResults() {
        String firstQuery = "A & B? # + é";
        String firstUrl = DownloadPolicy.searchUrl(firstQuery);
        String secondUrl = DownloadPolicy.searchUrl("Mario Kart 7");
        client.pages.put(firstUrl, results(firstUrl, "First search results"));
        client.pages.put(secondUrl, results(secondUrl, "Second search results"));
        EditText input = find(EditText.class, view -> true);
        input.setText(firstQuery);
        click("Search");
        assertNativeCatalog(firstUrl);
        input.setText("Mario Kart 7");
        click("Search");
        assertNativeCatalog(secondUrl);

        press(KeyEvent.KEYCODE_BUTTON_B);
        assertEquals(firstQuery, input.getText().toString());
        assertNotNull(CatalogUiTestSupport.find(root, TextView.class,
                view -> "First search results".contentEquals(view.getText()) && view.isShown()));
        assertEquals(1, client.fetchCount(firstUrl));
        assertEquals(1, client.fetchCount(secondUrl));
    }

    @Test public void rotationRestoresNativeCatalogQueryAndSelectedDownloadsTab() {
        find(EditText.class, view -> true).setText("Mario Kart 7");
        click("Search");
        String url = DownloadPolicy.searchUrl("Mario Kart 7");
        assertNativeCatalog(url);
        click("Downloads");
        rotateToLandscape();
        assertTrue(button("Downloads").isSelected());
        assertFalse(shelf().isShown());
        assertEquals("Mario Kart 7", find(EditText.class, view -> true).getText().toString());
        click("Browse");
        assertNativeCatalog(url);
        assertEquals(2, client.fetchCount(url));
    }

    @Test public void rotationRestoresShelfAndSelectedSettingsTabWithoutFetchingHiddenCatalog() {
        click("DLC");
        assertNativeCatalog("https://hshop.erista.me/c/dlc");
        click("Browse");
        click("Settings");
        rotateToLandscape();
        assertTrue(button("Settings").isSelected());
        click("Browse");
        assertTrue(shelf().isShown());
        assertFalse(resultsView().isShown());
        assertEquals(1, client.fetched.size());
    }

    @Test public void controllerBackReturnsToShelfBeforeFinishingTheActivity() {
        click("All games");
        assertNativeCatalog("https://hshop.erista.me/c/games");
        press(KeyEvent.KEYCODE_BUTTON_B);
        assertTrue(shelf().isShown());
        assertFalse(activity.isFinishing());
        press(KeyEvent.KEYCODE_BUTTON_B);
        assertTrue(activity.isFinishing());
    }

    @Test public void controllerSearchReturnsToBrowseAndFocusesSearch() {
        click("Settings");
        press(KeyEvent.KEYCODE_BUTTON_Y);
        assertTrue(button("Browse").isSelected());
        EditText search = find(EditText.class, view -> true);
        assertTrue(search.isShown());
        assertTrue(search.hasFocus());
        search.setText("Mario Kart 7");
        click("Search");
        assertNativeCatalog(DownloadPolicy.searchUrl("Mario Kart 7"));
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
    }

    private void bindActivity() {
        activity = controller.get();
        root = activity.findViewById(android.R.id.content);
    }

    private void assertNativeCatalog(String expectedUrl) {
        await("native catalog " + expectedUrl, () -> client.fetched.contains(expectedUrl) && optionalButton("Download") != null);
        assertTrue(button("Browse").isSelected());
        assertFalse(shelf().isShown());
        assertTrue(resultsView().isShown());
        assertTrue(find(TextView.class, view -> GAME.contentEquals(view.getText()) && view.isShown()).isShown());
        assertNull(optionalButton("‹ Back"));
        assertNull(optionalButton("Shelf"));
        assertNull(CatalogUiTestSupport.find(root, WebView.class, view -> true));
    }

    private void click(String label) { assertTrue(button(label).performClick()); shadowOf(Looper.getMainLooper()).idle(); }
    private void press(int keyCode) {
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode)));
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode)));
        shadowOf(Looper.getMainLooper()).idle();
    }
    private BrowseHomeView shelf() { return find(BrowseHomeView.class, view -> true); }
    private CatalogResultsView resultsView() { return find(CatalogResultsView.class, view -> true); }
    private Button button(String label) { return find(Button.class, view -> label.contentEquals(view.getText())); }
    private Button optionalButton(String label) { return CatalogUiTestSupport.find(root, Button.class, view -> label.contentEquals(view.getText())); }
    private <T extends View> T find(Class<T> type, Predicate<T> predicate) {
        T result = CatalogUiTestSupport.find(root, type, predicate);
        assertNotNull("Expected view: " + type.getSimpleName(), result);
        return result;
    }
}
