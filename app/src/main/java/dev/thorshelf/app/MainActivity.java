package dev.thorshelf.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.format.Formatter;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.Gravity;
import android.graphics.Typeface;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity implements DisplayManager.DisplayListener {
    private static final int SELECT_PREPARATION_FOLDER = 43;
    private static final int CHANGE_PREPARATION_FOLDER = 44;
    private static final int SAVE_NDS_DOCUMENT = 45;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService downloadQueries = Executors.newSingleThreadExecutor();
    private final ExecutorService artworkMetadataQueries = Executors.newSingleThreadExecutor();
    private final Set<String> artworkMetadataAttempted = new HashSet<>();
    private static final int MAX_ARTWORK_METADATA_REQUESTS = 32;
    private final Map<Long, DownloadCard> downloadCards = new LinkedHashMap<>();
    private final List<Button> tabButtons = new ArrayList<>();
    private SharedPreferences preferences;
    private DownloadStore downloads;
    private DisplayManager displays;
    private CompanionPresentation companion;
    static java.util.function.Supplier<CatalogClient> catalogClientFactory = CatalogClient::new;
    CatalogClient catalogClient = catalogClientFactory.get();
    static java.util.function.Supplier<NdsCatalogClient> ndsCatalogClientFactory = NdsCatalogClient::new;
    NdsCatalogClient ndsCatalogClient = ndsCatalogClientFactory.get();
    private final ExecutorService catalogQueries = Executors.newFixedThreadPool(2);
    private final ArrayList<String> catalogHistory = new ArrayList<>();
    private final android.util.LruCache<String, CatalogPage> catalogCache = new android.util.LruCache<>(8);
    private CatalogResultsView catalogResults;
    private CatalogVerification verification;
    private Future<?> catalogTask;
    private int catalogGeneration, downloadGeneration, verificationGeneration;
    private String userAgent;
    private String pendingDownload = "";
    private boolean parsingVerification;
    private EditText search;
    private TextView downloadSummary, exportStatus, displayStatus;
    private TextView downloadBadge, wifiStatus;
    private BrowseHomeView browseHome;
    private ScrollView ndsHome;
    private Button threeDsButton, ndsButton, languageButton, regionButton;
    private AlertDialog filterDialog;
    private String catalogLanguage = "";
    private String catalogRegion = "";
    private boolean ndsMode;
    private LinearLayout catalogPanel;
    private boolean showingShelf = true;
    private List<DownloadStore.Entry> latestEntries = new ArrayList<>();
    private FrameLayout content;
    private LinearLayout browsePanel, downloadList;
    private DownloadsView downloadsPanel;
    private ScrollView settingsPanel;
    private String sourcePage = DownloadPolicy.HOME;
    private String currentTitle = "hShop";
    private String queueSummary = "No downloads yet";
    private String renderedRows = "";
    private int tab = 0;
    private long exportId = -1;
    private String exportName = "";
    private long ndsExportId = -1;
    private String ndsExportName = "";
    private boolean deletingOriginal;
    private boolean checkingPreparationFolder;
    private TextView gamesFolderStatus;
    private Button changeGamesFolder;
    private boolean resumed;
    private boolean queryingDownloads;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshDownloads();
            handler.postDelayed(this, 1200);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        ndsMode = preferences.getBoolean("ndsMode", false);
        catalogLanguage = CatalogLanguages.normalize(preferences.getString("catalogLanguage", ""));
        catalogRegion = CatalogRegions.normalize(preferences.getString("catalogRegion", ""));
        downloads = new DownloadStore(this);
        displays = getSystemService(DisplayManager.class);
        if (state != null) {
            sourcePage = state.getString("sourcePage", DownloadPolicy.HOME);
            exportId = state.getLong("exportId", -1);
            exportName = state.getString("exportName", "");
            ndsExportId = state.getLong("ndsExportId", -1);
            ndsExportName = state.getString("ndsExportName", "");
            ndsMode = state.getBoolean("ndsMode", ndsMode);
            catalogLanguage = CatalogLanguages.normalize(state.getString("catalogLanguage", catalogLanguage));
            catalogRegion = CatalogRegions.normalize(state.getString("catalogRegion", catalogRegion));
            ArrayList<String> history = state.getStringArrayList("catalogHistory");
            if (history != null) for (String url : history) {
                if (isCatalogUrl(url) && catalogHistory.size() < 50) catalogHistory.add(url);
            }
        }
        buildUi();
        userAgent = WebSettings.getDefaultUserAgent(this);
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
        boolean restoreCatalog = state != null && !state.getBoolean("showingShelf", true)
                && isCatalogUrl(sourcePage);
        if (restoreCatalog) showCatalog(sourcePage, false);
        else setShelfVisible(true);
        if (state != null) search.setText(state.getString("searchText", ""));
        showTab(state == null ? 0 : state.getInt("tab", 0));
        if (state != null && state.getBoolean("checkingPreparationFolder", false)) continueCopy();
    }

    private boolean wideLayout() {
        return getResources().getConfiguration().screenWidthDp >= 700;
    }

    private void buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackground(Ui.dottedBackground(this));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
                view.setPadding(edges.left, edges.top, edges.right, edges.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        LinearLayout heading = Ui.row(this);
        heading.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
        heading.setBackgroundColor(Ui.BG);
        LinearLayout brand = Ui.row(this);
        brand.addView(Ui.logo(this, 34));
        TextView name = Ui.heading(this, "ThorShelf");
        name.setTextSize(24);
        name.setPadding(Ui.dp(this, 9), 0, Ui.dp(this, 12), 0);
        brand.addView(name);
        heading.addView(brand, new LinearLayout.LayoutParams(wideLayout() ? -2 : 0, -2, wideLayout() ? 0 : 1));
        LinearLayout tabs = Ui.row(this);
        Ui.pad(tabs, 3);
        tabs.setBackground(Ui.rounded(this, Ui.SOFT, Ui.BORDER, 28));
        String[] names = {"Browse", "Downloads", "Settings"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button button = Ui.tabButton(this, names[i], () -> {
                if (index == 0 && tab == 0) setShelfVisible(true);
                showTab(index);
            });
            tabButtons.add(button);
            tabs.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        downloadBadge = Ui.text(this, "", 12, Ui.ACCENT);
        downloadBadge.setGravity(Gravity.CENTER);
        downloadBadge.setMinWidth(Ui.dp(this, 24));
        downloadBadge.setContentDescription("Active downloads");
        downloadBadge.setVisibility(View.GONE);
        if (wideLayout()) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            tp.setMargins(Ui.dp(this, 20), 0, Ui.dp(this, 12), 0);
            heading.addView(tabs, tp);
        }
        heading.addView(downloadBadge);
        heading.addView(Ui.button(this, "Azahar ↗", this::azaharHelp));
        root.addView(heading);
        if (!wideLayout()) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
            tp.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 6));
            root.addView(tabs, tp);
        }
        View rule = new View(this); rule.setBackgroundColor(Ui.BORDER);
        root.addView(rule, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        buildCatalog(); buildDownloads(); buildSettings();
        content.addView(browsePanel, new FrameLayout.LayoutParams(-1, -1));
        content.addView(downloadsPanel, new FrameLayout.LayoutParams(-1, -1));
        content.addView(settingsPanel, new FrameLayout.LayoutParams(-1, -1));
        TextView hints = Ui.text(this, wideLayout()
                ? "Ⓐ  Select     Ⓑ  Back          L / R  Tabs          Ⓨ  Search"
                : "Ⓐ Select    Ⓑ Back    L / R Tabs    Ⓨ Search", 12, Ui.MUTED);
        hints.setGravity(Gravity.CENTER);
        hints.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        hints.setBackgroundColor(Ui.BG);
        root.addView(hints);
    }

    private void buildCatalog() {
        browsePanel = Ui.column(this);
        LinearLayout searchRow = Ui.row(this);
        searchRow.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15); search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.MUTED);
        search.setHint("Search 3DS games"); search.setContentDescription("Search Nintendo 3DS games");
        search.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        search.setMinHeight(Ui.dp(this, 48));
        search.setBackground(Ui.rounded(this, Ui.SURFACE, Ui.BORDER, 28));
        search.setOnFocusChangeListener((v, focused) ->
                search.setBackground(Ui.rounded(this, Ui.SURFACE, focused ? Ui.ORANGE : Ui.BORDER, 28)));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                submitSearch(); return true;
            }
            return false;
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams searchButton = new LinearLayout.LayoutParams(-2, -2);
        searchButton.setMarginStart(Ui.dp(this, 8));
        searchRow.addView(Ui.primaryButton(this, "Search", this::submitSearch), searchButton);
        browsePanel.addView(searchRow, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout platforms = Ui.row(this);
        platforms.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 6));
        TextView filterLabel = Ui.text(this, "Search in", 13, Ui.MUTED);
        filterLabel.setPadding(0, 0, Ui.dp(this, 12), 0);
        platforms.addView(filterLabel);
        LinearLayout platformOptions = Ui.row(this);
        Ui.pad(platformOptions, 3);
        platformOptions.setBackground(Ui.rounded(this, Ui.SOFT, Ui.BORDER, 28));
        threeDsButton = Ui.tabButton(this, "3DS", () -> selectPlatform(false));
        threeDsButton.setContentDescription("Search Nintendo 3DS games");
        ndsButton = Ui.tabButton(this, "DS", () -> selectPlatform(true));
        ndsButton.setContentDescription("Search Nintendo DS games");
        platformOptions.addView(threeDsButton, new LinearLayout.LayoutParams(0, -2, 1));
        platformOptions.addView(ndsButton, new LinearLayout.LayoutParams(0, -2, 1));
        platforms.addView(platformOptions, new LinearLayout.LayoutParams(wideLayout() ? Ui.dp(this, 170) : 0,
                -2, wideLayout() ? 0 : 1));
        LinearLayout filters = Ui.row(this);
        languageButton = filterButton(() -> chooseFilter(false));
        regionButton = filterButton(() -> chooseFilter(true));
        filters.addView(languageButton, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams region = new LinearLayout.LayoutParams(0, -2, 1);
        region.setMarginStart(Ui.dp(this, 8));
        filters.addView(regionButton, region);
        if (wideLayout()) {
            LinearLayout.LayoutParams options = new LinearLayout.LayoutParams(0, -2, 1);
            options.setMarginStart(Ui.dp(this, 12));
            platforms.addView(filters, options);
        }
        browsePanel.addView(platforms);
        if (!wideLayout()) {
            LinearLayout.LayoutParams options = new LinearLayout.LayoutParams(-1, -2);
            options.setMargins(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 6));
            browsePanel.addView(filters, options);
        }
        browseHome = new BrowseHomeView(this, this::loadCatalog);
        browsePanel.addView(browseHome, new LinearLayout.LayoutParams(-1, 0, 1));
        ndsHome = new ScrollView(this);
        LinearLayout dsBody = Ui.column(this);
        Ui.pad(dsBody, 18);
        LinearLayout dsCard = Ui.card(this);
        dsCard.addView(Ui.heading(this, "A new shelf for Nintendo DS."));
        dsCard.addView(Ui.text(this, "Explore Nintendo DS games across regions. Use Language and Region to find your edition.", 16, Ui.MUTED));
        dsCard.addView(Ui.primaryButton(this, "Browse DS games", () -> loadCatalog(NdsCatalog.HOME)));
        dsCard.addView(Ui.text(this, "Download a title, then choose Save DS game to copy its .nds file into your DS emulator’s games folder.", 15, Ui.TEXT));
        dsBody.addView(dsCard);
        ndsHome.addView(dsBody);
        browsePanel.addView(ndsHome, new LinearLayout.LayoutParams(-1, 0, 1));
        catalogPanel = Ui.column(this);
        catalogResults = new CatalogResultsView(this, new CatalogResultsView.Listener() {
            @Override public void onNavigate(String url) { loadCatalog(url); }
            @Override public void onDownload(CatalogPage.Entry entry) { downloadCatalogEntry(entry); }
            @Override public void onOpenDownloads() { showTab(1); }
        });
        catalogPanel.addView(catalogResults, new LinearLayout.LayoutParams(-1, 0, 1));
        browsePanel.addView(catalogPanel, new LinearLayout.LayoutParams(-1, 0, 1));
        updatePlatformUi();
    }

    private static boolean isCatalogUrl(String url) {
        return CatalogParser.isCatalogPageUrl(url) || NdsCatalog.isPageUrl(url);
    }

    private void selectPlatform(boolean ds) {
        if (ndsMode == ds) return;
        ndsMode = ds;
        preferences.edit().putBoolean("ndsMode", ds).apply();
        dismissVerification();
        // An old provider's in-flight response must not enqueue or open verification here.
        catalogResults.setDownloadState(pendingDownload, "Download", false);
        pendingDownload = "";
        downloadGeneration++;
        sourcePage = ds ? NdsCatalog.HOME : DownloadPolicy.HOME;
        setShelfVisible(true);
        updatePlatformUi();
        if (!search.getText().toString().trim().isEmpty()) submitSearch();
        else if (ds) loadCatalog(NdsCatalog.HOME);
        else if (hasActiveCatalogFilters()) loadCatalog(DownloadPolicy.HOME + "c/games");
    }

    private void updatePlatformUi() {
        threeDsButton.setSelected(!ndsMode);
        ndsButton.setSelected(ndsMode);
        search.setHint(ndsMode ? "Search DS games" : "Search 3DS games");
        search.setContentDescription(ndsMode ? "Search Nintendo DS games" : "Search Nintendo 3DS games");
        catalogResults.setNdsCatalog(ndsMode);
        updateFilterUi();
    }

    private Button filterButton(Runnable action) {
        Button button = Ui.button(this, "", action);
        button.setMinHeight(Ui.dp(this, 44));
        button.setMinimumHeight(Ui.dp(this, 44));
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return button;
    }

    private boolean hasActiveCatalogFilters() {
        return !catalogLanguage.isEmpty() || !catalogRegion.isEmpty();
    }

    private void updateFilterUi() {
        String label = CatalogLanguages.label(catalogLanguage);
        languageButton.setText("Language: " + label + " ▾");
        languageButton.setContentDescription("Filter game language: " + label);
        String region = CatalogRegions.label(catalogRegion);
        regionButton.setText("Region: " + region + " ▾");
        regionButton.setContentDescription("Filter game region: " + region);
        catalogResults.setFilters(catalogLanguage, catalogRegion);
    }

    private void chooseFilter(boolean region) {
        if (filterDialog != null) return;
        List<String> codes = new ArrayList<>();
        codes.add("");
        codes.addAll(region ? CatalogRegions.codes() : CatalogLanguages.codes());
        String[] labels = new String[codes.size()];
        for (int i = 0; i < codes.size(); i++) labels[i] = region
                ? CatalogRegions.label(codes.get(i)) : CatalogLanguages.label(codes.get(i));
        filterDialog = new AlertDialog.Builder(this).setTitle(region ? "Game region" : "Game language")
                .setSingleChoiceItems(labels, codes.indexOf(region ? catalogRegion : catalogLanguage), (dialog, which) -> {
                    dialog.dismiss();
                    selectFilter(codes.get(which), region);
                })
                .setNegativeButton("Cancel", null).create();
        filterDialog.setOnDismissListener(dialog -> filterDialog = null);
        filterDialog.setOnKeyListener((dialog, key, event) -> {
            if (key == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) dialog.dismiss();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                android.widget.ListView choices = ((AlertDialog) dialog).getListView();
                int selected = choices == null ? -1 : choices.getSelectedItemPosition();
                if (choices != null && choices.hasFocus() && selected >= 0) {
                    if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                        choices.performItemClick(choices.getSelectedView(), selected, choices.getItemIdAtPosition(selected));
                    }
                    return true;
                }
                KeyEvent mapped = new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                        KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount(), event.getMetaState(),
                        event.getDeviceId(), event.getScanCode(), event.getFlags(), event.getSource());
                return ((AlertDialog) dialog).dispatchKeyEvent(mapped);
            }
            return false;
        });
        filterDialog.show();
    }

    private void selectFilter(String code, boolean region) {
        String selected = region ? CatalogRegions.normalize(code) : CatalogLanguages.normalize(code);
        if ((region ? catalogRegion : catalogLanguage).equals(selected)) return;
        if (region) catalogRegion = selected;
        else catalogLanguage = selected;
        preferences.edit().putString(region ? "catalogRegion" : "catalogLanguage", selected).apply();
        updateFilterUi();
        dismissVerification();
        catalogResults.setDownloadState(pendingDownload, "Download", false);
        pendingDownload = "";
        downloadGeneration++;
        catalogGeneration++;
        if (catalogTask != null) catalogTask.cancel(true);
        String editionParent = null;
        if (NdsCatalog.isEditionSelectionUrl(sourcePage)) {
            for (int index = catalogHistory.size() - 1; index >= 0; index--) {
                String previous = catalogHistory.get(index);
                if (NdsCatalog.isPageUrl(previous) && !NdsCatalog.isDetailUrl(previous)
                        && !NdsCatalog.isEditionSelectionUrl(previous)) {
                    editionParent = previous;
                    break;
                }
            }
            if (editionParent == null) {
                String query = search.getText().toString().trim();
                editionParent = query.isEmpty() ? NdsCatalog.HOME : NdsCatalog.searchUrl(query);
            }
        }
        catalogHistory.clear();
        if (editionParent != null) {
            catalogHistory.add(CatalogFilters.apply(editionParent, catalogLanguage, catalogRegion, true));
        }
        catalogCache.evictAll();
        String next = sourcePage;
        boolean detail = NdsCatalog.isDetailUrl(next) || (CatalogParser.isCatalogPageUrl(next)
                && Uri.parse(next).getPath().matches("/t/[0-9]+/?"));
        if (showingShelf || detail) {
            String query = search.getText().toString().trim();
            next = !query.isEmpty() ? (ndsMode ? NdsCatalog.searchUrl(query) : DownloadPolicy.searchUrl(query))
                    : ndsMode ? NdsCatalog.HOME : DownloadPolicy.HOME + "c/games";
        }
        showCatalog(CatalogFilters.apply(next, catalogLanguage, catalogRegion, true), false);
    }

    private void setShelfVisible(boolean visible) {
        showingShelf = visible;
        if (visible) {
            catalogGeneration++;
            if (catalogTask != null) catalogTask.cancel(true);
            catalogHistory.clear();
        }
        browseHome.setVisibility(visible && !ndsMode ? View.VISIBLE : View.GONE);
        ndsHome.setVisibility(visible && ndsMode ? View.VISIBLE : View.GONE);
        catalogPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
        updateCompanion();
    }

    private void buildDownloads() {
        LinearLayout body = Ui.column(this);
        body.setClipChildren(false);
        LinearLayout heading = Ui.row(this);
        heading.addView(Ui.heading(this, "Downloads"), new LinearLayout.LayoutParams(0, -2, 1));
        wifiStatus = Ui.text(this, "Wi-Fi only", 12, Ui.MUTED);
        Ui.pad(wifiStatus, 10);
        wifiStatus.setBackground(Ui.rounded(this, Ui.SURFACE, Ui.BORDER, 24));
        heading.addView(wifiStatus);
        body.addView(heading);
        body.addView(Ui.text(this, "A little closer to your next adventure.", 15, Ui.MUTED));
        downloadSummary = Ui.text(this, "", 13, Ui.ACCENT); body.addView(downloadSummary);
        exportStatus = Ui.text(this, "", 14, Ui.AMBER); body.addView(exportStatus);
        downloadList = Ui.column(this);
        downloadList.setClipChildren(false);
        body.addView(downloadList, new LinearLayout.LayoutParams(-1, -2));
        downloadsPanel = new DownloadsView(this, body);
    }

    private void buildSettings() {
        settingsPanel = new ScrollView(this);
        LinearLayout body = Ui.column(this); Ui.pad(body, 18); settingsPanel.addView(body);
        body.addView(Ui.heading(this, "Make yourself at home."));
        body.addView(Ui.text(this, "A few little things, just the way you like them.", 15, Ui.MUTED));
        LinearLayout downloadSettings = settingsCard(body, "Downloads");
        Switch wifi = new Switch(this); wifi.setText("Wi-Fi only"); styleSwitch(wifi);
        wifi.setChecked(preferences.getBoolean("wifiOnly", true));
        wifi.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("wifiOnly", checked).apply();
            wifiStatus.setText(checked ? "Wi-Fi only" : "All networks");
        });
        downloadSettings.addView(wifi);
        downloadSettings.addView(Ui.text(this, "Applies to new downloads. Android keeps your queue moving in the background and retries interrupted transfers.", 14, Ui.MUTED));
        LinearLayout companionSettings = settingsCard(body, "Your second screen");
        Switch dual = new Switch(this); dual.setText("Companion display"); styleSwitch(dual);
        dual.setChecked(preferences.getBoolean("companion", true));
        dual.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("companion", checked).apply(); configureCompanion();
        });
        companionSettings.addView(dual);
        displayStatus = Ui.text(this, "", 14, Ui.MUTED); companionSettings.addView(displayStatus);
        companionSettings.addView(Ui.text(this, "Keep an eye on downloads and preparation while you browse. Works with an available second display, or on a single screen.", 14, Ui.MUTED));
        LinearLayout games = settingsCard(body, "Ready for Azahar");
        gamesFolderStatus = Ui.text(this, "", 15, Ui.TEXT);
        games.addView(gamesFolderStatus);
        changeGamesFolder = Ui.button(this, "Choose games folder", () -> openGamesFolderPicker(CHANGE_PREPARATION_FOLDER));
        games.addView(changeGamesFolder);
        games.addView(Ui.text(this, "Preparations use this folder automatically. Changing it applies to future preparations; existing files stay where they are.", 14, Ui.MUTED));
        updateGamesFolder();
        games.addView(Ui.text(this, "1. Search for a game and choose Download on its card.\n2. Choose Prepare for Azahar. Select your games folder the first time.\n3. Add that folder in Azahar, then refresh its game list.", 15, Ui.TEXT));
        games.addView(Ui.text(this, "Your original download stays safe. Preparation needs temporary internal storage of about five times the download size, plus space for the prepared copy. Updates and DLC still need Install CIA in Azahar and their matching game.", 14, Ui.MUTED));
        games.addView(Ui.primaryButton(this, "Open Azahar", this::launchAzahar));
        LinearLayout dsGames = settingsCard(body, "Nintendo DS games");
        dsGames.addView(Ui.text(this, "Choose DS under Search in to explore the catalog. Use Language and Region, then Choose edition when a game has multiple releases. Demos, prototypes, and known bad dumps are excluded. Completed downloads offer Save DS game: choose a new .nds file in your DS games folder, then open it in your DS emulator.", 15, Ui.TEXT));
        dsGames.addView(Ui.text(this, "DS games do not need CIA preparation. ZIPs must contain exactly one .nds file; games requiring extra data files may need manual setup. 7z archives and DSiWare are not supported by this DS workflow.", 14, Ui.MUTED));
        dsGames.addView(Ui.button(this, "DS catalog: DSiDrop", () -> openExternal("https://github.com/0x1a4-dev/DSiDrop")));
        dsGames.addView(Ui.button(this, "DS cover art: libretro", () -> openExternal("https://github.com/libretro-thumbnails/Nintendo_-_Nintendo_DS")));
        dsGames.addView(Ui.button(this, "DS language metadata: GameTDB", () -> openExternal("https://www.gametdb.com/DS/Downloads")));
        LinearLayout controls = settingsCard(body, "Made for your handheld");
        controls.addView(Ui.text(this, "D-pad: move focus · A: select · B: back\nL / R: switch tabs · Y: search\nUse touch if hShop asks for verification.", 15, Ui.TEXT));
        LinearLayout help = settingsCard(body, "A little help");
        help.addView(Ui.text(this, "If a download link expires, choose Download again for a fresh one. If verification will not load, update Android System WebView or open the catalog in your browser.", 14, Ui.MUTED));
        help.addView(Ui.button(this, "Open catalog in browser", () -> openExternal(ndsMode
                ? "https://github.com/0x1a4-dev/DSiDrop" : CatalogFilters.sourceUrl(sourcePage))));
        help.addView(Ui.button(this, "Open-source licenses", this::showLicenses));
        help.addView(Ui.button(this, "3DS cover art: GameTDB", () -> openExternal("https://www.gametdb.com/")));
        body.addView(Ui.text(this, "ThorShelf 0.5.0 · Unofficial companion\nCatalogs: hShop and DSiDrop. DS cover art: libretro. Not affiliated with these services, Nintendo, AYN, or Azahar. Shelf illustrations are bundled editorial artwork.", 12, Ui.MUTED));
    }

    private LinearLayout settingsCard(LinearLayout parent, String title) {
        LinearLayout card = Ui.card(this);
        TextView heading = Ui.heading(this, title); heading.setTextSize(20); card.addView(heading);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        parent.addView(card, params);
        return card;
    }

    private void styleSwitch(Switch control) {
        control.setTextColor(Ui.TEXT); control.setTextSize(16);
        control.setMinHeight(Ui.dp(this, 52));
        control.setThumbTintList(new android.content.res.ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {Ui.ACCENT, Ui.MUTED}));
        control.setTrackTintList(new android.content.res.ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {0xFFFFD4A5, Ui.BORDER}));
    }

    private void showLicenses() {
        downloadQueries.execute(() -> {
            String notice;
            try (InputStream input = getAssets().open("THIRD_PARTY_NOTICES.txt");
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                notice = new String(output.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                notice = "License notices could not be opened. They are also included with the ThorShelf source.";
            }
            String text = notice;
            handler.post(() -> {
                if (isDestroyed()) return;
                ScrollView scroll = new ScrollView(this);
                TextView details = Ui.text(this, text, 13, Ui.TEXT);
                details.setTextIsSelectable(true); Ui.pad(details, 16); scroll.addView(details);
                new AlertDialog.Builder(this).setTitle("Open-source licenses").setView(scroll)
                        .setPositiveButton("Close", null).show();
            });
        });
    }

    private void submitSearch() {
        String query = search.getText().toString().trim();
        if (query.isEmpty()) { search.requestFocus(); return; }
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
        loadCatalog(ndsMode ? NdsCatalog.searchUrl(query) : DownloadPolicy.searchUrl(query));
    }
    private void loadCatalog(String url) {
        if (!isCatalogUrl(url)) return;
        showTab(0);
        showCatalog(url, true);
    }

    private void showCatalog(String requestedUrl, boolean addHistory) {
        String url = CatalogFilters.apply(requestedUrl, catalogLanguage, catalogRegion, false);
        if (!isCatalogUrl(url)) return;
        ndsMode = NdsCatalog.isPageUrl(url);
        updatePlatformUi();
        if (Uri.parse(url).getQueryParameter("q") != null) {
            String query = Uri.parse(url).getQueryParameter("q");
            if (query != null) search.setText(query);
        }
        if (addHistory && !showingShelf && !sourcePage.equals(url)) {
            if (catalogHistory.size() == 50) catalogHistory.remove(0);
            catalogHistory.add(sourcePage);
        }
        sourcePage = url;
        setShelfVisible(false);
        int generation = ++catalogGeneration;
        if (catalogTask != null) catalogTask.cancel(true);
        currentTitle = ndsMode ? "Nintendo DS games"
                : url.contains("/search/results") ? "Search results" : "Catalog";
        // The DS client owns its feed's expiration; do not keep older derived pages here.
        CatalogPage cached = ndsMode ? null : catalogCache.get(url);
        if (cached != null) { renderCatalog(cached); return; }
        catalogResults.showLoading(currentTitle);
        boolean ds = NdsCatalog.isPageUrl(url);
        String cookie = ds ? null : CookieManager.getInstance().getCookie(url);
        catalogTask = catalogQueries.submit(() -> {
            try {
                CatalogPage page = ds ? ndsCatalogClient.fetch(url, userAgent)
                        : catalogClient.fetch(url, userAgent, cookie);
                handler.post(() -> {
                    if (isDestroyed() || generation != catalogGeneration) return;
                    if (page.verificationRequired && page.entries.isEmpty() && page.categories.isEmpty()) {
                        catalogResults.showError("hShop needs a quick verification before showing these results.",
                                () -> showCatalog(url, false), () -> verifyCatalog(url, null, generation));
                    } else {
                        if (!ds) catalogCache.put(url, page);
                        renderCatalog(page);
                    }
                });
            } catch (IOException | RuntimeException e) {
                handler.post(() -> {
                    if (isDestroyed() || generation != catalogGeneration) return;
                    boolean needsCheck = !ds && e instanceof CatalogClient.VerificationRequiredException;
                    catalogResults.showError(needsCheck ? "hShop needs a quick verification to continue."
                                    : ds ? "Couldn’t load the DS catalog. Check your connection and try again."
                                    : "Couldn’t load the catalog. Check your connection and try again.",
                            () -> showCatalog(url, false), needsCheck ? () -> verifyCatalog(url, null, generation) : null);
                });
            }
        });
    }

    private void renderCatalog(CatalogPage page) {
        currentTitle = page.title.isEmpty() ? "Catalog" : page.title;
        catalogResults.showPage(page);
        if (!pendingDownload.isEmpty()) catalogResults.setDownloadState(pendingDownload, "Getting link…", true);
        updateCompanion();
    }

    private void downloadCatalogEntry(CatalogPage.Entry entry) {
        if (NdsCatalog.isEditionSelectionUrl(entry.url)) { loadCatalog(entry.url); return; }
        if (!pendingDownload.isEmpty()) { toast("Finishing your current download request…"); return; }
        if (NdsCatalog.isDetailUrl(entry.url)) { downloadNdsEntry(entry); return; }
        if (!CatalogParser.isCatalogPageUrl(entry.url) || !Uri.parse(entry.url).getPath().matches("/t/[0-9]+/?")) return;
        pendingDownload = entry.url;
        int generation = ++downloadGeneration;
        catalogResults.setDownloadState(entry.url, "Getting link…", true);
        String cookie = CookieManager.getInstance().getCookie(entry.url);
        catalogQueries.execute(() -> {
            try {
                // The queue survives rotation and process death; button state alone cannot prevent duplicates.
                for (DownloadStore.Entry existing : downloads.list()) {
                    if (existing.isActive() && entry.url.equals(existing.sourcePage)) {
                        handler.post(() -> {
                            if (!activeDownload(generation)) return;
                            pendingDownload = "";
                            catalogResults.setDownloadState(entry.url, "Queued ✓", false);
                            showTab(1);
                            toast("This title is already in your download queue.");
                        });
                        return;
                    }
                }
                // Resolve every click anew. Signed links are never restored from saved state or reused from cache.
                CatalogPage page = catalogClient.fetch(entry.url, userAgent, cookie);
                handler.post(() -> {
                    if (!activeDownload(generation)) return;
                    if (!page.downloadUrl.isEmpty()) resolveCatalogDownload(entry, page.downloadUrl, generation);
                    else if (page.verificationRequired) verifyCatalog(entry.url, entry, generation);
                    else downloadFailed(entry, generation, "hShop did not provide a download link. Try again shortly.");
                });
            } catch (IOException | RuntimeException e) {
                handler.post(() -> {
                    if (!activeDownload(generation)) return;
                    if (e instanceof CatalogClient.VerificationRequiredException) verifyCatalog(entry.url, entry, generation);
                    else downloadFailed(entry, generation, "Couldn’t get a fresh download link. Check your connection and try again.");
                });
            }
        });
    }

    private void downloadNdsEntry(CatalogPage.Entry entry) {
        pendingDownload = entry.url;
        int generation = ++downloadGeneration;
        catalogResults.setDownloadState(entry.url, "Getting link…", true);
        catalogQueries.execute(() -> {
            try {
                for (DownloadStore.Entry existing : downloads.list()) {
                    if (existing.isActive() && entry.url.equals(existing.sourcePage)) {
                        handler.post(() -> {
                            if (!activeDownload(generation)) return;
                            pendingDownload = "";
                            catalogResults.setDownloadState(entry.url, "Queued ✓", false);
                            showTab(1);
                            toast("This title is already in your download queue.");
                        });
                        return;
                    }
                }
                CatalogPage page = ndsCatalogClient.fetch(entry.url, userAgent);
                if (!page.isDetail() || page.entries.size() != 1
                        || !entry.url.equals(page.entries.get(0).url) || page.downloadUrl.isEmpty()) {
                    throw new IOException("The selected DS edition is no longer available.");
                }
                CatalogPage.Entry edition = page.entries.get(0);
                CatalogClient.DownloadInfo file = catalogClient.inspectNdsDownload(page.downloadUrl, userAgent);
                handler.post(() -> {
                    if (!activeDownload(generation)) return;
                    try {
                        // Keep the catalog's readable title but retain the verified response's file type.
                        String extension = file.name.substring(file.name.lastIndexOf('.'));
                        downloads.enqueueNds(file.url, GameFilename.clean(edition.title + extension), userAgent,
                                edition.url, preferences.getBoolean("wifiOnly", true), edition.artworkUrl);
                        pendingDownload = "";
                        catalogResults.setDownloadState(entry.url, "Queued ✓", false);
                        refreshDownloads();
                        toast(edition.title + " added to Downloads");
                    } catch (RuntimeException e) {
                        downloadFailed(entry, generation, "Could not start this DS download. Check free space and Android Download Manager.");
                    }
                });
            } catch (IOException | RuntimeException e) {
                handler.post(() -> downloadFailed(entry, generation,
                        "The DS download is unavailable or is not a supported .nds or ZIP file. Try again later."));
            }
        });
    }

    private boolean activeDownload(int generation) {
        return !isDestroyed() && generation == downloadGeneration && !pendingDownload.isEmpty();
    }

    private void resolveCatalogDownload(CatalogPage.Entry entry, String link, int generation) {
        String cookie = CookieManager.getInstance().getCookie(link);
        catalogResults.setDownloadState(entry.url, "Starting…", true);
        catalogQueries.execute(() -> {
            try {
                CatalogClient.DownloadInfo file = catalogClient.inspectDownload(link, userAgent, cookie, entry.url);
                handler.post(() -> {
                    if (!activeDownload(generation)) return;
                    try {
                        downloads.enqueue(file.url, file.name, userAgent, CookieManager.getInstance().getCookie(file.url),
                                entry.url, preferences.getBoolean("wifiOnly", true), entry.artworkUrl);
                        pendingDownload = "";
                        catalogResults.setDownloadState(entry.url, "Queued ✓", false);
                        refreshDownloads();
                        toast(entry.title + " added to Downloads");
                    } catch (RuntimeException e) {
                        downloadFailed(entry, generation, "Could not start this download. Check free space and enable Android Download Manager.");
                    }
                });
            } catch (IOException | RuntimeException e) {
                handler.post(() -> downloadFailed(entry, generation,
                        "The download link could not be opened. Tap Download to request a fresh link."));
            }
        });
    }

    private void downloadFailed(CatalogPage.Entry entry, int generation, String message) {
        if (!activeDownload(generation)) return;
        pendingDownload = "";
        catalogResults.setDownloadState(entry.url, "Download", false);
        toast(message);
    }

    private void verifyCatalog(String url, CatalogPage.Entry entry, int generation) {
        dismissVerification();
        int verificationSession = ++verificationGeneration;
        String browserUrl = CatalogFilters.sourceUrl(url);
        verification = new CatalogVerification(this, browserUrl, entry != null, new CatalogVerification.Listener() {
            @Override public void onHtml(String html) {
                if (isDestroyed() || verification == null || verificationSession != verificationGeneration
                        || parsingVerification) return;
                parsingVerification = true;
                CatalogVerification expected = verification;
                catalogQueries.execute(() -> {
                    CatalogPage parsed;
                    try { parsed = CatalogParser.parse(browserUrl, html); }
                    catch (RuntimeException e) { parsed = null; }
                    CatalogPage page = parsed;
                    handler.post(() -> {
                        if (isDestroyed() || verification == null || verification != expected) return;
                        parsingVerification = false;
                        if (page == null) return;
                        if (entry != null && activeDownload(generation) && !page.downloadUrl.isEmpty()) {
                            dismissVerification();
                            resolveCatalogDownload(entry, page.downloadUrl, generation);
                        } else if (entry == null && generation == catalogGeneration && !page.verificationRequired) {
                            dismissVerification();
                            if (hasActiveCatalogFilters()) {
                                // Reapply native filters after verification refreshes the cookie;
                                // browser HTML may include titles outside the selected filters.
                                showCatalog(url, false);
                            } else {
                                catalogCache.put(url, page);
                                renderCatalog(page);
                            }
                        }
                    });
                });
            }
            @Override public void onCancel() {
                dismissVerification();
                if (entry != null && activeDownload(generation)) {
                    pendingDownload = "";
                    downloadGeneration++;
                    catalogResults.setDownloadState(entry.url, "Download", false);
                }
            }
        });
        verification.show();
        if (!resumed) verification.pause();
    }

    private void dismissVerification() {
        verificationGeneration++;
        if (verification != null) { verification.dismiss(); verification = null; }
        parsingVerification = false;
    }

    private void refreshDownloads() {
        if (queryingDownloads || isDestroyed()) return;
        queryingDownloads = true;
        downloadQueries.execute(() -> {
            try {
                List<DownloadStore.Entry> entries = downloads.list();
                handler.post(() -> {
                    queryingDownloads = false;
                    if (!isDestroyed()) renderDownloads(entries);
                });
            } catch (RuntimeException e) {
                handler.post(() -> {
                    queryingDownloads = false;
                    if (!isDestroyed()) downloadSummary.setText("Download Manager is unavailable. Try reopening the app.");
                });
            }
        });
    }

    private void renderDownloads(List<DownloadStore.Entry> entries) {
        latestEntries = new ArrayList<>(entries);
        int active = 0, complete = 0;
        StringBuilder keys = new StringBuilder();
        for (DownloadStore.Entry entry : entries) {
            keys.append(entry.id).append(':').append(entry.status).append(';');
            if (entry.isActive()) active++;
            if (entry.isComplete()) complete++;
        }
        queueSummary = entries.isEmpty() ? "No downloads yet" : active + " active · " + complete + " downloaded · " + entries.size() + " total";
        downloadSummary.setText(queueSummary);
        downloadBadge.setText(Integer.toString(active));
        downloadBadge.setContentDescription(active + " active downloads");
        downloadBadge.setVisibility(active > 0 ? View.VISIBLE : View.GONE);
        wifiStatus.setText(preferences.getBoolean("wifiOnly", true) ? "Wi-Fi only" : "All networks");
        String exportMessage = ExportService.status(this);
        exportStatus.setText(exportMessage);
        exportStatus.setVisibility(exportMessage.isEmpty() ? View.GONE : View.VISIBLE);
        // Recover interrupted jobs even when there are no CIA cards or companion display.
        PreparationService.status(this);
        if (!renderedRows.equals(keys.toString()) || (entries.isEmpty() && downloadList.getChildCount() == 0)) {
            renderedRows = keys.toString(); downloadList.removeAllViews(); downloadCards.clear();
            if (entries.isEmpty()) {
                LinearLayout empty = Ui.card(this);
                empty.addView(Ui.heading(this, "A shelf full of possibilities."));
                empty.addView(Ui.text(this, "Find a game in Browse. Your downloads and progress will appear here.", 15, Ui.MUTED));
                empty.addView(Ui.primaryButton(this, "Browse games", () -> { showTab(0); setShelfVisible(true); }));
                downloadList.addView(empty);
            }
            for (DownloadStore.Entry entry : entries) {
                DownloadCard card = new DownloadCard(entry); downloadCards.put(entry.id, card);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                p.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 8)); downloadList.addView(card.root, p);
            }
        }
        for (DownloadStore.Entry entry : entries) {
            DownloadCard card = downloadCards.get(entry.id);
            if (card != null) card.update(entry);
        }
        if (tab == 1) backfillDownloadArtwork(entries);
        updateCompanion();
    }

    private void backfillDownloadArtwork(List<DownloadStore.Entry> entries) {
        Map<String, List<DownloadStore.Entry>> missing = new LinkedHashMap<>();
        for (DownloadStore.Entry entry : entries) {
            String source = entry.sourcePage;
            if (!entry.artworkUrl.isEmpty() || !CatalogParser.isCatalogPageUrl(source)
                    || !Uri.parse(source).getEncodedPath().matches("/t/[0-9]{1,12}/?")) continue;
            if (!missing.containsKey(source)) missing.put(source, new ArrayList<>());
            missing.get(source).add(entry);
        }
        for (Map.Entry<String, List<DownloadStore.Entry>> group : missing.entrySet()) {
            if (artworkMetadataAttempted.size() >= MAX_ARTWORK_METADATA_REQUESTS) break;
            String source = group.getKey();
            if (!artworkMetadataAttempted.add(source)) continue;
            CatalogPage cached = catalogCache.get(source);
            String cookie = CookieManager.getInstance().getCookie(source);
            artworkMetadataQueries.execute(() -> {
                try {
                    CatalogPage page = cached != null ? cached : catalogClient.fetch(source, userAgent, cookie);
                    if (!page.isDetail() || page.verificationRequired || Thread.currentThread().isInterrupted()) return;
                    for (CatalogPage.Entry item : page.entries) {
                        if (!source.equals(item.url) || !ArtworkPolicy.isAllowed(item.artworkUrl)) continue;
                        List<DownloadStore.Entry> updated = new ArrayList<>();
                        for (DownloadStore.Entry entry : group.getValue()) {
                            if (downloads.updateArtwork(entry.id, item.artworkUrl)) updated.add(entry);
                        }
                        if (!updated.isEmpty()) handler.post(() -> {
                            if (isDestroyed()) return;
                            for (DownloadStore.Entry entry : updated) {
                                DownloadCard card = downloadCards.get(entry.id);
                                if (card != null) card.updateArtwork(entry.displayName, item.artworkUrl);
                            }
                            if (tab == 1) refreshDownloads();
                        });
                        break;
                    }
                } catch (IOException | RuntimeException unavailable) {
                    // Keep the fallback; a refresh must not repeatedly retry missing artwork.
                }
            });
        }
    }

    private final class DownloadCard {
        final LinearLayout root;
        final FrameLayout artwork;
        final TextView status;
        final ProgressBar progress;
        final Button prepare;
        final Button deleteOriginal;
        String artworkUrl;
        boolean cancellingPreparation;
        DownloadCard(DownloadStore.Entry entry) {
            root = Ui.card(MainActivity.this); Ui.pad(root, 14);
            LinearLayout details = Ui.row(MainActivity.this);
            details.setGravity(Gravity.TOP);
            artwork = new FrameLayout(MainActivity.this);
            artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            artworkUrl = entry.artworkUrl;
            artwork.addView(GameArtwork.thumbnail(MainActivity.this, entry.displayName, artworkUrl, 64),
                    new FrameLayout.LayoutParams(-1, -1));
            details.addView(artwork,
                    new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 64), Ui.dp(MainActivity.this, 64)));
            LinearLayout text = Ui.column(MainActivity.this);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
            textParams.setMarginStart(Ui.dp(MainActivity.this, 12));
            details.addView(text, textParams);
            TextView title = Ui.text(MainActivity.this, GameArtwork.displayTitle(entry.displayName), 17, Ui.TEXT);
            title.setTypeface(null, Typeface.BOLD);
            text.addView(title);
            status = Ui.text(MainActivity.this, "", 13, Ui.MUTED); text.addView(status);
            progress = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000); Ui.styleProgress(progress);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, Ui.dp(MainActivity.this, 6));
            pp.setMargins(0, Ui.dp(MainActivity.this, 6), 0, Ui.dp(MainActivity.this, 8));
            text.addView(progress, pp);
            root.addView(details);
            if (!entry.isComplete()) {
                root.addView(Ui.button(MainActivity.this, entry.isActive() ? "Cancel" : "Remove", () ->
                        new AlertDialog.Builder(MainActivity.this).setTitle(entry.isActive() ? "Cancel download?" : "Remove download?")
                                .setMessage("This removes the transfer and any partial file. You can download it again from its catalog page.")
                                .setNegativeButton("Keep", null).setPositiveButton("Remove", (d, w) -> {
                                    downloadQueries.execute(() -> {
                                        try {
                                            downloads.cancel(entry.id);
                                            handler.post(() -> {
                                                if (!isDestroyed()) {
                                                    catalogResults.setDownloadState(entry.sourcePage, "Download", false);
                                                    refreshDownloads();
                                                }
                                            });
                                        } catch (RuntimeException e) {
                                            handler.post(() -> {
                                                if (!isDestroyed()) toast("Could not remove this download. Try Android’s Downloads app.");
                                            });
                                        }
                                    });
                                }).show()));
            }
            if (entry.isComplete() && entry.name.toLowerCase(Locale.ROOT).endsWith(".cia")) {
                prepare = Ui.primaryButton(MainActivity.this, "Prepare for Azahar", () -> {
                    if (cancellingPreparation) {
                        if (PreparationService.isRunning(MainActivity.this)
                                && PreparationService.downloadId(MainActivity.this) == entry.id) {
                            PreparationService.cancel(MainActivity.this);
                        }
                        refreshDownloads();
                    } else {
                        prepareCopy(entry);
                    }
                });
                root.addView(prepare);
                deleteOriginal = Ui.button(MainActivity.this, "Delete original", () -> confirmDeleteOriginal(entry));
                deleteOriginal.setVisibility(View.GONE);
                root.addView(deleteOriginal);
            } else {
                prepare = null;
                deleteOriginal = null;
            }
            String lowerName = entry.name.toLowerCase(Locale.ROOT);
            if (entry.isComplete() && (lowerName.endsWith(".nds")
                    || (NdsCatalog.isDetailUrl(entry.sourcePage) && lowerName.endsWith(".zip")))) {
                root.addView(Ui.primaryButton(MainActivity.this, "Save DS game", () -> saveNdsGame(entry)));
                root.addView(Ui.text(MainActivity.this, lowerName.endsWith(".zip")
                        ? "Extract the single .nds file to your DS games folder. Extra data files need manual setup."
                        : "Copy this .nds file to your DS games folder, then open it in your DS emulator.", 13, Ui.MUTED));
            }
        }
        void updateArtwork(String title, String url) {
            if (url.isEmpty() || artworkUrl.equals(url)) return;
            artworkUrl = url;
            artwork.removeAllViews();
            artwork.addView(GameArtwork.thumbnail(MainActivity.this, title, url, 64),
                    new FrameLayout.LayoutParams(-1, -1));
        }
        void update(DownloadStore.Entry entry) {
            updateArtwork(entry.displayName, entry.artworkUrl);
            String size = Formatter.formatFileSize(MainActivity.this, entry.downloaded);
            if (entry.total > 0) size += " / " + Formatter.formatFileSize(MainActivity.this, entry.total);
            status.setText(entry.statusLabel + " · " + size);
            status.setTextColor(entry.isComplete() ? Ui.SUCCESS : Ui.MUTED);
            progress.setVisibility(entry.isActive() ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(entry.total <= 0);
            if (entry.total > 0) progress.setProgress((int) Math.min(1000, entry.downloaded * 1000.0 / entry.total));
            if (deleteOriginal != null) {
                boolean preparing = PreparationService.isRunning(MainActivity.this);
                boolean currentPreparation = PreparationService.downloadId(MainActivity.this) == entry.id;
                cancellingPreparation = preparing && currentPreparation;
                prepare.setText(cancellingPreparation ? "Cancel preparation" : "Prepare for Azahar");
                prepare.setEnabled(!deletingOriginal && (!preparing || currentPreparation));
                deleteOriginal.setVisibility(entry.isPrepared() ? View.VISIBLE : View.GONE);
                deleteOriginal.setEnabled(!deletingOriginal && !preparing);
                if (currentPreparation) {
                    String message = PreparationService.status(MainActivity.this);
                    if (preparing) {
                        int percent = PreparationService.progress(MainActivity.this);
                        status.setText(message);
                        status.setTextColor(Ui.ACCENT);
                        progress.setVisibility(View.VISIBLE);
                        progress.setIndeterminate(false);
                        progress.setProgress(percent * 10);
                    } else if (!message.isEmpty()) {
                        status.append("\n" + message);
                        status.setTextColor(PreparationService.isReady(MainActivity.this) ? Ui.SUCCESS : Ui.AMBER);
                    }
                }
            }
        }
    }

    private void saveNdsGame(DownloadStore.Entry entry) {
        if (ndsExportId >= 0) return;
        ndsExportId = entry.id;
        String name = entry.displayName;
        ndsExportName = GameFilename.clean(name.substring(0, name.lastIndexOf('.')) + ".nds");
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, SAVE_NDS_DOCUMENT);
            return;
        }
        openNdsSavePicker();
    }

    private void openNdsSavePicker() {
        if (ndsExportId < 0) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, ndsExportName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, SAVE_NDS_DOCUMENT); }
        catch (ActivityNotFoundException e) {
            ndsExportId = -1;
            toast("Android’s file picker is unavailable.");
        }
    }

    private void confirmDeleteOriginal(DownloadStore.Entry entry) {
        new AlertDialog.Builder(this).setTitle("Delete original download?")
                .setMessage("Delete the original download of “" + GameArtwork.displayTitle(entry.displayName)
                        + "” and remove it from this list? Your prepared Azahar copy will stay in your games folder.")
                .setNegativeButton("Keep original", null)
                .setPositiveButton("Delete original", (dialog, which) -> {
                    if (deletingOriginal) return;
                    deletingOriginal = true;
                    renderDownloads(latestEntries);
                    downloadQueries.execute(() -> {
                        String error = null;
                        try { PreparationService.deleteOriginal(getApplicationContext(), entry.id); }
                        catch (RuntimeException e) {
                            error = e.getMessage() == null ? "Could not delete the original download. Try again." : e.getMessage();
                        }
                        String result = error;
                        handler.post(() -> {
                            deletingOriginal = false;
                            if (isDestroyed()) return;
                            if (result == null) {
                                catalogResults.setDownloadState(entry.sourcePage, "Download", false);
                                toast("Original deleted. Your prepared copy is still in your games folder.");
                            } else toast(result);
                            refreshDownloads();
                        });
                    });
                }).show();
    }
    private void prepareCopy(DownloadStore.Entry entry) {
        if (deletingOriginal) {
            toast("Wait for the original download to finish deleting.");
            return;
        }
        if (checkingPreparationFolder) {
            toast("Checking your games folder…");
            return;
        }
        if (PreparationService.isRunning(this)) {
            toast("A file is already being prepared. Wait for it to finish or cancel it first.");
            return;
        }
        if (PreparationFolder.saved(preferences) != null) {
            exportId = entry.id;
            exportName = entry.displayName;
            requestCopyAction();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Prepare for Azahar")
                .setMessage("Prepare “" + entry.displayName + "” on this device. Your original stays in Downloads.\n\nChoose your games folder once, such as ROMs/n3ds. Future preparations use it automatically; change it in Settings. Base games become playable .3ds files. Add the same folder as Azahar’s Games Folder once, then refresh its game list. Updates and DLC remain CIA files and require “Install CIA” in Azahar.\n\nPreparation needs temporary internal storage of about five times the download’s size, plus room for your saved copy.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose games folder", (dialog, which) -> {
                    exportId = entry.id;
                    exportName = entry.displayName;
                    requestCopyAction();
                }).show();
    }
    private void requestCopyAction() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
            return;
        }
        continueCopy();
    }
    private void continueCopy() {
        if (exportId < 0 || checkingPreparationFolder) return;
        Uri folder = PreparationFolder.saved(preferences);
        if (folder == null) {
            openCopyPicker();
            return;
        }
        checkingPreparationFolder = true;
        long id = exportId;
        String name = exportName;
        downloadQueries.execute(() -> {
            boolean available = PreparationFolder.canWrite(getApplicationContext(), folder);
            handler.post(() -> {
                checkingPreparationFolder = false;
                if (isDestroyed() || exportId != id) return;
                if (!available) {
                    toast("Your saved games folder is unavailable. Choose it again or select another folder.");
                    openCopyPicker();
                    return;
                }
                startPreparation(id, folder, name);
            });
        });
    }
    private void startPreparation(long id, Uri folder, String name) {
        try { PreparationService.start(this, id, folder, name); }
        catch (RuntimeException e) {
            toast(e.getMessage() == null ? "Could not start preparation. Try again from Downloads." : e.getMessage());
        } finally {
            exportId = -1;
        }
        showTab(1);
    }
    private void openCopyPicker() {
        openGamesFolderPicker(SELECT_PREPARATION_FOLDER);
    }
    private void openGamesFolderPicker(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        Uri previous = PreparationFolder.saved(preferences);
        if (previous != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, previous);
        try { startActivityForResult(intent, request); }
        catch (ActivityNotFoundException e) { toast("No file picker is available on this device."); }
    }
    private void updateGamesFolder() {
        Uri folder = PreparationFolder.saved(preferences);
        gamesFolderStatus.setText(PreparationFolder.label(folder));
        changeGamesFolder.setText(folder == null ? "Choose games folder" : "Change games folder");
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 42 && exportId >= 0) continueCopy();
        if (request == SAVE_NDS_DOCUMENT && ndsExportId >= 0) openNdsSavePicker();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAVE_NDS_DOCUMENT) {
            if (result != RESULT_OK || data == null || data.getData() == null || ndsExportId < 0) {
                ndsExportId = -1;
                return;
            }
            Uri destination = data.getData();
            try {
                int flags = ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                        ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                        | ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                        ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
                if (flags != 0) {
                    try { getContentResolver().takePersistableUriPermission(destination, flags); }
                    catch (SecurityException ignored) { /* Temporary permission can still allow the export. */ }
                }
                ExportService.startNds(this, ndsExportId, destination, ndsExportName);
            } catch (RuntimeException e) {
                toast(e.getMessage() == null ? "Could not save this DS game. Try again from Downloads." : e.getMessage());
            } finally {
                ndsExportId = -1;
            }
            showTab(1);
            return;
        }
        if (request == CHANGE_PREPARATION_FOLDER) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            Uri folder = data.getData();
            if (!PreparationFolder.isFolder(folder)) {
                toast("Choose a games folder.");
                return;
            }
            PreparationFolder.retainAccess(this, data);
            if (!PreparationFolder.hasPersistedAccess(this, folder)) {
                toast("This folder’s access could not be saved. Choose another games folder.");
                return;
            }
            preferences.edit().putString(PreparationFolder.PREFERENCE, folder.toString()).apply();
            updateGamesFolder();
            return;
        }
        if (request != SELECT_PREPARATION_FOLDER) return;
        if (result != RESULT_OK || data == null || data.getData() == null || exportId < 0) {
            exportId = -1; return;
        }
        Uri destination = data.getData();
        if (!PreparationFolder.isFolder(destination)) {
            toast("Choose a games folder.");
            exportId = -1;
            return;
        }
        PreparationFolder.retainAccess(this, data);
        try {
            preferences.edit().putString(PreparationFolder.PREFERENCE, destination.toString()).apply();
            updateGamesFolder();
            PreparationService.start(this, exportId, destination, exportName);
        } catch (RuntimeException e) {
            toast(e.getMessage() == null ? "Could not start preparation. Try again from Downloads." : e.getMessage());
        } finally {
            exportId = -1;
        }
        showTab(1);
    }

    private void showTab(int next) {
        tab = Math.max(0, Math.min(2, next));
        browsePanel.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        downloadsPanel.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        settingsPanel.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < tabButtons.size(); i++) tabButtons.get(i).setSelected(i == tab);
        if (tab == 1) refreshDownloads();
        if (tab == 2) updateGamesFolder();
        updateCompanion();
    }
    private void azaharHelp() {
        new AlertDialog.Builder(this).setTitle("Open in Azahar")
                .setMessage("Prepare your downloaded game into a folder such as ROMs/n3ds. Set that same folder as Azahar’s Games Folder once, then refresh its game list.\n\nUpdates and DLC remain CIA files. Choose “Install CIA” inside Azahar and select the prepared file; they need their matching game.")
                .setNegativeButton("Close", null).setPositiveButton("Open Azahar", (d, w) -> launchAzahar()).show();
    }
    private void launchAzahar() {
        for (String pkg : new String[] {"org.azahar_emu.azahar", "io.github.lime3ds.android"}) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) { startActivity(intent); return; }
        }
        new AlertDialog.Builder(this).setTitle("Azahar was not found")
                .setMessage("Install the official Android version, then return here. Custom builds can be opened from your launcher.")
                .setNegativeButton("Close", null)
                .setPositiveButton("Azahar website", (d, w) -> openExternal("https://azahar-emu.org/")).show();
    }
    private void openExternal(String url) {
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException e) { toast("No web browser is available."); }
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void navigateBack() {
        if (tab != 0) showTab(0);
        else if (!showingShelf && !catalogHistory.isEmpty()) {
            showCatalog(catalogHistory.remove(catalogHistory.size() - 1), false);
        }
        else if (!showingShelf) setShelfVisible(true);
        else super.onBackPressed();
    }
    @Override public void onBackPressed() { navigateBack(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (key == KeyEvent.KEYCODE_BUTTON_A) {
            KeyEvent mapped = new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                    KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount(), event.getMetaState(),
                    event.getDeviceId(), event.getScanCode(), event.getFlags(), event.getSource());
            return super.dispatchKeyEvent(mapped);
        }
        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BUTTON_L1
                || key == KeyEvent.KEYCODE_BUTTON_R1 || key == KeyEvent.KEYCODE_BUTTON_Y) {
            if (down && event.getRepeatCount() == 0) {
                if (key == KeyEvent.KEYCODE_BUTTON_B) navigateBack();
                else if (key == KeyEvent.KEYCODE_BUTTON_Y) {
                    showTab(0); search.requestFocus();
                    getSystemService(InputMethodManager.class).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
                } else {
                    showTab((tab + (key == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : 2)) % 3);
                    tabButtons.get(tab).requestFocus();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void configureCompanion() {
        if (!resumed) return;
        boolean enabled = preferences.getBoolean("companion", true);
        int ownDisplay = getWindowManager().getDefaultDisplay().getDisplayId();
        Display target = null;
        if (enabled) {
            for (Display display : displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
                if (display.getDisplayId() != ownDisplay && display.isValid()) { target = display; break; }
            }
        }
        if (companion != null && (!companion.isShowing() || target == null
                || companion.getDisplay().getDisplayId() != target.getDisplayId())) {
            companion.dismiss(); companion = null;
        }
        if (target != null && companion == null) {
            try {
                CompanionPresentation created = new CompanionPresentation(this, target);
                companion = created;
                created.setOnDismissListener(dialog -> {
                    if (companion == created) {
                        companion = null;
                        displayStatus.setText("Companion display disconnected. Waiting for an available display.");
                    }
                });
                created.show();
            } catch (WindowManager.InvalidDisplayException | SecurityException e) {
                companion = null;
            }
        }
        displayStatus.setText(!enabled ? "Companion display is off." : companion == null
                ? "No available secondary presentation display. Single-screen mode is active."
                : "Connected to " + companion.getDisplay().getName());
        updateCompanion();
    }
    private void updateCompanion() {
        if (companion != null) {
            companion.update(companionPageLabel(), queueSummary, latestEntries, tab == 1);
        }
    }
    private String companionPageLabel() {
        if (tab == 1) return "Downloads";
        if (tab == 2) return "Settings";
        String platform = ndsMode ? "Nintendo DS" : "Nintendo 3DS";
        String query = showingShelf ? null : Uri.parse(sourcePage).getQueryParameter("q");
        String label = showingShelf ? "Browse " + platform + " games"
                : query != null && !query.trim().isEmpty() ? platform + " · Search: " + query
                : platform + " · " + currentTitle;
        if (!catalogLanguage.isEmpty()) label += " · " + CatalogLanguages.label(catalogLanguage);
        if (!catalogRegion.isEmpty()) label += " · " + CatalogRegions.label(catalogRegion);
        return label;
    }
    @Override public void onDisplayAdded(int id) { configureCompanion(); }
    @Override public void onDisplayRemoved(int id) { configureCompanion(); }
    @Override public void onDisplayChanged(int id) { configureCompanion(); }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (verification != null) verification.resume();
        displays.registerDisplayListener(this, handler); configureCompanion();
        handler.removeCallbacks(refresh); handler.post(refresh);
    }
    @Override protected void onPause() {
        resumed = false; handler.removeCallbacks(refresh);
        if (verification != null) verification.pause();
        displays.unregisterDisplayListener(this);
        if (companion != null) { companion.dismiss(); companion = null; }
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putStringArrayList("catalogHistory", new ArrayList<>(catalogHistory));
        out.putString("searchText", search.getText().toString());
        out.putInt("tab", tab); out.putString("sourcePage", sourcePage);
        out.putBoolean("showingShelf", showingShelf);
        out.putLong("exportId", exportId); out.putString("exportName", exportName);
        out.putLong("ndsExportId", ndsExportId); out.putString("ndsExportName", ndsExportName);
        out.putBoolean("ndsMode", ndsMode);
        out.putString("catalogLanguage", catalogLanguage);
        out.putString("catalogRegion", catalogRegion);
        out.putBoolean("checkingPreparationFolder", checkingPreparationFolder);
    }
    @Override protected void onDestroy() {
        catalogGeneration++; downloadGeneration++;
        if (filterDialog != null) filterDialog.dismiss();
        dismissVerification();
        catalogQueries.shutdownNow();
        downloadQueries.shutdownNow();
        artworkMetadataQueries.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (companion != null) companion.dismiss();
        super.onDestroy();
    }
}
