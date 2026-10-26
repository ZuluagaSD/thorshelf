package dev.thorshelf.app;

import android.app.DownloadManager;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A passive view of the main screen's queue; updates share the activity's refresh lifecycle. */
final class CompanionPresentation extends Presentation {
    private TextView pageView, queueView, headlineView, detailView, percentView, downloadingView, preparingView;
    private LinearLayout progressRow;
    private ProgressBar progressView;
    private ParcelView parcelView;
    private String page = "Browse hShop", queue = "No downloads yet";
    private List<DownloadStore.Entry> entries = Collections.emptyList();

    CompanionPresentation(Context context, Display display) {
        super(context, display, R.style.AppTheme);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Context c = getContext();
        boolean compact = c.getResources().getConfiguration().screenHeightDp < 440;
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setBackground(Ui.dottedBackground(c));
        LinearLayout body = Ui.column(c);
        Ui.pad(body, compact ? 14 : 22);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        LinearLayout brand = Ui.row(c);
        brand.addView(Ui.logo(c, 25), new LinearLayout.LayoutParams(Ui.dp(c, 25), Ui.dp(c, 25)));
        TextView name = Ui.text(c, "ThorShelf", 18, Ui.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams brandName = new LinearLayout.LayoutParams(-2, -2);
        brandName.setMargins(Ui.dp(c, 8), 0, Ui.dp(c, 12), 0);
        brand.addView(name, brandName);
        View separator = new View(c);
        separator.setBackgroundColor(Ui.BORDER);
        brand.addView(separator, new LinearLayout.LayoutParams(Ui.dp(c, 1), Ui.dp(c, 20)));
        TextView companion = Ui.text(c, "Companion", 13, Ui.MUTED);
        companion.setPadding(Ui.dp(c, 12), 0, 0, 0);
        brand.addView(companion);
        body.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        parcelView = Ui.parcel(c);
        parcelView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams parcelSize = new LinearLayout.LayoutParams(Ui.dp(c, compact ? 196 : 240),
                Ui.dp(c, compact ? 110 : 156));
        parcelSize.topMargin = Ui.dp(c, compact ? 4 : 10);
        body.addView(parcelView, parcelSize);

        headlineView = centered(c, "Your next adventure awaits", compact ? 21 : 25, Ui.TEXT);
        headlineView.setTypeface(null, Typeface.BOLD);
        body.addView(headlineView, new LinearLayout.LayoutParams(-1, -2));
        detailView = centered(c, "", 14, Ui.MUTED);
        body.addView(detailView, new LinearLayout.LayoutParams(-1, -2));

        progressRow = Ui.row(c);
        progressView = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        progressView.setMax(100);
        Ui.styleProgress(progressView);
        progressRow.addView(progressView, new LinearLayout.LayoutParams(0, Ui.dp(c, 12), 1));
        percentView = Ui.text(c, "", 14, Ui.TEXT);
        percentView.setTypeface(null, Typeface.BOLD);
        percentView.setPadding(Ui.dp(c, 12), 0, 0, 0);
        progressRow.addView(percentView);
        LinearLayout.LayoutParams progressSize = new LinearLayout.LayoutParams(-1, Ui.dp(c, 30));
        progressSize.setMargins(Ui.dp(c, 20), Ui.dp(c, 3), Ui.dp(c, 20), Ui.dp(c, 3));
        body.addView(progressRow, progressSize);

        View divider = new View(c);
        divider.setBackgroundColor(Ui.BORDER);
        LinearLayout.LayoutParams dividerSize = new LinearLayout.LayoutParams(-1, Ui.dp(c, 1));
        dividerSize.setMargins(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
        body.addView(divider, dividerSize);

        LinearLayout counters = Ui.row(c);
        downloadingView = centered(c, "No downloads", 13, Ui.SUCCESS);
        preparingView = centered(c, "Ready when you are", 13, Ui.SUCCESS);
        Ui.weighted(counters, downloadingView);
        View counterDivider = new View(c);
        counterDivider.setBackgroundColor(Ui.BORDER);
        counters.addView(counterDivider, new LinearLayout.LayoutParams(Ui.dp(c, 1), Ui.dp(c, 20)));
        Ui.weighted(counters, preparingView);
        body.addView(counters, new LinearLayout.LayoutParams(-1, -2));

        queueView = centered(c, queue, 12, Ui.MUTED);
        body.addView(queueView, new LinearLayout.LayoutParams(-1, -2));
        pageView = centered(c, "On main screen: " + page, 13, Ui.MUTED);
        pageView.setMaxLines(2);
        pageView.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(pageView, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
        render();
    }

    void update(String currentPage, String summary) {
        page = currentPage == null || currentPage.trim().isEmpty() ? "Browse hShop" : currentPage;
        queue = summary == null ? "" : summary;
        render();
    }

    void update(String currentPage, String summary, List<DownloadStore.Entry> downloads) {
        entries = downloads == null ? Collections.emptyList() : new ArrayList<>(downloads);
        update(currentPage, summary);
    }

    private void render() {
        if (headlineView == null) return;
        Context c = getContext();
        String preparation = PreparationService.status(c);
        boolean preparing = PreparationService.isRunning(c);
        boolean ready = PreparationService.isReady(c);
        int running = 0, active = 0, complete = 0;
        DownloadStore.Entry next = null;
        for (DownloadStore.Entry entry : entries) {
            if (entry.isComplete()) complete++;
            if (!entry.isActive()) continue;
            active++;
            if (entry.status == DownloadManager.STATUS_RUNNING) running++;
            if (next == null || (next.status != DownloadManager.STATUS_RUNNING
                    && entry.status == DownloadManager.STATUS_RUNNING)) next = entry;
        }

        progressRow.setVisibility(View.GONE);
        progressView.setIndeterminate(false);
        parcelView.setAnimating(preparing || running > 0);
        if (preparing) {
            headlineView.setText("Getting things ready");
            detailView.setText(preparation);
            showProgress(PreparationService.progress(c), false);
        } else if (next != null) {
            boolean downloading = next.status == DownloadManager.STATUS_RUNNING;
            headlineView.setText(downloading ? "On its way to your shelf" : "An adventure in the queue");
            detailView.setText(next.displayName + "\n" + next.statusLabel);
            if (next.total > 0) {
                int percent = (int) Math.min(100d, Math.max(0d, next.downloaded * 100d / next.total));
                showProgress(percent, false);
            } else if (downloading) {
                showProgress(0, true);
            }
        } else if (ready) {
            headlineView.setText("Ready for your next adventure");
            detailView.setText(PreparationText.summary(preparation, true));
            showProgress(100, false);
        } else if (!preparation.isEmpty()) {
            headlineView.setText("Preparation update");
            detailView.setText(preparation);
        } else if (complete > 0) {
            headlineView.setText("A little more adventure");
            detailView.setText("Your downloads are ready to prepare on the main screen.");
        } else {
            headlineView.setText("Your next adventure awaits");
            detailView.setText("Browse on the main screen. Your downloads will appear here.");
        }
        downloadingView.setText(running > 0 ? running + " downloading"
                : active > 0 ? active + " waiting"
                : complete > 0 ? complete + " downloaded" : "No downloads");
        preparingView.setText(preparing ? "1 preparing" : ready ? "Game ready" : "Ready when you are");
        // Older callers can still provide a queue summary without repeating a preparation message.
        String queueLine = queue.contains("\n") ? queue.substring(0, queue.indexOf('\n')) : queue;
        queueView.setText(queueLine);
        queueView.setVisibility(queueLine.isEmpty() ? View.GONE : View.VISIBLE);
        pageView.setText("On main screen: " + page);
    }

    private void showProgress(int percent, boolean indeterminate) {
        progressRow.setVisibility(View.VISIBLE);
        progressView.setIndeterminate(indeterminate);
        int bounded = Math.max(0, Math.min(100, percent));
        progressView.setProgress(bounded);
        percentView.setText(indeterminate ? "" : bounded + "%");
        percentView.setVisibility(indeterminate ? View.GONE : View.VISIBLE);
    }

    private static TextView centered(Context c, String text, int size, int color) {
        TextView view = Ui.text(c, text, size, color);
        view.setGravity(Gravity.CENTER);
        return view;
    }
}
