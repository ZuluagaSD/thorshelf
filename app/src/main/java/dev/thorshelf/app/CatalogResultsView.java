package dev.thorshelf.app;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The public catalog presented with the same native controls as the front shelf. */
final class CatalogResultsView extends LinearLayout {
    interface Listener {
        void onNavigate(String url);
        void onDownload(CatalogPage.Entry entry);
        default void onOpenDownloads() { }
    }

    private final Listener listener;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final Map<String, List<Button>> downloadButtons = new HashMap<>();
    private final Map<String, DownloadState> downloadStates = new LinkedHashMap<String, DownloadState>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, DownloadState> eldest) {
            return size() > 128;
        }
    };
    private CatalogPage page;
    private boolean wide;
    private boolean ndsCatalog;
    private String language = "";
    private String region = "";

    CatalogResultsView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        wide = context.getResources().getConfiguration().screenWidthDp >= 760;
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setFocusableInTouchMode(true);
        scroll.setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(context, 12));
        body = Ui.column(context);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        updateBodyPadding();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        boolean measuredWide = width >= Ui.dp(getContext(), 760);
        if (width > 0 && measuredWide != wide) {
            wide = measuredWide;
            updateBodyPadding();
            if (page != null) renderPage();
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    void showLoading(String title) {
        clearPage();
        addHeading(valueOr(title, "The catalog"), "Loading your next adventure…");
        LinearLayout card = Ui.card(getContext());
        LinearLayout row = Ui.row(getContext());
        ProgressBar progress = new ProgressBar(getContext());
        Ui.styleProgress(progress);
        progress.setContentDescription("Loading catalog");
        row.addView(progress, new LinearLayout.LayoutParams(Ui.dp(getContext(), 28), Ui.dp(getContext(), 28)));
        TextView message = Ui.text(getContext(), "Bringing the catalog to your shelf", 15, Ui.MUTED);
        LinearLayout.LayoutParams copy = new LinearLayout.LayoutParams(0, -2, 1);
        copy.leftMargin = Ui.dp(getContext(), 14);
        row.addView(message, copy);
        card.addView(row);
        body.addView(card, spaced(-1, -2, 14));
    }

    void showError(String message, Runnable retry, Runnable verify) {
        clearPage();
        String title = verify == null ? "Couldn’t open the catalog" : "A quick check to continue";
        addHeading(title, "");
        LinearLayout card = Ui.card(getContext());
        TextView explanation = Ui.text(getContext(), valueOr(message,
                "Check your connection and try again."), 15, Ui.MUTED);
        explanation.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(explanation);
        LinearLayout actions = wide ? Ui.row(getContext()) : Ui.column(getContext());
        if (verify != null) actions.addView(Ui.primaryButton(getContext(), "Verify to continue", verify));
        if (retry != null) {
            Button retryButton = verify == null
                    ? Ui.primaryButton(getContext(), "Try again", retry)
                    : Ui.button(getContext(), "Try again", retry);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            if (verify != null) {
                if (wide) params.leftMargin = Ui.dp(getContext(), 10);
                else params.topMargin = Ui.dp(getContext(), 10);
            }
            actions.addView(retryButton, params);
        }
        if (actions.getChildCount() > 0) card.addView(actions, spaced(-1, -2, 14));
        body.addView(card, spaced(-1, -2, 14));
        addAttribution();
    }

    void showPage(CatalogPage page) {
        this.page = page;
        ndsCatalog = NdsCatalog.isPageUrl(page.url);
        // Replacing a focused result must not scroll straight to its action button.
        if (hasFocus()) scroll.requestFocus();
        renderPage();
        scroll.scrollTo(0, 0);
        scroll.post(() -> {
            if (this.page == page && scroll.isFocused()) scroll.scrollTo(0, 0);
        });
    }

    void setNdsCatalog(boolean ndsCatalog) {
        this.ndsCatalog = ndsCatalog;
    }

    void setFilters(String language, String region) {
        this.language = CatalogLanguages.normalize(language);
        this.region = CatalogRegions.normalize(region);
    }

    void setDownloadState(String titleUrl, String label, boolean busy) {
        if (titleUrl == null) return;
        String text = valueOr(label, "Download");
        if (!busy && "Download".equals(text)) downloadStates.remove(titleUrl);
        else downloadStates.put(titleUrl, new DownloadState(text, busy));
        List<Button> buttons = downloadButtons.get(titleUrl);
        if (buttons == null) return;
        for (Button button : buttons) applyDownloadState(button, downloadStates.get(titleUrl));
    }

    private void clearPage() {
        page = null;
        body.removeAllViews();
        downloadButtons.clear();
        scroll.scrollTo(0, 0);
    }

    private void renderPage() {
        body.removeAllViews();
        downloadButtons.clear();
        if (!page.isDetail()) addHeading(valueOr(page.title, "The catalog"), page.summary);
        if (page.categories != null && !page.categories.isEmpty()) {
            List<View> categories = new ArrayList<>();
            for (CatalogPage.Category category : page.categories) categories.add(categoryCard(category));
            addGrid(categories, wide ? 2 : 1);
        }
        if (page.entries != null && !page.entries.isEmpty()) {
            List<View> entries = new ArrayList<>();
            for (CatalogPage.Entry entry : page.entries) entries.add(entryCard(entry, page.isDetail()));
            addGrid(entries, wide && !page.isDetail() ? 2 : 1);
        } else if (page.categories == null || page.categories.isEmpty()) {
            LinearLayout empty = Ui.card(getContext());
            TextView title = Ui.text(getContext(), emptyTitle(), 18, Ui.TEXT);
            title.setTypeface(null, Typeface.BOLD);
            empty.addView(title);
            empty.addView(Ui.text(getContext(), emptyMessage(), 15, Ui.MUTED));
            body.addView(empty, spaced(-1, -2, 14));
        }
        if (page.isDetail() && hasText(page.summary)
                && !page.summary.equals("No description is available for this content.")) {
            body.addView(Ui.text(getContext(), page.summary, 14, Ui.MUTED), spaced(-1, -2, 16));
        }
        addPagination();
        addAttribution();
    }

    private String emptyTitle() {
        if (language.isEmpty() && region.isEmpty()) return "No titles on this shelf yet";
        return "No " + (language.isEmpty() ? "" : CatalogLanguages.label(language) + " ") + "titles"
                + (region.isEmpty() ? "" : " from " + CatalogRegions.label(region)) + " on this page";
    }

    private String emptyMessage() {
        if (language.isEmpty() && region.isEmpty()) return "Try a different title or browse another category.";
        String message = language.isEmpty()
                ? "Try another region or All regions."
                : "Titles without listed language information are excluded. "
                        + (region.isEmpty() ? "Try another language or All languages."
                                : "Try another language or region, or reset both filters.");
        return message + (hasText(page.nextUrl) ? " You can also check the next page." : "");
    }

    private void addHeading(String title, String summary) {
        TextView heading = Ui.heading(getContext(), title);
        heading.setTextSize(24);
        heading.setAccessibilityHeading(true);
        body.addView(heading);
        if (hasText(summary)) body.addView(Ui.text(getContext(), summary, 14, Ui.MUTED));
    }

    private View categoryCard(CatalogPage.Category category) {
        LinearLayout card = Ui.card(getContext());
        TextView title = Ui.text(getContext(), category.title, 19, Ui.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);
        if (hasText(category.description)) {
            card.addView(Ui.text(getContext(), category.description, 14, Ui.MUTED));
        }
        Button open = Ui.button(getContext(), "Browse category", () -> listener.onNavigate(category.url));
        open.setContentDescription("Browse " + category.title);
        revealCardOnFocus(open, card);
        card.addView(open, spaced(-2, -2, 10));
        return card;
    }

    private View entryCard(CatalogPage.Entry entry, boolean detail) {
        Context context = getContext();
        LinearLayout card = Ui.card(context);
        LinearLayout identity = Ui.row(context);
        identity.setGravity(Gravity.TOP);
        int artSize = detail ? 86 : 64;
        identity.addView(GameArtwork.thumbnail(context, entry.title, entry.artworkUrl, artSize),
                new LinearLayout.LayoutParams(Ui.dp(context, artSize), Ui.dp(context, artSize)));
        LinearLayout copy = Ui.column(context);
        TextView title = Ui.text(context, valueOr(entry.title, "Untitled content"), detail ? 22 : 18, Ui.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        copy.addView(title);
        String metadata = metadata(entry, detail);
        if (hasText(metadata)) copy.addView(Ui.text(context, metadata, 13, Ui.MUTED));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = Ui.dp(context, 14);
        identity.addView(copy, copyParams);
        card.addView(identity);

        if (NdsCatalog.isEditionSelectionUrl(entry.url)) {
            Button editions = Ui.primaryButton(context, "Choose edition", () -> listener.onNavigate(entry.url));
            editions.setContentDescription("Choose an edition of " + entry.title);
            revealCardOnFocus(editions, card);
            card.addView(editions, spaced(-1, -2, 14));
            return card;
        }

        if (detail) {
            if (hasText(entry.contentType)) addDetail(card, "Content", entry.contentType);
            if (hasText(entry.titleId)) addDetail(card, "Title ID", entry.titleId);
            String languages = languageNames(entry);
            if (hasText(languages)) addDetail(card, "Languages", languages);
            else card.addView(Ui.text(context, "Language not listed", 14, Ui.MUTED), spaced(-1, -2, 8));
        }
        LinearLayout actions = Ui.row(context);
        Button download = Ui.primaryButton(context, "Download", () -> {
            DownloadState latest = downloadStates.get(entry.url);
            if (isQueued(latest)) listener.onOpenDownloads();
            else if (latest == null || !latest.busy) listener.onDownload(entry);
        });
        download.setTag(entry.title);
        revealCardOnFocus(download, card);
        List<Button> buttons = downloadButtons.get(entry.url);
        if (buttons == null) {
            buttons = new ArrayList<>();
            downloadButtons.put(entry.url, buttons);
        }
        buttons.add(download);
        applyDownloadState(download, downloadStates.get(entry.url));
        actions.addView(download, new LinearLayout.LayoutParams(0, -2, 1));
        if (!detail) {
            Button details = Ui.button(context, "Details", () -> listener.onNavigate(entry.url));
            details.setContentDescription("View details for " + entry.title);
            revealCardOnFocus(details, card);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.leftMargin = Ui.dp(context, 10);
            actions.addView(details, params);
        }
        card.addView(actions, spaced(-1, -2, 14));
        return card;
    }

    private void addDetail(LinearLayout card, String label, String value) {
        TextView detail = Ui.text(getContext(), label + ": " + value, 14, Ui.MUTED);
        card.addView(detail, spaced(-1, -2, 8));
    }

    private String metadata(CatalogPage.Entry entry, boolean detail) {
        List<String> pieces = new ArrayList<>();
        if (hasText(entry.category)) pieces.add(metadataLabel(entry.category));
        if (NdsCatalog.isEditionSelectionUrl(entry.url) && hasText(entry.contentType)) pieces.add(entry.contentType);
        if (hasText(entry.region) && !entry.region.equalsIgnoreCase(entry.category)) pieces.add(metadataLabel(entry.region));
        if (hasText(entry.size)) pieces.add(entry.size);
        String languages = languageNames(entry);
        if (!detail && hasText(languages)) pieces.add(languages);
        return String.join(" · ", pieces);
    }

    private static String languageNames(CatalogPage.Entry entry) {
        List<String> labels = new ArrayList<>();
        for (String code : entry.languages) {
            if (!CatalogLanguages.normalize(code).isEmpty()) labels.add(CatalogLanguages.label(code));
        }
        return String.join(", ", labels);
    }

    private void addGrid(List<View> cards, int columns) {
        for (int start = 0; start < cards.size(); start += columns) {
            LinearLayout row = Ui.row(getContext());
            row.setGravity(Gravity.TOP);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            for (int index = start; index < Math.min(start + columns, cards.size()); index++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                if (index > start) params.leftMargin = Ui.dp(getContext(), 14);
                row.addView(cards.get(index), params);
            }
            if (columns == 2 && row.getChildCount() == 1) {
                View spacer = new View(getContext());
                spacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 1, 1);
                params.leftMargin = Ui.dp(getContext(), 14);
                row.addView(spacer, params);
            }
            body.addView(row, spaced(-1, -2, 14));
        }
    }

    private void addPagination() {
        if (!hasText(page.previousUrl) && !hasText(page.nextUrl)) return;
        LinearLayout navigation = Ui.row(getContext());
        if (hasText(page.previousUrl)) {
            Button previous = Ui.button(getContext(), "‹ Previous", () -> listener.onNavigate(page.previousUrl));
            previous.setContentDescription("Previous catalog page");
            navigation.addView(previous);
        }
        navigation.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1));
        if (hasText(page.nextUrl)) {
            Button next = Ui.primaryButton(getContext(), "Next ›", () -> listener.onNavigate(page.nextUrl));
            next.setContentDescription("Next catalog page");
            navigation.addView(next);
        }
        body.addView(navigation, spaced(-1, -2, 18));
    }

    private void addAttribution() {
        TextView attribution = Ui.text(getContext(), ndsCatalog
                ? page != null && page.summary.contains("NDS-Shop")
                        ? "Catalog and artwork from NDS-Shop · Check the region for each edition"
                        : "DS catalog: DSiDrop · Cover art: libretro · Check the language and region for each edition"
                : "Catalog information from hShop · Artwork is illustrative"
                        + (language.isEmpty() ? "" : " · Languages: 3dsdb.com"), 13, Ui.MUTED);
        body.addView(attribution, spaced(-1, -2, 16));
    }

    private LinearLayout.LayoutParams spaced(int width, int height, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = Ui.dp(getContext(), topDp);
        return params;
    }

    private void updateBodyPadding() {
        int side = Ui.dp(getContext(), wide ? 18 : 16);
        body.setPadding(side, Ui.dp(getContext(), 8), side, Ui.dp(getContext(), 20));
    }

    private void revealCardOnFocus(View action, View card) {
        action.setOnFocusChangeListener((view, focused) -> {
            if (!focused) return;
            action.post(() -> {
                if (!action.hasFocus() || !card.isAttachedToWindow()) return;
                int gap = Ui.dp(getContext(), 8);
                // Large text or a short viewport may require scrolling within a card.
                int visibleHeight = scroll.getHeight() - scroll.getVerticalFadingEdgeLength() * 2;
                if (card.getHeight() + gap * 2 > visibleHeight) return;
                card.requestRectangleOnScreen(new Rect(0, -gap,
                        card.getWidth(), card.getHeight() + gap), false);
            });
        });
    }

    private void applyDownloadState(Button button, DownloadState state) {
        String label = state == null ? "Download" : state.label;
        button.setText(label);
        button.setEnabled(state == null || !state.busy);
        button.setContentDescription(isQueued(state) ? "Open downloads for " + button.getTag()
                : label + " " + button.getTag());
    }

    private static boolean isQueued(DownloadState state) {
        return state != null && !state.busy && "Queued ✓".equals(state.label);
    }

    private static String metadataLabel(String value) {
        String label = value.trim().replace('-', ' ');
        if ("dlc".equalsIgnoreCase(label)) return "DLC";
        int first = label.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first)))
                + label.substring(Character.charCount(first));
    }

    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    private static String valueOr(String value, String fallback) { return hasText(value) ? value : fallback; }

    private static final class DownloadState {
        final String label;
        final boolean busy;

        DownloadState(String label, boolean busy) {
            this.label = label;
            this.busy = busy;
        }
    }
}
