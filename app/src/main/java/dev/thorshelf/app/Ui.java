package dev.thorshelf.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Shared native controls for ThorShelf's warm, handheld-friendly shop. */
final class Ui {
    private Ui() { }

    static AppTheme.Palette colors(Context c) { return AppTheme.palette(c); }

    static int dp(Context c, int v) { return Math.round(c.getResources().getDisplayMetrics().density * v); }

    static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    static void pad(View v, int amount) {
        int p = dp(v.getContext(), amount);
        v.setPadding(p, p, p, p);
    }

    static TextView text(Context c, String value, int sp, int color) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        v.setLineSpacing(dp(c, 2), 1f);
        v.setPadding(0, dp(c, 4), 0, dp(c, 4));
        return v;
    }

    static TextView heading(Context c, String value) {
        TextView v = text(c, value, 26, colors(c).TEXT);
        v.setTypeface(Typeface.create("sans-serif-rounded", Typeface.BOLD));
        v.setLetterSpacing(-0.025f);
        return v;
    }

    static GradientDrawable rounded(Context c, int color, int stroke) {
        return rounded(c, color, stroke, 18);
    }

    static GradientDrawable rounded(Context c, int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        if (stroke != 0) d.setStroke(dp(c, 1), stroke);
        return d;
    }

    static LinearLayout card(Context c) { return card(c, colors(c).SURFACE); }

    static LinearLayout card(Context c, int color) {
        LinearLayout v = column(c);
        pad(v, 18);
        v.setBackground(rounded(c, color, colors(c).BORDER));
        v.setElevation(colors(c).oled ? 0 : dp(c, 1));
        return v;
    }

    static Button button(Context c, String label, Runnable action) {
        return makeButton(c, label, action, false, false);
    }

    static Button primaryButton(Context c, String label, Runnable action) {
        return makeButton(c, label, action, true, false);
    }

    static Button tabButton(Context c, String label, Runnable action) {
        return makeButton(c, label, action, false, true);
    }

    static Button settingsButton(Context c, String label, Runnable action, boolean primary) {
        return makeButton(c, label, action, primary, false, 12);
    }

    private static Button makeButton(Context c, String label, Runnable action, boolean primary, boolean tab) {
        return makeButton(c, label, action, primary, tab, 26);
    }

    private static Button makeButton(Context c, String label, Runnable action, boolean primary, boolean tab, int radius) {
        AppTheme.Palette colors = colors(c);
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(tab ? 14 : 13);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setIncludeFontPadding(false);
        b.setTextColor(new ColorStateList(new int[][] {
                new int[] {-android.R.attr.state_enabled},
                new int[] {android.R.attr.state_selected}, new int[] {}},
                new int[] {colors.MUTED, tab || primary ? colors.ON_ACCENT : colors.TEXT,
                        primary ? colors.ON_ACCENT : colors.TEXT}));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(c, tab ? 44 : 48));
        b.setMinimumHeight(dp(c, tab ? 44 : 48));
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        b.setStateListAnimator(null);
        b.setBackgroundTintList(null);
        b.setElevation(0);
        int fill = primary ? colors.ACCENT : tab ? Color.TRANSPARENT : colors.SOFT;
        int stroke = primary || tab ? 0 : colors.BORDER;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {-android.R.attr.state_enabled}, rounded(c, colors.SOFT, colors.BORDER, radius));
        states.addState(new int[] {android.R.attr.state_focused, android.R.attr.state_selected},
                focusBackground(c, tab ? colors.ACCENT : fill, radius));
        states.addState(new int[] {android.R.attr.state_focused}, focusBackground(c, fill, radius));
        states.addState(new int[] {android.R.attr.state_selected}, rounded(c, tab ? colors.ACCENT : fill, stroke, radius));
        states.addState(new int[] {}, rounded(c, fill, stroke, radius));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.dark ? 0x40FFA64D : 0x30F58821), states,
                rounded(c, Color.WHITE, 0, radius)));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** Keep controller focus visible even when the focused tab is already selected. */
    private static Drawable focusBackground(Context c, int fill, int radius) {
        AppTheme.Palette colors = colors(c);
        GradientDrawable outer = rounded(c, colors.SURFACE, 0, radius);
        outer.setStroke(dp(c, 2), colors.ORANGE);
        GradientDrawable inner = rounded(c, fill == Color.TRANSPARENT ? colors.SOFT : fill, 0, Math.max(0, radius - 4));
        LayerDrawable layers = new LayerDrawable(new Drawable[] {outer, inner});
        int inset = dp(c, 4);
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    static void styleProgress(ProgressBar progress) {
        Context c = progress.getContext();
        AppTheme.Palette colors = colors(c);
        GradientDrawable track = rounded(c, colors.BORDER, 0, 20);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                colors.dark ? new int[] {colors.ACCENT, 0xFFFFC477}
                        : new int[] {Color.rgb(243, 122, 20), Color.rgb(255, 162, 54)});
        fill.setCornerRadius(dp(c, 20));
        ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable drawable = new LayerDrawable(new Drawable[] {track, clip});
        drawable.setId(0, android.R.id.background);
        drawable.setId(1, android.R.id.progress);
        progress.setProgressDrawable(drawable);
        progress.setIndeterminateTintList(ColorStateList.valueOf(colors.ORANGE));
        progress.setProgressTintList(null);
        progress.setProgressBackgroundTintList(null);
    }

    static ImageView logo(Context c, int sizeDp) {
        ImageView image = new ImageView(c);
        image.setImageResource(R.drawable.thorshelf_icon_foreground);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    static ParcelView parcel(Context c) { return new ParcelView(c); }

    static Drawable dottedBackground(Context c) { return new DottedBackground(c); }

    static void weighted(LinearLayout row, View child) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(dp(row.getContext(), 3), dp(row.getContext(), 3), dp(row.getContext(), 3), dp(row.getContext(), 3));
        row.addView(child, p);
    }

    private static final class DottedBackground extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final AppTheme.Palette colors;
        private int alpha = 255;
        DottedBackground(Context c) {
            density = c.getResources().getDisplayMetrics().density;
            colors = colors(c);
        }
        @Override public void draw(Canvas canvas) {
            paint.setColor(colors.BG);
            paint.setAlpha(alpha);
            canvas.drawRect(getBounds(), paint);
            if (colors.oled) return;
            paint.setColor(colors.dark ? colors.ORANGE : 0xFFD99545);
            paint.setAlpha((colors.dark ? 12 : 22) * alpha / 255);
            float step = 16 * density;
            for (float y = getBounds().top + step / 2; y < getBounds().bottom; y += step) {
                for (float x = getBounds().left + step / 2; x < getBounds().right; x += step) {
                    canvas.drawCircle(x, y, 0.8f * density, paint);
                }
            }
        }
        @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return alpha == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT; }
    }
}
