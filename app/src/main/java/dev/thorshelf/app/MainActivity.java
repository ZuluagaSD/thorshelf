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
import android.widget.HorizontalScrollView;
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
    private Button cancelPreparation, openPrepared;
    private LinearLayout preparationPanel;
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
        showTab(state == null ? 0 : state.getInt("tab", 0));
    }

    private void buildUi() {
        boolean compact = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
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
        LinearLayout heading = Ui.row(this); Ui.pad(heading, 10);
        LinearLayout brand = Ui.column(this);
        brand.addView(Ui.heading(this, "ThorShelf"));
        if (!compact) brand.addView(Ui.text(this, "hShop on your handheld", 12, Ui.MINT));
        heading.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(Ui.button(this, "Azahar ↗", this::azaharHelp));
        root.addView(heading);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        buildBrowser();
        buildDownloads();
        buildSetup();
        content.addView(browsePanel, new FrameLayout.LayoutParams(-1, -1));
        content.addView(downloadsPanel, new FrameLayout.LayoutParams(-1, -1));
        content.addView(setupPanel, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout tabs = Ui.row(this); Ui.pad(tabs, 6);
        String[] names = {"Browse", "Downloads", "Setup"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button button = Ui.button(this, names[i], () -> showTab(index));
            tabButtons.add(button); Ui.weighted(tabs, button);
        }
        root.addView(tabs);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void buildBrowser() {
        boolean compact = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        browsePanel = Ui.column(this);
        LinearLayout searchRow = Ui.row(this); Ui.pad(searchRow, 6);
        if (compact) searchRow.addView(Ui.button(this, "‹ Back", this::navigateBack));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(16); search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.MUTED);
        search.setHint("Search the 3DS catalog");
        search.setContentDescription("Search hShop");
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
        searchRow.addView(Ui.button(this, "Search", this::submitSearch));
        if (compact) searchRow.addView(Ui.button(this, "Reload", () -> browser.reload()));
        browsePanel.addView(searchRow);
        HorizontalScrollView shortcuts = new HorizontalScrollView(this);
        shortcuts.setHorizontalScrollBarEnabled(false);
        LinearLayout links = Ui.row(this);
        addShortcut(links, "Home", DownloadPolicy.HOME);
        addShortcut(links, "Games", DownloadPolicy.HOME + "c/games");
        addShortcut(links, "Updates", DownloadPolicy.HOME + "c/updates");
        addShortcut(links, "DLC", DownloadPolicy.HOME + "c/dlc");
        addShortcut(links, "Virtual Console", DownloadPolicy.HOME + "c/virtual-console");
        shortcuts.addView(links);
        if (!compact) browsePanel.addView(shortcuts);
        LinearLayout tools = Ui.row(this);
        tools.addView(Ui.button(this, "‹ Back", this::navigateBack));
        pageTitle = Ui.text(this, "hShop", 13, Ui.MUTED);
        pageTitle.setSingleLine(true); pageTitle.setEllipsize(TextUtils.TruncateAt.END);
        tools.addView(pageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(Ui.button(this, "Reload", () -> browser.reload()));
        if (!compact) browsePanel.addView(tools);
        browserError = Ui.text(this, "", 14, Ui.AMBER);
        Ui.pad(browserError, 8); browserError.setVisibility(View.GONE);
        browsePanel.addView(browserError);
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100); browsePanel.addView(loading, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));
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
        browsePanel.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void addShortcut(LinearLayout row, String name, String url) {
        Button b = Ui.button(this, name, () -> loadCatalog(url));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        row.addView(b, p);
    }

    private void buildDownloads() {
        downloadsPanel = new ScrollView(this);
        LinearLayout body = Ui.column(this); Ui.pad(body, 16);
        downloadsPanel.addView(body);
        body.addView(Ui.heading(this, "Downloads"));
        body.addView(Ui.text(this, "Saved to Downloads / ThorShelf. Prepare a completed CIA into your Azahar games folder, then refresh Azahar’s game list.", 14, Ui.MUTED));
        downloadSummary = Ui.text(this, "", 15, Ui.MINT); body.addView(downloadSummary);
        exportStatus = Ui.text(this, "", 14, Ui.AMBER); body.addView(exportStatus);
        preparationPanel = Ui.column(this); Ui.pad(preparationPanel, 12);
        preparationPanel.setBackground(Ui.rounded(this, Ui.PANEL, 0));
        preparationPanel.addView(Ui.text(this, "PREPARE FOR AZAHAR", 12, Ui.MINT));
        preparationStatus = Ui.text(this, "", 15, Ui.TEXT); preparationPanel.addView(preparationStatus);
        preparationProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        preparationProgress.setMax(100); preparationPanel.addView(preparationProgress);
        cancelPreparation = Ui.button(this, "Cancel preparation", () -> {
            PreparationService.cancel(this);
            toast("Stopping at the next safe point. Your original download will stay intact.");
            refreshDownloads();
        });
        preparationPanel.addView(cancelPreparation);
        preparedGuidance = Ui.text(this, "", 14, Ui.MUTED);
        preparationPanel.addView(preparedGuidance);
        openPrepared = Ui.button(this, "Open Azahar", this::launchAzahar);
        preparationPanel.addView(openPrepared);
        preparationPanel.setVisibility(View.GONE); body.addView(preparationPanel);
        downloadList = Ui.column(this); body.addView(downloadList);
    }

    private void buildSetup() {
        setupPanel = new ScrollView(this);
        LinearLayout body = Ui.column(this); Ui.pad(body, 20); setupPanel.addView(body);
        body.addView(Ui.text(this, "MADE FOR YOUR HANDHELD", 12, Ui.MINT));
        body.addView(Ui.heading(this, "A shelf for your next game."));
        body.addView(Ui.text(this, "Browse hShop, open a title, complete its browser check, then tap the download link. Android keeps downloads running when you leave ThorShelf.", 16, Ui.TEXT));
        body.addView(Ui.heading(this, "Downloads"));
        Switch wifi = new Switch(this); wifi.setText("Wi-Fi only"); wifi.setTextColor(Ui.TEXT);
        wifi.setMinHeight(Ui.dp(this, 56)); wifi.setChecked(preferences.getBoolean("wifiOnly", true));
        wifi.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean("wifiOnly", checked).apply());
        body.addView(wifi);
        body.addView(Ui.text(this, "Applies to new downloads. Android retries interrupted transfers when it can. If a link expires, return to its catalog page for a fresh one.", 14, Ui.MUTED));
        body.addView(Ui.heading(this, "Your second screen"));
        Switch dual = new Switch(this); dual.setText("Companion display"); dual.setTextColor(Ui.TEXT);
        dual.setMinHeight(Ui.dp(this, 56)); dual.setChecked(preferences.getBoolean("companion", true));
        dual.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("companion", checked).apply(); configureCompanion();
        });
        body.addView(dual);
        displayStatus = Ui.text(this, "", 14, Ui.MUTED); body.addView(displayStatus);
        body.addView(Ui.text(this, "Shows the current page and download status on an available second display. The app also works on a single screen.", 14, Ui.MUTED));
        body.addView(Ui.heading(this, "Using Azahar"));
        body.addView(Ui.text(this, "In Downloads, choose “Prepare for Azahar” on a completed CIA and select your games folder, such as ROMs/n3ds. ThorShelf decrypts and checks a new copy entirely on this device. Base games are saved as playable .3ds files. Keep about five times the download’s size free in internal storage for temporary files, plus room for the saved copy.\n\nSet that same folder as Azahar’s Games Folder once, then refresh its game list after preparation.\n\nUpdates and DLC stay as CIA files: use “Install CIA” in Azahar and select the prepared file. They need their matching game. Preparation does not add emulator support for DSiWare or other unsupported content.", 15, Ui.TEXT));
        body.addView(Ui.button(this, "Open Azahar", this::launchAzahar));
        body.addView(Ui.heading(this, "Controls"));
        body.addView(Ui.text(this, "D-pad: move focus · A: select · B: back\nL / R: switch tabs · Y: search\nUse touch for the website’s verification challenge.", 15, Ui.TEXT));
        body.addView(Ui.heading(this, "Browser help"));
        body.addView(Ui.text(this, "If verification will not load, update Android System WebView. You can also open the current catalog page in your browser.", 14, Ui.MUTED));
        body.addView(Ui.button(this, "Open catalog in browser", () -> openExternal(sourcePage)));
        body.addView(Ui.text(this, "ThorShelf 0.4.0 · Unofficial companion\nCatalog and availability are provided by hShop. Native preparation uses cia3ds-android and Project_CTR; license notices are included with the app. Not affiliated with hShop, Nintendo, AYN, or Azahar.", 12, Ui.MUTED));
        body.addView(Ui.button(this, "Open-source licenses", this::showLicenses));
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
        browser.requestFocus(); loadCatalog(DownloadPolicy.searchUrl(query));
    }
    private void loadCatalog(String url) {
        if (!DownloadPolicy.isCatalog(url)) return;
        showTab(0); browser.loadUrl(url);
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
        int active = 0, complete = 0;
        StringBuilder keys = new StringBuilder();
        for (DownloadStore.Entry entry : entries) {
            keys.append(entry.id).append(':').append(entry.status).append(';');
            if (entry.isActive()) active++;
            if (entry.isComplete()) complete++;
        }
        queueSummary = entries.isEmpty() ? "No downloads yet" : active + " active · " + complete + " downloaded · " + entries.size() + " total";
        downloadSummary.setText(queueSummary);
        String exportMessage = ExportService.status(this);
        exportStatus.setText(exportMessage);
        exportStatus.setVisibility(exportMessage.isEmpty() ? View.GONE : View.VISIBLE);
        String preparationMessage = PreparationService.status(this);
        boolean preparing = PreparationService.isRunning(this);
        preparationStatus.setText(preparationMessage);
        preparationPanel.setVisibility(preparationMessage.isEmpty() ? View.GONE : View.VISIBLE);
        preparationProgress.setProgress(PreparationService.progress(this));
        preparationProgress.setVisibility(preparing ? View.VISIBLE : View.GONE);
        cancelPreparation.setVisibility(preparing ? View.VISIBLE : View.GONE);
        boolean ready = PreparationService.isReady(this);
        preparedGuidance.setText(PreparationService.requiresInstall(this)
                ? "In Azahar, choose “Install CIA” and select the prepared file. Updates and DLC need their matching game."
                : "Set this folder as Azahar’s Games Folder once, then refresh its game list to see your game.");
        preparedGuidance.setVisibility(ready ? View.VISIBLE : View.GONE);
        openPrepared.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (!renderedRows.equals(keys.toString()) || (entries.isEmpty() && downloadList.getChildCount() == 0)) {
            renderedRows = keys.toString(); downloadList.removeAllViews(); downloadCards.clear();
            if (entries.isEmpty()) {
                downloadList.addView(Ui.text(this, "Your queue is ready.\nFind a title in Browse to get started.", 18, Ui.TEXT));
                downloadList.addView(Ui.button(this, "Browse games", () -> loadCatalog(DownloadPolicy.HOME + "c/games")));
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
            root = Ui.column(MainActivity.this); Ui.pad(root, 14);
            root.setBackground(Ui.rounded(MainActivity.this, Ui.PANEL, 0));
            root.addView(Ui.text(MainActivity.this, entry.name, 17, Ui.TEXT));
            status = Ui.text(MainActivity.this, "", 13, Ui.MUTED); root.addView(status);
            progress = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000); root.addView(progress);
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
                root.addView(Ui.button(MainActivity.this, "Prepare for Azahar", () -> prepareCopy(entry)));
            }
        }
        void update(DownloadStore.Entry entry) {
            String size = Formatter.formatFileSize(MainActivity.this, entry.downloaded);
            if (entry.total > 0) size += " / " + Formatter.formatFileSize(MainActivity.this, entry.total);
            status.setText(entry.statusLabel + "\n" + size);
            progress.setVisibility(entry.isActive() ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(entry.total <= 0);
            if (entry.total > 0) progress.setProgress((int) Math.min(1000, entry.downloaded * 1000.0 / entry.total));
        }
    }

    private void saveCopy(DownloadStore.Entry entry) {
        exportId = entry.id; exportName = entry.name; exportPreparing = false;
        requestCopyPicker();
    }
    private void prepareCopy(DownloadStore.Entry entry) {
        if (PreparationService.isRunning(this)) {
            toast("A file is already being prepared. Wait for it to finish or cancel it first.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Prepare for Azahar")
                .setMessage("Prepare “" + entry.name + "” on this device. Your original stays in Downloads.\n\nChoose your games folder, such as ROMs/n3ds. Base games become playable .3ds files. Add the same folder as Azahar’s Games Folder once, then refresh its game list. Updates and DLC remain CIA files and require “Install CIA” in Azahar.\n\nPreparation needs temporary internal storage of about five times the download’s size, plus room for your saved copy.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose games folder", (dialog, which) -> {
                    exportId = entry.id;
                    exportName = entry.name;
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
        else if (browser.canGoBack()) browser.goBack();
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
            String summary = queueSummary;
            if (PreparationService.isRunning(this)) summary += "\n" + PreparationService.status(this);
            companion.update(currentTitle, summary);
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
        out.putLong("exportId", exportId); out.putString("exportName", exportName);
        out.putBoolean("exportPreparing", exportPreparing);
    }
    @Override protected void onDestroy() {
        downloadQueries.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (companion != null) companion.dismiss();
        if (browser != null) { browsePanel.removeView(browser); browser.destroy(); }
        super.onDestroy();
    }
}
