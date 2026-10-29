package dev.thorshelf.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

/** Persisted appearance choice with immutable palettes resolved for each display context. */
final class AppTheme {
    static final String PREFERENCE = "theme";

    enum Mode {
        SYSTEM("system", "System"), LIGHT("light", "Light"), DARK("dark", "Dark"), OLED("oled", "OLED black");

        final String id;
        final String label;

        Mode(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final Palette LIGHT = new Palette(false, false,
            0xFFFAF8F2, Color.WHITE, 0xFFF3EEE5, 0xFF2D2827, 0xFF6F6862,
            0xFFDFD6CA, 0xFFF58821, 0xFFC45100, 0xFF277C47, 0xFF945600,
            Color.WHITE, 0xFFFFD4A5);
    private static final Palette DARK = new Palette(true, false,
            0xFF141312, 0xFF211E1C, 0xFF2C2723, 0xFFF4EEE8, 0xFFBEB2A9,
            0xFF51463E, 0xFFFFA64D, 0xFFFFA64D, 0xFF83D9A1, 0xFFFFC477,
            0xFF23150A, 0xFF79502C);
    private static final Palette OLED = new Palette(true, true,
            Color.BLACK, Color.BLACK, 0xFF171412, 0xFFF4EEE8, 0xFFBEB2A9,
            0xFF3B342D, 0xFFFFA64D, 0xFFFFA64D, 0xFF83D9A1, 0xFFFFC477,
            0xFF23150A, 0xFF79502C);

    private AppTheme() { }

    static Mode selected(Context context) {
        String id = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(PREFERENCE, Mode.SYSTEM.id);
        for (Mode mode : Mode.values()) if (mode.id.equals(id)) return mode;
        return Mode.SYSTEM;
    }

    static Mode resolved(Context context) {
        Mode selected = selected(context);
        if (selected != Mode.SYSTEM) return selected;
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES ? Mode.DARK : Mode.LIGHT;
    }

    static Palette palette(Context context) {
        switch (resolved(context)) {
            case OLED: return OLED;
            case DARK: return DARK;
            default: return LIGHT;
        }
    }

    static int style(Context context) {
        switch (resolved(context)) {
            case OLED: return R.style.AppThemeOled;
            case DARK: return R.style.AppThemeDark;
            default: return R.style.AppThemeLight;
        }
    }

    /** Apply before Activity.onCreate so all native widgets and dialogs get the selected theme. */
    static void apply(Activity activity) {
        activity.setTheme(style(activity));
        applyWindow(activity.getWindow(), activity);
    }

    /** Keep system controls legible on the selected background, including presentation windows. */
    static void applyWindow(Window window, Context context) {
        if (window == null) return;
        Palette colors = palette(context);
        // Installing DecorView reads the native theme's window defaults. Do that first
        // so those defaults cannot overwrite explicit contrast/icon settings below.
        View decor = window.getDecorView();
        window.setStatusBarColor(colors.BG);
        window.setNavigationBarColor(colors.BG);
        window.setNavigationBarDividerColor(colors.BG);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        decor.setForceDarkAllowed(false);
        int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility((decor.getSystemUiVisibility() & ~lightFlags) | (colors.dark ? 0 : lightFlags));
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(colors.dark ? 0 : mask, mask);
            }
        }
    }

    static final class Palette {
        final int BG, SURFACE, PANEL, SOFT, TEXT, MUTED, BORDER, ORANGE, ACCENT, SUCCESS, MINT, AMBER;
        final int ON_ACCENT, SWITCH_TRACK;
        final boolean dark, oled;

        private Palette(boolean dark, boolean oled, int bg, int surface, int soft, int text, int muted,
                int border, int orange, int accent, int success, int amber, int onAccent, int switchTrack) {
            this.dark = dark;
            this.oled = oled;
            BG = bg;
            SURFACE = surface;
            PANEL = surface;
            SOFT = soft;
            TEXT = text;
            MUTED = muted;
            BORDER = border;
            ORANGE = orange;
            ACCENT = accent;
            SUCCESS = success;
            MINT = accent;
            AMBER = amber;
            ON_ACCENT = onAccent;
            SWITCH_TRACK = switchTrack;
        }
    }
}
