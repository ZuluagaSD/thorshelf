package dev.thorshelf.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.Locale;

/** Bundled editorial illustrations, with lightweight initial tiles for all other titles. */
final class GameArtwork {
    private static final LruCache<String, Bitmap> BITMAPS = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private static final int[] TILE_COLORS = {
            Color.rgb(255, 231, 207), Color.rgb(220, 238, 220), Color.rgb(221, 234, 247),
            Color.rgb(235, 224, 244), Color.rgb(248, 229, 200)
    };
    private static final int[] INK_COLORS = {
            Color.rgb(155, 78, 24), Color.rgb(61, 105, 64), Color.rgb(57, 87, 126),
            Color.rgb(105, 76, 130), Color.rgb(135, 98, 37)
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
            initial.setTextColor(INK_COLORS[color]);
            initial.setBackground(Ui.rounded(context, TILE_COLORS[color], 0));
            view = initial;
        }
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setFocusable(false);
        view.setMinimumWidth(Ui.dp(context, sizeDp));
        view.setMinimumHeight(Ui.dp(context, sizeDp));
        return view;
    }

    static ImageView image(Context context, int resource, int targetDp) {
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(bitmap(context, resource, Ui.dp(context, targetDp)));
        image.setBackground(Ui.rounded(context, Color.rgb(255, 238, 219), 0));
        image.setClipToOutline(true);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    static String displayTitle(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "Game";
        String clean = GameFilename.clean(filename).replaceFirst("(?i)\\.(cia|cci|cxi|3ds|3dsx|nds|zip|7z)$", "");
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
