package dev.thorshelf.app;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class CompanionPresentation extends Presentation {
    private TextView pageView, queueView;
    private String page = "Browse hShop", queue = "No downloads yet";
    CompanionPresentation(Context context, Display display) { super(context, display, R.style.AppTheme); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Context c = getContext();
        ScrollView scroll = new ScrollView(c); scroll.setBackgroundColor(Ui.BG);
        LinearLayout body = Ui.column(c); Ui.pad(body, 24); scroll.addView(body);
        body.addView(Ui.text(c, "THORSHELF  /  COMPANION", 13, Ui.MINT));
        body.addView(Ui.heading(c, "Your next adventure."));
        body.addView(Ui.text(c, "ON THE OTHER SCREEN", 12, Ui.MUTED));
        pageView = Ui.text(c, page, 20, Ui.TEXT); body.addView(pageView);
        body.addView(Ui.text(c, "DOWNLOADS", 12, Ui.MUTED));
        queueView = Ui.text(c, queue, 18, Ui.MINT); body.addView(queueView);
        body.addView(Ui.text(c, "Azahar uses decrypted content. CIA packages must be installed from inside Azahar.", 14, Ui.MUTED));
        body.addView(Ui.text(c, "D-pad · navigate\nA · select    B · back\nL / R · switch tabs    Y · search", 14, Ui.MUTED));
        setContentView(scroll);
    }
    void update(String currentPage, String summary) {
        page = currentPage; queue = summary;
        if (pageView != null) pageView.setText(page);
        if (queueView != null) queueView.setText(queue);
    }
}
