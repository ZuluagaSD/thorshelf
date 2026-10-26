package dev.thorshelf.app;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Gives the download queue a full-width scrolling viewport. */
final class DownloadsView extends LinearLayout {
    DownloadsView(Context context, View queueContent) {
        super(context);
        Ui.pad(this, 18);
        setClipChildren(false);
        ScrollView queue = new ScrollView(context);
        queue.setFillViewport(true);
        queue.setVerticalScrollBarEnabled(false);
        queue.setClipToPadding(false);
        queue.setPadding(Ui.dp(context, 2), 0, Ui.dp(context, 2), Ui.dp(context, 4));
        queue.addView(queueContent, new ScrollView.LayoutParams(-1, -2));
        setOrientation(VERTICAL);
        addView(queue, new LayoutParams(-1, -1));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int side = Ui.dp(getContext(), 18);
        int vertical = Ui.dp(getContext(), MeasureSpec.getSize(heightSpec) < Ui.dp(getContext(), 300) ? 8 : 18);
        setPadding(side, vertical, side, vertical);
        super.onMeasure(widthSpec, heightSpec);
    }
}
