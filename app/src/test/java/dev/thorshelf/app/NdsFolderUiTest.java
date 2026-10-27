package dev.thorshelf.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static dev.thorshelf.app.CatalogUiTestSupport.await;
import static dev.thorshelf.app.CatalogUiTestSupport.find;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Reusable DS destinations stay independent of the automatic 3DS games folder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class NdsFolderUiTest {
    private static final int ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private DownloadManager manager;
    private DownloadStore store;
    private FolderProvider documents;

    @Before public void launch() {
        controller = Robolectric.buildActivity(MainActivity.class).create().start().visible();
        activity = controller.get();
        ReflectionHelpers.setField(activity, "queryingDownloads", true);
        if (Build.VERSION.SDK_INT >= 33) shadowOf(activity.getApplication())
                .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        manager = activity.getSystemService(DownloadManager.class);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        store = new DownloadStore(activity);
        documents = new FolderProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = FolderProvider.AUTHORITY;
        info.exported = true;
        info.grantUriPermissions = true;
        documents.attachInfo(activity.getApplication(), info);
        ShadowContentResolver.registerProviderInternal(info.authority, documents);
        showSettings();
    }

    @After public void close() throws Exception {
        drainDownloads();
        controller.stop().destroy();
        // Inspect queued export intents without executing the export service's file-copy worker.
        activity.getSharedPreferences("export_status", 0).edit().clear().commit();
        ReflectionHelpers.setStaticField(ExportService.class, "active", false);
        ReflectionHelpers.setStaticField(ExportService.class, "activeToken", null);
    }

    @Test public void dsSettingsOffersOnlyFolderSelectionAndMelonDs() {
        assertEquals(Arrays.asList("Choose games folder", "Open melonDS"), buttonLabels(dsSection()));
        for (String oldLink : new String[]{"DS catalog: DSiDrop", "DS cover art: libretro", "DS language metadata: GameTDB"}) {
            assertNull(find(settings(), Button.class, button -> oldLink.contentEquals(button.getText())));
        }
    }

    @Test public void dsFolderPickerRequestsPersistentAccessWithoutUsingThreeDsDestination() {
        remember(PreparationFolder.PREFERENCE, "3ds-games");

        dsButton("Choose games folder").performClick();

        ShadowActivity.IntentForResult picker = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(46, picker.requestCode);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.intent.getAction());
        assertEquals(ACCESS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, picker.intent.getFlags());
        assertNull(picker.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        assertNull(picker.intent.getType());
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void selectedDsFolderSurvivesRotationAndLeavesThreeDsFolderUnchanged() {
        Uri threeDs = remember(PreparationFolder.PREFERENCE, "3ds-games");
        dsButton("Choose games folder").performClick();
        assertEquals(46, shadowOf(activity).getNextStartedActivityForResult().requestCode);
        Uri ds = documents.tree("ds-games");

        selectDs(ds, ACCESS);
        rotate();

        assertEquals(ds, NdsGamesFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertEquals(threeDs, PreparationFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertTrue(PreparationFolder.hasPersistedAccess(activity, ds));
        assertTrue(PreparationFolder.hasPersistedAccess(activity, threeDs));
        assertNotNull(dsButton("Change games folder"));
        assertNotNull(find(dsSection(), TextView.class,
                text -> PreparationFolder.label(ds).contentEquals(text.getText())));
        assertEquals(Arrays.asList("Change games folder", "Open melonDS"), buttonLabels(dsSection()));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void changingDsFolderStartsAtItsOwnPreviousFolderAndCancelKeepsBothSelections() {
        Uri threeDs = remember(PreparationFolder.PREFERENCE, "3ds-games");
        Uri ds = remember(NdsGamesFolder.PREFERENCE, "ds-games");
        showSettings();
        dsButton("Change games folder").performClick();
        ShadowActivity.IntentForResult picker = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(46, picker.requestCode);
        assertEquals(ds, picker.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));

        activity.onActivityResult(46, MainActivity.RESULT_CANCELED, null);

        assertEquals(ds, NdsGamesFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertEquals(threeDs, PreparationFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertTrue(PreparationFolder.hasPersistedAccess(activity, ds));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void cancelledFirstDsFolderSelectionKeepsFolderUnset() {
        dsButton("Choose games folder").performClick();
        assertEquals(46, shadowOf(activity).getNextStartedActivityForResult().requestCode);

        activity.onActivityResult(46, MainActivity.RESULT_CANCELED, null);

        assertNull(NdsGamesFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertNotNull(dsButton("Choose games folder"));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void readOnlyGrantCannotReplaceRememberedDsFolder() {
        Uri previous = remember(NdsGamesFolder.PREFERENCE, "ds-games");
        showSettings();
        dsButton("Change games folder").performClick();
        shadowOf(activity).getNextStartedActivityForResult();
        Uri readOnly = documents.tree("read-only");

        selectDs(readOnly, Intent.FLAG_GRANT_READ_URI_PERMISSION);

        assertEquals(previous, NdsGamesFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertTrue(PreparationFolder.hasPersistedAccess(activity, previous));
        assertFalse(PreparationFolder.hasPersistedAccess(activity, readOnly));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void dsFolderPickerResultAfterRotationRetainsSelection() {
        dsButton("Choose games folder").performClick();
        assertEquals(46, shadowOf(activity).getNextStartedActivityForResult().requestCode);
        rotate();
        Uri folder = documents.tree("ds-games");

        selectDs(folder, ACCESS);

        assertEquals(folder, NdsGamesFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertNotNull(dsButton("Change games folder"));
    }

    @Test public void saveDsUsesRememberedFolderWithoutAnotherPickerOrThreeDsPreparation() {
        Uri threeDs = remember(PreparationFolder.PREFERENCE, "3ds-games");
        Uri ds = remember(NdsGamesFolder.PREFERENCE, "ds-games");
        long id = addDs("Tiny Adventure.nds");

        button("Save DS game").performClick();

        Intent export = awaitExport();
        assertEquals(id, export.getLongExtra("download_id", -1));
        assertTrue(export.getBooleanExtra("nds", false));
        assertEquals("Tiny Adventure.nds", export.getStringExtra("name"));
        assertEquals(documents.createdUri.toString(), export.getStringExtra("destination"));
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(ds, DocumentsContract.getTreeDocumentId(ds)),
                documents.createdParent);
        assertEquals(1, documents.createCalls);
        assertEquals("Tiny Adventure.nds", documents.createdName);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertFalse(PreparationService.isRunning(activity));
        assertEquals(threeDs, PreparationFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertNotNull(shadowOf(manager).getRequest(id));
    }

    @Test public void savedDsFolderAlsoRoutesZipExportsToAnNdsDocument() {
        remember(NdsGamesFolder.PREFERENCE, "ds-games");
        long id = addDs("Tiny Adventure.zip");

        button("Save DS game").performClick();

        Intent export = awaitExport();
        assertEquals(id, export.getLongExtra("download_id", -1));
        assertEquals("Tiny Adventure.nds", export.getStringExtra("name"));
        assertEquals("Tiny Adventure.nds", documents.createdName);
        assertTrue(export.getBooleanExtra("nds", false));
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void revokedDsFolderAccessCanBeRestoredAndPendingSaveResumes() {
        Uri ds = remember(NdsGamesFolder.PREFERENCE, "ds-games");
        activity.getContentResolver().releasePersistableUriPermission(ds, ACCESS);
        long id = addDs("Tiny Adventure.nds");
        button("Save DS game").performClick();
        await("restoring DS folder access", () -> shadowOf(activity).peekNextStartedActivityForResult() != null);
        ShadowActivity.IntentForResult picker = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(46, picker.requestCode);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.intent.getAction());
        assertEquals(ds, picker.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        assertEquals(ds, NdsGamesFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertEquals(0, documents.createCalls);
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());

        selectDs(ds, ACCESS);

        Intent export = awaitExport();
        assertEquals(id, export.getLongExtra("download_id", -1));
        assertTrue(PreparationFolder.hasPersistedAccess(activity, ds));
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(ds, DocumentsContract.getTreeDocumentId(ds)),
                documents.createdParent);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void cancellingRevokedFolderRecoveryDoesNotLeaveAPendingSave() {
        Uri ds = remember(NdsGamesFolder.PREFERENCE, "ds-games");
        activity.getContentResolver().releasePersistableUriPermission(ds, ACCESS);
        addDs("Tiny Adventure.nds");
        button("Save DS game").performClick();
        await("restoring DS folder access", () -> shadowOf(activity).peekNextStartedActivityForResult() != null);
        assertEquals(46, shadowOf(activity).getNextStartedActivityForResult().requestCode);

        activity.onActivityResult(46, MainActivity.RESULT_CANCELED, null);
        showSettings();
        dsButton("Change games folder").performClick();
        assertEquals(46, shadowOf(activity).getNextStartedActivityForResult().requestCode);
        Uri replacement = documents.tree("replacement-ds");
        selectDs(replacement, ACCESS);

        assertEquals(replacement, NdsGamesFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertEquals(0, documents.createCalls);
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void disconnectedDsFolderKeepsItsSelectionAndCanBeRetriedAfterReconnect() {
        Uri ds = remember(NdsGamesFolder.PREFERENCE, "disconnected-ds");
        documents.unavailable = true;
        long id = addDs("Tiny Adventure.nds");

        button("Save DS game").performClick();

        await("DS destination failure", () -> ShadowToast.getTextOfLatestToast() != null
                && ShadowToast.getTextOfLatestToast().contains("Could not save to your DS games folder"));
        assertEquals(ds, NdsGamesFolder.saved(activity.getSharedPreferences("settings", 0)));
        assertEquals(0, documents.createCalls);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());

        documents.unavailable = false;
        button("Save DS game").performClick();

        Intent export = awaitExport();
        assertEquals(id, export.getLongExtra("download_id", -1));
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(ds, DocumentsContract.getTreeDocumentId(ds)),
                documents.createdParent);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test @Config(sdk = 35)
    public void notificationPermissionResponseKeepsTheRememberedDsFolderRoute() {
        Uri ds = remember(NdsGamesFolder.PREFERENCE, "ds-games");
        addDs("Tiny Adventure.nds");
        shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        button("Save DS game").performClick();
        ShadowActivity.IntentForResult permission = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(45, permission.requestCode);
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", permission.intent.getAction());
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        activity.onRequestPermissionsResult(45, new String[]{Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_GRANTED});

        awaitExport();
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(ds, DocumentsContract.getTreeDocumentId(ds)),
                documents.createdParent);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    private long addDs(String name) {
        long id = store.enqueueNds("https://github.com/owner/game/releases/download/v1/" + name.replace(" ", "%20"),
                name, null, NdsCatalog.HOME, false);
        shadowOf(shadowOf(manager).getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        ReflectionHelpers.callInstanceMethod(activity, "renderDownloads",
                ReflectionHelpers.ClassParameter.from(List.class, store.list()));
        ReflectionHelpers.callInstanceMethod(activity, "showTab", ReflectionHelpers.ClassParameter.from(int.class, 1));
        return id;
    }

    private Intent awaitExport() {
        await("DS export in remembered folder", () -> shadowOf(activity.getApplication()).peekNextStartedService() != null);
        Intent export = shadowOf(activity.getApplication()).getNextStartedService();
        assertEquals(ExportService.class.getName(), export.getComponent().getClassName());
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        return export;
    }

    private Uri remember(String preference, String id) {
        Uri folder = documents.tree(id);
        activity.getContentResolver().takePersistableUriPermission(folder, ACCESS);
        activity.getSharedPreferences("settings", 0).edit().putString(preference, folder.toString()).commit();
        return folder;
    }

    private void selectDs(Uri folder, int flags) {
        activity.onActivityResult(46, MainActivity.RESULT_OK, new Intent().setData(folder).addFlags(flags));
    }

    private void showSettings() {
        ReflectionHelpers.callInstanceMethod(activity, "showTab", ReflectionHelpers.ClassParameter.from(int.class, 2));
    }

    private void rotate() {
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        configuration.screenWidthDp = 960;
        configuration.screenHeightDp = 540;
        controller.configurationChange(configuration).visible();
        activity = controller.get();
        ReflectionHelpers.setField(activity, "queryingDownloads", true);
    }

    private void drainDownloads() throws Exception {
        ExecutorService worker = ReflectionHelpers.getField(activity, "downloadQueries");
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private View settings() { return ReflectionHelpers.getField(activity, "settingsPanel"); }

    private ViewGroup dsSection() {
        TextView heading = find(settings(), TextView.class, text -> "Nintendo DS games".contentEquals(text.getText()));
        assertNotNull("DS settings section", heading);
        return (ViewGroup) heading.getParent();
    }

    private Button dsButton(String label) {
        Button found = find(dsSection(), Button.class, button -> label.contentEquals(button.getText()));
        assertNotNull("DS settings action " + label, found);
        return found;
    }

    private Button button(String label) {
        Button found = find(activity.findViewById(android.R.id.content), Button.class,
                button -> label.contentEquals(button.getText()));
        assertNotNull(label, found);
        return found;
    }

    private static List<String> buttonLabels(View view) {
        List<String> labels = new ArrayList<>();
        if (view instanceof Button) labels.add(((Button) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup children = (ViewGroup) view;
            for (int i = 0; i < children.getChildCount(); i++) labels.addAll(buttonLabels(children.getChildAt(i)));
        }
        return labels;
    }

    /** Implements just SAF folder checks and empty destination creation; no ROM bytes are copied. */
    public static final class FolderProvider extends ContentProvider {
        static final String AUTHORITY = "dev.thorshelf.test.ds-folder-ui";
        volatile boolean unavailable;
        volatile Uri createdUri;
        volatile Uri createdParent;
        volatile String createdName;
        volatile int createCalls;

        Uri tree(String id) { return DocumentsContract.buildTreeDocumentUri(AUTHORITY, id); }

        @Override public boolean onCreate() { return true; }

        @Override public String getType(Uri uri) {
            return uri.equals(createdUri) ? "application/octet-stream" : DocumentsContract.Document.MIME_TYPE_DIR;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            if (unavailable) throw new SecurityException("Synthetic disconnected storage");
            String[] columns = projection == null ? new String[]{OpenableColumns.SIZE} : projection;
            MatrixCursor result = new MatrixCursor(columns);
            if ("children".equals(uri.getLastPathSegment())) return result;
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case DocumentsContract.Document.COLUMN_MIME_TYPE: values[i] = getType(uri); break;
                    case DocumentsContract.Document.COLUMN_FLAGS:
                        values[i] = uri.equals(createdUri) ? DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                                | DocumentsContract.Document.FLAG_SUPPORTS_DELETE : DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
                        break;
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID: values[i] = DocumentsContract.getDocumentId(uri); break;
                    case OpenableColumns.SIZE: values[i] = 0; break;
                    case OpenableColumns.DISPLAY_NAME: values[i] = uri.equals(createdUri) ? createdName : "DS games"; break;
                }
            }
            result.addRow(values);
            return result;
        }

        @Override public Bundle call(String method, String arg, Bundle extras) {
            if (!"android:createDocument".equals(method)) throw new UnsupportedOperationException(method);
            if (unavailable) throw new SecurityException("Synthetic disconnected storage");
            createdParent = extras.getParcelable("uri");
            createdName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            createCalls++;
            createdUri = DocumentsContract.buildDocumentUriUsingTree(createdParent,
                    DocumentsContract.getTreeDocumentId(createdParent) + "/created-" + createCalls);
            shadowOf(getContext().getContentResolver()).registerInputStreamSupplier(createdUri,
                    () -> new ByteArrayInputStream(new byte[0]));
            Bundle result = new Bundle();
            result.putParcelable("uri", createdUri);
            return result;
        }

        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
    }
}
