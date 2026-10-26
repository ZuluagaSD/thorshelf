package dev.thorshelf.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** An offline, editorial front shelf; every selection opens the real public catalog. */
final class BrowseHomeView extends ScrollView {
    interface Listener { void onOpenCatalog(String url); }

    private static final int PEACH = Color.rgb(255, 235, 215);
    private static final int ORANGE = Color.rgb(245, 136, 33);
    private final Listener listener;
    private final LinearLayout body;
    private boolean wide;
    private boolean compact;

    BrowseHomeView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setFillViewport(true);
        setClipToPadding(false);
        setVerticalScrollBarEnabled(false);
        body = Ui.column(context);
        addView(body, new ScrollView.LayoutParams(-1, -2));
        wide = context.getResources().getConfiguration().screenWidthDp >= 600;
        rebuild();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = View.MeasureSpec.getSize(widthMeasureSpec);
        boolean measuredWide = available >= Ui.dp(getContext(), 600);
        int availableHeight = View.MeasureSpec.getSize(heightMeasureSpec);
        boolean measuredCompact = measuredWide && availableHeight > 0
                && View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED
                && availableHeight < Ui.dp(getContext(), 420);
        if (available > 0 && (measuredWide != wide || measuredCompact != compact)) {
            wide = measuredWide;
            compact = measuredCompact;
            rebuild();
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void rebuild() {
        Context context = getContext();
        body.removeAllViews();
        int side = Ui.dp(context, wide ? 24 : 16);
        body.setPadding(side, Ui.dp(context, 6), side, Ui.dp(context, 20));
        body.addView(hero(), new LinearLayout.LayoutParams(-1, -2));
        body.addView(categories(), spaced(-1, -2, compact ? 8 : 10));

        String[] labels = {"Animal Crossing", "Mario Kart 7", "Professor Layton", "A Link Between Worlds"};
        String[] titles = {"Animal Crossing - New Leaf", "Mario Kart 7",
                "Professor Layton and the Miracle Mask", "The Legend of Zelda - A Link Between Worlds"};
        int[] art = {R.drawable.shelf_animal_crossing, R.drawable.shelf_mario_kart,
                R.drawable.shelf_layton, R.drawable.shelf_zelda};
        int columns = wide ? 4 : 2;
        for (int start = 0; start < titles.length; start += columns) {
            LinearLayout row = Ui.row(context);
            row.setGravity(Gravity.TOP);
            for (int index = start; index < Math.min(start + columns, titles.length); index++) {
                View tile = gameTile(labels[index], titles[index], art[index]);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                if (index > start) params.leftMargin = Ui.dp(context, 12);
                row.addView(tile, params);
            }
            body.addView(row, spaced(-1, -2, compact ? 8 : 10));
        }
        TextView note = Ui.text(context, "Illustrated picks · Choose a title to find its versions on hShop.", 12, Ui.MUTED);
        body.addView(note, spaced(-1, -2, 10));
    }

    private View hero() {
        Context context = getContext();
        LinearLayout copy = Ui.column(context);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(copy, compact ? 12 : wide ? 16 : 20);
        TextView eyebrow = Ui.text(context, "ON THE SHELF", compact ? 10 : 11, Color.rgb(159, 89, 34));
        eyebrow.setPadding(0, 0, 0, Ui.dp(context, 3));
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setTypeface(null, Typeface.BOLD);
        copy.addView(eyebrow);
        TextView title = Ui.text(context, "Pokémon Omega Ruby", compact ? 24 : wide ? 28 : 26, Ui.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setLineSpacing(0, 1.02f);
        copy.addView(title);
        if (!compact) copy.addView(Ui.text(context, "A little nostalgia. A new adventure.", 14, Ui.MUTED));
        Button open = Ui.primaryButton(context, "View game", () -> openTitle("Pokemon Omega Ruby"));
        open.setContentDescription("View Pokémon Omega Ruby in the catalog");
        copy.addView(open, spaced(-2, -2, 6));

        ImageView artwork = GameArtwork.image(context, R.drawable.shelf_hero, wide ? 960 : 400);
        artwork.setBackgroundColor(PEACH);
        artwork.setClipToOutline(false);
        if (wide) {
            LinearLayout copyRow = Ui.row(context);
            copyRow.addView(copy, new LinearLayout.LayoutParams(0, -2, 0.55f));
            View breathingRoom = new View(context);
            breathingRoom.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            copyRow.addView(breathingRoom, new LinearLayout.LayoutParams(0, 1, 0.45f));
            FrameLayout hero = new HeroFrame(context, copyRow, compact ? 130 : 180);
            hero.setBackground(Ui.rounded(context, PEACH, 0));
            hero.setClipToOutline(true);
            // Keep the complete character visible in a short banner; the pale image edge
            // blends into the matching surface beneath the overlaid title.
            artwork.setScaleType(ImageView.ScaleType.FIT_END);
            hero.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
            View fade = new View(context);
            fade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[] {PEACH, Color.argb(217, 255, 235, 215), Color.argb(0, 255, 235, 215)}));
            hero.addView(fade, new FrameLayout.LayoutParams(-1, -1));
            hero.addView(copyRow, new FrameLayout.LayoutParams(-1, -1));
            return hero;
        }
        LinearLayout hero = Ui.column(context);
        hero.setBackground(Ui.rounded(context, PEACH, 0));
        hero.setClipToOutline(true);
        hero.addView(artwork, new LinearLayout.LayoutParams(-1, Ui.dp(context, 170)));
        hero.addView(copy, new LinearLayout.LayoutParams(-1, -2));
        return hero;
    }

    private View categories() {
        Context context = getContext();
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout chips = Ui.row(context);
        String[] labels = {"All games", "Updates", "DLC", "Virtual Console"};
        String[] paths = {"games", "updates", "dlc", "virtual-console"};
        for (int index = 0; index < labels.length; index++) {
            final String url = DownloadPolicy.HOME + "c/" + paths[index];
            Button chip = index == 0
                    ? Ui.primaryButton(context, labels[index], () -> listener.onOpenCatalog(url))
                    : Ui.button(context, labels[index], () -> listener.onOpenCatalog(url));
            if (index == 0) chip.setSelected(true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            if (index > 0) params.leftMargin = Ui.dp(context, 8);
            chips.addView(chip, params);
        }
        scroll.addView(chips, new HorizontalScrollView.LayoutParams(-2, -2));
        return scroll;
    }

    private View gameTile(String label, String title, int resource) {
        Context context = getContext();
        LinearLayout tile = Ui.column(context);
        Ui.pad(tile, compact ? 5 : 6);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, Ui.rounded(context, Color.WHITE, ORANGE));
        background.addState(new int[] {android.R.attr.state_pressed}, Ui.rounded(context, PEACH, ORANGE));
        background.addState(new int[] {}, Ui.rounded(context, Color.WHITE, 0));
        tile.setBackground(background);
        tile.setElevation(Ui.dp(context, 1));
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        tile.setContentDescription(title + ". Find in the hShop catalog.");
        tile.setOnClickListener(view -> openTitle(title));
        ImageView art = new ShelfImageView(context, wide, compact);
        art.setImageDrawable(GameArtwork.image(context, resource, 280).getDrawable());
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.rounded(context, PEACH, 0));
        art.setClipToOutline(true);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(art, new LinearLayout.LayoutParams(-1, -2));
        TextView name = Ui.text(context, label, 14, Ui.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        name.setPadding(Ui.dp(context, 5), Ui.dp(context, compact ? 5 : 9),
                Ui.dp(context, 5), Ui.dp(context, compact ? 3 : 5));
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(name);
        return tile;
    }

    private void openTitle(String title) { listener.onOpenCatalog(DownloadPolicy.searchUrl(title)); }

    private LinearLayout.LayoutParams spaced(int width, int height, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = Ui.dp(getContext(), topDp);
        return params;
    }

    private static final class HeroFrame extends FrameLayout {
        private final View copy;
        private final int minimumHeightDp;

        HeroFrame(Context context, View copy, int minimumHeightDp) {
            super(context);
            this.copy = copy;
            this.minimumHeightDp = minimumHeightDp;
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // The title and action determine the banner height, including at larger font
            // scales. A background bitmap's intrinsic dimensions must not size the card.
            copy.measure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int desiredHeight = Math.max(Ui.dp(getContext(), minimumHeightDp), copy.getMeasuredHeight());
            int height = View.resolveSize(desiredHeight, heightMeasureSpec);
            super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        }
    }

    private static final class ShelfImageView extends ImageView {
        private final boolean wide;
        private final boolean compact;
        ShelfImageView(Context context, boolean wide, boolean compact) {
            super(context);
            this.wide = wide;
            this.compact = compact;
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = View.MeasureSpec.getSize(widthMeasureSpec);
            int height = compact ? Math.min(Math.round(width * 0.4f), Ui.dp(getContext(), 96))
                    : wide ? Math.round(width * 0.66f) : width;
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        }
    }
}
