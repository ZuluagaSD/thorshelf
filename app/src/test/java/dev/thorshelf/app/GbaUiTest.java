package dev.thorshelf.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
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
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static dev.thorshelf.app.CatalogUiTestSupport.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real bundled GBA metadata, synthetic network responses and document providers; no game payloads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class GbaUiTest {
    private static final String SHA1 = "f3ae088181bf583e55daF962a92bb46f4f1d07b7".toLowerCase(java.util.Locale.ROOT);
    private static final String DETAIL = "gba://catalog/title/" + SHA1;
    private static final int ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private DownloadManager manager;
    private MetadataClient metadata;
    private GbaCatalog.Rom rom;

    @Before public void launch() throws Exception {
        rom = GbaCatalog.lookup(DETAIL);
        metadata = new MetadataClient();
        MainActivity.catalogClientFactory = () -> metadata;
        GameArtwork.clearRemoteCacheForTests();
        GameArtwork.clientFactory = () -> new ArtworkClient() {
            @Override public Bitmap fetch(String url, int pixels) { return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888); }
        };
        controller = Robolectric.buildActivity(MainActivity.class).create().start().visible();
        bind();
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
    }

    private void bind() {
        activity = controller.get();
        manager = activity.getSystemService(DownloadManager.class);
        ReflectionHelpers.setField(activity, "queryingDownloads", true);
        if (Build.VERSION.SDK_INT >= 33) shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    }

    @After public void close() throws Exception {
        if (metadata.gate != null) metadata.gate.release.countDown();
        ExecutorService worker = ReflectionHelpers.getField(activity, "downloadQueries");
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
        controller.stop().destroy();
        MainActivity.catalogClientFactory = CatalogClient::new;
        MainActivity.gbaCatalogClientFactory = GbaCatalogClient::new;
        GameArtwork.clientFactory = ArtworkClient::new;
        GameArtwork.clearRemoteCacheForTests();
        activity.getSharedPreferences("export_status", 0).edit().clear().commit();
        ReflectionHelpers.setStaticField(ExportService.class, "active", false);
        ReflectionHelpers.setStaticField(ExportService.class, "activeToken", null);
    }

    @Test public void gbaBrowseSearchAndFiltersUseBundledCatalog() {
        button("GBA").performClick();
        await("GBA catalog", () -> shown("GBA catalog: No-Intro / libretro"));
        assertTrue(button("GBA").isSelected());
        assertEquals("Search GBA games", input().getHint().toString());
        input().setText("Metroid Fusion"); button("Search").performClick();
        await("GBA search", () -> shown("Metroid Fusion"));
        ReflectionHelpers.callInstanceMethod(activity, "selectFilter",
                ReflectionHelpers.ClassParameter.from(String.class, "en"), ReflectionHelpers.ClassParameter.from(boolean.class, false));
        await("English GBA filter", () -> "en".equals(CatalogFilters.selectedLanguage(source())) && shown("Metroid Fusion"));
        ReflectionHelpers.callInstanceMethod(activity, "selectFilter",
                ReflectionHelpers.ClassParameter.from(String.class, "europe"), ReflectionHelpers.ClassParameter.from(boolean.class, true));
        await("European GBA filter", () -> "europe".equals(CatalogFilters.selectedRegion(source())) && shown("Metroid Fusion"));
        assertEquals(0, metadata.hshopFetches);
        assertTrue(metadata.inspected.isEmpty());
    }

    @Test public void queuePreservesIdentityAcrossRotationAndPreventsActiveDuplicates() {
        detail(); button("Download").performClick();
        await("GBA enqueue", () -> store().list().size() == 1);
        DownloadStore.Entry entry = store().list().get(0);
        assertEquals(GamePlatform.GBA, entry.platform);
        assertEquals(SHA1, entry.expectedSha1);
        assertEquals(rom.sizeBytes, entry.expectedSize);
        assertEquals(DETAIL, entry.sourcePage);
        assertTrue(entry.name.endsWith(".gba"));
        assertEquals(DownloadManager.Request.NETWORK_WIFI, shadowOf(shadowOf(manager).getRequest(entry.id)).getAllowedNetworkTypes());
        shadowOf(shadowOf(manager).getRequest(entry.id)).setStatus(DownloadManager.STATUS_PENDING);
        rotate(); detail(); button("Download").performClick();
        await("duplicate redirected to Downloads", () -> button("Downloads").isSelected());
        assertEquals(1, store().list().size());
        assertEquals(1, metadata.inspected.size());
        assertEquals(0, metadata.hshopFetches);
    }

    @Test public void mismatchedHeaderSizeNeverEnqueues() {
        metadata.wrongSize = true;
        detail(); button("Download").performClick();
        await("GBA size rejected", () -> ShadowToast.getTextOfLatestToast() != null
                && ShadowToast.getTextOfLatestToast().contains("does not match the catalog"));
        assertTrue(store().list().isEmpty());
        assertTrue(button("Download").isEnabled());
    }

    @Test public void switchingPlatformsIgnoresPendingGbaDownloadResponse() {
        detail(); metadata.gate = new Gate(true); button("Download").performClick();
        await("blocked GBA inspection", () -> metadata.gate.entered.getCount() == 0);
        input().setText(""); button("3DS").performClick();
        metadata.gate.release.countDown();
        await("GBA inspection returned", () -> metadata.gate.finished.getCount() == 0);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(button("3DS").isSelected());
        assertTrue(store().list().isEmpty());
    }

    @Test public void gbaSavePickerSurvivesRotationAndStartsVerifiedExport() {
        long id = completed(); button("Save GBA game").performClick();
        ShadowActivity.IntentForResult picker = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(47, picker.requestCode);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.getAction());
        assertTrue(picker.intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".gba"));
        rotate();
        Uri destination = Uri.parse("content://test.gba/new-file");
        activity.onActivityResult(47, MainActivity.RESULT_OK, new Intent().setData(destination));
        Intent export = shadowOf(activity.getApplication()).getNextStartedService();
        assertNotNull(export);
        assertEquals(id, export.getLongExtra("download_id", -1));
        assertEquals("gba", export.getStringExtra("rom_platform"));
        assertFalse(export.getBooleanExtra("nds", false));
        assertNull(store().list().get(0).savedGbaDestination);
        assertFalse(PreparationService.isRunning(activity));
    }

    @Test public void cancellingGbaSaveKeepsDownloadAndNoVerifiedMarker() {
        long id = completed(); button("Save GBA game").performClick();
        shadowOf(activity).getNextStartedActivityForResult();
        activity.onActivityResult(47, MainActivity.RESULT_CANCELED, null);
        assertNotNull(shadowOf(manager).getRequest(id));
        assertNull(store().list().get(0).savedGbaDestination);
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void gbaFolderIsIndependentAndReusedForSaving() {
        NdsFolderUiTest.FolderProvider documents = new NdsFolderUiTest.FolderProvider();
        ProviderInfo info = new ProviderInfo(); info.authority = NdsFolderUiTest.FolderProvider.AUTHORITY;
        info.exported = true; info.grantUriPermissions = true;
        documents.attachInfo(activity.getApplication(), info);
        ShadowContentResolver.registerProviderInternal(info.authority, documents);
        Uri ds = documents.tree("ds"), threeDs = documents.tree("3ds"), gba = documents.tree("gba");
        activity.getSharedPreferences("settings", 0).edit()
                .putString(NdsGamesFolder.PREFERENCE, ds.toString())
                .putString(PreparationFolder.PREFERENCE, threeDs.toString()).commit();
        button("Settings").performClick();
        TextView heading = find(root(), TextView.class, v -> "Game Boy Advance games".contentEquals(v.getText()));
        Button choose = find((ViewGroup) heading.getParent(), Button.class, v -> "Choose games folder".contentEquals(v.getText()));
        choose.performClick();
        ShadowActivity.IntentForResult picker = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(48, picker.requestCode);
        assertNull(picker.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        activity.onActivityResult(48, MainActivity.RESULT_OK, new Intent().setData(gba).addFlags(ACCESS));
        assertEquals(gba, GbaGamesFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertEquals(ds, NdsGamesFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertEquals(threeDs, PreparationFolder.saved(activity.getSharedPreferences("settings", 0)));
        completed(); button("Save GBA game").performClick();
        await("folder GBA export", () -> shadowOf(activity.getApplication()).peekNextStartedService() != null);
        assertEquals("gba", shadowOf(activity.getApplication()).getNextStartedService().getStringExtra("rom_platform"));
        assertTrue(documents.createdName.endsWith(".gba"));
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(gba, "gba"), documents.createdParent);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void gbaSelectionAndCompanionLabelSurviveRotation() {
        detail(); rotate();
        assertTrue(button("GBA").isSelected());
        String label = ReflectionHelpers.callInstanceMethod(activity, "companionPageLabel");
        assertTrue(label.startsWith("Game Boy Advance"));
        assertEquals("gba", activity.getSharedPreferences("settings", 0).getString("platform", ""));
    }

    private void detail() {
        ReflectionHelpers.callInstanceMethod(activity, "loadCatalog", ReflectionHelpers.ClassParameter.from(String.class, DETAIL));
        await("GBA detail", () -> optionalButton("Download") != null && shown("Pokemon - Emerald"));
    }
    private long completed() {
        long id = store().enqueueGba(rom.downloadUrl, rom.title + ".gba", null, DETAIL, false, "", rom.sha1, rom.sizeBytes);
        shadowOf(shadowOf(manager).getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        ReflectionHelpers.callInstanceMethod(activity, "renderDownloads", ReflectionHelpers.ClassParameter.from(List.class, store().list()));
        button("Downloads").performClick(); return id;
    }
    private void rotate() {
        Configuration next = new Configuration(activity.getResources().getConfiguration());
        next.orientation = Configuration.ORIENTATION_LANDSCAPE; next.screenWidthDp = 960; next.screenHeightDp = 540;
        controller.configurationChange(next).visible(); bind();
    }
    private DownloadStore store() { return new DownloadStore(activity); }
    private View root() { return activity.findViewById(android.R.id.content); }
    private String source() { return ReflectionHelpers.getField(activity, "sourcePage"); }
    private EditText input() { return find(root(), EditText.class, v -> true); }
    private boolean shown(String text) { return find(root(), TextView.class, v -> v.isShown() && v.getText().toString().contains(text)) != null; }
    private Button optionalButton(String text) { return find(root(), Button.class, v -> v.isShown() && text.contentEquals(v.getText())); }
    private Button button(String text) {
        Button result = find(root(), Button.class, v -> text.contentEquals(v.getText())); assertNotNull(text, result); return result;
    }
    private final class MetadataClient extends CatalogClient {
        final List<String> inspected = new CopyOnWriteArrayList<>();
        volatile Gate gate; volatile boolean wrongSize; int hshopFetches;
        @Override public CatalogPage fetch(String url, String agent, String cookie) { hshopFetches++; return results(url, "3DS results"); }
        @Override public DownloadInfo inspectGbaDownload(String url, String agent) throws IOException {
            inspected.add(url);
            try { if (gate != null) gate.waitForRelease();
                return new DownloadInfo(url, rom.filename, "application/octet-stream", wrongSize ? 7 : rom.sizeBytes);
            } finally { if (gate != null) gate.finished.countDown(); }
        }
    }
}
