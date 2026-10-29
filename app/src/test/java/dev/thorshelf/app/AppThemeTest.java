package dev.thorshelf.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.widget.Button;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Arrays;

import static org.junit.Assert.*;

/** Checks actual OLED pixels and usable foregrounds, independently of catalog/network behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppThemeTest {
    private Context context;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("settings", 0).edit().remove(AppTheme.PREFERENCE).commit();
    }

    @Test public void systemFollowsNightConfigurationAndExplicitChoicesOverrideIt() {
        Context day = configuration(false), night = configuration(true);
        assertEquals(AppTheme.Mode.SYSTEM, AppTheme.selected(day));
        assertEquals(AppTheme.Mode.LIGHT, AppTheme.resolved(day));
        assertEquals(AppTheme.Mode.DARK, AppTheme.resolved(night));
        select(AppTheme.Mode.LIGHT);
        assertEquals(AppTheme.Mode.LIGHT, AppTheme.resolved(night));
        select(AppTheme.Mode.DARK);
        assertEquals(AppTheme.Mode.DARK, AppTheme.resolved(day));
        select(AppTheme.Mode.OLED);
        assertEquals(AppTheme.Mode.OLED, AppTheme.resolved(day));
        assertEquals(AppTheme.Mode.OLED, AppTheme.resolved(night));
        context.getSharedPreferences("settings", 0).edit().putString(AppTheme.PREFERENCE, "unknown").commit();
        assertEquals(AppTheme.Mode.SYSTEM, AppTheme.selected(day));
        assertEquals(AppTheme.Mode.DARK, AppTheme.resolved(night));
    }

    @Test public void palettesAreImmutableAndDoNotLeakBetweenContexts() {
        Context day = configuration(false), night = configuration(true);
        AppTheme.Palette light = Ui.colors(day), dark = Ui.colors(night);
        assertFalse(light.dark);
        assertTrue(dark.dark);
        assertNotEquals(light.BG, dark.BG);
        int lightBackground = light.BG, darkBackground = dark.BG;
        select(AppTheme.Mode.OLED);
        assertEquals(Color.BLACK, Ui.colors(day).BG);
        assertEquals(lightBackground, light.BG);
        assertEquals(darkBackground, dark.BG);
        select(AppTheme.Mode.SYSTEM);
        assertEquals(lightBackground, Ui.colors(day).BG);
        assertEquals(darkBackground, Ui.colors(night).BG);
    }

    @Test public void launchWindowStaysBlackBeforeSavedAppearanceCanBeApplied() {
        Context launch = new ContextThemeWrapper(configuration(false), R.style.AppTheme);
        TypedValue window = new TypedValue();
        assertTrue(launch.getTheme().resolveAttribute(android.R.attr.windowBackground, window, true));
        Drawable background = window.resourceId == 0 ? new ColorDrawable(window.data)
                : launch.getDrawable(window.resourceId);
        int edge = Ui.dp(launch, 256);
        Bitmap image = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888);
        background.setBounds(0, 0, edge, edge);
        background.draw(new Canvas(image));
        // The centered brand icon keeps its colors; the broad field surrounding it
        // must be black before the activity can read the saved theme preference.
        for (int y = 0; y < edge; y++) for (int x = 0; x < edge; x++) {
            if (x < edge / 4 || x >= 3 * edge / 4 || y < edge / 4 || y >= 3 * edge / 4) {
                assertEquals("Launch backdrop outside the icon", Color.BLACK, image.getPixel(x, y));
            }
        }
        image.recycle();
        if (Build.VERSION.SDK_INT >= 31) {
            TypedValue splash = new TypedValue();
            assertTrue(launch.getTheme().resolveAttribute(android.R.attr.windowSplashScreenBackground, splash, true));
            assertEquals(Color.BLACK, splash.resourceId == 0 ? splash.data : launch.getColor(splash.resourceId));
        }
        select(AppTheme.Mode.LIGHT);
        assertFalse(Ui.colors(launch).dark);
        assertNotEquals(Color.BLACK, Ui.colors(launch).BG);
    }

    @Test public void darkThemesKeepTextStatusAndPrimaryActionsReadable() {
        for (AppTheme.Mode mode : new AppTheme.Mode[] {AppTheme.Mode.DARK, AppTheme.Mode.OLED}) {
            select(mode);
            AppTheme.Palette colors = Ui.colors(context);
            for (int background : new int[] {colors.BG, colors.SURFACE, colors.SOFT}) {
                for (int foreground : new int[] {colors.TEXT, colors.MUTED, colors.ACCENT, colors.SUCCESS, colors.AMBER}) {
                    assertTrue(mode + " foreground contrast below 4.5", contrast(foreground, background) >= 4.5);
                }
            }
            assertTrue(mode + " primary action contrast below 4.5", contrast(colors.ON_ACCENT, colors.ACCENT) >= 4.5);
        }
    }

    @Test public void oledBackgroundAndCardsHaveNoLitDecorationOrElevation() {
        select(AppTheme.Mode.OLED);
        assertTrue(Ui.colors(context).oled);
        assertEquals(Color.BLACK, Ui.colors(context).BG);
        assertEquals(Color.BLACK, Ui.colors(context).SURFACE);
        int[] background = pixels(Ui.dottedBackground(context));
        for (int pixel : background) assertEquals("OLED background must remain pure black", Color.BLACK, pixel);
        LinearLayout card = Ui.card(context);
        assertEquals(0f, card.getElevation(), 0f);
        int[] cardPixels = pixels(card.getBackground());
        assertEquals(Color.BLACK, cardPixels[32 * 160 + 80]);
        select(AppTheme.Mode.DARK);
        assertFalse(Ui.colors(context).oled);
        assertNotEquals(Color.BLACK, Ui.colors(context).BG);
        assertNotEquals(Color.BLACK, pixels(Ui.dottedBackground(context))[32 * 160 + 80]);
    }

    @Test public void buttonsUseReadableSelectedDisabledAndControllerFocusStates() {
        for (AppTheme.Mode mode : new AppTheme.Mode[] {AppTheme.Mode.LIGHT, AppTheme.Mode.DARK, AppTheme.Mode.OLED}) {
            select(mode);
            AppTheme.Palette colors = Ui.colors(context);
            Button primary = Ui.primaryButton(context, "Download", () -> { });
            assertEquals(colors.ON_ACCENT, primary.getCurrentTextColor());
            primary.setEnabled(false);
            assertEquals(colors.MUTED, primary.getCurrentTextColor());
            Button tab = Ui.tabButton(context, "GBA", () -> { });
            tab.setSelected(true);
            assertEquals(colors.ON_ACCENT, tab.getCurrentTextColor());
            Drawable drawable = tab.getBackground();
            drawable.setState(new int[] {android.R.attr.state_enabled, android.R.attr.state_selected});
            int[] selected = pixels(drawable);
            drawable.setState(new int[] {android.R.attr.state_enabled, android.R.attr.state_selected, android.R.attr.state_focused});
            int[] focused = pixels(drawable);
            assertFalse(mode + " selected tabs still need a distinct controller focus ring", Arrays.equals(selected, focused));
            assertTrue(tab.isFocusable());
        }
    }

    private Context configuration(boolean night) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return context.createConfigurationContext(config);
    }
    private void select(AppTheme.Mode mode) {
        context.getSharedPreferences("settings", 0).edit().putString(AppTheme.PREFERENCE, mode.id).commit();
    }
    private static int[] pixels(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(160, 64, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 160, 64);
        drawable.draw(new Canvas(bitmap));
        int[] pixels = new int[160 * 64];
        bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 64);
        bitmap.recycle();
        return pixels;
    }
    private static double contrast(int a, int b) {
        double first = luminance(a), second = luminance(b);
        return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
    }
    private static double luminance(int color) {
        return .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) + .0722 * linear(Color.blue(color));
    }
    private static double linear(int channel) {
        double value = channel / 255d;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
}
