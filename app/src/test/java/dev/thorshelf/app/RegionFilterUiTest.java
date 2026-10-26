package dev.thorshelf.app;

import android.app.AlertDialog;
import android.content.res.Configuration;
import android.os.Looper;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.thorshelf.app.CatalogUiTestSupport.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Region and combined-filter navigation with synthetic metadata and no network traffic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, qualifiers = "w411dp-h891dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
public class RegionFilterUiTest {
    private static final String GAMES = DownloadPolicy.HOME + "c/games";
    private static final String FEED = """
            {"page":1,"limit":200,"total":4,"revision":1,"entries":[
              {"title":"American Adventure","fileName":"american","systems":["DS"],
               "version":"(USA) (En)","downloads":{"American.nds":{
                 "url":"https://db-nds-shop.fr/api/v1/download/American.nds","size":1024}}},
              {"title":"European Adventure","fileName":"european","systems":["DS"],
               "version":"(Europe) (En)","downloads":{"European.nds":{
                 "url":"https://db-nds-shop.fr/api/v1/download/European.nds","size":1024}}},
              {"title":"French Adventure","fileName":"french","systems":["DS"],
               "version":"(France)","downloads":{"French.nds":{
                 "url":"https://db-nds-shop.fr/api/v1/download/French.nds","size":1024}}},
              {"title":"World Adventure","fileName":"world","systems":["DS"],
               "version":"(World)","downloads":{"World.nds":{
                 "url":"https://db-nds-shop.fr/api/v1/download/World.nds","size":1024}}}
            ]}
            """;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private View root;
    private DsClient ds;
    private ThreeDsClient threeDs;

    @Before public void launch() {
        ds = new DsClient();
        threeDs = new ThreeDsClient();
        MainActivity.ndsCatalogClientFactory = () -> ds;
        MainActivity.catalogClientFactory = () -> threeDs;
        controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        bind();
    }

    @After public void close() {
        ds.releaseAll();
        if (controller != null) controller.pause().stop().destroy();
        MainActivity.ndsCatalogClientFactory = NdsCatalogClient::new;
        MainActivity.catalogClientFactory = CatalogClient::new;
    }

    @Test public void adjacentSelectorsCombineDsFiltersAndResetEachIndependently() {
        assertEquals("Region: All regions ▾", regionButton().getText().toString());
        assertEquals("Filter game region: All regions", regionButton().getContentDescription());
        assertTrue(regionButton().isFocusable());
        assertSame(languageButton().getParent(), regionButton().getParent());
        assertTrue(ds.fetched.isEmpty());
        assertTrue(threeDs.fetched.isEmpty());
        button("DS").performClick();
        await("unfiltered DS games", () -> text("World Adventure") != null);
        choose(true, "europe");
        await("European DS games", () -> ds.fetched.contains(filtered(NdsCatalog.HOME, "", "europe"))
                && text("European Adventure") != null);
        assertNotNull(text("French Adventure"));
        assertNull(text("American Adventure"));
        choose(false, "en");
        await("English European DS games", () -> ds.fetched.contains(filtered(NdsCatalog.HOME, "en", "europe"))
                && text("European Adventure") != null);
        assertNull(text("French Adventure"));
        choose(true, "");
        await("all regions keeps English", () -> text("American Adventure") != null);
        assertNotNull(text("European Adventure"));
        assertNull(text("French Adventure"));
        assertNull(text("World Adventure"));
        choose(true, "europe");
        await("Europe restored", () -> text("American Adventure") == null && text("European Adventure") != null);
        choose(false, "");
        await("all languages keeps Europe", () -> text("French Adventure") != null);
        assertNull(text("American Adventure"));
        assertEquals("Region: Europe ▾", regionButton().getText().toString());
    }

    @Test public void filtersSurvivePlatformRotationAndRestartAndRegionChangeResetsPagination() {
        input().setText("Adventure");
        choose(false, "en");
        await("English 3DS search", () -> text("American Adventure") != null);
        choose(true, "europe");
        String search = DownloadPolicy.searchUrl("Adventure");
        await("combined 3DS search", () -> threeDs.fetched.contains(filtered(search, "en", "europe"))
                && text("European Adventure") != null);
        assertNull(text("American Adventure"));
        button("DS").performClick();
        await("combined DS search", () -> ds.fetched.contains(filtered(NdsCatalog.searchUrl("Adventure"), "en", "europe"))
                && text("European Adventure") != null);
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.orientation = Configuration.ORIENTATION_LANDSCAPE;
        config.screenWidthDp = 960;
        config.screenHeightDp = 540;
        controller.configurationChange(config).visible();
        bind();
        await("combined filters restored after rotation", () -> text("European Adventure") != null);
        assertEquals("Adventure", input().getText().toString());
        assertEquals("Language: English ▾", languageButton().getText().toString());
        assertEquals("Region: Europe ▾", regionButton().getText().toString());
        assertSame(languageButton().getParent(), regionButton().getParent());
        threeDs.paginationBase = search;
        threeDs.paginated = true;
        button("3DS").performClick();
        await("3DS filtered pagination", () -> optionalButton("Next ›") != null);
        button("Next ›").performClick();
        await("second combined page", () -> threeDs.fetched.contains(filtered(search + "&offset=100", "en", "europe"))
                && text("European Adventure") != null);
        choose(true, "north-america");
        await("region change resets to first page", () -> threeDs.fetched.contains(filtered(search, "en", "north-america"))
                && text("American Adventure") != null);
        assertEquals("Adventure", input().getText().toString());
        assertNull(text("European Adventure"));
        controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        bind();
        assertEquals("Language: English ▾", languageButton().getText().toString());
        assertEquals("Region: North America ▾", regionButton().getText().toString());
        button("All games").performClick();
        await("persisted filters apply on restart", () -> text("American Adventure") != null);
        assertNull(text("European Adventure"));
    }

    @Test public void regionOnlyEmptyResultDoesNotClaimLanguageMetadataWasExcluded() {
        button("DS").performClick();
        await("DS catalog", () -> text("World Adventure") != null);
        choose(true, "japan");
        await("empty Japanese region", () -> text("No titles from Japan on this page") != null);
        assertNotNull(text("Try another region or All regions."));
        assertNull(find(root, TextView.class, view -> view.isShown()
                && view.getText().toString().contains("without listed language information")));
        choose(false, "fr");
        await("combined empty state", () -> text("No French titles from Japan on this page") != null);
        assertNotNull(find(root, TextView.class, view -> view.isShown()
                && view.getText().toString().contains("without listed language information")));
    }

    @Test public void regionChangeCancelsOlderCatalogResultsAndPreservesLanguage() {
        choose(false, "en");
        await("English 3DS titles", () -> text("American Adventure") != null);
        ds.gate = new Gate(true);
        button("DS").performClick();
        await("old DS request started", () -> ds.gate.entered.getCount() == 0);
        Gate old = ds.gate;
        ds.gate = null;
        choose(true, "europe");
        await("European result supersedes old request", () -> text("European Adventure") != null);
        old.release.countDown();
        await("old DS request finished", () -> old.finished.getCount() == 0);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(text("American Adventure"));
        assertNull(text("French Adventure"));
        assertEquals("Language: English ▾", languageButton().getText().toString());
        assertEquals("Region: Europe ▾", regionButton().getText().toString());
    }

    @Test public void verificationWithOnlyRegionSelectedRefiltersAndStripsInternalBrowserParameters() {
        threeDs.requireVerification = true;
        choose(true, "north-america");
        await("region-only verification action", () -> optionalButton("Verify to continue") != null);
        button("Verify to continue").performClick();
        CatalogVerification verification = ReflectionHelpers.getField(activity, "verification");
        assertNotNull(verification);
        WebView browser = ReflectionHelpers.getField(verification, "webView");
        String request = filtered(GAMES, "", "north-america");
        assertEquals(CatalogFilters.sourceUrl(request), shadowOf(browser).getLastLoadedUrl());
        assertFalse(shadowOf(browser).getLastLoadedUrl().contains("thorshelf_"));
        CatalogVerification.Listener listener = ReflectionHelpers.getField(verification, "listener");
        threeDs.requireVerification = false;
        listener.onHtml("""
                <main><div class="sitedesc"><h1>Catalog</h1></div><div class="list">
                  <div class="elements"><a class="list-entry" href="/t/202">
                    <div class="base-info"><h3>European Adventure</h3></div>
                  </a></div>
                </div></main>
                """);
        await("verified result reapplies region", () -> threeDs.fetched.stream().filter(request::equals).count() == 2
                && text("American Adventure") != null);
        assertNull(text("European Adventure"));
        assertNull(ReflectionHelpers.getField(activity, "verification"));
        assertTrue(shadowOf(browser).wasDestroyCalled());
        assertEquals("Language: All languages ▾", languageButton().getText().toString());
    }

    private void choose(boolean region, String code) {
        (region ? regionButton() : languageButton()).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        List<String> codes = region ? CatalogRegions.codes() : CatalogLanguages.codes();
        int index = code.isEmpty() ? 0 : codes.indexOf(code) + 1;
        assertTrue(index >= 0);
        shadowOf(dialog).clickOnItem(index);
        assertFalse(dialog.isShowing());
    }

    private void bind() {
        activity = controller.get();
        root = activity.findViewById(android.R.id.content);
    }
    private static String filtered(String url, String language, String region) {
        return CatalogFilters.apply(url, language, region, false);
    }
    private EditText input() { return find(root, EditText.class, view -> true); }
    private TextView text(String value) {
        return find(root, TextView.class, view -> view.isShown() && value.contentEquals(view.getText()));
    }
    private Button languageButton() { return filterButton("Language: "); }
    private Button regionButton() { return filterButton("Region: "); }
    private Button filterButton(String prefix) {
        Button button = find(root, Button.class, view -> view.getText().toString().startsWith(prefix));
        assertNotNull(button);
        return button;
    }
    private Button optionalButton(String name) {
        return find(root, Button.class, view -> view.isShown() && name.contentEquals(view.getText()));
    }
    private Button button(String name) {
        Button button = optionalButton(name);
        assertNotNull(name, button);
        return button;
    }

    private static final class DsClient extends NdsCatalogClient {
        final NdsCatalog catalog = NdsCatalog.parseApiPage(FEED).catalog;
        final List<String> fetched = new CopyOnWriteArrayList<>();
        final List<Gate> gates = new CopyOnWriteArrayList<>();
        volatile Gate gate;
        @Override public CatalogPage fetch(String url, String agent) throws IOException {
            fetched.add(url);
            Gate current = gate;
            try {
                if (current != null) {
                    gates.add(current);
                    current.waitForRelease();
                }
                return catalog.page(url);
            } finally {
                if (current != null) current.finished.countDown();
            }
        }
        void releaseAll() { for (Gate gate : gates) gate.release.countDown(); }
    }

    private static final class ThreeDsClient extends CatalogClient {
        final List<String> fetched = new CopyOnWriteArrayList<>();
        final List<CatalogPage.Entry> entries = List.of(
                entry(201, "American Adventure", "north-america", "en"),
                entry(202, "European Adventure", "europe", "en"),
                entry(203, "French Adventure", "europe", "fr"),
                entry(204, "World Adventure", "world", ""));
        volatile boolean paginated;
        volatile String paginationBase = GAMES;
        volatile boolean requireVerification;

        @Override public CatalogPage fetch(String url, String agent, String cookie) throws IOException {
            fetched.add(url);
            if (requireVerification) throw new VerificationRequiredException("Synthetic region challenge");
            String language = CatalogLanguageFilter.selected(url);
            String region = CatalogFilters.selectedRegion(url);
            List<CatalogPage.Entry> matches = new ArrayList<>();
            for (CatalogPage.Entry entry : entries) {
                if ((language.isEmpty() || entry.languages.contains(language))
                        && (region.isEmpty() || entry.region.equals(region))) matches.add(entry);
            }
            String next = paginated && !url.contains("offset=100")
                    ? filtered(paginationBase + (paginationBase.contains("?") ? "&" : "?") + "offset=100", language, region) : "";
            return new CatalogPage(url, "Nintendo 3DS games", "Synthetic filtered catalog", matches,
                    Collections.emptyList(), next, "", "", false);
        }
        private static CatalogPage.Entry entry(int id, String title, String region, String language) {
            return new CatalogPage.Entry("https://hshop.erista.me/t/" + id, title, "games", region,
                    "1 MiB", "", "", "", language.isEmpty() ? Collections.emptyList() : List.of(language));
        }
    }
}
