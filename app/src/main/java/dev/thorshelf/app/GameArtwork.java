package dev.thorshelf.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Catalog artwork with bundled illustrations and initials available immediately as fallbacks. */
final class GameArtwork {
    private static final LruCache<String, Bitmap> BITMAPS = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private static final LruCache<String, Bitmap> REMOTE_BITMAPS = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor ARTWORK_WORKERS = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
                Thread worker = new Thread(runnable, "catalog-artwork");
                worker.setDaemon(true);
                return worker;
            });
    static Supplier<ArtworkClient> clientFactory = ArtworkClient::new;

    static {
        ARTWORK_WORKERS.allowCoreThreadTimeOut(true);
    }
    private static final int[] TILE_COLORS = {
            Color.rgb(255, 231, 207), Color.rgb(220, 238, 220), Color.rgb(221, 234, 247),
            Color.rgb(235, 224, 244), Color.rgb(248, 229, 200)
    };
    private static final int[] INK_COLORS = {
            Color.rgb(155, 78, 24), Color.rgb(61, 105, 64), Color.rgb(57, 87, 126),
            Color.rgb(105, 76, 130), Color.rgb(135, 98, 37)
    };
    private static final int[] DARK_TILE_COLORS = {
            0xFF382C21, 0xFF25362B, 0xFF263341, 0xFF342B3E, 0xFF383124
    };
    private static final int[] DARK_INK_COLORS = {
            0xFFF4BC87, 0xFFA4D5AB, 0xFFA9C9ED, 0xFFCAB6E3, 0xFFE5CF9A
    };

    private GameArtwork() { }

    /** Returns a decorative square; the containing row supplies the accessible title. */
    static View thumbnail(Context context, String title, int sizeDp) {
        int resource = illustration(title);
        View view;
        if (resource != 0) {
            view = image(context, resource, sizeDp);
        } else {
            TextView initial = new TextView(context);
            String display = displayTitle(title);
            int color = Math.floorMod(display.hashCode(), TILE_COLORS.length);
            initial.setText(initials(display));
            initial.setTextSize(Math.min(28, Math.max(16, sizeDp / 3)));
            initial.setTypeface(null, Typeface.BOLD);
            initial.setGravity(Gravity.CENTER);
            AppTheme.Palette colors = Ui.colors(context);
            initial.setTextColor(colors.dark ? DARK_INK_COLORS[color] : INK_COLORS[color]);
            int background = colors.oled ? colors.SURFACE
                    : colors.dark ? DARK_TILE_COLORS[color] : TILE_COLORS[color];
            initial.setBackground(Ui.rounded(context, background, colors.oled ? colors.BORDER : 0));
            view = initial;
        }
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setFocusable(false);
        view.setMinimumWidth(Ui.dp(context, sizeDp));
        view.setMinimumHeight(Ui.dp(context, sizeDp));
        return view;
    }

    /** Remote metadata is optional: an unavailable image must never hide the title's fallback. */
    static View thumbnail(Context context, String title, String imageUrl, int sizeDp) {
        if (!ArtworkPolicy.isAllowed(imageUrl)) return thumbnail(context, title, sizeDp);
        return new RemoteThumbnail(context, title, imageUrl, sizeDp);
    }

    static void clearRemoteCacheForTests() {
        REMOTE_BITMAPS.evictAll();
    }

    private static final class RemoteThumbnail extends FrameLayout {
        private final String imageUrl;
        private final int pixels;
        private final String cacheKey;
        private int generation;
        private boolean loaded;
        private ArtworkRequest request;
        private Future<?> pending;

        RemoteThumbnail(Context context, String title, String imageUrl, int sizeDp) {
            super(context);
            this.imageUrl = imageUrl;
            int edge = 64;
            while (edge < Ui.dp(context, sizeDp) && edge < 512) edge *= 2;
            pixels = edge;
            cacheKey = pixels + ":" + imageUrl;
            addView(thumbnail(context, title, sizeDp), new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            setMinimumWidth(Ui.dp(context, sizeDp));
            setMinimumHeight(Ui.dp(context, sizeDp));
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            setFocusable(false);
            Bitmap cached = REMOTE_BITMAPS.get(cacheKey);
            if (cached != null) show(cached);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (loaded || request != null) return;
            Bitmap cached = REMOTE_BITMAPS.get(cacheKey);
            if (cached != null) {
                show(cached);
                return;
            }
            request = new ArtworkRequest(this, ++generation, imageUrl, pixels, cacheKey, clientFactory);
            pending = ARTWORK_WORKERS.submit(request);
        }

        @Override protected void onDetachedFromWindow() {
            generation++;
            if (request != null) request.cancelled = true;
            if (pending != null) {
                pending.cancel(true);
                if (pending instanceof Runnable) ARTWORK_WORKERS.remove((Runnable) pending);
            }
            request = null;
            pending = null;
            super.onDetachedFromWindow();
        }

        void complete(int requestGeneration, Bitmap bitmap) {
            if (!isAttachedToWindow() || generation != requestGeneration) return;
            request = null;
            pending = null;
            if (bitmap != null) show(bitmap);
        }

        private void show(Bitmap bitmap) {
            ImageView image = new ImageView(getContext());
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(bitmap);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            image.setFocusable(false);
            removeAllViews();
            addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            loaded = true;
        }
    }

    /** A queued request holds no Activity or View strongly, including while delivering a result. */
    private static final class ArtworkRequest implements Runnable {
        private final WeakReference<RemoteThumbnail> target;
        private final int generation;
        private final String imageUrl;
        private final int pixels;
        private final String cacheKey;
        private final Supplier<ArtworkClient> factory;
        volatile boolean cancelled;

        ArtworkRequest(RemoteThumbnail target, int generation, String imageUrl, int pixels,
                       String cacheKey, Supplier<ArtworkClient> factory) {
            this.target = new WeakReference<>(target);
            this.generation = generation;
            this.imageUrl = imageUrl;
            this.pixels = pixels;
            this.cacheKey = cacheKey;
            this.factory = factory;
        }

        @Override public void run() {
            if (cancelled) return;
            Bitmap bitmap = REMOTE_BITMAPS.get(cacheKey);
            try {
                if (bitmap == null) bitmap = factory.get().fetch(imageUrl, pixels);
            } catch (IOException | RuntimeException unavailable) {
                // The title, metadata and download actions remain usable without artwork.
            }
            if (cancelled || Thread.currentThread().isInterrupted()) return;
            if (bitmap != null) REMOTE_BITMAPS.put(cacheKey, bitmap);
            Bitmap result = bitmap;
            MAIN.post(() -> {
                RemoteThumbnail view = target.get();
                if (!cancelled && view != null) view.complete(generation, result);
            });
        }
    }

    static ImageView image(Context context, int resource, int targetDp) {
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(bitmap(context, resource, Ui.dp(context, targetDp)));
        AppTheme.Palette colors = Ui.colors(context);
        image.setBackground(Ui.rounded(context, colors.dark ? colors.SURFACE : Color.rgb(255, 238, 219), 0));
        image.setClipToOutline(true);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    static String displayTitle(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "Game";
        String clean = GameFilename.clean(filename).replaceFirst("(?i)\\.(cia|cci|cxi|3ds|3dsx|nds|gba|zip|7z)$", "");
        return clean.isEmpty() ? "Game" : clean;
    }

    private static int illustration(String title) {
        String key = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        if (key.contains("omega ruby")) return R.drawable.shelf_hero;
        if (key.contains("animal crossing") && (key.contains("new leaf") || key.equals("animal crossing"))) {
            return R.drawable.shelf_animal_crossing;
        }
        if (key.contains("mario kart 7")) return R.drawable.shelf_mario_kart;
        if (key.contains("professor layton") && (key.contains("miracle mask") || key.equals("professor layton"))) {
            return R.drawable.shelf_layton;
        }
        if (key.contains("a link between worlds")) return R.drawable.shelf_zelda;
        return 0;
    }

    private static Bitmap bitmap(Context context, int resource, int requestedPixels) {
        // Bucket sizes keep the same illustration reusable across queue rows and activity recreation.
        int edge = 128;
        while (edge < requestedPixels && edge < 1024) edge *= 2;
        String key = resource + ":" + edge;
        Bitmap cached = BITMAPS.get(key);
        if (cached != null) return cached;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        options.inScaled = false;
        BitmapFactory.decodeResource(context.getResources(), resource, options);
        options.inSampleSize = 1;
        while (options.outWidth / (options.inSampleSize * 2) >= edge
                && options.outHeight / (options.inSampleSize * 2) >= edge) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap decoded = BitmapFactory.decodeResource(context.getResources(), resource, options);
        if (decoded != null) BITMAPS.put(key, decoded);
        return decoded;
    }

    private static String initials(String title) {
        StringBuilder result = new StringBuilder();
        for (String word : title.split("\\s+")) {
            if (word.isEmpty() || word.equalsIgnoreCase("the")) continue;
            int first = word.codePointAt(0);
            if (!Character.isLetterOrDigit(first)) continue;
            result.appendCodePoint(Character.toUpperCase(first));
            if (result.codePointCount(0, result.length()) == 2) break;
        }
        return result.length() == 0 ? "G" : result.toString();
    }
}
