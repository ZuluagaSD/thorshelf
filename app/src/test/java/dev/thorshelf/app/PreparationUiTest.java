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

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

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
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        if (Build.VERSION.SDK_INT >= 33) shadowOf(activity.getApplication())
                .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
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

    @After public void close() {
        controller.pause().stop().destroy();
        // These UI tests inspect queued service intents without executing the preparation worker.
        activity.getSharedPreferences("preparation_status", 0).edit().clear().commit();
        ReflectionHelpers.setStaticField(PreparationService.class, "active", false);
        ReflectionHelpers.setStaticField(PreparationService.class, "activeToken", null);
        ReflectionHelpers.setStaticField(PreparationService.class, "cancelledToken", null);
    }

    @Test public void preparationChoosesGamesFolderWithPersistentWriteAccess() throws Exception {
        add("Synthetic.CIA", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(43, launch.requestCode);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launch.intent.getAction());
        assertNull(launch.intent.getStringExtra(Intent.EXTRA_TITLE));
        assertNull(launch.intent.getType());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                launch.intent.getFlags());
        assertNull(launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        assertEquals(1, store.list().size());
    }

    @Test public void completedDownloadOffersOnlyPreparationBeforeItHasBeenPrepared() throws Exception {
        long id = add("Synthetic.CIA", DownloadManager.STATUS_SUCCESSFUL);
        render();

        assertTrue(button("Prepare for Azahar").isShown());
        assertEquals(View.GONE, find(card(id), "Delete original").getVisibility());
        assertNull(find(activity.findViewById(android.R.id.content), "Catalog page"));
        assertNull(find(activity.findViewById(android.R.id.content), "Save a copy"));
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void deletionEligibilityBelongsToEachDownloadRatherThanGlobalReadyStatus() throws Exception {
        long prepared = add("Prepared.cia", DownloadManager.STATUS_SUCCESSFUL);
        long untouched = add("Untouched.cia", DownloadManager.STATUS_SUCCESSFUL);
        markPrepared(prepared);
        activity.getSharedPreferences("preparation_status", 0).edit()
                .putBoolean("ready", true).putString("status", "Saved Prepared.3ds").commit();

        render();

        assertTrue(PreparationService.isReady(activity));
        assertTrue(find(card(prepared), "Delete original").isShown());
        assertEquals(View.GONE, find(card(untouched), "Delete original").getVisibility());
        assertTrue(find(card(prepared), "Prepare for Azahar").isShown());
        assertTrue(find(card(untouched), "Prepare for Azahar").isShown());
    }

    @Test public void successfulPreparationRevealsDeleteWithoutRebuildingDownloadCard() throws Exception {
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        ViewGroup originalCard = card(id);
        Button delete = find(originalCard, "Delete original");
        assertEquals(View.GONE, delete.getVisibility());

        markPrepared(id);
        render();

        assertSame(originalCard, card(id));
        assertSame(delete, find(card(id), "Delete original"));
        assertTrue(delete.isShown());
        assertTrue(delete.isEnabled());
    }

    @Test public void deleteOriginalRemainsAvailableAfterActivityRecreation() throws Exception {
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        markPrepared(id);
        render();
        assertTrue(button("Delete original").isShown());
        Bundle state = new Bundle();
        controller.saveInstanceState(state).pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).create(state).start()
                .restoreInstanceState(state).resume().visible();
        activity = controller.get();

        render();

        assertFalse(PreparationService.isReady(activity));
        assertTrue(button("Delete original").isShown());
        assertTrue(button("Prepare for Azahar").isShown());
    }

    @Test public void keepingOriginalDismissesDeletionConfirmationWithoutRemovingDownload() throws Exception {
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        markPrepared(id);
        render();
        button("Delete original").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        assertEquals("Delete original download?", shadowOf(dialog).getTitle().toString());
        assertEquals("Delete original", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals("Keep original", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(dialog.isShowing());
        assertNotNull(shadowOf(manager).getRequest(id));
        assertEquals(1, store.list().size());
        assertTrue(store.list().get(0).isPrepared());
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void deletionIsDisabledWhilePreparationIsRunning() throws Exception {
        Uri folder = rememberFolder("games");
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        markPrepared(id);
        render();
        assertTrue(button("Delete original").isEnabled());

        button("Prepare for Azahar").performClick();
        assertPreparationStartedIn(folder);
        render();

        assertTrue(PreparationService.isRunning(activity));
        assertTrue(button("Delete original").isShown());
        assertFalse(button("Delete original").isEnabled());
        assertEquals(1, store.list().size());
    }

    @Test public void activePreparationCanBeCancelledFromItsCardWithoutNotificationPermission() throws Exception {
        Uri folder = rememberFolder("games");
        long other = add("Another game.cia", DownloadManager.STATUS_SUCCESSFUL);
        long preparing = add("Preparing game.cia", DownloadManager.STATUS_SUCCESSFUL);
        markPrepared(preparing);
        render();
        find(card(preparing), "Prepare for Azahar").performClick();
        Intent started = assertPreparationStartedIn(folder);
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
            assertEquals(PackageManager.PERMISSION_DENIED,
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        }
        String message = "Decrypting package… 42%";
        activity.getSharedPreferences("preparation_status", 0).edit()
                .putString("status", message).putInt("progress", 42).commit();

        render();

        Button cancel = find(card(preparing), "Cancel preparation");
        assertNotNull(cancel);
        assertTrue(cancel.isShown());
        assertTrue(cancel.isEnabled());
        assertEquals(message, this.<TextView>cardField(preparing, "status").getText().toString());
        ProgressBar progress = cardField(preparing, "progress");
        assertTrue(progress.isShown());
        assertFalse(progress.isIndeterminate());
        assertEquals(420, progress.getProgress());
        assertNull(find(card(other), "Cancel preparation"));
        assertFalse(find(card(other), "Prepare for Azahar").isEnabled());
        assertFalse(find(card(preparing), "Delete original").isEnabled());
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

    @Test public void preparationFailureStaysOnItsDownloadCardAfterRecreation() throws Exception {
        long failed = add("Failed preparation.cia", DownloadManager.STATUS_SUCCESSFUL);
        long other = add("Untouched game.cia", DownloadManager.STATUS_SUCCESSFUL);
        String failure = "Preparation failed: synthetic write error. The original download is safe.";
        activity.getSharedPreferences("preparation_status", 0).edit()
                .putLong("download_id", failed).putBoolean("in_progress", false)
                .putBoolean("ready", false).putString("status", failure).commit();
        Bundle state = new Bundle();
        controller.saveInstanceState(state).pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).create(state).start()
                .restoreInstanceState(state).resume().visible();
        activity = controller.get();

        render();

        assertTrue(this.<TextView>cardField(failed, "status").getText().toString().contains(failure));
        assertFalse(this.<TextView>cardField(other, "status").getText().toString().contains(failure));
        assertTrue(find(card(failed), "Prepare for Azahar").isEnabled());
        assertTrue(find(card(other), "Prepare for Azahar").isEnabled());
        assertNull(find(card(failed), "Cancel preparation"));
        assertEquals(View.GONE, this.<ProgressBar>cardField(failed, "progress").getVisibility());
        assertEquals(2, store.list().size());
    }

    @Test public void clickingCancelAfterPreparationFinishesDoesNotStartAnotherPreparation() throws Exception {
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        ReflectionHelpers.setStaticField(PreparationService.class, "active", true);
        ReflectionHelpers.setStaticField(PreparationService.class, "activeToken", "synthetic-active-job");
        activity.getSharedPreferences("preparation_status", 0).edit()
                .putLong("download_id", id).putBoolean("in_progress", true)
                .putString("status", "Decrypting package… 42%").putInt("progress", 42).commit();
        render();
        Button cancel = find(card(id), "Cancel preparation");
        assertNotNull(cancel);

        // The worker can finish between the last UI refresh and this controller click.
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

    @Test public void preparationAndDeletionAreDisabledUntilOriginalDeletionFinishes() throws Exception {
        long id = add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        markPrepared(id);
        render();
        assertTrue(button("Prepare for Azahar").isEnabled());
        assertTrue(button("Delete original").isEnabled());

        ReflectionHelpers.setField(activity, "deletingOriginal", true);
        render();

        assertFalse(button("Prepare for Azahar").isEnabled());
        assertFalse(button("Delete original").isEnabled());
        assertTrue(button("Delete original").isShown());
        assertEquals(1, store.list().size());

        ReflectionHelpers.setField(activity, "deletingOriginal", false);
        render();
        assertTrue(button("Prepare for Azahar").isEnabled());
        assertTrue(button("Delete original").isEnabled());
    }

    @Test public void legacyDownloadUsesCleanNameForPreparation() throws Exception {
        Uri folder = rememberFolder("games");
        add("synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        android.content.SharedPreferences records = activity.getSharedPreferences("thorshelf_downloads_v1", 0);
        JSONArray array = new JSONArray(records.getString("records", "[]"));
        String original = "000400000011C400 Pokemon Omega Ruby (CTR-P-ECRA) (v3.3.0) (W).legit-a3bfbe6f17f0.cia";
        array.getJSONObject(0).put("name", original);
        records.edit().putString("records", array.toString()).commit();
        render();
        button("Prepare for Azahar").performClick();
        Intent service = assertPreparationStartedIn(folder);
        assertEquals("Pokemon Omega Ruby (World).cia", service.getStringExtra("name"));
        assertEquals(original, store.list().get(0).name);
    }

    @Test public void revokedFolderAccessAsksAgainAtPreviousLocationWithoutConfirmation() throws Exception {
        Uri folder = rememberFolder("games");
        activity.getContentResolver().releasePersistableUriPermission(folder, FOLDER_ACCESS);
        add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();
        await(() -> shadowOf(activity).peekNextStartedActivityForResult() != null);
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(43, launch.requestCode);
        assertEquals(folder, launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        assertEquals(folder.toString(), savedFolder());
        assertNoConfirmation();
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void selectedFolderIsRememberedWithPersistentReadAndWriteAccess() throws Exception {
        add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(43, shadowOf(activity).getNextStartedActivityForResult().requestCode);

        Uri folder = documents.tree("games");
        activity.onActivityResult(43, MainActivity.RESULT_OK,
                new Intent().setData(folder).addFlags(FOLDER_ACCESS));
        assertPreparationStartedIn(folder);
        assertEquals(folder.toString(), savedFolder());
        assertTrue(hasPersistentAccess(folder));
    }

    @Test public void rememberedFolderIsReusedAfterActivityRecreationWithoutAsking() throws Exception {
        Uri folder = rememberFolder("games");
        add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        Bundle state = new Bundle();
        controller.saveInstanceState(state).pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).create(state).start()
                .restoreInstanceState(state).resume().visible();
        activity = controller.get();
        render();

        button("Prepare for Azahar").performClick();

        assertPreparationStartedIn(folder);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNoConfirmation();
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(folder, "games"), documents.lastQuery);
    }

    @Test public void settingsCanChooseFolderBeforePreparingAnyDownload() throws Exception {
        button("Settings").performClick();
        assertNull(find(activity.findViewById(android.R.id.content), "Setup"));
        button("Choose games folder").performClick();
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(44, launch.requestCode);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launch.intent.getAction());
        assertEquals(FOLDER_ACCESS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, launch.intent.getFlags());

        Uri folder = documents.tree("chosen-in-settings");
        activity.onActivityResult(44, MainActivity.RESULT_OK,
                new Intent().setData(folder).addFlags(FOLDER_ACCESS));
        await(() -> folder.toString().equals(savedFolder()));

        assertTrue(hasPersistentAccess(folder));
        assertTrue(button("Change games folder").isShown());
        assertFalse(PreparationService.isRunning(activity));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void changedSettingsFolderIsUsedAndPreviousGrantIsRetained() throws Exception {
        Uri previous = rememberFolder("old-games");
        button("Settings").performClick();
        button("Change games folder").performClick();
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(44, launch.requestCode);
        assertEquals(previous, launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        Uri replacement = documents.tree("new-games");
        activity.onActivityResult(44, MainActivity.RESULT_OK,
                new Intent().setData(replacement).addFlags(FOLDER_ACCESS));
        await(() -> replacement.toString().equals(savedFolder()));
        assertTrue(hasPersistentAccess(previous));
        assertTrue(hasPersistentAccess(replacement));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());

        add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();
        assertPreparationStartedIn(replacement);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNoConfirmation();
    }

    @Test public void cancelingSettingsFolderChangeKeepsPreviousDestination() throws Exception {
        Uri previous = rememberFolder("games");
        button("Settings").performClick();
        button("Change games folder").performClick();
        assertEquals(44, shadowOf(activity).getNextStartedActivityForResult().requestCode);

        activity.onActivityResult(44, MainActivity.RESULT_CANCELED, null);

        assertEquals(previous.toString(), savedFolder());
        assertTrue(hasPersistentAccess(previous));
        assertTrue(button("Change games folder").isShown());
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();
        assertPreparationStartedIn(previous);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void settingsDoesNotReplaceFolderWithoutPersistentWriteAccess() {
        Uri previous = rememberFolder("games");
        button("Settings").performClick();
        button("Change games folder").performClick();
        assertEquals(44, shadowOf(activity).getNextStartedActivityForResult().requestCode);

        Uri replacement = documents.tree("read-only");
        activity.onActivityResult(44, MainActivity.RESULT_OK, new Intent().setData(replacement)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));

        assertEquals(previous.toString(), savedFolder());
        assertTrue(hasPersistentAccess(previous));
        assertFalse(hasPersistentAccess(replacement));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void unavailableSavedFolderOpensPickerAndPreservesPreviousChoice() throws Exception {
        Uri folder = rememberFolder("games");
        documents.unavailable = true;
        add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();

        await(() -> shadowOf(activity).peekNextStartedActivityForResult() != null);
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(43, launch.requestCode);
        assertEquals(folder, launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        assertEquals(folder.toString(), savedFolder());
        assertNoConfirmation();
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
    }

    @Test public void savedFolderThatCannotCreateFilesAsksForAnotherFolder() throws Exception {
        Uri folder = rememberFolder("read-only-games");
        documents.writable = false;
        add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();

        await(() -> shadowOf(activity).peekNextStartedActivityForResult() != null);
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(43, launch.requestCode);
        assertEquals(folder, launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        assertNoConfirmation();
    }

    @Test @Config(sdk = 35)
    public void notificationPermissionResponseResumesSavedFolderReuse() throws Exception {
        Uri folder = rememberFolder("games");
        shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();

        assertNotNull(shadowOf(activity).getLastRequestedPermission());
        assertEquals(42, shadowOf(activity).getLastRequestedPermission().requestCode);
        ShadowActivity.IntentForResult permissions = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(permissions);
        assertEquals(42, permissions.requestCode);
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", permissions.intent.getAction());
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        activity.onRequestPermissionsResult(42, new String[]{Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_GRANTED});

        assertPreparationStartedIn(folder);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNoConfirmation();
    }

    @Test public void pendingPreparationSurvivesActivityRecreation() throws Exception {
        add("Synthetic.CIA", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        Bundle state = new Bundle();
        controller.saveInstanceState(state).pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).create(state).start()
                .restoreInstanceState(state).resume().visible();
        activity = controller.get();
        Bundle restored = new Bundle();
        controller.saveInstanceState(restored);
        assertEquals(store.list().get(0).id, restored.getLong("exportId"));
        assertEquals(store.list().get(0).name, restored.getString("exportName"));

        // A permission response delivered after recreation must retain the folder picker route.
        activity.onRequestPermissionsResult(42, new String[0], new int[0]);
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(43, launch.requestCode);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launch.intent.getAction());
    }

    @Test public void cancelingFolderSelectionDoesNotStartPreparation() throws Exception {
        add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(shadowOf(activity).getNextStartedActivityForResult());
        activity.onActivityResult(43, MainActivity.RESULT_CANCELED, null);
        assertFalse(PreparationService.isRunning(activity));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        activity.onRequestPermissionsResult(42, new String[0], new int[0]);
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void unfinishedAndArchiveDownloadsDoNotOfferPreparation() throws Exception {
        add("Incomplete.cia", DownloadManager.STATUS_RUNNING);
        add("Failed.cia", DownloadManager.STATUS_FAILED);
        add("Archive.zip", DownloadManager.STATUS_SUCCESSFUL);
        render();
        assertNull(find(activity.findViewById(android.R.id.content), "Prepare for Azahar"));
        assertNull(find(activity.findViewById(android.R.id.content), "Delete original"));
        assertNull(find(activity.findViewById(android.R.id.content), "Catalog page"));
        assertNull(find(activity.findViewById(android.R.id.content), "Save a copy"));
        assertTrue(button("Cancel").isShown());
        assertTrue(button("Remove").isShown());
    }

    @Test public void decliningPreparationDoesNotOpenPickerOrStartService() throws Exception {
        add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertFalse(PreparationService.isRunning(activity));
        assertFalse(PreparationService.isReady(activity));
    }

    private long add(String filename, int state) {
        long id = store.enqueue("https://download1.erista.me/synthetic", filename, null, null,
                DownloadPolicy.HOME, false);
        shadowOf(shadowOf(manager).getRequest(id)).setStatus(state);
        return id;
    }

    private void markPrepared(long id) throws Exception {
        android.content.SharedPreferences records = activity.getSharedPreferences("thorshelf_downloads_v1", 0);
        JSONArray array = new JSONArray(records.getString("records", "[]"));
        for (int index = 0; index < array.length(); index++) {
            if (array.getJSONObject(index).getLong("id") != id) continue;
            Uri destination = DocumentsContract.buildDocumentUriUsingTree(documents.tree("games"), "prepared-" + id);
            array.getJSONObject(index).put("preparedDestination", destination.toString());
        }
        assertTrue(records.edit().putString("records", array.toString()).commit());
    }

    private ViewGroup card(long id) {
        return cardField(id, "root");
    }

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

    private String savedFolder() {
        return activity.getSharedPreferences("settings", 0).getString("lastPreparationFolder", "");
    }

    private boolean hasPersistentAccess(Uri folder) {
        return activity.getContentResolver().getPersistedUriPermissions().stream()
                .anyMatch(permission -> folder.equals(permission.getUri())
                        && permission.isReadPermission() && permission.isWritePermission());
    }

    private Intent assertPreparationStartedIn(Uri folder) throws Exception {
        await(() -> shadowOf(activity.getApplication()).peekNextStartedService() != null);
        Intent service = shadowOf(activity.getApplication()).getNextStartedService();
        assertEquals(PreparationService.class.getName(), service.getComponent().getClassName());
        assertEquals(folder.toString(), service.getStringExtra("folder"));
        assertEquals(store.list().get(0).id, service.getLongExtra("download_id", -1));
        assertNull(shadowOf(activity.getApplication()).getNextStartedService());
        return service;
    }

    private void assertNoConfirmation() {
        AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("Saved folders should not require another confirmation", latest == null || !latest.isShowing());
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

    private void render() throws Exception {
        Method method = MainActivity.class.getDeclaredMethod("renderDownloads", List.class);
        method.setAccessible(true);
        method.invoke(activity, store.list());
        button("Downloads").performClick();
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
