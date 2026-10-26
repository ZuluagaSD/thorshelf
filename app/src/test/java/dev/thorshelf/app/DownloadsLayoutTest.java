package dev.thorshelf.app;

import android.app.DownloadManager;
import android.graphics.Rect;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;

import static dev.thorshelf.app.CatalogUiTestSupport.find;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Measures the full-width queue and its per-download actions at handheld screen sizes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, qualifiers = "w960dp-h400dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class DownloadsLayoutTest {
    private static final String TITLE = "Professor Layton and the Miracle Mask (USA)";
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private ViewGroup root;
    private DownloadStore store;
    private DownloadManager manager;

    @After public void close() {
        if (controller != null) controller.stop().destroy();
        RuntimeEnvironment.setFontScale(1f);
    }

    @Test public void completedDownloadUsesTheFullLandscapeWidthAndKeepsItsActionsReachable() throws Exception {
        launch(1f);
        long id = addDownload(TITLE + ".cia");
        markPrepared(id);
        renderAndLayout(960, 400);

        assertFullWidthQueue();
        assertActionReachable("Prepare for Azahar", button("Prepare for Azahar"));
        assertActionReachable("Delete original", button("Delete original"));
    }

    @Test public void scrollingManyDownloadsReachesTheLastCardAndItsPreparationAction() {
        launch(1f);
        addDownloads(12);
        renderAndLayout(960, 400);

        assertFullWidthQueue();
        scrollToBottom();
        assertTrue("Fixture must overflow the queue", queue().getScrollY() > 0);
        ViewGroup list = (ViewGroup) field("downloadList");
        View lastCard = list.getChildAt(list.getChildCount() - 1);
        Button prepare = find(lastCard, Button.class, view -> "Prepare for Azahar".contentEquals(view.getText()));
        assertNotNull(prepare);
        assertContained("The last download's action", prepare, queue());
        assertTrue(prepare.isEnabled());
    }

    @Test @Config(qualifiers = "w800dp-h280dp-land-mdpi")
    public void largeTextKeepsLongDownloadTitlesReadableAndActionsReachableByScrolling() throws Exception {
        launch(1.5f);
        String title = TITLE + " Special Edition";
        long id = addDownload(title + ".cia");
        markPrepared(id);
        renderAndLayout(800, 280);

        TextView titleView = find(field("downloadList"), TextView.class, view -> title.contentEquals(view.getText()));
        assertNotNull("The complete download title must be retained", titleView);
        assertNotNull(titleView.getLayout());
        assertEquals("The title must wrap without silently losing its ending", 0,
                titleView.getLayout().getEllipsisCount(titleView.getLayout().getLineCount() - 1));
        assertFullWidthQueue();
        assertActionReachable("Prepare for Azahar", button("Prepare for Azahar"));
        assertActionReachable("Delete original", button("Delete original"));
        assertTrue("Long content must remain scrollable", queue().getScrollY() > 0);
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-mdpi")
    public void portraitQueueUsesTheViewportAndKeepsItsLastDownloadReachable() {
        launch(1f);
        addDownloads(8);
        renderAndLayout(411, 891);

        assertFullWidthQueue();
        scrollToBottom();
        assertTrue(queue().getScrollY() > 0);
        ViewGroup list = (ViewGroup) field("downloadList");
        View lastCard = list.getChildAt(list.getChildCount() - 1);
        Button prepare = find(lastCard, Button.class, view -> "Prepare for Azahar".contentEquals(view.getText()));
        assertNotNull(prepare);
        assertContained("The last portrait action", prepare, queue());
    }

    @Test public void resizingTheQueuePreservesFocusedDownloadAction() {
        launch(1f);
        addDownload(TITLE + ".cia");
        renderAndLayout(960, 400);
        Button prepare = button("Prepare for Azahar");
        prepare.setFocusableInTouchMode(true);
        assertTrue(prepare.requestFocus());

        renderAndLayout(411, 891);
        assertTrue("Resizing to portrait must preserve controller focus", prepare.hasFocus());
        assertFullWidthQueue();
        renderAndLayout(960, 400);
        assertTrue("Resizing to landscape must preserve controller focus", prepare.hasFocus());
        assertActionReachable("Prepare for Azahar", prepare);
    }

    private void launch(float fontScale) {
        RuntimeEnvironment.setFontScale(fontScale);
        // Start without resuming: periodic DownloadManager refreshes are unrelated to layout.
        controller = Robolectric.buildActivity(MainActivity.class).create().start().visible();
        activity = controller.get();
        root = activity.findViewById(android.R.id.content);
        store = new DownloadStore(activity);
        manager = activity.getSystemService(DownloadManager.class);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        ReflectionHelpers.setField(activity, "queryingDownloads", true);
        ReflectionHelpers.callInstanceMethod(activity, "showTab", ReflectionHelpers.ClassParameter.from(int.class, 1));
    }

    private long addDownload(String name) {
        long id = store.enqueue("https://download1.erista.me/synthetic", name,
                null, null, DownloadPolicy.HOME, false);
        shadowOf(shadowOf(manager).getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        return id;
    }

    private void addDownloads(int count) {
        for (int i = 0; i < count; i++) addDownload("Synthetic game " + i + ".cia");
    }

    private void markPrepared(long id) throws Exception {
        android.content.SharedPreferences records = activity.getSharedPreferences("thorshelf_downloads_v1", 0);
        JSONArray array = new JSONArray(records.getString("records", "[]"));
        for (int i = 0; i < array.length(); i++) {
            if (array.getJSONObject(i).getLong("id") == id) {
                array.getJSONObject(i).put("preparedDestination", "content://synthetic.documents/prepared-" + id);
            }
        }
        assertTrue(records.edit().putString("records", array.toString()).commit());
    }

    private void renderAndLayout(int widthDp, int heightDp) {
        ReflectionHelpers.callInstanceMethod(activity, "renderDownloads",
                ReflectionHelpers.ClassParameter.from(List.class, store.list()));
        int width = Ui.dp(activity, widthDp), height = Ui.dp(activity, heightDp);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private void assertFullWidthQueue() {
        View downloads = field("downloadsPanel");
        assertContained("The Downloads viewport", downloads, root);
        assertContained("The scrolling queue", queue(), downloads);
        assertEquals("The queue should use all available horizontal space",
                downloads.getWidth() - downloads.getPaddingLeft() - downloads.getPaddingRight(), queue().getWidth());
    }

    private void assertActionReachable(String label, View action) {
        ViewGroup body = (ViewGroup) queue().getChildAt(0);
        Rect target = new Rect();
        action.getDrawingRect(target);
        body.offsetDescendantRectToMyCoords(action, target);
        queue().scrollTo(0, target.top);
        assertContained(label, action, queue());
        assertTrue(label + " must remain at least 48dp tall", action.getHeight() >= Ui.dp(activity, 48));
    }

    private void scrollToBottom() {
        ScrollView scroll = queue();
        int range = scroll.getChildAt(0).getHeight() - scroll.getHeight()
                + scroll.getPaddingTop() + scroll.getPaddingBottom();
        scroll.scrollTo(0, Math.max(0, range));
    }

    private void assertContained(String label, View inner, View outer) {
        Rect actual = bounds(inner), limit = bounds(outer);
        assertTrue(label + " must have a visible measured area: " + actual, actual.width() > 0 && actual.height() > 0);
        assertTrue(label + " must fit within " + limit + ", was " + actual, limit.contains(actual));
    }

    private Rect bounds(View view) {
        Rect bounds = new Rect();
        view.getDrawingRect(bounds);
        if (view != root) root.offsetDescendantRectToMyCoords(view, bounds);
        return bounds;
    }

    private Button button(String label) {
        Button button = find(field("downloadList"), Button.class, view -> label.contentEquals(view.getText()));
        assertNotNull(label, button);
        return button;
    }

    private View field(String name) { return ReflectionHelpers.getField(activity, name); }

    private ScrollView queue() {
        ViewParent parent = field("downloadList").getParent();
        while (parent instanceof View) {
            if (parent instanceof ScrollView) return (ScrollView) parent;
            parent = parent.getParent();
        }
        throw new AssertionError("The download list must have a scrolling viewport");
    }
}
