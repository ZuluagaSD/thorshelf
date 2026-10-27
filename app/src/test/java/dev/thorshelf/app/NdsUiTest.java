package dev.thorshelf.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.thorshelf.app.CatalogUiTestSupport.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Synthetic catalog and queue only: no live feeds or game downloads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class NdsUiTest {
    private static final String LINK = "https://db-nds-shop.fr/api/v1/download/Adventure.nds";
    private static final String FEED = """
            {"page":1,"limit":200,"total":1,"revision":1,"entries":[
              {"title":"Tiny Adventure","fileName":"tiny-adventure","systems":["DS"],
               "version":"(World)","downloads":{"Adventure.nds":{
                 "url":"https://db-nds-shop.fr/api/v1/download/Adventure.nds","size":1024}}}
            ]}
            """;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private View root;
    private DownloadManager manager;
    private DsClient ds;
    private MetadataClient metadata;
    private String titleUrl;

    @Before public void launch() throws Exception {
        ds = new DsClient();
        metadata = new MetadataClient();
        MainActivity.ndsCatalogClientFactory = () -> ds;
        MainActivity.catalogClientFactory = () -> metadata;
        titleUrl = ds.catalog.page(NdsCatalog.HOME).entries.get(0).url;
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        bind();
        if (Build.VERSION.SDK_INT >= 33) shadowOf(activity.getApplication())
                .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
    }

    @After public void close() {
        if (ds.gate != null) ds.gate.release.countDown();
        if (metadata.downloadGate != null) metadata.downloadGate.release.countDown();
        controller.pause().stop().destroy();
        MainActivity.ndsCatalogClientFactory = NdsCatalogClient::new;
        MainActivity.catalogClientFactory = CatalogClient::new;
        activity.getSharedPreferences("export_status", 0).edit().clear().commit();
        ReflectionHelpers.setStaticField(ExportService.class, "active", false);
        ReflectionHelpers.setStaticField(ExportService.class, "activeToken", null);
    }

    @Test public void switchSearchAndReturnToThreeDsKeepCatalogsSeparate() {
        assertTrue(ds.fetched.isEmpty());
        openDs();
        assertTrue(button("DS").isSelected());
        assertTrue(metadata.fetched.isEmpty());
        assertNotNull(find(root, TextView.class, text -> text.isShown()
                && text.getText().toString().contains("NDS-Shop")));
        input().setText("Tiny");
        button("Search").performClick();
        await("DS search", () -> ds.fetched.contains(NdsCatalog.searchUrl("Tiny")) && optionalButton("Download") != null);
        button("3DS").performClick();
        await("same query in 3DS", () -> metadata.fetched.contains(DownloadPolicy.searchUrl("Tiny"))
                && optionalButton("Download") != null);
        assertEquals("Tiny", input().getText().toString());
        assertTrue(button("3DS").isSelected());
        assertFalse(button("DS").isSelected());
        button("DS").performClick();
        await("same query in DS", () -> ds.fetched.size() == 3 && optionalButton("Download") != null);
        assertEquals(NdsCatalog.searchUrl("Tiny"), ds.fetched.get(2));
        assertEquals("Tiny", input().getText().toString());
        assertTrue(button("DS").isSelected());
        assertFalse(button("3DS").isSelected());
        button("3DS").performClick();
        input().setText("Mario");
        button("Search").performClick();
        await("hShop search", () -> metadata.fetched.contains(DownloadPolicy.searchUrl("Mario")));
        assertNull(find(root, WebView.class, view -> true));
    }

    @Test public void detailsBackAndRotationKeepDsSelectionAndQuery() {
        openDs();
        input().setText("Tiny");
        button("Search").performClick();
        await("search result", () -> ds.fetched.contains(NdsCatalog.searchUrl("Tiny")) && optionalButton("Details") != null);
        button("Details").performClick();
        await("DS detail", () -> ds.fetched.contains(titleUrl) && optionalButton("Download") != null);
        activity.onBackPressed();
        assertEquals("Tiny", input().getText().toString());
        button("Downloads").performClick();
        rotate();
        assertTrue(button("Downloads").isSelected());
        assertTrue(button("DS").isSelected());
        assertEquals("Tiny", input().getText().toString());
        button("Browse").performClick();
        await("restored DS catalog", () -> optionalButton("Download") != null);
        assertTrue(metadata.fetched.isEmpty());
    }

    @Test public void englishPokemonSearchShowsFrenchEditionAndQueuesTheNdsFile() {
        ds.catalog = NdsCatalog.parseApiPage("""
                {"page":1,"limit":200,"total":2,"revision":1,"entries":[
                  {"title":"Pokémon Version Blanche","fileName":"pokemon-version-blanche",
                   "titleId":"IRAF","systems":["DS"],"version":"(France)",
                   "downloads":{"White.nds":{"url":"https://db-nds-shop.fr/api/v1/download/White.nds","size":268435456},
                     "White.cia":{"url":"https://db-nds-shop.fr/api/v1/download/White.cia","size":195328}}},
                  {"title":"Pokémon Version Noire","fileName":"pokemon-version-noire",
                   "titleId":"IRBF","systems":["DS"],"version":"(France)",
                   "downloads":{"Black.nds":{"url":"https://db-nds-shop.fr/api/v1/download/Black.nds","size":268435456}}}
                ]}
                """).catalog;
        input().setText("Pokemon White");
        button("DS").performClick();
        await("French White edition", () -> find(root, TextView.class, text -> text.isShown()
                && "Pokémon Version Blanche".contentEquals(text.getText())) != null);
        assertNotNull(find(root, TextView.class, text -> text.isShown()
                && text.getText().toString().contains("France")));
        assertNull(find(root, TextView.class, text -> text.isShown()
                && "Pokémon Version Noire".contentEquals(text.getText())));
        button("Details").performClick();
        await("White details", () -> ds.fetched.stream().anyMatch(NdsCatalog::isDetailUrl)
                && optionalButton("Download") != null);
        button("Download").performClick();
        await("White queued", () -> new DownloadStore(activity).list().size() == 1);
        assertEquals("https://db-nds-shop.fr/api/v1/download/White.nds", metadata.inspected.get(0));
        DownloadStore.Entry queued = new DownloadStore(activity).list().get(0);
        assertTrue(queued.name.endsWith(".nds"));
        assertTrue(queued.displayName.contains("Blanche"));
        assertTrue(metadata.fetched.isEmpty());

        input().setText("Pokemon Black");
        button("Search").performClick();
        await("French Black edition", () -> find(root, TextView.class, text -> text.isShown()
                && "Pokémon Version Noire".contentEquals(text.getText())) != null);
        assertNull(find(root, TextView.class, text -> text.isShown()
                && "Pokémon Version Blanche".contentEquals(text.getText())));
        assertEquals("Pokemon Black", input().getText().toString());
    }

    @Test public void dsDownloadUsesNativeQueueAndSurvivesActivityRecreationWithoutDuplicates() {
        openDs();
        button("Download").performClick();
        await("queued DS game", () -> new DownloadStore(activity).list().size() == 1);
        DownloadStore.Entry entry = new DownloadStore(activity).list().get(0);
        assertEquals("Tiny Adventure.nds", entry.name);
        assertEquals(titleUrl, entry.sourcePage);
        assertEquals(1, metadata.inspected.size());
        assertEquals(LINK, metadata.inspected.get(0));
        assertEquals(DownloadManager.Request.NETWORK_WIFI,
                shadowOf(shadowOf(manager).getRequest(entry.id)).getAllowedNetworkTypes());
        shadowOf(shadowOf(manager).getRequest(entry.id)).setStatus(DownloadManager.STATUS_PENDING);
        rotate();
        await("recreated DS result", () -> optionalButton("Download") != null);
        button("Download").performClick();
        await("existing queue", () -> button("Downloads").isSelected());
        assertEquals(1, new DownloadStore(activity).list().size());
        assertEquals(1, metadata.inspected.size());
    }

    @Test public void switchingPlatformIgnoresAnOldDsResponse() {
        ds.gate = new Gate(true);
        button("DS").performClick();
        await("blocked DS request", () -> ds.gate.entered.getCount() == 0);
        button("3DS").performClick();
        ds.gate.release.countDown();
        await("DS response finished", () -> ds.gate.finished.getCount() == 0);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(button("3DS").isSelected());
        assertNotNull(find(root, BrowseHomeView.class, View::isShown));
        assertNull(optionalButton("Download"));
    }

    @Test public void dsFailureOffersRetryWithoutHshopVerification() {
        ds.fail = true;
        button("DS").performClick();
        await("DS error", () -> optionalButton("Try again") != null);
        assertNull(optionalButton("Verify to continue"));
        assertNull(find(root, WebView.class, view -> true));
        ds.fail = false;
        button("Try again").performClick();
        await("retried DS results", () -> optionalButton("Download") != null);
    }

    @Test public void switchingToDsInvalidatesAnUnfinishedHshopDownloadChallenge() {
        input().setText("Tiny");
        button("Search").performClick();
        await("hShop result", () -> optionalButton("Download") != null);
        metadata.downloadGate = new Gate(true);
        button("Download").performClick();
        await("hShop download detail blocked", () -> metadata.downloadGate.entered.getCount() == 0);
        openDs();
        metadata.downloadGate.release.countDown();
        await("old hShop response finished", () -> metadata.downloadGate.finished.getCount() == 0);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(find(root, WebView.class, view -> true));
        assertNull(ReflectionHelpers.getField(activity, "verification"));
        assertTrue(button("Download").isEnabled());
        button("Download").performClick();
        await("DS download unblocked", () -> new DownloadStore(activity).list().size() == 1);
        assertEquals(titleUrl, new DownloadStore(activity).list().get(0).sourcePage);
    }

    @Test public void completedDsZipUsesNewNdsDocumentPickerAndPreservesOriginalOnCancel() {
        long id = addCompletedDs();
        button("Save DS game").performClick();
        ShadowActivity.IntentForResult picker = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(45, picker.requestCode);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.getAction());
        assertEquals("Tiny Adventure.nds", picker.intent.getStringExtra(Intent.EXTRA_TITLE));
        assertNull(optionalButton("Prepare for Azahar"));
        assertNull(optionalButton("Open in melonDS"));
        activity.onActivityResult(45, MainActivity.RESULT_CANCELED, null);
        assertEquals(id, new DownloadStore(activity).list().get(0).id);
        assertNull(shadowOf(activity).getNextStartedService());
        button("Save DS game").performClick();
        assertNotNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void savePickerResultAfterRotationStartsDsExportOnly() {
        long id = addCompletedDs();
        button("Save DS game").performClick();
        shadowOf(activity).getNextStartedActivityForResult();
        rotate();
        activity.onActivityResult(45, MainActivity.RESULT_OK,
                new Intent().setData(Uri.parse("content://synthetic.documents/new-game")));
        Intent started = shadowOf(activity).getNextStartedService();
        assertNotNull(started);
        assertEquals(ExportService.class.getName(), started.getComponent().getClassName());
        assertEquals(id, started.getLongExtra("download_id", -1));
        assertEquals("Tiny Adventure.nds", started.getStringExtra("name"));
        assertFalse(PreparationService.isRunning(activity));
        assertEquals(1, new DownloadStore(activity).list().size());
    }

    @Test public void emulatorShortcutFollowsPlatformAndSurvivesRotation() {
        assertNotNull(optionalButton("Azahar ↗"));
        openDs();
        assertNotNull(optionalButton("melonDS ↗"));
        assertNull(optionalButton("Azahar ↗"));
        rotate();
        button("melonDS ↗").performClick();
        AlertDialog help = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Open in melonDS", shadowOf(help).getTitle().toString());
        help.dismiss();
        button("3DS").performClick();
        assertNotNull(optionalButton("Azahar ↗"));
    }

    @Test public void dsZipWithCatalogHomeSourceOffersSaveAndLaunch() {
        long id = new DownloadStore(activity).enqueueNds(
                "https://github.com/owner/game/releases/download/v1/Game.zip",
                "Game.zip", null, NdsCatalog.HOME, true);
        shadowOf(shadowOf(manager).getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        button("Downloads").performClick();
        await("DS ZIP save action", () -> optionalButton("Save DS game") != null);
        assertNull(optionalButton("Open in melonDS"));
        assertTrue(new DownloadStore(activity).markDsSaved(id, Uri.parse("content://synthetic.documents/game")));
        button("Downloads").performClick();
        await("DS ZIP launch action", () -> optionalButton("Open in melonDS") != null);
        assertNull(optionalButton("Prepare for Azahar"));
    }

    @Test public void settingsOffersMelonDsAndExplainsWhenItIsMissing() {
        button("Settings").performClick();
        button("Open melonDS").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("melonDS is unavailable", shadowOf(dialog).getTitle().toString());
        assertEquals("Get melonDS", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertNull(shadowOf(activity).getNextStartedActivity());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        Intent website = shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, website.getAction());
        assertEquals(MelonDsLauncher.WEBSITE, website.getDataString());
    }

    @Test public void savedDsActionAppearsAfterExportAndPersistsAfterRotation() {
        long id = addCompletedDs();
        assertNull(optionalButton("Open in melonDS"));
        Uri saved = Uri.parse("content://synthetic.documents/saved-game");
        assertTrue(new DownloadStore(activity).markDsSaved(id, saved));
        // Refresh existing cards without rebuilding the activity or changing download status.
        button("Downloads").performClick();
        await("saved DS launch action", () -> optionalButton("Open in melonDS") != null);
        rotate();
        await("restored DS launch action", () -> optionalButton("Open in melonDS") != null);
        shadowOf(activity.getContentResolver()).registerInputStreamSupplier(saved,
                () -> new ByteArrayInputStream(new byte[] {1}));
        button("Open in melonDS").performClick();
        await("missing melonDS guidance", () -> ShadowAlertDialog.getLatestAlertDialog() != null);
        assertEquals("melonDS is unavailable", shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getTitle().toString());
        assertEquals(saved, new DownloadStore(activity).list().get(0).savedDsDestination);
        assertNull(optionalButton("Prepare for Azahar"));
    }

    @Test public void missingSavedDsFileOffersSaveAgainWithoutLaunching() {
        long id = addCompletedDs();
        Uri saved = Uri.parse("content://synthetic.documents/missing-game");
        assertTrue(new DownloadStore(activity).markDsSaved(id, saved));
        shadowOf(activity.getContentResolver()).registerInputStreamSupplier(saved,
                () -> new ByteArrayInputStream(new byte[0]));
        button("Downloads").performClick();
        await("saved DS action", () -> optionalButton("Open in melonDS") != null);
        button("Open in melonDS").performClick();
        await("unavailable saved game", () -> ShadowAlertDialog.getLatestAlertDialog() != null);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Saved DS game unavailable", shadowOf(dialog).getTitle().toString());
        assertNull(shadowOf(activity).getNextStartedActivity());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Intent.ACTION_CREATE_DOCUMENT,
                shadowOf(activity).getNextStartedActivityForResult().intent.getAction());
    }

    private long addCompletedDs() {
        long id = new DownloadStore(activity).enqueueNds(LINK, "Tiny Adventure.nds", null, titleUrl, true);
        shadowOf(shadowOf(manager).getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        button("Downloads").performClick();
        await("save DS action", () -> optionalButton("Save DS game") != null);
        return id;
    }

    private void openDs() {
        button("DS").performClick();
        await("DS results", () -> optionalButton("Download") != null);
    }

    private void rotate() {
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        configuration.screenWidthDp = 960;
        configuration.screenHeightDp = 540;
        controller.configurationChange(configuration).visible();
        bind();
    }

    private void bind() {
        activity = controller.get();
        root = activity.findViewById(android.R.id.content);
        manager = activity.getSystemService(DownloadManager.class);
    }

    private EditText input() { return find(root, EditText.class, view -> true); }
    private Button optionalButton(String name) {
        return find(root, Button.class, view -> view.isShown() && name.contentEquals(view.getText()));
    }
    private Button button(String name) {
        Button button = find(root, Button.class, view -> name.contentEquals(view.getText()));
        assertNotNull(name, button);
        return button;
    }

    private static final class DsClient extends NdsCatalogClient {
        NdsCatalog catalog = NdsCatalog.parseApiPage(FEED).catalog;
        final List<String> fetched = new CopyOnWriteArrayList<>();
        volatile Gate gate;
        volatile boolean fail;
        DsClient() throws Exception { }
        @Override public CatalogPage fetch(String url, String agent) throws IOException {
            fetched.add(url);
            try {
                if (gate != null) gate.waitForRelease();
                if (fail) throw new IOException("Synthetic unavailable feed");
                return catalog.page(url);
            } finally {
                if (gate != null) gate.finished.countDown();
            }
        }
    }

    private static final class MetadataClient extends CatalogClient {
        final List<String> fetched = new CopyOnWriteArrayList<>();
        final List<String> inspected = new CopyOnWriteArrayList<>();
        volatile Gate downloadGate;
        @Override public CatalogPage fetch(String url, String agent, String cookie) throws IOException {
            fetched.add(url);
            if (TITLE.equals(url) && downloadGate != null) {
                try {
                    downloadGate.waitForRelease();
                    throw new VerificationRequiredException("Synthetic old hShop challenge");
                } finally {
                    downloadGate.finished.countDown();
                }
            }
            return results(url, "3DS results");
        }
        @Override public DownloadInfo inspectNdsDownload(String url, String agent) {
            inspected.add(url);
            return new DownloadInfo(url, "Adventure.nds", "application/octet-stream", 1024);
        }
    }
}
