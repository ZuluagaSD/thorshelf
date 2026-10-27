package dev.thorshelf.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Completed CIA downloads save automatically; the UI only handles destination, recovery and launch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class PreparationUiTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private DownloadStore store;
    private DownloadManager manager;
    private FolderProvider documents;
    private static final int FOLDER_ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    @Before public void launch() {
        controller = Robolectric.buildActivity(MainActivity.class).create().start().visible();
        activity = controller.get();
        // Test snapshots explicitly, without unrelated periodic refreshes or running the service worker.
        ReflectionHelpers.setField(activity, "queryingDownloads", true);
        store = new DownloadStore(activity);
        manager = activity.getSystemService(DownloadManager.class);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        documents = new FolderProvider();
        ProviderInfo provider = new ProviderInfo();
        provider.authority = FolderProvider.AUTHORITY;
        provider.exported = true;
        provider.grantUriPermissions = true;
        documents.attachInfo(activity.getApplication(), provider);
        ShadowContentResolver.registerProviderInternal(provider.authority, documents);
    }

    @After public void close() throws Exception {
        drainDownloads();
        controller.stop().destroy();
        activity.getSharedPreferences("preparation_status", 0).edit().clear().commit();
        ReflectionHelpers.setStaticField(PreparationService.class, "active", false);
        ReflectionHelpers.setStaticField(PreparationService.class, "activeToken", null);
        ReflectionHelpers.setStaticField(PreparationService.class, "cancelledToken", null);
    }

    @Test public void completedDownloadAsksOnlyForItsMissingGamesFolder() {
        long id = add("Synthetic.CIA", DownloadManager.STATUS_SUCCESSFUL);
        render();

        assertTrue(find(card(id), "Choose games folder").isShown());
        assertNoManualPreparationActions();
        assertNull(find(activity.findViewById(android.R.id.content), "Catalog page"));
        assertNull(find(activity.findViewById(android.R.id.content), "Save a copy"));
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void gamesFolderActionOpensPersistentFolderPickerWithoutConfirmation() {
        long id = add("Synthetic.CIA", DownloadManager.STATUS_SUCCESSFUL);
        render();
        find(card(id), "Choose games folder").performClick();

        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(44, launch.requestCode);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launch.intent.getAction());
        assertNull(launch.intent.getStringExtra(Intent.EXTRA_TITLE));
        assertNull(launch.intent.getType());
        assertEquals(FOLDER_ACCESS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, launch.intent.getFlags());
        assertNull(launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        assertNoConfirmation();
        assertEquals(1, store.list().size());
    }

    @Test public void selectedFolderIsRememberedAndStartsCompletedDownloadAutomatically() throws Exception {
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        find(card(id), "Choose games folder").performClick();
        assertEquals(44, shadowOf(activity).getNextStartedActivityForResult().requestCode);
        Uri folder = documents.tree("games");

        selectFolder(folder, FOLDER_ACCESS);

        assertPreparationStartedIn(folder, id);
        assertEquals(folder.toString(), savedFolder());
        assertTrue(hasPersistentAccess(folder));
        assertNoConfirmation();
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void refreshSavesCompletedDownloadWithoutAnyPreparationAction() throws Exception {
        Uri folder = rememberFolder("games");
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);

        refresh();

        assertPreparationStartedIn(folder, id);
        render();
        assertTrue(find(card(id), "Cancel saving").isShown());
        assertNoManualPreparationActions();
        assertNoConfirmation();
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void savedDownloadUpdatesItsCardWithoutAnotherPreparationOrDeleteAction() throws Exception {
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        ViewGroup originalCard = card(id);

        markSaved(id);
        render();

        assertSame(originalCard, card(id));
        assertTrue(find(card(id), "Open Azahar").isShown());
        assertNoManualPreparationActions();
        assertNoVisibleAction(card(id), "Choose games folder");
        assertNoVisibleAction(card(id), "Retry saving");
    }

    @Test public void savedDownloadAndLaunchActionSurviveSourceRemovalAndActivityRecreation() throws Exception {
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        markSaved(id);
        manager.remove(id);
        recreate();
        render();

        assertTrue(store.list().get(0).isComplete());
        assertTrue(find(card(id), "Open Azahar").isShown());
        assertNoManualPreparationActions();
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void activeSavingCanBeCancelledFromItsCardWithoutNotificationPermission() throws Exception {
        Uri folder = rememberFolder("games");
        long preparing = add("Preparing game.cia", DownloadManager.STATUS_SUCCESSFUL);
        refresh();
        Intent started = assertPreparationStartedIn(folder, preparing);
        long other = add("Another game.cia", DownloadManager.STATUS_SUCCESSFUL);
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
            assertEquals(PackageManager.PERMISSION_DENIED,
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        }
        String message = "Decrypting package… 42%";
        activity.getSharedPreferences("preparation_status", 0).edit()
                .putString("status", message).putInt("progress", 42).commit();
        render();

        Button cancel = find(card(preparing), "Cancel saving");
        assertNotNull(cancel);
        assertTrue(cancel.isShown());
        assertTrue(cancel.isEnabled());
        assertEquals(message, this.<TextView>cardField(preparing, "status").getText().toString());
        ProgressBar progress = cardField(preparing, "progress");
        assertTrue(progress.isShown());
        assertFalse(progress.isIndeterminate());
        assertEquals(420, progress.getProgress());
        assertNoVisibleAction(card(other), "Cancel saving");
        assertFalse(this.<TextView>cardField(other, "status").getText().toString().contains(message));

        cancel.performClick();

        Intent cancellation = shadowOf(activity.getApplication()).getNextStartedService();
        assertNotNull(cancellation);
        assertEquals("dev.thorshelf.app.CANCEL_PREPARATION", cancellation.getAction());
        assertEquals(started.getStringExtra("token"), cancellation.getStringExtra("token"));
        assertEquals(started.getStringExtra("token"),
                ReflectionHelpers.getStaticField(PreparationService.class, "cancelledToken"));
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertEquals(2, store.list().size());
    }

    @Test public void failedSavingStaysOnItsOwnCardAfterActivityRecreation() throws Exception {
        rememberFolder("games");
        long failed = add("Failed save.cia", DownloadManager.STATUS_SUCCESSFUL);
        long other = add("Untouched game.cia", DownloadManager.STATUS_SUCCESSFUL);
        String failure = "Saving failed: synthetic write error. The original download is safe.";
        markFailure(failed, failure);
        recreate();
        render();

        assertTrue(this.<TextView>cardField(failed, "status").getText().toString().contains(failure));
        assertFalse(this.<TextView>cardField(other, "status").getText().toString().contains(failure));
        assertTrue(find(card(failed), "Retry saving").isEnabled());
        assertNoVisibleAction(card(failed), "Cancel saving");
        assertEquals(View.GONE, this.<ProgressBar>cardField(failed, "progress").getVisibility());
        assertNoManualPreparationActions();
        assertEquals(2, store.list().size());
    }

    @Test public void cancelledSavingCanBeRetriedExplicitly() throws Exception {
        Uri folder = rememberFolder("games");
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        markFailure(id, "Saving cancelled. The original download is safe.");
        render();

        find(card(id), "Retry saving").performClick();

        assertPreparationStartedIn(folder, id);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNoConfirmation();
    }

    @Test public void clickingCancelAfterSavingFinishesDoesNotStartAnotherSave() throws Exception {
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        ReflectionHelpers.setStaticField(PreparationService.class, "active", true);
        ReflectionHelpers.setStaticField(PreparationService.class, "activeToken", "synthetic-active-job");
        activity.getSharedPreferences("preparation_status", 0).edit()
                .putLong("download_id", id).putBoolean("in_progress", true)
                .putString("status", "Decrypting package… 42%").putInt("progress", 42).commit();
        render();
        Button cancel = find(card(id), "Cancel saving");
        assertNotNull(cancel);

        // The worker can finish between the last UI refresh and this controller click.
        markSaved(id);
        ReflectionHelpers.setStaticField(PreparationService.class, "active", false);
        ReflectionHelpers.setStaticField(PreparationService.class, "activeToken", null);
        activity.getSharedPreferences("preparation_status", 0).edit()
                .putBoolean("in_progress", false).putBoolean("ready", true)
                .putString("status", "Saved Synthetic.3ds").putInt("progress", 100).commit();
        cancel.performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNoConfirmation();
        assertFalse(PreparationService.isRunning(activity));
        assertEquals(1, store.list().size());
    }

    @Test public void automaticSavingUsesCleanNameForLegacyDownload() throws Exception {
        Uri folder = rememberFolder("games");
        long id = add("synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        String original = "000400000011C400 Pokemon Omega Ruby (CTR-P-ECRA) (v3.3.0) (W).legit-a3bfbe6f17f0.cia";
        updateRecord(id, new JSONObject().put("name", original));

        refresh();

        Intent service = assertPreparationStartedIn(folder, id);
        assertEquals("Pokemon Omega Ruby (World).cia", service.getStringExtra("name"));
        assertEquals(original, store.list().get(0).name);
    }

    @Test public void revokedFolderAccessOffersPickerAtPreviousLocation() throws Exception {
        Uri folder = rememberFolder("games");
        activity.getContentResolver().releasePersistableUriPermission(folder, FOLDER_ACCESS);
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        refresh();
        render();
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());

        find(card(id), "Choose games folder").performClick();

        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(44, launch.requestCode);
        assertEquals(folder, launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        assertEquals(folder.toString(), savedFolder());
        assertNoConfirmation();
    }

    @Test public void unavailableFolderCanBeReplacedFromItsDownloadAndSavingResumes() throws Exception {
        Uri previous = rememberFolder("disconnected-games");
        documents.unavailable = true;
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        refresh();
        render();
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        assertTrue(this.<TextView>cardField(id, "status").getText().toString().contains("unavailable"));
        find(card(id), "Choose games folder").performClick();
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(previous, launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        assertEquals(previous.toString(), savedFolder());
        Uri replacement = documents.tree("available-games");
        documents.unavailable = false;

        selectFolder(replacement, FOLDER_ACCESS);

        assertPreparationStartedIn(replacement, id);
        assertEquals(replacement.toString(), savedFolder());
        assertNoConfirmation();
    }

    @Test public void replacingFolderFromOneCardResumesAllDownloadsWaitingForThatFolder() throws Exception {
        rememberFolder("disconnected-games");
        documents.unavailable = true;
        long oldest = add("First game.cia", DownloadManager.STATUS_SUCCESSFUL);
        long newest = add("Second game.cia", DownloadManager.STATUS_SUCCESSFUL);
        refresh();
        render();
        assertTrue(find(card(oldest), "Choose games folder").isShown());
        assertTrue(find(card(newest), "Choose games folder").isShown());
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        find(card(newest), "Choose games folder").performClick();
        assertEquals(44, shadowOf(activity).getNextStartedActivityForResult().requestCode);
        documents.unavailable = false;
        Uri replacement = documents.tree("available-games");

        selectFolder(replacement, FOLDER_ACCESS);

        assertPreparationStartedIn(replacement, oldest);
        drainDownloads();
        for (DownloadStore.Entry entry : store.list()) {
            assertFalse("The replacement folder should unblock every folder failure",
                    DownloadCompletion.needsFolder(entry));
        }
        render();
        assertTrue(find(card(oldest), "Cancel saving").isShown());
        assertNoVisibleAction(card(newest), "Choose games folder");
        assertTrue(this.<TextView>cardField(newest, "status").getText().toString().contains("Waiting to save"));
    }

    @Test public void folderThatCannotCreateFilesOffersAnotherDestination() throws Exception {
        Uri previous = rememberFolder("read-only-games");
        documents.writable = false;
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        refresh();
        render();

        find(card(id), "Choose games folder").performClick();

        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(44, launch.requestCode);
        assertEquals(previous, launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        assertNoConfirmation();
    }

    @Test public void rememberedFolderIsReusedAfterActivityRecreationAutomatically() throws Exception {
        Uri folder = rememberFolder("games");
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        recreate();

        refresh();

        assertPreparationStartedIn(folder, id);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNoConfirmation();
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(folder, "games"), documents.lastQuery);
    }

    @Test public void settingsCanChooseFolderBeforeAnyDownload() throws Exception {
        button("Settings").performClick();
        assertNull(find(activity.findViewById(android.R.id.content), "Setup"));
        settingsButton("Choose games folder").performClick();
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(44, launch.requestCode);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launch.intent.getAction());
        assertEquals(FOLDER_ACCESS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, launch.intent.getFlags());
        Uri folder = documents.tree("chosen-in-settings");

        selectFolder(folder, FOLDER_ACCESS);
        drainDownloads();

        assertEquals(folder.toString(), savedFolder());
        assertTrue(hasPersistentAccess(folder));
        assertTrue(settingsButton("Change games folder").isShown());
        assertFalse(PreparationService.isRunning(activity));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void changedSettingsFolderIsUsedAndPreviousGrantIsRetained() throws Exception {
        Uri previous = rememberFolder("old-games");
        button("Settings").performClick();
        settingsButton("Change games folder").performClick();
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(44, launch.requestCode);
        assertEquals(previous, launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        Uri replacement = documents.tree("new-games");
        selectFolder(replacement, FOLDER_ACCESS);
        drainDownloads();
        assertEquals(replacement.toString(), savedFolder());
        assertTrue(hasPersistentAccess(previous));
        assertTrue(hasPersistentAccess(replacement));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);

        refresh();

        assertPreparationStartedIn(replacement, id);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNoConfirmation();
    }

    @Test public void cancellingFolderSelectionKeepsPreviousDestination() throws Exception {
        Uri previous = rememberFolder("games");
        button("Settings").performClick();
        settingsButton("Change games folder").performClick();
        assertEquals(44, shadowOf(activity).getNextStartedActivityForResult().requestCode);

        activity.onActivityResult(44, MainActivity.RESULT_CANCELED, null);

        assertEquals(previous.toString(), savedFolder());
        assertTrue(hasPersistentAccess(previous));
        assertTrue(settingsButton("Change games folder").isShown());
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        refresh();
        assertPreparationStartedIn(previous, id);
    }

    @Test public void settingsDoesNotReplaceFolderWithoutPersistentWriteAccess() throws Exception {
        Uri previous = rememberFolder("games");
        button("Settings").performClick();
        settingsButton("Change games folder").performClick();
        assertEquals(44, shadowOf(activity).getNextStartedActivityForResult().requestCode);
        Uri replacement = documents.tree("read-only");

        selectFolder(replacement, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        drainDownloads();

        assertEquals(previous.toString(), savedFolder());
        assertTrue(hasPersistentAccess(previous));
        assertFalse(hasPersistentAccess(replacement));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test @Config(sdk = 35)
    public void automaticSavingDoesNotRequireNotificationPermissionPrompt() throws Exception {
        Uri folder = rememberFolder("games");
        shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);

        refresh();

        assertPreparationStartedIn(folder, id);
        assertNull(shadowOf(activity).getLastRequestedPermission());
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNoConfirmation();
    }

    @Test public void selectedFolderResultAfterRecreationStillStartsPendingDownloads() throws Exception {
        long id = add("Synthetic.CIA", DownloadManager.STATUS_SUCCESSFUL);
        render();
        find(card(id), "Choose games folder").performClick();
        assertEquals(44, shadowOf(activity).getNextStartedActivityForResult().requestCode);
        recreate();
        Uri folder = documents.tree("games");

        selectFolder(folder, FOLDER_ACCESS);

        assertPreparationStartedIn(folder, id);
        assertEquals(folder.toString(), savedFolder());
    }

    @Test public void cancellingFirstFolderSelectionDoesNotStartSaving() {
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        find(card(id), "Choose games folder").performClick();
        assertNotNull(shadowOf(activity).getNextStartedActivityForResult());

        activity.onActivityResult(44, MainActivity.RESULT_CANCELED, null);
        render();

        assertTrue(find(card(id), "Choose games folder").isShown());
        assertFalse(PreparationService.isRunning(activity));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        assertEquals("", savedFolder());
    }

    @Test public void unfinishedAndArchiveDownloadsDoNotStartSaving() throws Exception {
        rememberFolder("games");
        add("Incomplete.cia", DownloadManager.STATUS_RUNNING);
        add("Failed.cia", DownloadManager.STATUS_FAILED);
        add("Archive.zip", DownloadManager.STATUS_SUCCESSFUL);

        refresh();
        render();

        assertNoManualPreparationActions();
        assertNoVisibleAction(ReflectionHelpers.getField(activity, "downloadList"), "Choose games folder");
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        assertTrue(button("Cancel").isShown());
        assertTrue(button("Remove").isShown());
    }

    private long add(String filename, int state) {
        long id = store.enqueue("https://download1.erista.me/synthetic", filename, null, null,
                DownloadPolicy.HOME, false);
        shadowOf(shadowOf(manager).getRequest(id)).setStatus(state);
        return id;
    }

    private void markSaved(long id) throws Exception {
        Uri destination = DocumentsContract.buildDocumentUriUsingTree(documents.tree("games"), "saved-" + id);
        updateRecord(id, new JSONObject().put("preparedDestination", destination.toString())
                .put("originalDeleted", true).put("completionAttempted", true)
                .put("completionStatus", "Saved to your games folder.").put("requiresInstall", false));
    }

    private void markFailure(long id, String message) throws Exception {
        updateRecord(id, new JSONObject().put("completionAttempted", true).put("completionStatus", message));
    }

    private void updateRecord(long id, JSONObject changes) throws Exception {
        android.content.SharedPreferences records = activity.getSharedPreferences("thorshelf_downloads_v1", 0);
        JSONArray array = new JSONArray(records.getString("records", "[]"));
        for (int index = 0; index < array.length(); index++) {
            JSONObject record = array.getJSONObject(index);
            if (record.getLong("id") != id) continue;
            for (java.util.Iterator<String> keys = changes.keys(); keys.hasNext();) {
                String key = keys.next();
                record.put(key, changes.get(key));
            }
        }
        assertTrue(records.edit().putString("records", array.toString()).commit());
    }

    private void recreate() throws Exception {
        drainDownloads();
        Bundle state = new Bundle();
        controller.saveInstanceState(state).stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class);
        activity = controller.get();
        ReflectionHelpers.setField(activity, "queryingDownloads", true);
        controller.create(state).start().restoreInstanceState(state).visible();
    }

    private ViewGroup card(long id) { return cardField(id, "root"); }

    private <T> T cardField(long id, String name) {
        Map<Long, ?> cards = ReflectionHelpers.getField(activity, "downloadCards");
        Object card = cards.get(id);
        assertNotNull("Expected download card " + id, card);
        return ReflectionHelpers.getField(card, name);
    }

    private Uri rememberFolder(String id) {
        Uri folder = documents.tree(id);
        activity.getContentResolver().takePersistableUriPermission(folder, FOLDER_ACCESS);
        activity.getSharedPreferences("settings", 0).edit()
                .putString("lastPreparationFolder", folder.toString()).commit();
        return folder;
    }

    private void selectFolder(Uri folder, int flags) {
        ReflectionHelpers.setField(activity, "queryingDownloads", false);
        activity.onActivityResult(44, MainActivity.RESULT_OK, new Intent().setData(folder).addFlags(flags));
    }

    private String savedFolder() {
        return activity.getSharedPreferences("settings", 0).getString("lastPreparationFolder", "");
    }

    private boolean hasPersistentAccess(Uri folder) {
        return activity.getContentResolver().getPersistedUriPermissions().stream()
                .anyMatch(permission -> folder.equals(permission.getUri())
                        && permission.isReadPermission() && permission.isWritePermission());
    }

    private Intent assertPreparationStartedIn(Uri folder, long id) throws Exception {
        await(() -> shadowOf(activity.getApplication()).peekNextStartedService() != null);
        Intent service = shadowOf(activity.getApplication()).getNextStartedService();
        assertEquals(PreparationService.class.getName(), service.getComponent().getClassName());
        assertEquals(folder.toString(), service.getStringExtra("folder"));
        assertEquals(id, service.getLongExtra("download_id", -1));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        return service;
    }

    private void assertNoManualPreparationActions() {
        View root = activity.findViewById(android.R.id.content);
        assertNull(find(root, "Prepare for Azahar"));
        assertNull(find(root, "Delete original"));
    }

    private void assertNoVisibleAction(View root, String label) {
        Button action = find(root, label);
        assertTrue(label + " should not be shown", action == null || action.getVisibility() != View.VISIBLE);
    }

    private void assertNoConfirmation() {
        AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("Automatic saving should not need a confirmation", latest == null || !latest.isShowing());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Folder operation did not finish within five seconds", condition.getAsBoolean());
    }

    /** Supplies only folder metadata; UI tests never open or write game files. */
    public static final class FolderProvider extends ContentProvider {
        static final String AUTHORITY = "dev.thorshelf.test.folder-ui";
        volatile Uri lastQuery;
        volatile boolean unavailable;
        volatile boolean writable = true;

        Uri tree(String id) { return DocumentsContract.buildTreeDocumentUri(AUTHORITY, id); }
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return DocumentsContract.Document.MIME_TYPE_DIR; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            if (unavailable) throw new SecurityException("The selected storage is disconnected");
            lastQuery = uri;
            String[] columns = projection == null ? new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case DocumentsContract.Document.COLUMN_MIME_TYPE:
                        values[i] = DocumentsContract.Document.MIME_TYPE_DIR;
                        break;
                    case DocumentsContract.Document.COLUMN_FLAGS:
                        values[i] = writable ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : 0;
                        break;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                        values[i] = "Games";
                        break;
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                        values[i] = DocumentsContract.getDocumentId(uri);
                        break;
                }
            }
            cursor.addRow(values);
            return cursor;
        }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
    }

    private void render() {
        ReflectionHelpers.setField(activity, "queryingDownloads", true);
        ReflectionHelpers.callInstanceMethod(activity, "renderDownloads",
                ReflectionHelpers.ClassParameter.from(List.class, store.list()));
        ReflectionHelpers.callInstanceMethod(activity, "showTab", ReflectionHelpers.ClassParameter.from(int.class, 1));
    }

    private void refresh() throws Exception {
        ReflectionHelpers.setField(activity, "queryingDownloads", false);
        ReflectionHelpers.callInstanceMethod(activity, "refreshDownloads");
        drainDownloads();
    }

    private void drainDownloads() throws Exception {
        ExecutorService worker = ReflectionHelpers.getField(activity, "downloadQueries");
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private Button settingsButton(String label) {
        Button button = find(ReflectionHelpers.getField(activity, "settingsPanel"), label);
        assertNotNull("Expected Settings button " + label, button);
        return button;
    }

    private Button button(String label) {
        Button button = find(activity.findViewById(android.R.id.content), label);
        assertNotNull("Expected button " + label, button);
        return button;
    }

    private static Button find(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button child = find(group.getChildAt(i), label);
                if (child != null) return child;
            }
        }
        return null;
    }
}
