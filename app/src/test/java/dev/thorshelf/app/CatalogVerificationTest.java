package dev.thorshelf.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowWebView;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the isolated security-check browser without any network traffic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, qualifiers = "w411dp-h891dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
public class CatalogVerificationTest {
    private static final String TITLE = "https://hshop.erista.me/t/2476";
    private ActivityController<Activity> controller;
    private Activity activity;
    private CatalogVerification verification;
    private final RecordingListener listener = new RecordingListener();

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
    }

    @After public void tearDown() {
        if (verification != null) verification.dismiss();
        if (controller != null) controller.pause().stop().destroy();
    }

    @Test public void nativeShelfCreatesNoBrowserButVerificationHasAnIsolatedBrowser() {
        ActivityController<MainActivity> shelfController = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            View content = shelfController.get().findViewById(android.R.id.content);
            assertNull(findWebView(content));
            assertNull(ReflectionHelpers.getField(shelfController.get(), "verification"));
            open(TITLE);
            assertNotNull(webView());
            assertEquals(TITLE, shadowOf(webView()).getLastLoadedUrl());
            assertNull(findWebView(content));
        } finally {
            shelfController.pause().stop().destroy();
        }
    }

    @Test public void verificationEnablesChallengeScriptsWithoutFileAccessOrNativeBridge() {
        open(TITLE);
        WebView browser = webView();
        WebSettings settings = browser.getSettings();
        assertTrue(settings.getJavaScriptEnabled());
        assertTrue(settings.getDomStorageEnabled());
        assertFalse(settings.getAllowFileAccess());
        assertFalse(settings.getAllowContentAccess());
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.getMixedContentMode());
        assertFalse(settings.getJavaScriptCanOpenWindowsAutomatically());
        assertFalse(settings.supportMultipleWindows());
        Map<?, ?> interfaces = ReflectionHelpers.getField(shadowOf(browser), "javascriptInterfaces");
        assertTrue(interfaces.isEmpty());
        assertTrue(shadowOf(browser).getCreatedPorts().isEmpty());
    }

    @Test public void mainFrameStaysOnExactRequestedPageButNormalChallengeSubframesContinue() {
        open(TITLE);
        WebView browser = webView();
        WebViewClient client = shadowOf(browser).getWebViewClient();
        assertFalse(client.shouldOverrideUrlLoading(browser, request(TITLE, true)));
        assertFalse(client.shouldOverrideUrlLoading(browser, request(TITLE + "#download", true)));
        for (String destination : new String[] {
                "https://hshop.erista.me/t/2899", "https://hshop.erista.me/c/games",
                TITLE + "?unexpected=true", "https://hshop.erista.me.evil.example/t/2476",
                "http://hshop.erista.me/t/2476", "file:///sdcard/secret", "content://provider/secret",
                "https://download4.erista.me/content/2476?token=example"}) {
            assertTrue(destination, client.shouldOverrideUrlLoading(browser, request(destination, true)));
        }
        assertFalse(client.shouldOverrideUrlLoading(browser,
                request("https://challenges.cloudflare.com/turnstile/v0/example", false)));
    }

    @Test public void encodedSearchQueriesCannotBeReinterpretedAsDifferentQueryParameters() {
        String intended = "https://hshop.erista.me/search/results?q=A%26qt=Id&qt=Text";
        open(intended);
        WebView browser = webView();
        assertTrue(shadowOf(browser).getWebViewClient().shouldOverrideUrlLoading(browser,
                request("https://hshop.erista.me/search/results?q=A&qt=Id&qt=Text", true)));
    }

    @Test public void unsupportedPageIsRejectedBeforeBrowserIsCreated() {
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogVerification(activity, "https://other.example/t/2476", true, listener));
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogVerification(activity, "https://hshop.erista.me/wiki", true, listener));
    }

    @Test public void completedDocumentIsReturnedAsDataWithoutAddingJavascriptInterface() {
        open(TITLE);
        String html = "<html><body><p>Completed check</p></body></html>";
        callback().onReceiveValue(JSONObject.quote(html));
        assertEquals(Collections.singletonList(html), listener.html);
        Map<?, ?> interfaces = ReflectionHelpers.getField(shadowOf(webView()), "javascriptInterfaces");
        assertTrue(interfaces.isEmpty());
    }

    @Test public void callbackRejectsNonStringOrMalformedJavascriptResults() {
        open(TITLE);
        for (String invalid : new String[] {"null", "{}", "false", "123", "{", "\"\""}) {
            callback().onReceiveValue(invalid);
        }
        assertTrue(listener.html.isEmpty());
    }

    @Test public void pendingCallbackCannotAcceptHtmlFromAnotherOriginOrTitle() {
        open(TITLE);
        WebView browser = webView();
        ValueCallback<String> original = callback();
        browser.loadUrl("https://other.example/t/2476");
        original.onReceiveValue(JSONObject.quote("<html>Untrusted page</html>"));
        assertTrue(listener.html.isEmpty());
        browser.loadUrl("https://hshop.erista.me/t/2899");
        original.onReceiveValue(JSONObject.quote("<html>Another title</html>"));
        assertTrue(listener.html.isEmpty());
    }

    @Test public void cancellationNotifiesOnceDestroysBrowserAndIgnoresLateHtml() {
        open(TITLE);
        WebView browser = webView();
        ValueCallback<String> pending = callback();
        dialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, listener.cancellations);
        assertTrue(shadowOf(browser).wasDestroyCalled());
        assertNull(browser.getParent());
        pending.onReceiveValue(JSONObject.quote("<html>Too late</html>"));
        assertTrue(listener.html.isEmpty());
        verification.dismiss();
        assertEquals(1, listener.cancellations);
    }

    @Test public void systemCancelAlsoClosesBrowserWhileProgrammaticDismissDoesNotCancelRequest() {
        open(TITLE);
        WebView cancelledBrowser = webView();
        dialog().cancel();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, listener.cancellations);
        assertTrue(shadowOf(cancelledBrowser).wasDestroyCalled());

        open(TITLE);
        WebView dismissedBrowser = webView();
        verification.dismiss();
        assertEquals(1, listener.cancellations);
        assertTrue(shadowOf(dismissedBrowser).wasDestroyCalled());
    }

    @Test public void pauseStopsPollingAndIgnoresAnAlreadyPendingCallbackUntilResume() {
        open(TITLE);
        WebView browser = webView();
        ShadowWebView shadow = shadowOf(browser);
        ValueCallback<String> pending = callback();
        verification.pause();
        pending.onReceiveValue(JSONObject.quote("<html>Paused result</html>"));
        assertTrue(listener.html.isEmpty());
        assertTrue(shadow.wasOnPauseCalled());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertSame(pending, shadow.getLastEvaluatedJavascriptCallback());

        verification.resume();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(shadow.wasOnResumeCalled());
        ValueCallback<String> resumed = shadow.getLastEvaluatedJavascriptCallback();
        assertNotSame(pending, resumed);
        resumed.onReceiveValue(JSONObject.quote("<html>Resumed result</html>"));
        assertEquals(Collections.singletonList("<html>Resumed result</html>"), listener.html);
    }

    private void open(String url) {
        if (verification != null) verification.dismiss();
        verification = new CatalogVerification(activity, url, true, listener);
        verification.show();
    }

    private WebView webView() { return ReflectionHelpers.getField(verification, "webView"); }
    private AlertDialog dialog() { return ReflectionHelpers.getField(verification, "dialog"); }

    private ValueCallback<String> callback() {
        WebView browser = webView();
        ShadowWebView shadow = shadowOf(browser);
        shadow.getWebViewClient().onPageFinished(browser, browser.getUrl());
        ValueCallback<String> callback = shadow.getLastEvaluatedJavascriptCallback();
        assertNotNull("A matching completed document should be inspected", callback);
        return callback;
    }

    private static WebResourceRequest request(String url, boolean mainFrame) {
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(url); }
            @Override public boolean isForMainFrame() { return mainFrame; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return true; }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                WebView found = findWebView(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class RecordingListener implements CatalogVerification.Listener {
        final List<String> html = new ArrayList<>();
        int cancellations;
        @Override public void onHtml(String value) { html.add(value); }
        @Override public void onCancel() { cancellations++; }
    }
}
