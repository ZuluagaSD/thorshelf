package dev.thorshelf.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.thorshelf.app.CatalogUiTestSupport.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Artwork lifecycle checks use synthetic bitmaps and gated clients, never network requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class GameArtworkTest {
    private static final String IMAGE = "https://raw.githubusercontent.com/example/game/main/icon.png";
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private StubClient client;

    @Before public void launch() {
        GameArtwork.clearRemoteCacheForTests();
        client = new StubClient();
        GameArtwork.clientFactory = () -> client;
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        root = new FrameLayout(activity);
        activity.setContentView(root);
    }

    @After public void close() {
        root.removeAllViews();
        for (Response response : client.responses) {
            if (response.gate != null) response.gate.release.countDown();
        }
        controller.pause().stop().destroy();
        GameArtwork.clientFactory = ArtworkClient::new;
        GameArtwork.clearRemoteCacheForTests();
    }

    @Test public void absentOrInvalidMetadataKeepsTheExistingFallbackWithoutFetching() {
        for (String url : new String[] {null, "", "file:///private/icon.png", "https://example.invalid/icon.png"}) {
            View thumbnail = GameArtwork.thumbnail(activity, "Tiny Adventure", url, 64);
            root.addView(thumbnail);
            assertTrue(thumbnail instanceof TextView);
            assertEquals("TA", ((TextView) thumbnail).getText().toString());
        }
        View bundled = GameArtwork.thumbnail(activity, "Mario Kart 7", "", 64);
        root.addView(bundled);
        assertTrue(bundled instanceof ImageView);
        assertNotNull(((ImageView) bundled).getDrawable());
        assertEquals(0, client.calls.get());
    }

    @Test public void attachedArtworkLoadsOffTheMainThreadAndReplacesInitials() {
        Bitmap bitmap = bitmap(Color.RED);
        Response response = new Response(bitmap, new Gate(false), null);
        client.responses.add(response);
        View thumbnail = GameArtwork.thumbnail(activity, "Tiny Adventure", IMAGE, 64);
        assertEquals(0, client.calls.get());
        assertEquals("TA", find(thumbnail, TextView.class, view -> true).getText().toString());
        root.addView(thumbnail);
        await("artwork worker started", () -> response.gate.entered.getCount() == 0);
        assertFalse(client.onMainThread);
        assertEquals(IMAGE, client.requestedUrl);
        assertTrue(client.targetPixels >= Ui.dp(activity, 64));
        assertTrue(client.targetPixels <= 512);
        assertNotNull(find(thumbnail, TextView.class, view -> true));
        response.gate.release.countDown();
        await("remote bitmap displayed", () -> displayedBitmap(thumbnail) == bitmap);
        assertNull(find(thumbnail, TextView.class, view -> true));
        assertEquals(ImageView.ScaleType.FIT_CENTER, find(thumbnail, ImageView.class, view -> true).getScaleType());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                thumbnail.getImportantForAccessibility());
        assertFalse(thumbnail.isFocusable());
    }

    @Test public void threeDsCoverReplacesInitialsAndIsReusedForTheSameEdition() {
        String cover = ThreeDsArtwork.url("games", "north-america", "CTR-P-BRME");
        Bitmap bitmap = bitmap(Color.BLUE);
        Response response = new Response(bitmap, new Gate(false), null);
        client.responses.add(response);
        View first = GameArtwork.thumbnail(activity, "Mario & Luigi - Superstar Saga + Bowser's Minions", cover, 64);
        assertEquals("ML", find(first, TextView.class, view -> true).getText().toString());
        root.addView(first);
        await("3DS cover worker started", () -> response.gate.entered.getCount() == 0);
        assertEquals("https://art.gametdb.com/3ds/coverM/US/BRME.jpg", client.requestedUrl);
        response.gate.release.countDown();
        await("3DS initials replaced by box art", () -> displayedBitmap(first) == bitmap);
        root.removeView(first);
        View queueCopy = GameArtwork.thumbnail(activity, "Mario & Luigi (North America).cia", cover, 64);
        root.addView(queueCopy);
        assertSame(bitmap, displayedBitmap(queueCopy));
        assertEquals(1, client.calls.get());
    }

    @Test public void failedArtworkKeepsFallbackAndRetriesOnReattachment() {
        Response failure = new Response(null, null, new IOException("Synthetic missing image"));
        Bitmap bitmap = bitmap(Color.GREEN);
        client.responses.add(failure);
        client.responses.add(new Response(bitmap, null, null));
        View thumbnail = GameArtwork.thumbnail(activity, "Tiny Adventure", IMAGE, 64);
        root.addView(thumbnail);
        await("artwork failure", () -> failure.finished.getCount() == 0);
        assertEquals("TA", find(thumbnail, TextView.class, view -> true).getText().toString());
        assertNull(displayedBitmap(thumbnail));
        root.removeView(thumbnail);
        root.addView(thumbnail);
        await("artwork retried on attachment", () -> displayedBitmap(thumbnail) == bitmap);
        assertEquals(2, client.calls.get());
    }

    @Test public void detachedRequestCannotOverwriteReattachedArtworkOrItsCache() {
        Response oldResponse = new Response(bitmap(Color.RED), new Gate(true), null);
        Bitmap current = bitmap(Color.BLUE);
        Response newResponse = new Response(current, new Gate(false), null);
        client.responses.add(oldResponse);
        client.responses.add(newResponse);
        View thumbnail = GameArtwork.thumbnail(activity, "Tiny Adventure", IMAGE, 64);
        root.addView(thumbnail);
        await("first artwork request", () -> oldResponse.gate.entered.getCount() == 0);
        root.removeView(thumbnail);
        root.addView(thumbnail);
        await("fresh artwork request", () -> newResponse.gate.entered.getCount() == 0);
        newResponse.gate.release.countDown();
        await("fresh artwork displayed", () -> displayedBitmap(thumbnail) == current);
        oldResponse.gate.release.countDown();
        await("cancelled request finished", () -> oldResponse.finished.getCount() == 0);
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(current, displayedBitmap(thumbnail));
        View reused = GameArtwork.thumbnail(activity, "Tiny Adventure", IMAGE, 64);
        root.addView(reused);
        assertSame(current, displayedBitmap(reused));
        assertEquals(2, client.calls.get());
    }

    @Test public void detachCancelsOutstandingFetchAndLeavesFallbackIntact() {
        Response response = new Response(bitmap(Color.RED), new Gate(false), null);
        client.responses.add(response);
        View thumbnail = GameArtwork.thumbnail(activity, "Tiny Adventure", IMAGE, 64);
        root.addView(thumbnail);
        await("artwork started", () -> response.gate.entered.getCount() == 0);
        root.removeView(thumbnail);
        await("artwork interrupted", () -> response.finished.getCount() == 0);
        assertEquals(1, response.gate.release.getCount());
        assertNull(displayedBitmap(thumbnail));
        assertNotNull(find(thumbnail, TextView.class, view -> true));
    }

    @Test public void cachedArtworkIsReusedWithoutFetchingAgain() {
        Bitmap bitmap = bitmap(Color.GREEN);
        client.responses.add(new Response(bitmap, null, null));
        View first = GameArtwork.thumbnail(activity, "Tiny Adventure", IMAGE, 64);
        root.addView(first);
        await("initial artwork loaded", () -> displayedBitmap(first) == bitmap);
        root.removeView(first);
        View second = GameArtwork.thumbnail(activity, "Tiny Adventure", IMAGE, 64);
        assertSame(bitmap, displayedBitmap(second));
        root.addView(second);
        assertEquals(1, client.calls.get());
    }

    private static Bitmap bitmap(int color) {
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private static Bitmap displayedBitmap(View root) {
        ImageView image = find(root, ImageView.class, view -> view.getDrawable() instanceof BitmapDrawable);
        return image == null ? null : ((BitmapDrawable) image.getDrawable()).getBitmap();
    }

    private static final class Response {
        final Bitmap bitmap;
        final Gate gate;
        final IOException failure;
        final CountDownLatch finished = new CountDownLatch(1);
        Response(Bitmap bitmap, Gate gate, IOException failure) {
            this.bitmap = bitmap;
            this.gate = gate;
            this.failure = failure;
        }
    }

    private static final class StubClient extends ArtworkClient {
        final List<Response> responses = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean onMainThread;
        volatile String requestedUrl;
        volatile int targetPixels;

        @Override public Bitmap fetch(String url, int pixels) throws IOException {
            Response response = responses.get(calls.getAndIncrement());
            onMainThread = Looper.myLooper() == Looper.getMainLooper();
            requestedUrl = url;
            targetPixels = pixels;
            try {
                if (response.gate != null) response.gate.waitForRelease();
                if (response.failure != null) throw response.failure;
                return response.bitmap;
            } finally {
                response.finished.countDown();
            }
        }
    }
}
