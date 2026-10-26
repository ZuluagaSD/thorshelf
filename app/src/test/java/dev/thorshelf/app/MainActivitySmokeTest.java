package dev.thorshelf.app;

import android.view.KeyEvent;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;

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
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Android framework smoke checks; ShadowWebView never contacts the catalog. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivitySmokeTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private View root;

    @Before public void launch() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        root = activity.findViewById(android.R.id.content);
    }

    @After public void close() {
        if (controller != null) controller.pause().stop().destroy();
    }

    @Test public void coldLaunchLoadsTheCatalog() {
        WebView browser = find(WebView.class, view -> true);
        assertEquals("https://hshop.erista.me/", shadowOf(browser).getLastLoadedUrl());
        assertTrue(button("Browse").isSelected());
        assertFalse(button("Downloads").isSelected());
        assertFalse(button("Setup").isSelected());
    }

    @Test public void searchSubmitsEncodedTextToThePublicCatalog() {
        EditText search = find(EditText.class, view -> true);
        search.setText("  A & B? # + é  ");
        button("Search").performClick();

        WebView browser = find(WebView.class, view -> true);
        assertEquals("https://hshop.erista.me/search/results?q=A+%26+B%3F+%23+%2B+%C3%A9&qt=Text&lgy=false",
                shadowOf(browser).getLastLoadedUrl());
        assertTrue(button("Browse").isSelected());
    }

    @Test public void shoulderButtonsNavigateAndWrapNativeTabs() {
        press(KeyEvent.KEYCODE_BUTTON_R1);
        assertTrue(button("Downloads").isSelected());
        assertFalse(button("Browse").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_R1);
        assertTrue(button("Setup").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_R1);
        assertTrue(button("Browse").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_L1);
        assertTrue(button("Setup").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_L1);
        assertTrue(button("Downloads").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_Y);
        assertTrue(button("Browse").isSelected());
        assertTrue(find(EditText.class, view -> true).hasFocus());
    }

    @Test public void hshopDownloadHeadersReachConfirmationAndQueueAsCia() throws Exception {
        DownloadManager manager = activity.getSystemService(DownloadManager.class);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        String filename = "00040000000A8500 Professor Layton and the Miracle Mask (CTR-P-AKKE) (v0.2.0) (U).piratelegit.cia";
        // The two filename parameters together broke URLUtil on Android 13 and earlier.
        downloadCallback("attachment; filename=\"" + filename + "\"; filename*=UTF-8''"
                + "00040000000A8500%20Professor%20Layton%20and%20the%20Miracle%20Mask%20%28CTR-P-AKKE%29%20%28v0.2.0%29%20%28U%29.piratelegit.cia",
                "application/octet-stream");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertTrue(new DownloadStore(activity).list().isEmpty());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        List<DownloadStore.Entry> entries = new DownloadStore(activity).list();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).name.endsWith(".cia"));
        assertEquals("Professor Layton and the Miracle Mask (USA).cia", entries.get(0).name);
        assertTrue(shadowOf(shadowOf(manager).getRequest(entries.get(0).id))
                .getDestination().getPath().endsWith(".cia"));
        assertTrue(button("Downloads").isSelected());
    }

    @Test public void htmlResponseIsRejectedEvenWithCiaFilename() throws Exception {
        downloadCallback("attachment; filename=\"game.cia\"", "text/html; charset=UTF-8");
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertTrue(new DownloadStore(activity).list().isEmpty());
        assertEquals("This link is not a supported game or archive file.", ShadowToast.getTextOfLatestToast());
    }

    private void downloadCallback(String disposition, String mime) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod("confirmDownload", String.class,
                String.class, String.class, String.class, long.class);
        method.setAccessible(true);
        method.invoke(activity, "https://download4.erista.me/opaque-download-token", "ThorShelf test",
                disposition, mime, 828400640L);
    }

    private void press(int keyCode) {
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode)));
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode)));
    }

    private Button button(String text) {
        return find(Button.class, view -> text.contentEquals(view.getText()));
    }

    private <T extends View> T find(Class<T> type, Predicate<T> predicate) {
        T result = findIn(root, type, predicate);
        assertNotNull("Expected view: " + type.getSimpleName(), result);
        return result;
    }

    private static <T extends View> T findIn(View view, Class<T> type, Predicate<T> predicate) {
        if (type.isInstance(view) && predicate.test(type.cast(view))) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T result = findIn(group.getChildAt(i), type, predicate);
                if (result != null) return result;
            }
        }
        return null;
    }
}
