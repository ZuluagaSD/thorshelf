package dev.thorshelf.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(17, 27, 32);
    static final int PANEL = Color.rgb(29, 43, 49);
    static final int TEXT = Color.rgb(238, 244, 243);
    static final int MUTED = Color.rgb(169, 188, 192);
    static final int MINT = Color.rgb(145, 222, 200);
    static final int AMBER = Color.rgb(244, 201, 132);
    private Ui() { }
    static int dp(Context c, int v) { return Math.round(c.getResources().getDisplayMetrics().density * v); }
    static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v;
    }
    static LinearLayout row(Context c) {
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }
    static void pad(View v, int amount) {
        int p = dp(v.getContext(), amount); v.setPadding(p, p, p, p);
    }
    static TextView text(Context c, String value, int sp, int color) {
        TextView v = new TextView(c); v.setText(value); v.setTextSize(sp); v.setTextColor(color);
        v.setPadding(0, dp(c, 4), 0, dp(c, 4)); return v;
    }
    static TextView heading(Context c, String value) {
        TextView v = text(c, value, 24, TEXT); v.setTypeface(null, Typeface.BOLD); return v;
    }
    static GradientDrawable rounded(Context c, int color, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, 12));
        if (stroke != 0) d.setStroke(dp(c, 2), stroke); return d;
    }
    static Button button(Context c, String label, Runnable action) {
        Button b = new Button(c); b.setText(label); b.setTextSize(13); b.setAllCaps(false);
        b.setTextColor(new ColorStateList(new int[][] {new int[] {android.R.attr.state_enabled}, new int[] {}},
                new int[] {TEXT, MUTED}));
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, rounded(c, PANEL, MINT));
        background.addState(new int[] {android.R.attr.state_pressed}, rounded(c, Color.rgb(49, 75, 76), MINT));
        background.addState(new int[] {android.R.attr.state_selected}, rounded(c, PANEL, MINT));
        background.addState(new int[] {}, rounded(c, PANEL, 0));
        b.setBackground(background); b.setOnClickListener(v -> action.run());
        return b;
    }
    static void weighted(LinearLayout row, View child) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(dp(row.getContext(), 3), dp(row.getContext(), 3), dp(row.getContext(), 3), dp(row.getContext(), 3));
        row.addView(child, p);
    }
}
