package dev.thorshelf.app;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static dev.thorshelf.app.CatalogUiTestSupport.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the real appearance chooser and recreation without downloading game content. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, qualifiers = "notnight")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ThemeUiTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private FakeClient client;
    private boolean resumed;

    @Before public void launch() {
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit()
                .remove(AppTheme.PREFERENCE).commit();
        client = new FakeClient();
        MainActivity.catalogClientFactory = () -> client;
        GameArtwork.clearRemoteCacheForTests();
        GameArtwork.clientFactory = () -> new ArtworkClient() {
            @Override public Bitmap fetch(String url, int pixels) {
                return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            }
        };
        controller = Robolectric.buildActivity(MainActivity.class).create().start().visible();
        bind();
    }

    @After public void close() throws Exception {
        client.releaseAll();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        ExecutorService worker = ReflectionHelpers.getField(activity, "downloadQueries");
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
        if (resumed) controller.pause();
        controller.stop().destroy();
        MainActivity.catalogClientFactory = CatalogClient::new;
        GameArtwork.clientFactory = ArtworkClient::new;
        GameArtwork.clearRemoteCacheForTests();
    }

    @Test public void chooserOffersSystemLightDarkAndOledAndCancelKeepsCurrentAppearance() {
        button("Settings").performClick();
        MainActivity original = activity;
        button("Theme: System").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Choose theme", shadowOf(dialog).getTitle().toString());
        assertEquals(4, dialog.getListView().getAdapter().getCount());
        assertEquals("System", dialog.getListView().getAdapter().getItem(0));
        assertEquals("Light", dialog.getListView().getAdapter().getItem(1));
        assertEquals("Dark", dialog.getListView().getAdapter().getItem(2));
        assertEquals("OLED black", dialog.getListView().getAdapter().getItem(3));
        assertEquals(0, dialog.getListView().getCheckedItemPosition());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(original, controller.get());
        assertEquals(AppTheme.Mode.SYSTEM, AppTheme.selected(activity));
        assertFalse(Ui.colors(activity).dark);
    }

    @Test public void changingThemePreservesGbaSearchFiltersAndDownloadHistory() throws Exception {
        button("GBA").performClick();
        await("GBA catalog", () -> shown("GBA catalog: No-Intro / libretro"));
        input().setText("Metroid Fusion"); button("Search").performClick();
        await("GBA results", () -> shown("Metroid Fusion"));
        ReflectionHelpers.callInstanceMethod(activity, "selectFilter",
                ReflectionHelpers.ClassParameter.from(String.class, "en"), ReflectionHelpers.ClassParameter.from(boolean.class, false));
        await("English filter", () -> "en".equals(CatalogFilters.selectedLanguage(source())) && shown("Metroid Fusion"));
        String sourceBefore = source();
        DownloadManager manager = activity.getSystemService(DownloadManager.class);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        String detail = "gba://catalog/title/f3ae088181bf583e55daf962a92bb46f4f1d07b7";
        GbaCatalog.Rom rom = GbaCatalog.lookup(detail);
        long id = new DownloadStore(activity).enqueueGba(rom.downloadUrl, rom.filename, null, detail, true, "", rom.sha1, rom.sizeBytes);
        shadowOf(shadowOf(manager).getRequest(id)).setStatus(DownloadManager.STATUS_PENDING);
        choose(AppTheme.Mode.OLED);
        assertTrue(button("Settings").isSelected());
        assertEquals("Theme: OLED black", button("Theme: OLED black").getText().toString());
        assertEquals("Metroid Fusion", input().getText().toString());
        assertEquals(sourceBefore, source());
        assertEquals("en", CatalogFilters.selectedLanguage(source()));
        assertEquals(GamePlatform.GBA, ReflectionHelpers.getField(activity, "platform"));
        DownloadStore.Entry entry = new DownloadStore(activity).list().get(0);
        assertEquals(id, entry.id);
        assertEquals(rom.sha1, entry.expectedSha1);
        assertEquals(rom.sizeBytes, entry.expectedSize);
        assertNotNull(shadowOf(manager).getRequest(id));
        button("Browse").performClick();
        await("restored GBA results", () -> shown("Metroid Fusion") && optionalButton("Download") != null);
        assertTrue(button("GBA").isSelected());
        assertEquals(Ui.colors(activity).TEXT, input().getCurrentTextColor());
    }

    @Test public void oledSelectionThemesNativeDialogAndSystemBarsAndPersistsColdLaunch() {
        choose(AppTheme.Mode.OLED);
        assertWindow(activity.getWindow(), true, Color.BLACK);
        button("Theme: OLED black").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals(Color.BLACK, themeColor(dialog.getContext(), android.R.attr.colorBackground));
        assertEquals(Ui.colors(activity).TEXT, themeColor(dialog.getContext(), android.R.attr.textColorPrimary));
        assertEquals(AppTheme.Mode.OLED.ordinal(), dialog.getListView().getCheckedItemPosition());
        dialog.dismiss();
        controller.stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).create().start().visible();
        bind();
        assertEquals(AppTheme.Mode.OLED, AppTheme.selected(activity));
        assertEquals(Color.BLACK, Ui.colors(activity).BG);
        assertWindow(activity.getWindow(), true, Color.BLACK);
        choose(AppTheme.Mode.LIGHT);
        assertWindow(activity.getWindow(), false, Ui.colors(activity).BG);
    }

    @Test public void systemAppearanceTracksNightChangesWhileFixedLightStaysLight() {
        assertFalse(Ui.colors(activity).dark);
        configureNight(true);
        assertTrue(Ui.colors(activity).dark);
        assertEquals(AppTheme.Mode.SYSTEM, AppTheme.selected(activity));
        assertEquals(Ui.colors(activity).TEXT, input().getCurrentTextColor());
        assertWindow(activity.getWindow(), true, Ui.colors(activity).BG);
        choose(AppTheme.Mode.LIGHT);
        assertFalse(Ui.colors(activity).dark);
        configureNight(false);
        configureNight(true);
        assertEquals(AppTheme.Mode.LIGHT, AppTheme.selected(activity));
        assertFalse(Ui.colors(activity).dark);
        assertWindow(activity.getWindow(), false, Ui.colors(activity).BG);
    }

    @Test public void companionRebuildsWithOledThemeAndCurrentScreen() {
        int type = ReflectionHelpers.getStaticField(Display.class, "TYPE_VIRTUAL");
        int id = ShadowDisplayManager.addDisplay("w620dp-h540dp", type);
        Display display = activity.getSystemService(DisplayManager.class).getDisplay(id);
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION);
        controller.resume(); resumed = true;
        shadowOf(Looper.getMainLooper()).idle();
        CompanionPresentation before = companion();
        assertNotNull(before);
        assertTrue(before.isShowing());
        choose(AppTheme.Mode.OLED);
        CompanionPresentation after = companion();
        assertNotNull(after);
        assertNotSame(before, after);
        assertFalse(before.isShowing());
        assertTrue(after.isShowing());
        assertTrue(Ui.colors(after.getContext()).oled);
        assertEquals(Color.BLACK, themeColor(after.getContext(), android.R.attr.colorBackground));
        assertNotNull(find(after.getWindow().getDecorView(), TextView.class,
                v -> "On main screen: Settings".contentEquals(v.getText())));
        assertWindow(after.getWindow(), true, Color.BLACK);
    }

    private void choose(AppTheme.Mode mode) {
        button("Settings").performClick();
        button("Theme: " + AppTheme.selected(activity).label).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        MainActivity previous = activity;
        int index = mode.ordinal();
        dialog.getListView().performItemClick(null, index, index);
        await("theme recreation", () -> controller.get() != previous);
        bind();
        controller.visible();
        assertEquals(mode, AppTheme.selected(activity));
    }
    private void configureNight(boolean night) {
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        controller.configurationChange(config).visible();
        bind();
    }
    private void bind() {
        activity = controller.get();
        ReflectionHelpers.setField(activity, "queryingDownloads", true);
    }
    private void assertWindow(Window window, boolean dark, int background) {
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 draws the app behind a transparent status bar. Navigation bars
            // can retain the requested color in three-button mode; gestures are transparent.
            assertEquals(Color.TRANSPARENT, window.getStatusBarColor());
            int navigation = window.getNavigationBarColor();
            assertTrue(navigation == Color.TRANSPARENT || navigation == background);
        } else {
            assertEquals(background, window.getStatusBarColor());
            assertEquals(background, window.getNavigationBarColor());
        }
        assertFalse(window.isStatusBarContrastEnforced());
        assertFalse(window.isNavigationBarContrastEnforced());
        assertDrawableBackground(window.getDecorView().getBackground(), background);
        ViewGroup content = window.findViewById(android.R.id.content);
        assertNotNull(content);
        assertTrue(content.getChildCount() > 0);
        // The root paints its inset/padding area too, providing the actual underlay
        // behind transparent system bars rather than relying only on theme metadata.
        assertDrawableBackground(content.getChildAt(0).getBackground(), background);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        assertEquals(dark ? 0 : flags, window.getDecorView().getSystemUiVisibility() & flags);
        if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            assertEquals(dark ? 0 : mask, window.getInsetsController().getSystemBarsAppearance() & mask);
        }
    }
    private static void assertDrawableBackground(Drawable drawable, int expected) {
        assertNotNull("The window and root need an opaque theme background", drawable);
        Rect bounds = new Rect(drawable.getBounds());
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 32, 32);
        drawable.draw(new Canvas(bitmap));
        assertEquals(expected, bitmap.getPixel(0, 0));
        assertEquals(expected, bitmap.getPixel(1, 1));
        drawable.setBounds(bounds);
        bitmap.recycle();
    }

    private static int themeColor(Context context, int attribute) {
        TypedValue value = new TypedValue();
        assertTrue(context.getTheme().resolveAttribute(attribute, value, true));
        return value.resourceId == 0 ? value.data : context.getColor(value.resourceId);
    }
    private CompanionPresentation companion() { return ReflectionHelpers.getField(activity, "companion"); }
    private String source() { return ReflectionHelpers.getField(activity, "sourcePage"); }
    private View root() { return activity.findViewById(android.R.id.content); }
    private EditText input() { return find(root(), EditText.class, v -> true); }
    private boolean shown(String text) { return find(root(), TextView.class, v -> v.isShown() && v.getText().toString().contains(text)) != null; }
    private Button optionalButton(String text) { return find(root(), Button.class, v -> v.isShown() && text.contentEquals(v.getText())); }
    private Button button(String text) {
        Button result = find(root(), Button.class, v -> text.contentEquals(v.getText()));
        assertNotNull(text, result); return result;
    }
}
