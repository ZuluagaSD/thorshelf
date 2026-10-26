package dev.thorshelf.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

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

import java.lang.reflect.Method;
import java.util.List;

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

    @Before public void launch() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        if (Build.VERSION.SDK_INT >= 33) shadowOf(activity.getApplication())
                .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        store = new DownloadStore(activity);
        manager = activity.getSystemService(DownloadManager.class);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
    }

    @After public void close() { controller.pause().stop().destroy(); }

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

    @Test public void saveCopyStillChoosesAnIndividualUnchangedFile() throws Exception {
        add("Synthetic.CIA", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Save a copy").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(41, launch.requestCode);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, launch.intent.getAction());
        assertEquals(store.list().get(0).name, launch.intent.getStringExtra(Intent.EXTRA_TITLE));
        assertEquals("application/octet-stream", launch.intent.getType());
        assertTrue(launch.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertNull(launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
    }

    @Test public void folderPickerStartsAtPreviouslySelectedGamesFolder() throws Exception {
        Uri folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fn3ds");
        activity.getSharedPreferences("settings", 0).edit()
                .putString("lastPreparationFolder", folder.toString()).commit();
        add("Synthetic.cia", DownloadManager.STATUS_SUCCESSFUL);
        render();
        button("Prepare for Azahar").performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        ShadowActivity.IntentForResult launch = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(folder, launch.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
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
        assertTrue(restored.getBoolean("exportPreparing"));

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
        add("Archive.zip", DownloadManager.STATUS_SUCCESSFUL);
        render();
        assertNull(find(activity.findViewById(android.R.id.content), "Prepare for Azahar"));
        button("Save a copy").performClick();
        assertEquals(41, shadowOf(activity).getNextStartedActivityForResult().requestCode);
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

    private void add(String filename, int state) {
        long id = store.enqueue("https://download1.erista.me/synthetic", filename, null, null,
                DownloadPolicy.HOME, false);
        shadowOf(shadowOf(manager).getRequest(id)).setStatus(state);
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
