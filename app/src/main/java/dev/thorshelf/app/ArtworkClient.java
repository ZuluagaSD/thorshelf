package dev.thorshelf.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Fetches a small public raster image without catalog/browser request metadata. */
public class ArtworkClient {
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    static final int MAX_IMAGE_DIMENSION = 8192;
    static final long MAX_IMAGE_PIXELS = 16L * 1024 * 1024;
    private static final int MAX_TARGET_PIXELS = 512;
    private static final int MAX_REDIRECTS = 4;
    private final ConnectionFactory connections;

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    public ArtworkClient() {
        this(url -> (HttpURLConnection) url.openConnection());
    }

    ArtworkClient(ConnectionFactory connections) {
        this.connections = connections;
    }

    public Bitmap fetch(String url, int targetPixels) throws IOException {
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("Artwork must be loaded in the background.");
        }
        int target = Math.max(1, Math.min(targetPixels, MAX_TARGET_PIXELS));
        String current = url;
        for (int redirects = 0; ; redirects++) {
            checkCancelled();
            if (!ArtworkPolicy.isAllowed(current)) throw new IOException("Unsupported artwork address.");
            HttpURLConnection connection = connections.open(new URL(current));
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "image/png,image/jpeg,image/webp,image/gif");
                connection.setRequestProperty("Accept-Encoding", "gzip");
                int status = connection.getResponseCode();
                checkCancelled();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects >= MAX_REDIRECTS) throw new IOException("Too many artwork redirects.");
                    current = redirect(current, connection.getHeaderField("Location"));
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Artwork returned HTTP " + status + ".");
                }
                String mime = connection.getContentType();
                mime = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!isSupportedMime(mime)) throw new IOException("Artwork is not a supported raster image.");
                if (connection.getContentLengthLong() > MAX_BODY_BYTES) {
                    throw new IOException("Artwork is too large.");
                }
                String encoding = connection.getContentEncoding();
                encoding = encoding == null ? "" : encoding.trim();
                if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("identity")
                        && !encoding.equalsIgnoreCase("gzip")) {
                    throw new IOException("Artwork returned an unsupported encoding.");
                }
                byte[] bytes;
                try (InputStream raw = new LimitedInputStream(connection.getInputStream());
                     InputStream decoded = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(raw) : raw) {
                    bytes = readBody(decoded);
                }
                checkCancelled();
                return decode(bytes, mime, target);
            } finally {
                connection.disconnect();
            }
        }
    }

    private static byte[] readBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[8192];
        while (true) {
            checkCancelled();
            int count = input.read(buffer);
            if (count == -1) break;
            if (count > MAX_BODY_BYTES - output.size()) throw new IOException("Artwork is too large.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Bitmap decode(byte[] bytes, String mime, int target) throws IOException {
        if (!hasSignature(bytes, mime)) throw new IOException("Artwork is not a valid raster image.");
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            if (width <= 0 || height <= 0 || !mime.equals(bounds.outMimeType)) {
                throw new IOException("Artwork is not a valid raster image.");
            }
            if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
                    || (long) width * height > MAX_IMAGE_PIXELS) {
                throw new IOException("Artwork dimensions are too large.");
            }
            checkCancelled();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            options.inSampleSize = 1;
            while ((width + options.inSampleSize - 1) / options.inSampleSize > target
                    || (height + options.inSampleSize - 1) / options.inSampleSize > target) {
                options.inSampleSize *= 2;
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) throw new IOException("Artwork could not be decoded.");
            if (bitmap.getWidth() > target || bitmap.getHeight() > target) {
                throw new IOException("Artwork decoder exceeded the requested size.");
            }
            checkCancelled();
            return bitmap;
        } catch (IOException invalid) {
            if (bitmap != null) bitmap.recycle();
            throw invalid;
        } catch (RuntimeException | OutOfMemoryError invalid) {
            if (bitmap != null) bitmap.recycle();
            throw new IOException("Artwork could not be decoded.", invalid);
        }
    }

    private static boolean isSupportedMime(String mime) {
        return mime.equals("image/png") || mime.equals("image/jpeg")
                || mime.equals("image/webp") || mime.equals("image/gif");
    }

    private static boolean hasSignature(byte[] bytes, String mime) {
        if (bytes.length < 12) return false;
        switch (mime) {
            case "image/png":
                return (bytes[0] & 255) == 137 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                        && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
            case "image/jpeg":
                return (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
            case "image/webp":
                return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                        && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            case "image/gif":
                return bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8'
                        && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
            default:
                return false;
        }
    }

    private static String redirect(String current, String location) throws IOException {
        if (location == null || location.isEmpty() || location.length() > 2048) {
            throw new IOException("Artwork returned an invalid redirect.");
        }
        try {
            String target = new URI(current).resolve(new URI(location)).toString();
            if (!ArtworkPolicy.isAllowed(target)) throw new IOException("Artwork redirected to an unsupported address.");
            return target;
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            throw new IOException("Artwork returned an invalid redirect.", invalid);
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Artwork request cancelled.");
    }

    /** Bounds wire bytes separately from expanded gzip bytes, including chunked responses. */
    private static final class LimitedInputStream extends FilterInputStream {
        private int count;

        LimitedInputStream(InputStream input) { super(input); }

        @Override public int read() throws IOException {
            checkCancelled();
            int value = in.read();
            if (value != -1 && ++count > MAX_BODY_BYTES) throw new IOException("Artwork is too large.");
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            checkCancelled();
            int read = in.read(buffer, offset, Math.min(length, MAX_BODY_BYTES - count + 1));
            if (read > 0) {
                count += read;
                if (count > MAX_BODY_BYTES) throw new IOException("Artwork is too large.");
            }
            return read;
        }
    }
}
