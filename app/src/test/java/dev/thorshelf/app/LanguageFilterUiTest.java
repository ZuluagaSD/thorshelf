package dev.thorshelf.app;

import android.app.AlertDialog;
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

/** Selector and navigation checks use synthetic metadata and never contact catalog services. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, qualifiers = "w411dp-h891dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
public class LanguageFilterUiTest {
    private static final String GAMES = DownloadPolicy.HOME + "c/games";
    private static final String THREE_DS_ENGLISH = "https://hshop.erista.me/t/101";
    private static final String THREE_DS_FRENCH = "https://hshop.erista.me/t/102";
    private static final String THREE_DS_UNKNOWN = "https://hshop.erista.me/t/103";
    private static final String FEED = """
            {"page":1,"limit":200,"total":3,"revision":1,"entries":[
              {"title":"English Adventure","fileName":"english-adventure","systems":["DS"],
               "version":"(Europe) (En)","downloads":{"English.nds":{
                 "url":"https://db-nds-shop.fr/api/v1/download/English.nds","size":1024}}},
              {"title":"French Adventure","fileName":"french-adventure","systems":["DS"],
               "version":"(France)","downloads":{"French.nds":{
                 "url":"https://db-nds-shop.fr/api/v1/download/French.nds","size":1024}}},
              {"title":"Mystery Adventure","fileName":"mystery-adventure","systems":["DS"],
               "version":"(World)","downloads":{"Mystery.nds":{
                 "url":"https://db-nds-shop.fr/api/v1/download/Mystery.nds","size":1024}}}
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
        threeDs.releaseAll();
        if (controller != null) controller.pause().stop().destroy();
        MainActivity.ndsCatalogClientFactory = NdsCatalogClient::new;
        MainActivity.catalogClientFactory = CatalogClient::new;
    }

    @Test public void defaultsToAllLanguagesAndTheControllerCanCancelOrSelectLanguage() {
        assertTrue(threeDs.fetched.isEmpty());
        assertTrue(ds.fetched.isEmpty());
        assertTrue(languageButton().isFocusable());
        assertEquals("Filter game language: All languages", languageButton().getContentDescription());
        languageButton().performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("All languages", dialog.getListView().getAdapter().getItem(0));
        assertEquals(0, dialog.getListView().getCheckedItemPosition());
        assertTrue(dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)));
        assertFalse(dialog.isShowing());
        assertEquals("Language: All languages ▾", languageButton().getText().toString());
        assertTrue(threeDs.fetched.isEmpty());
        assertTrue(ds.fetched.isEmpty());
        shadowOf(Looper.getMainLooper()).idle();
        languageButton().performClick();
        AlertDialog selection = ShadowAlertDialog.getLatestAlertDialog();
        shadowOf(Looper.getMainLooper()).idle();
        // SDK 35's synthetic dialog window needs a layout before ListView can move focus.
        View decor = selection.getWindow().getDecorView();
        int width = Ui.dp(activity, 380), height = Ui.dp(activity, 600);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
        selection.getListView().requestFocus();
        int english = CatalogLanguages.codes().indexOf("en") + 1;
        for (int step = 0; step < 3 && selection.getListView().getSelectedItemPosition() != english; step++) {
            selection.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
            selection.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertTrue(selection.getListView().hasFocus());
        assertEquals(english, selection.getListView().getSelectedItemPosition());
        selection.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
        selection.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A));
        await("controller selected English", () -> title("English Adventure") != null);
        assertFalse(selection.isShowing());
        assertEquals("Language: English ▾", languageButton().getText().toString());
    }

    @Test public void dsFilterExcludesOtherAndUnknownLanguagesAndAllRestoresThem() {
        button("DS").performClick();
        await("all DS games", () -> title("Mystery Adventure") != null);
        chooseLanguage("en");
        await("English DS games", () -> ds.fetched.contains(filtered(NdsCatalog.HOME, "en"))
                && title("English Adventure") != null);
        assertNull(title("French Adventure"));
        assertNull(title("Mystery Adventure"));
        assertTrue(threeDs.fetched.isEmpty());
        assertNotNull(find(root, TextView.class, view -> view.isShown()
                && view.getText().toString().contains("Europe ·")
                && view.getText().toString().contains("English")));
        chooseLanguage("fr");
        await("French DS games", () -> title("French Adventure") != null);
        assertNull(title("English Adventure"));
        assertNull(title("Mystery Adventure"));
        chooseLanguage("");
        await("all DS games restored", () -> title("Mystery Adventure") != null);
        assertNotNull(title("English Adventure"));
        assertNotNull(title("French Adventure"));
        chooseLanguage("en");
        button("3DS").performClick();
        await("empty-query platform switch respects language", () -> threeDs.fetched.contains(filtered(GAMES, "en"))
                && title("English Adventure") != null);
        assertNull(title("French Adventure"));
    }

    @Test public void languageAndQuerySurvivePlatformChangesRotationAndRestart() {
        input().setText("Adventure");
        chooseLanguage("fr");
        await("French 3DS search", () -> threeDs.fetched.contains(filtered(DownloadPolicy.searchUrl("Adventure"), "fr"))
                && title("French Adventure") != null);
        assertNull(title("English Adventure"));
        assertNull(title("Mystery Adventure"));
        button("DS").performClick();
        await("French DS search", () -> ds.fetched.contains(filtered(NdsCatalog.searchUrl("Adventure"), "fr"))
                && title("French Adventure") != null);
        assertEquals("Adventure", input().getText().toString());
        rotate();
        await("restored French DS search", () -> title("French Adventure") != null);
        assertTrue(button("DS").isSelected());
        assertEquals("Language: French ▾", languageButton().getText().toString());
        assertEquals("Adventure", input().getText().toString());
        button("3DS").performClick();
        await("French 3DS after switch", () -> title("French Adventure") != null);
        assertTrue(button("3DS").isSelected());
        controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        bind();
        assertEquals("Language: French ▾", languageButton().getText().toString());
        button("All games").performClick();
        await("persisted language applied", () -> threeDs.fetched.contains(filtered(GAMES, "fr"))
                && title("French Adventure") != null);
        assertNull(title("English Adventure"));
    }

    @Test public void detailsAndBackKeepTheSelectedLanguageAndShowLanguageMetadata() {
        button("DS").performClick();
        await("DS catalog", () -> title("Mystery Adventure") != null);
        chooseLanguage("en");
        await("English result", () -> title("English Adventure") != null);
        button("Details").performClick();
        await("English details", () -> text("Languages: English") != null);
        assertTrue(NdsCatalog.isDetailUrl(ds.fetched.get(ds.fetched.size() - 1)));
        activity.onBackPressed();
        await("filtered DS list after Back", () -> optionalButton("Details") != null);
        assertEquals(filtered(NdsCatalog.HOME, "en"), ds.fetched.get(ds.fetched.size() - 1));
        assertNull(title("French Adventure"));
        chooseLanguage("");
        await("unknown language restored", () -> title("Mystery Adventure") != null);
        Button mysteryDetails = find(root, Button.class, view -> view.isShown()
                && view.getContentDescription() != null
                && "View details for Mystery Adventure".contentEquals(view.getContentDescription()));
        assertNotNull(mysteryDetails);
        mysteryDetails.performClick();
        await("unknown language disclosed on detail", () -> text("Language not listed") != null);
        chooseLanguage("fr");
        await("language change leaves detail for filtered list", () -> optionalButton("Details") != null
                && title("French Adventure") != null);
    }

    @Test public void noLanguageMatchesExplainsUnknownMetadataWithoutClaimingEntireCatalogIsEmpty() {
        button("DS").performClick();
        await("DS catalog", () -> title("Mystery Adventure") != null);
        chooseLanguage("ja");
        await("empty Japanese result", () -> text("No Japanese titles on this page") != null);
        assertNotNull(find(root, TextView.class, view -> view.isShown()
                && view.getText().toString().contains("without listed language information are excluded")));
        assertNull(optionalButton("Download"));
        assertEquals("Language: Japanese ▾", languageButton().getText().toString());
    }

    @Test public void newLanguageCancelsAnOlderCatalogResponse() {
        ds.gate = new Gate(true);
        button("DS").performClick();
        await("old DS request started", () -> ds.gate.entered.getCount() == 0);
        Gate old = ds.gate;
        ds.gate = null;
        chooseLanguage("fr");
        await("new French result", () -> title("French Adventure") != null);
        old.release.countDown();
        await("old response finished", () -> old.finished.getCount() == 0);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(title("English Adventure"));
        assertNull(title("Mystery Adventure"));
        assertEquals("Language: French ▾", languageButton().getText().toString());
    }

    @Test public void languageChangeInvalidatesAnInFlightDownloadVerification() {
        chooseLanguage("en");
        await("English 3DS game", () -> title("English Adventure") != null);
        threeDs.downloadGate = new Gate(true);
        button("Download").performClick();
        await("download request started", () -> threeDs.downloadGate.entered.getCount() == 0);
        chooseLanguage("fr");
        await("French game after filter change", () -> title("French Adventure") != null);
        threeDs.downloadGate.release.countDown();
        await("old download finished", () -> threeDs.downloadGate.finished.getCount() == 0);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(find(root, android.webkit.WebView.class, view -> true));
        assertTrue(button("Download").isEnabled());
        assertTrue(new DownloadStore(activity).list().isEmpty());
    }

    @Test public void verificationStripsLocalParametersAndReappliesTheActiveLanguageBeforeRendering() {
        threeDs.requireVerification = true;
        chooseLanguage("fr");
        await("verification action", () -> optionalButton("Verify to continue") != null);
        button("Verify to continue").performClick();
        CatalogVerification verification = ReflectionHelpers.getField(activity, "verification");
        assertNotNull(verification);
        WebView browser = ReflectionHelpers.getField(verification, "webView");
        String request = filtered(GAMES, "fr");
        assertEquals(CatalogLanguageFilter.sourceUrl(request), shadowOf(browser).getLastLoadedUrl());
        assertFalse(shadowOf(browser).getLastLoadedUrl().contains("thorshelf_"));
        CatalogVerification.Listener listener = ReflectionHelpers.getField(verification, "listener");
        threeDs.requireVerification = false;
        listener.onHtml("""
                <main><div class="sitedesc"><h1>Catalog</h1></div><div class="list">
                  <div class="elements"><a class="list-entry" href="/t/101">
                    <div class="base-info"><h3>English Adventure</h3></div>
                  </a></div>
                </div></main>
                """);
        await("verified page filtered again", () -> threeDs.fetched.stream().filter(request::equals).count() == 2
                && title("French Adventure") != null);
        assertNull(title("English Adventure"));
        assertNull(ReflectionHelpers.getField(activity, "verification"));
        assertTrue(shadowOf(browser).wasDestroyCalled());
        assertNotNull(find(root, TextView.class, view -> view.isShown()
                && view.getText().toString().contains("Languages: 3dsdb.com")));
    }

    @Test public void changingLanguageResetsThreeDsPaginationAndKeepsNextPageLanguage() {
        threeDs.paginated = true;
        button("All games").performClick();
        await("3DS first page", () -> optionalButton("Next ›") != null);
        button("Next ›").performClick();
        await("3DS second page", () -> threeDs.fetched.contains(GAMES + "?offset=100")
                && optionalButton("Download") != null);
        chooseLanguage("fr");
        await("French first page", () -> threeDs.fetched.contains(filtered(GAMES, "fr"))
                && title("French Adventure") != null);
        assertEquals(filtered(GAMES, "fr"), threeDs.fetched.get(threeDs.fetched.size() - 1));
        button("Next ›").performClick();
        await("French next page", () -> threeDs.fetched.contains(filtered(GAMES + "?offset=100", "fr")));
        assertEquals("Language: French ▾", languageButton().getText().toString());
    }

    private void chooseLanguage(String code) {
        languageButton().performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        int index = code.isEmpty() ? 0 : CatalogLanguages.codes().indexOf(code) + 1;
        assertTrue(index >= 0);
        shadowOf(dialog).clickOnItem(index);
        assertFalse(dialog.isShowing());
    }

    private void rotate() {
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.orientation = Configuration.ORIENTATION_LANDSCAPE;
        config.screenWidthDp = 960;
        config.screenHeightDp = 540;
        controller.configurationChange(config).visible();
        bind();
    }

    private void bind() {
        activity = controller.get();
        root = activity.findViewById(android.R.id.content);
    }

    private static String filtered(String url, String language) {
        return CatalogLanguageFilter.apply(url, language, false);
    }

    private EditText input() { return find(root, EditText.class, view -> true); }
    private TextView title(String value) { return text(value); }
    private TextView text(String value) {
        return find(root, TextView.class, view -> view.isShown() && value.contentEquals(view.getText()));
    }
    private Button languageButton() {
        Button button = find(root, Button.class, view -> view.getText().toString().startsWith("Language: "));
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
                entry(THREE_DS_ENGLISH, "English Adventure", "en"),
                entry(THREE_DS_FRENCH, "French Adventure", "fr"),
                entry(THREE_DS_UNKNOWN, "Mystery Adventure", ""));
        volatile Gate downloadGate;
        volatile boolean paginated;
        volatile boolean requireVerification;

        @Override public CatalogPage fetch(String url, String agent, String cookie) throws IOException {
            fetched.add(url);
            String language = CatalogLanguageFilter.selected(url);
            if (requireVerification) throw new VerificationRequiredException("Synthetic listing challenge");
            if (THREE_DS_ENGLISH.equals(url) && downloadGate != null) {
                try {
                    downloadGate.waitForRelease();
                    throw new VerificationRequiredException("Synthetic old challenge");
                } finally { downloadGate.finished.countDown(); }
            }
            List<CatalogPage.Entry> matches = new ArrayList<>();
            boolean detail = url.equals(THREE_DS_ENGLISH) || url.equals(THREE_DS_FRENCH) || url.equals(THREE_DS_UNKNOWN);
            for (CatalogPage.Entry entry : entries) {
                if (detail ? url.equals(entry.url) : language.isEmpty() || entry.languages.contains(language)) matches.add(entry);
            }
            String next = paginated && !url.contains("offset=100") ? filtered(GAMES + "?offset=100", language) : "";
            String summary = language.isEmpty() ? "Synthetic catalog" : CatalogLanguages.label(language) + " matches on this page";
            return new CatalogPage(url, "Nintendo 3DS games", summary, matches, Collections.emptyList(), next, "", "", false);
        }

        private static CatalogPage.Entry entry(String url, String title, String language) {
            return new CatalogPage.Entry(url, title, "games", "World", "1 MiB", "", "", "",
                    language.isEmpty() ? Collections.emptyList() : Collections.singletonList(language));
        }
        void releaseAll() { if (downloadGate != null) downloadGate.release.countDown(); }
    }
}
