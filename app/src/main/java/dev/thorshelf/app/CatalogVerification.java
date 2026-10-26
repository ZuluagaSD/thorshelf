package dev.thorshelf.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import org.json.JSONTokener;

/** The real hShop check is shown only when required; it is never solved by the app. */
final class CatalogVerification {
    interface Listener {
        void onHtml(String html);
        void onCancel();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WebView webView;
    private final AlertDialog dialog;
    private final String pageUrl;
    private boolean closed;
    private boolean paused;
    private boolean reading;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (closed || paused) return;
            inspectPage();
            handler.postDelayed(this, 1500);
        }
    };
    private final Listener listener;

    @SuppressWarnings("SetJavaScriptEnabled")
    CatalogVerification(Activity activity, String pageUrl, boolean download, Listener listener) {
        if (!CatalogParser.isCatalogPageUrl(pageUrl)) throw new IllegalArgumentException("Invalid catalog page");
        this.pageUrl = pageUrl;
        this.listener = listener;
        LinearLayout body = Ui.column(activity);
        Ui.pad(body, 12);
        body.addView(Ui.text(activity, download
                ? "Complete hShop’s check below. Your download will continue automatically."
                : "Complete hShop’s check below to return to your shelf.", 14, Ui.MUTED));
        webView = new WebView(activity);
        webView.setBackgroundColor(Ui.BG);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                // Stay on the requested page. The widget's normal subframe/fetch traffic is unaffected.
                return !samePage(request.getUrl().toString());
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                reading = false;
                if (!samePage(url)) view.stopLoading();
            }
            @Override public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                inspectPage();
            }
        });
        // Never start a transfer from a web click: native code checks fresh response headers first.
        webView.setDownloadListener((url, userAgent, disposition, mime, size) -> inspectPage());
        int height = Math.max(180, Math.min(460,
                activity.getResources().getConfiguration().screenHeightDp - 180));
        body.addView(webView, new LinearLayout.LayoutParams(-1, Ui.dp(activity, height)));
        dialog = new AlertDialog.Builder(activity).setTitle("Verify with hShop").setView(body)
                .setNegativeButton("Cancel", (d, w) -> listener.onCancel()).create();
        dialog.setOnCancelListener(d -> listener.onCancel());
        dialog.setOnDismissListener(d -> closeBrowser());
    }

    void show() {
        dialog.show();
        webView.loadUrl(pageUrl);
        handler.postDelayed(poll, 1500);
    }

    void dismiss() { dialog.dismiss(); closeBrowser(); }
    void pause() { paused = true; handler.removeCallbacks(poll); webView.onPause(); }
    void resume() {
        if (closed) return;
        paused = false; webView.onResume();
        handler.removeCallbacks(poll); handler.post(poll);
    }

    private boolean samePage(String candidate) {
        if (!CatalogParser.isCatalogPageUrl(candidate)) return false;
        android.net.Uri expected = android.net.Uri.parse(pageUrl);
        android.net.Uri actual = android.net.Uri.parse(candidate);
        return expected.getEncodedPath().equals(actual.getEncodedPath())
                && java.util.Objects.equals(expected.getEncodedQuery(), actual.getEncodedQuery());
    }

    private void inspectPage() {
        if (closed || paused || reading || !samePage(webView.getUrl())) return;
        reading = true;
        // A return-value callback only: no JavaScript-to-native interface is exposed to web content.
        webView.evaluateJavascript("(function(){var h=document.documentElement.outerHTML;"
                + "return h.length<=2097152?h:'';})()", value -> {
            reading = false;
            if (closed || paused || !samePage(webView.getUrl())) return;
            try {
                Object decoded = new JSONTokener(value).nextValue();
                if (decoded instanceof String && !((String) decoded).isEmpty()) listener.onHtml((String) decoded);
            } catch (org.json.JSONException ignored) { /* Wait for the next completed document. */ }
        });
    }

    private void closeBrowser() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        webView.stopLoading();
        ((LinearLayout) webView.getParent()).removeView(webView);
        webView.destroy();
    }
}
