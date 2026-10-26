package dev.thorshelf.app;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.thorshelf.app.CatalogUiTestSupport.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Edition selection must never start a transfer before the user chooses a release. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class NdsCuratedUiTest {
    private static final String BASE = "https://archive.org/download/pack-roms-nintendo-ds-eu-usa-jap-rabbits-games/";
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private View root;
    private DsClient ds;
    private MetadataClient metadata;

    @Before public void launch() throws Exception {
        ds = new DsClient();
        metadata = new MetadataClient();
        MainActivity.ndsCatalogClientFactory = () -> ds;
        MainActivity.catalogClientFactory = () -> metadata;
        controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        bind();
        activity.getSystemService(DownloadManager.class).enqueue(
                new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
    }

    @After public void close() {
        controller.pause().stop().destroy();
        MainActivity.ndsCatalogClientFactory = NdsCatalogClient::new;
        MainActivity.catalogClientFactory = CatalogClient::new;
    }

    @Test public void choosesAnEditionBeforeQueueingAndKeepsItsZipAndRegionAfterRotation() {
        openDs();
        button("Choose edition").performClick();
        await("edition choices", () -> editionDownload("Europe") != null);
        assertTrue(metadata.inspected.isEmpty());
        assertTrue(new DownloadStore(activity).list().isEmpty());

        editionDownload("Europe").performClick();
        await("European ZIP queued", () -> new DownloadStore(activity).list().size() == 1);
        assertEquals(1, metadata.inspected.size());
        assertTrue(Uri.parse(metadata.inspected.get(0)).getPath().endsWith("Tiny Adventure (Europe) (En).zip"));
        DownloadStore.Entry entry = new DownloadStore(activity).list().get(0);
        assertTrue(entry.name.endsWith(".zip"));
        assertTrue(entry.displayName.contains("Europe"));
        assertTrue(NdsCatalog.isDetailUrl(entry.sourcePage));
        assertFalse(NdsCatalog.isEditionSelectionUrl(entry.sourcePage));
        shadowOf(shadowOf(activity.getSystemService(DownloadManager.class)).getRequest(entry.id))
                .setStatus(DownloadManager.STATUS_PENDING);

        rotate();
        await("edition choices restored", () -> editionDownload("Europe") != null);
        editionDownload("Europe").performClick();
        await("existing download opened", () -> button("Downloads").isSelected());
        assertEquals(1, new DownloadStore(activity).list().size());
        assertEquals(1, metadata.inspected.size());
    }

    @Test public void languageAndRegionApplyToEditionChoicesAndBackKeepsTheSearch() {
        openDs();
        choose("Language:", "English");
        await("English game group", () -> optionalButton("Choose edition") != null);
        button("Choose edition").performClick();
        await("English editions", () -> editionDownload("Europe") != null);
        assertNull(editionDownload("France"));
        assertNotNull(editionDownload("USA"));
        choose("Region:", "Europe");
        await("European edition only", () -> editionDownload("Europe") != null
                && editionDownload("USA") == null);
        assertNull(editionDownload("France"));
        assertTrue(metadata.inspected.isEmpty());
        activity.onBackPressed();
        await("filtered search restored", () -> optionalButton("Download") != null);
        assertEquals("Tiny Adventure", find(root, EditText.class, view -> true).getText().toString());
        assertEquals("Language: English ▾", filter("Language:").getText().toString());
        assertEquals("Region: Europe ▾", filter("Region:").getText().toString());
        button("Download").performClick();
        await("single matching edition queued", () -> new DownloadStore(activity).list().size() == 1);
        assertTrue(new DownloadStore(activity).list().get(0).displayName.contains("Europe"));
    }

    private void openDs() {
        find(root, EditText.class, view -> true).setText("Tiny Adventure");
        button("DS").performClick();
        await("grouped DS result", () -> optionalButton("Choose edition") != null);
    }

    private Button editionDownload(String edition) {
        return find(root, Button.class, view -> view.isShown() && view.getTag() instanceof String
                && ((String) view.getTag()).contains("(" + edition + ")"));
    }

    private Button filter(String prefix) {
        return find(root, Button.class, view -> view.isShown() && view.getText().toString().startsWith(prefix));
    }

    private void choose(String prefix, String label) {
        filter(prefix).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        for (int index = 0; index < dialog.getListView().getAdapter().getCount(); index++) {
            if (label.equals(dialog.getListView().getAdapter().getItem(index).toString())) {
                shadowOf(dialog).clickOnItem(index);
                shadowOf(Looper.getMainLooper()).idle();
                return;
            }
        }
        fail("Missing filter option " + label);
    }

    private void rotate() {
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.orientation = Configuration.ORIENTATION_LANDSCAPE;
        config.screenWidthDp = 960;
        config.screenHeightDp = 540;
        controller.configurationChange(config).visible();
        bind();
    }

    private void bind() { activity = controller.get(); root = activity.findViewById(android.R.id.content); }
    private Button optionalButton(String text) {
        return find(root, Button.class, view -> view.isShown() && text.contentEquals(view.getText()));
    }
    private Button button(String text) { Button result = optionalButton(text); assertNotNull(text, result); return result; }

    private static final class DsClient extends NdsCatalogClient {
        final NdsCatalog catalog;
        DsClient() throws Exception {
            JSONObject meta = new JSONObject().put("id", "nds").put("ext", "zip").put("arch", "zip")
                    .put("out", "nds").put("checksum", "md5").put("download", BASE.substring(0, BASE.length() - 1))
                    .put("source", "https://archive.org/details/pack-roms-nintendo-ds-eu-usa-jap-rabbits-games")
                    .put("coverSource", "https://github.com/libretro-thumbnails/Nintendo_-_Nintendo_DS")
                    .put("coverBase", "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Nintendo_DS/master/Named_Boxarts/")
                    .put("total", 3);
            JSONArray rows = new JSONArray();
            for (String region : new String[]{"USA", "Europe", "France"}) {
                String language = region.equals("France") ? "Fr" : "En";
                String name = "Tiny Adventure (" + region + ") (" + language + ")";
                rows.put(new JSONObject().put("n", name).put("t", "Tiny Adventure").put("r", region)
                        .put("l", language).put("s", 1024).put("m", String.format("%032d", rows.length() + 1))
                        .put("h", String.format("%040d", rows.length() + 1))
                        .put("c", JSONObject.NULL));
            }
            catalog = NdsCatalog.parseDsiDrop(new JSONObject().put("meta", meta).put("roms", rows).toString());
        }
        @Override public CatalogPage fetch(String url, String userAgent) { return catalog.page(url); }
    }

    private static final class MetadataClient extends CatalogClient {
        final List<String> inspected = new CopyOnWriteArrayList<>();
        @Override public DownloadInfo inspectNdsDownload(String url, String userAgent) {
            inspected.add(url);
            return new DownloadInfo(url, Uri.parse(url).getLastPathSegment(), "application/zip", 1024);
        }
    }
}
