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
import android.text.TextUtils;
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
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity implements DisplayManager.DisplayListener {
    private static final int CREATE_COPY = 41;
    private static final int SELECT_PREPARATION_FOLDER = 43;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService downloadQueries = Executors.newSingleThreadExecutor();
    private final Map<Long, DownloadCard> downloadCards = new LinkedHashMap<>();
    private final List<Button> tabButtons = new ArrayList<>();
    private SharedPreferences preferences;
    private DownloadStore downloads;
    private DisplayManager displays;
    private CompanionPresentation companion;
    private WebView browser;
    private EditText search;
    private TextView pageTitle, browserError, downloadSummary, exportStatus, preparationStatus, preparedGuidance, displayStatus;
    private ProgressBar loading, preparationProgress;
    private Button cancelPreparation, openPrepared, preparationDetails;
    private LinearLayout preparationPanel;
    private TextView preparationHeading, preparationPercent, downloadBadge, wifiStatus, shelfHeading;
    private ParcelView preparationParcel;
    private BrowseHomeView browseHome;
    private LinearLayout webPanel;
    private boolean showingShelf = true;
    private List<DownloadStore.Entry> latestEntries = new ArrayList<>();
    private FrameLayout content;
    private LinearLayout browsePanel, downloadList;
    private ScrollView downloadsPanel, setupPanel;
    private String sourcePage = DownloadPolicy.HOME;
    private String currentTitle = "hShop";
    private String queueSummary = "No downloads yet";
    private String renderedRows = "";
    private int tab = 0;
    private long exportId = -1;
    private String exportName = "";
    private boolean exportPreparing;
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
        downloads = new DownloadStore(this);
        displays = getSystemService(DisplayManager.class);
        if (state != null) {
            sourcePage = state.getString("sourcePage", DownloadPolicy.HOME);
            exportId = state.getLong("exportId", -1);
            exportName = state.getString("exportName", "");
            exportPreparing = state.getBoolean("exportPreparing", false);
        }
        buildUi();
        Bundle savedBrowser = state == null ? null : state.getBundle("browser");
        if (savedBrowser == null || browser.restoreState(savedBrowser) == null) {
            browser.loadUrl(DownloadPolicy.isCatalog(sourcePage) ? sourcePage : DownloadPolicy.HOME);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
        setShelfVisible(state == null || state.getBoolean("showingShelf", true));
        showTab(state == null ? 0 : state.getInt("tab", 0));
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
        String[] names = {"Browse", "Downloads", "Setup"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button button = Ui.tabButton(this, names[i], () -> showTab(index));
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
        buildBrowser(); buildDownloads(); buildSetup();
        content.addView(browsePanel, new FrameLayout.LayoutParams(-1, -1));
        content.addView(downloadsPanel, new FrameLayout.LayoutParams(-1, -1));
        content.addView(setupPanel, new FrameLayout.LayoutParams(-1, -1));
        TextView hints = Ui.text(this, wideLayout()
                ? "Ⓐ  Select     Ⓑ  Back          L / R  Tabs          Ⓨ  Search"
                : "Ⓐ Select    Ⓑ Back    L / R Tabs    Ⓨ Search", 12, Ui.MUTED);
        hints.setGravity(Gravity.CENTER);
        hints.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        hints.setBackgroundColor(Ui.BG);
        root.addView(hints);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void buildBrowser() {
        browsePanel = Ui.column(this);
        LinearLayout searchArea = wideLayout() ? Ui.row(this) : Ui.column(this);
        searchArea.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        shelfHeading = Ui.heading(this, "Your next adventure.");
        shelfHeading.setTextSize(wideLayout() ? 25 : 24);
        if (wideLayout()) searchArea.addView(shelfHeading, new LinearLayout.LayoutParams(0, -2, 1));
        else searchArea.addView(shelfHeading);
        LinearLayout searchRow = Ui.row(this);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15); search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.MUTED);
        search.setHint("Search the catalog"); search.setContentDescription("Search hShop");
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
        if (wideLayout()) searchArea.addView(searchRow, new LinearLayout.LayoutParams(0, -2, 1));
        else searchArea.addView(searchRow, new LinearLayout.LayoutParams(-1, -2));
        browsePanel.addView(searchArea);
        browseHome = new BrowseHomeView(this, this::loadCatalog);
        browsePanel.addView(browseHome, new LinearLayout.LayoutParams(-1, 0, 1));
        webPanel = Ui.column(this);
        LinearLayout tools = Ui.row(this);
        tools.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 6));
        tools.addView(Ui.button(this, "‹ Back", this::navigateBack));
        tools.addView(Ui.button(this, "Shelf", () -> setShelfVisible(true)));
        pageTitle = Ui.text(this, "hShop", 13, Ui.MUTED);
        pageTitle.setSingleLine(true); pageTitle.setEllipsize(TextUtils.TruncateAt.END);
        pageTitle.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        tools.addView(pageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(Ui.button(this, "Reload", () -> browser.reload()));
        webPanel.addView(tools);
        browserError = Ui.text(this, "", 14, Ui.AMBER);
        Ui.pad(browserError, 8); browserError.setVisibility(View.GONE);
        webPanel.addView(browserError);
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100); Ui.styleProgress(loading);
        webPanel.addView(loading, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));
        browser = new WebView(this); browser.setBackgroundColor(Ui.BG);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true); // Required by hShop's interactive download challenge.
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true);
        // Keep the default WebView user agent so challenge sessions stay consistent.
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                if (DownloadPolicy.isAllowedDownload(url)) return false;
                if ("https".equalsIgnoreCase(request.getUrl().getScheme())) {
                    new AlertDialog.Builder(MainActivity.this).setTitle("Open another website?")
                            .setMessage(request.getUrl().getHost())
                            .setPositiveButton("Open browser", (d, w) -> openExternal(url))
                            .setNegativeButton("Stay here", null).show();
                } else if (!"about:blank".equals(url)) {
                    toast("This link type cannot be opened from the catalog.");
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                browserError.setVisibility(View.GONE);
                loading.setVisibility(View.VISIBLE);
                if (DownloadPolicy.isCatalog(url)) sourcePage = url;
            }
            @Override public void onPageFinished(WebView view, String url) {
                loading.setVisibility(View.INVISIBLE);
                CookieManager.getInstance().flush();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) browserProblem("Couldn’t load hShop. Check your connection, then tap Reload.");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse error) {
                if (request.isForMainFrame()) browserProblem("hShop returned HTTP " + error.getStatusCode()
                        + ". Complete any browser check shown below, or try Reload.");
            }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) { loading.setProgress(progress); }
            @Override public void onReceivedTitle(WebView view, String title) {
                currentTitle = title == null ? "hShop" : title;
                pageTitle.setText(currentTitle); updateCompanion();
            }
        });
        browser.setDownloadListener(this::confirmDownload);
        webPanel.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        browsePanel.addView(webPanel, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void setShelfVisible(boolean visible) {
        showingShelf = visible;
        browseHome.setVisibility(visible ? View.VISIBLE : View.GONE);
        webPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
        shelfHeading.setVisibility(visible ? View.VISIBLE : View.GONE);
        updateCompanion();
    }

    private void buildDownloads() {
        downloadsPanel = new ScrollView(this);
        downloadsPanel.setFillViewport(true);
        LinearLayout body = Ui.column(this); Ui.pad(body, 18);
        downloadsPanel.addView(body);
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
        LinearLayout columns = wideLayout() ? Ui.row(this) : Ui.column(this);
        columns.setGravity(Gravity.TOP);
        body.addView(columns, new LinearLayout.LayoutParams(-1, -2));
        downloadList = Ui.column(this);
        preparationPanel = Ui.card(this, 0xFFFFEED8);
        preparationHeading = Ui.text(this, "YOUR NEXT ADVENTURE", 11, Ui.ACCENT);
        preparationHeading.setLetterSpacing(0.12f);
        preparationPanel.addView(preparationHeading);
        preparationParcel = Ui.parcel(this);
        preparationPanel.addView(preparationParcel, new LinearLayout.LayoutParams(-1, Ui.dp(this, wideLayout() ? 120 : 100)));
        preparationStatus = Ui.text(this, "", 15, Ui.TEXT); preparationPanel.addView(preparationStatus);
        preparationPercent = Ui.text(this, "", 13, Ui.ACCENT); preparationPanel.addView(preparationPercent);
        preparationProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        preparationProgress.setMax(100); Ui.styleProgress(preparationProgress);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 8));
        progressParams.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        preparationPanel.addView(preparationProgress, progressParams);
        cancelPreparation = Ui.button(this, "Cancel preparation", () -> {
            PreparationService.cancel(this);
            toast("Stopping at the next safe point. Your original download will stay intact.");
            refreshDownloads();
        });
        preparationPanel.addView(cancelPreparation);
        preparedGuidance = Ui.text(this, "", 14, Ui.MUTED); preparationPanel.addView(preparedGuidance);
        openPrepared = Ui.primaryButton(this, "Open Azahar", this::launchAzahar);
        preparationDetails = Ui.button(this, "Details", () -> new AlertDialog.Builder(this)
                .setTitle("Preparation details").setMessage(PreparationService.status(this))
                .setPositiveButton("Close", null).show());
        LinearLayout preparationActions = Ui.row(this);
        Ui.weighted(preparationActions, openPrepared);
        Ui.weighted(preparationActions, preparationDetails);
        preparationPanel.addView(preparationActions);
        if (wideLayout()) {
            columns.addView(downloadList, new LinearLayout.LayoutParams(0, -2, 1.8f));
            LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(0, -2, 1);
            panelParams.setMargins(Ui.dp(this, 16), Ui.dp(this, 8), 0, 0);
            columns.addView(preparationPanel, panelParams);
        } else {
            columns.addView(preparationPanel, new LinearLayout.LayoutParams(-1, -2));
            columns.addView(downloadList, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void buildSetup() {
        setupPanel = new ScrollView(this);
        LinearLayout body = Ui.column(this); Ui.pad(body, 18); setupPanel.addView(body);
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
        games.addView(Ui.text(this, "1. Download a game from its catalog page.\n2. Choose Prepare for Azahar and select your games folder.\n3. Add that folder in Azahar, then refresh its game list.", 15, Ui.TEXT));
        games.addView(Ui.text(this, "Your original download stays safe. Preparation needs temporary internal storage of about five times the download size, plus space for the prepared copy. Updates and DLC still need Install CIA in Azahar and their matching game.", 14, Ui.MUTED));
        games.addView(Ui.primaryButton(this, "Open Azahar", this::launchAzahar));
        LinearLayout controls = settingsCard(body, "Made for your handheld");
        controls.addView(Ui.text(this, "D-pad: move focus · A: select · B: back\nL / R: switch tabs · Y: search\nUse touch for the website’s verification challenge.", 15, Ui.TEXT));
        LinearLayout help = settingsCard(body, "A little help");
        help.addView(Ui.text(this, "If a download link expires, return to its catalog page for a fresh one. If verification will not load, update Android System WebView or open the catalog in your browser.", 14, Ui.MUTED));
        help.addView(Ui.button(this, "Open catalog in browser", () -> openExternal(sourcePage)));
        help.addView(Ui.button(this, "Open-source licenses", this::showLicenses));
        body.addView(Ui.text(this, "ThorShelf 0.4.1 · Unofficial companion\nCatalog and availability are provided by hShop. Not affiliated with hShop, Nintendo, AYN, or Azahar. Shelf illustrations are bundled editorial artwork.", 12, Ui.MUTED));
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
        loadCatalog(DownloadPolicy.searchUrl(query));
    }
    private void loadCatalog(String url) {
        if (!DownloadPolicy.isCatalog(url)) return;
        showTab(0); setShelfVisible(false); browser.loadUrl(url); browser.requestFocus();
    }
    private void browserProblem(String message) {
        browserError.setText(message); browserError.setVisibility(View.VISIBLE);
        loading.setVisibility(View.INVISIBLE);
    }
    private void confirmDownload(String url, String agent, String disposition, String mime, long size) {
        if (!DownloadPolicy.isAllowedDownload(url)) {
            toast("This download host is not supported. Open the catalog page in your browser from Setup.");
            return;
        }
        String name = DownloadFilename.resolve(url, disposition, mime);
        if (name == null) {
            toast("This link is not a supported game or archive file."); return;
        }
        String page = sourcePage;
        String length = size > 0 ? Formatter.formatFileSize(this, size) : "Size provided when the download starts";
        new AlertDialog.Builder(this).setTitle("Download this file?")
                .setMessage(name + "\n" + length + "\n\nSave to Downloads / ThorShelf.\n\nAfter downloading, choose “Prepare for Azahar” to save a game into your games folder. Updates and DLC still require “Install CIA” inside Azahar.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download", (dialog, which) -> {
                    try {
                        downloads.enqueue(url, name, agent, CookieManager.getInstance().getCookie(url),
                                page, preferences.getBoolean("wifiOnly", true));
                        showTab(1); refreshDownloads();
                        toast("Download added");
                    } catch (RuntimeException e) {
                        toast("Could not start this download. Check free space and enable Android Download Manager.");
                    }
                }).show();
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
        String preparationMessage = PreparationService.status(this);
        boolean preparing = PreparationService.isRunning(this);
        boolean ready = PreparationService.isReady(this);
        preparationStatus.setText(preparationMessage.isEmpty()
                ? "Download a game, then prepare it for Azahar. Your next adventure starts here."
                : PreparationText.summary(preparationMessage, ready));
        preparationStatus.setTextSize(ready ? 17 : 15);
        preparationDetails.setVisibility(preparationMessage.isEmpty() ? View.GONE : View.VISIBLE);
        preparationParcel.getLayoutParams().height = Ui.dp(this, ready ? 80 : wideLayout() ? 120 : 100);
        preparationParcel.requestLayout();
        preparationHeading.setText(preparing ? "PREPARING FOR AZAHAR"
                : PreparationService.isReady(this) ? "READY FOR AZAHAR" : "YOUR NEXT ADVENTURE");
        preparationParcel.setAnimating(preparing);
        preparationPercent.setText(PreparationService.progress(this) + "%");
        preparationPercent.setVisibility(preparing ? View.VISIBLE : View.GONE);
        preparationPanel.setVisibility(wideLayout() || !preparationMessage.isEmpty() ? View.VISIBLE : View.GONE);
        preparationProgress.setProgress(PreparationService.progress(this));
        preparationProgress.setVisibility(preparing ? View.VISIBLE : View.GONE);
        cancelPreparation.setVisibility(preparing ? View.VISIBLE : View.GONE);
        preparedGuidance.setText(PreparationService.requiresInstall(this)
                ? "Open Azahar and choose Install CIA to add the prepared file."
                : "Refresh your Azahar games folder to see the game.");
        preparedGuidance.setVisibility(ready ? View.VISIBLE : View.GONE);
        openPrepared.setVisibility(ready ? View.VISIBLE : View.GONE);
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
        updateCompanion();
    }

    private final class DownloadCard {
        final LinearLayout root;
        final TextView status;
        final ProgressBar progress;
        DownloadCard(DownloadStore.Entry entry) {
            root = Ui.card(MainActivity.this); Ui.pad(root, 14);
            LinearLayout details = Ui.row(MainActivity.this);
            details.setGravity(Gravity.TOP);
            details.addView(GameArtwork.thumbnail(MainActivity.this, entry.displayName, 64),
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
            LinearLayout actions = Ui.row(MainActivity.this);
            Ui.weighted(actions, Ui.button(MainActivity.this, "Catalog page", () -> loadCatalog(entry.sourcePage)));
            if (entry.isComplete()) {
                Ui.weighted(actions, Ui.button(MainActivity.this, "Save a copy", () -> saveCopy(entry)));
            } else {
                Ui.weighted(actions, Ui.button(MainActivity.this, entry.isActive() ? "Cancel" : "Remove", () ->
                        new AlertDialog.Builder(MainActivity.this).setTitle(entry.isActive() ? "Cancel download?" : "Remove download?")
                                .setMessage("This removes the transfer and any partial file. You can download it again from its catalog page.")
                                .setNegativeButton("Keep", null).setPositiveButton("Remove", (d, w) -> {
                                    downloadQueries.execute(() -> {
                                        try {
                                            downloads.cancel(entry.id);
                                            handler.post(() -> { if (!isDestroyed()) refreshDownloads(); });
                                        } catch (RuntimeException e) {
                                            handler.post(() -> {
                                                if (!isDestroyed()) toast("Could not remove this download. Try Android’s Downloads app.");
                                            });
                                        }
                                    });
                                }).show()));
            }
            root.addView(actions);
            if (entry.isComplete() && entry.name.toLowerCase(Locale.ROOT).endsWith(".cia")) {
                root.addView(Ui.primaryButton(MainActivity.this, "Prepare for Azahar", () -> prepareCopy(entry)));
            }
        }
        void update(DownloadStore.Entry entry) {
            String size = Formatter.formatFileSize(MainActivity.this, entry.downloaded);
            if (entry.total > 0) size += " / " + Formatter.formatFileSize(MainActivity.this, entry.total);
            status.setText(entry.statusLabel + " · " + size);
            status.setTextColor(entry.isComplete() ? Ui.SUCCESS : Ui.MUTED);
            progress.setVisibility(entry.isActive() ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(entry.total <= 0);
            if (entry.total > 0) progress.setProgress((int) Math.min(1000, entry.downloaded * 1000.0 / entry.total));
        }
    }

    private void saveCopy(DownloadStore.Entry entry) {
        exportId = entry.id; exportName = entry.displayName; exportPreparing = false;
        requestCopyPicker();
    }
    private void prepareCopy(DownloadStore.Entry entry) {
        if (PreparationService.isRunning(this)) {
            toast("A file is already being prepared. Wait for it to finish or cancel it first.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Prepare for Azahar")
                .setMessage("Prepare “" + entry.displayName + "” on this device. Your original stays in Downloads.\n\nChoose your games folder, such as ROMs/n3ds. Base games become playable .3ds files. Add the same folder as Azahar’s Games Folder once, then refresh its game list. Updates and DLC remain CIA files and require “Install CIA” in Azahar.\n\nPreparation needs temporary internal storage of about five times the download’s size, plus room for your saved copy.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose games folder", (dialog, which) -> {
                    exportId = entry.id;
                    exportName = entry.displayName;
                    exportPreparing = true;
                    requestCopyPicker();
                }).show();
    }
    private void requestCopyPicker() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
            return;
        }
        openCopyPicker();
    }
    private void openCopyPicker() {
        Intent intent;
        if (exportPreparing) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            String previous = preferences.getString("lastPreparationFolder", "");
            if (!previous.isEmpty()) {
                Uri folder = Uri.parse(previous);
                if ("content".equals(folder.getScheme()) && DocumentsContract.isTreeUri(folder)) {
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder);
                }
            }
        } else {
            intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, exportName);
        }
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, exportPreparing ? SELECT_PREPARATION_FOLDER : CREATE_COPY); }
        catch (ActivityNotFoundException e) { toast("No file picker is available on this device."); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 42 && exportId >= 0) openCopyPicker();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CREATE_COPY && request != SELECT_PREPARATION_FOLDER) return;
        if (result != RESULT_OK || data == null || data.getData() == null || exportId < 0) {
            exportId = -1; exportPreparing = false; return;
        }
        Uri destination = data.getData();
        try {
            if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                    getContentResolver().takePersistableUriPermission(destination,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } else {
                    getContentResolver().takePersistableUriPermission(destination, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                }
            }
        } catch (SecurityException ignored) {
            // Some document providers give a transient grant; the foreground copy can still use it.
        }
        try {
            if (request == SELECT_PREPARATION_FOLDER) {
                PreparationService.start(this, exportId, destination, exportName);
                preferences.edit().putString("lastPreparationFolder", destination.toString()).apply();
            } else ExportService.start(this, exportId, destination, exportName);
        } catch (RuntimeException e) {
            toast(e.getMessage() == null ? "Could not start the copy. Try again from Downloads." : e.getMessage());
        } finally {
            exportId = -1;
            exportPreparing = false;
        }
        showTab(1);
    }

    private void showTab(int next) {
        tab = Math.max(0, Math.min(2, next));
        browsePanel.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        downloadsPanel.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        setupPanel.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < tabButtons.size(); i++) tabButtons.get(i).setSelected(i == tab);
        if (tab == 1) refreshDownloads();
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
        else if (!showingShelf && browser.canGoBack()) browser.goBack();
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
            companion.update(tab == 1 ? "Downloads" : tab == 2 ? "Setup"
                    : showingShelf ? "Your next adventure" : currentTitle, queueSummary, latestEntries);
        }
    }
    @Override public void onDisplayAdded(int id) { configureCompanion(); }
    @Override public void onDisplayRemoved(int id) { configureCompanion(); }
    @Override public void onDisplayChanged(int id) { configureCompanion(); }
    @Override protected void onResume() {
        super.onResume(); resumed = true; browser.onResume();
        displays.registerDisplayListener(this, handler); configureCompanion();
        handler.removeCallbacks(refresh); handler.post(refresh);
    }
    @Override protected void onPause() {
        resumed = false; handler.removeCallbacks(refresh); browser.onPause();
        displays.unregisterDisplayListener(this);
        if (companion != null) { companion.dismiss(); companion = null; }
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        Bundle webState = new Bundle(); browser.saveState(webState); out.putBundle("browser", webState);
        out.putInt("tab", tab); out.putString("sourcePage", sourcePage);
        out.putBoolean("showingShelf", showingShelf);
        out.putLong("exportId", exportId); out.putString("exportName", exportName);
        out.putBoolean("exportPreparing", exportPreparing);
    }
    @Override protected void onDestroy() {
        downloadQueries.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (companion != null) companion.dismiss();
        if (browser != null) { webPanel.removeView(browser); browser.destroy(); }
        super.onDestroy();
    }
}
