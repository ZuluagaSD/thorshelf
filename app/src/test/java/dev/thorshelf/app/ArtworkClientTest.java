package dev.thorshelf.app;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

/** Real raster decoding with synthetic images; all HTTP connections are in-memory fakes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ArtworkClientTest {
    private static final String IMAGE = "https://raw.githubusercontent.com/example/game/main/icon.png";

    @Test public void fetchesGameTdbJpegCoverWithoutCatalogHeaders() throws Exception {
        String cover = ThreeDsArtwork.url("games", "north-america", "CTR-P-BRME");
        Response response = image(encodedBitmap(400, 360, Bitmap.CompressFormat.JPEG))
                .header("Content-Type", "image/jpeg");
        Factory factory = new Factory(response);
        Bitmap bitmap = fetch(new ArtworkClient(factory), cover, 128);
        try {
            assertEquals(Arrays.asList("https://art.gametdb.com/3ds/coverM/US/BRME.jpg"), factory.opened);
            assertTrue(bitmap.getWidth() <= 128);
            assertTrue(bitmap.getHeight() <= 128);
            assertNull(response.getRequestProperty("Cookie"));
            assertNull(response.getRequestProperty("Referer"));
            assertNull(response.getRequestProperty("User-Agent"));
            assertTrue(response.closed);
            assertTrue(response.disconnected);
        } finally { bitmap.recycle(); }
    }

    @Test public void ndsShopBoxartRedirectPreservesImageHeaderIsolation() throws Exception {
        String icon = "https://db-nds-shop.fr/api/v1/assets/icons/pokemon.png";
        String encoded = "Pok%C3%A9mon%20(France)%20%5Bb%5D.nds.png";
        Response redirect = new Response(302).header("Location", "../boxart/" + encoded);
        Response response = image(png(64, 64));
        Factory factory = new Factory(redirect, response);
        Bitmap bitmap = fetch(new ArtworkClient(factory), icon, 64);
        bitmap.recycle();
        assertEquals(Arrays.asList(icon, "https://db-nds-shop.fr/api/v1/assets/boxart/" + encoded), factory.opened);
        for (Response connection : new Response[] {redirect, response}) {
            assertEquals("GET", connection.getRequestMethod());
            assertNull(connection.getRequestProperty("Cookie"));
            assertNull(connection.getRequestProperty("Referer"));
            assertNull(connection.getRequestProperty("User-Agent"));
            assertNull(connection.getRequestProperty("Authorization"));
            assertTrue(connection.disconnected);
        }
    }

    @Test public void ndsShopImageRedirectCannotBecomeADownloadOrAnArbitraryEndpoint() throws Exception {
        String icon = "https://db-nds-shop.fr/api/v1/assets/icons/game.png";
        for (String destination : new String[] {
                "/api/v1/download/Game.nds", "/api/v1/download/Game.cia", "/api/v1/assets/qr/Game.png",
                "/api/v1/assets/boxart/Game.png?url=https://evil.example/Game.png",
                "/api/v1/assets/boxart/%252e%252e%252fGame.png", "https://hshop.erista.me/t/1"}) {
            Response response = new Response(302).header("Location", destination);
            Factory factory = new Factory(response);
            assertThrows(IOException.class, () -> fetch(new ArtworkClient(factory), icon, 64));
            assertEquals(Arrays.asList(icon), factory.opened);
            assertFalse(response.requested);
            assertTrue(response.disconnected);
        }
    }

    @Test public void fetchesAndSamplesImageWithoutBrowserMetadata() throws Exception {
        Response response = image(png(1024, 512));
        Factory factory = new Factory(response);
        Bitmap bitmap = fetch(new ArtworkClient(factory), IMAGE, 128);
        try {
            assertEquals(128, bitmap.getWidth());
            assertEquals(64, bitmap.getHeight());
            assertEquals(Color.RED, bitmap.getPixel(0, 0));
            assertEquals(Arrays.asList(IMAGE), factory.opened);
            assertEquals("GET", response.getRequestMethod());
            assertEquals("gzip", response.getRequestProperty("Accept-Encoding"));
            assertNull(response.getRequestProperty("Cookie"));
            assertNull(response.getRequestProperty("Referer"));
            assertNull(response.getRequestProperty("User-Agent"));
            assertNull(response.getRequestProperty("Authorization"));
            assertFalse(response.getInstanceFollowRedirects());
            assertFalse(response.getUseCaches());
            assertTrue(response.getConnectTimeout() > 0);
            assertTrue(response.getReadTimeout() > 0);
            assertTrue(response.closed);
            assertTrue(response.disconnected);
        } finally { bitmap.recycle(); }
    }

    @Test public void capsDecodeTargetAndDoesNotUpscaleSmallIcons() throws Exception {
        Bitmap large = fetch(new ArtworkClient(new Factory(image(png(1024, 512)))), IMAGE, Integer.MAX_VALUE);
        Bitmap small = fetch(new ArtworkClient(new Factory(image(png(32, 16)))), IMAGE, 128);
        try {
            assertEquals(512, large.getWidth());
            assertEquals(256, large.getHeight());
            assertEquals(32, small.getWidth());
            assertEquals(16, small.getHeight());
        } finally { large.recycle(); small.recycle(); }
    }

    @Test public void decodesJpegWebpAndFirstGifFrame() throws Exception {
        byte[][] bodies = {encodedBitmap(32, 16, Bitmap.CompressFormat.JPEG),
                encodedBitmap(32, 16, Bitmap.CompressFormat.WEBP),
                Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")};
        String[] mimes = {"image/jpeg", "image/webp", "image/gif"};
        for (int i = 0; i < bodies.length; i++) {
            Bitmap bitmap = fetch(new ArtworkClient(new Factory(image(bodies[i]).header("Content-Type", mimes[i]))), IMAGE, 128);
            assertTrue(bitmap.getWidth() > 0);
            assertTrue(bitmap.getHeight() > 0);
            bitmap.recycle();
        }
    }

    @Test public void followsApprovedRelativeRedirectAndReadsGzip() throws Exception {
        Response redirect = new Response(302).header("Location", "next.png");
        Response response = image(gzip(png(32, 16))).header("Content-Encoding", "gzip");
        Factory factory = new Factory(redirect, response);
        Bitmap bitmap = fetch(new ArtworkClient(factory), IMAGE, 128);
        bitmap.recycle();
        assertEquals(Arrays.asList(IMAGE, "https://raw.githubusercontent.com/example/game/main/next.png"), factory.opened);
        assertTrue(redirect.disconnected);
        assertTrue(response.closed);
        assertTrue(response.disconnected);
        assertNull(redirect.getRequestProperty("Cookie"));
        assertNull(response.getRequestProperty("Cookie"));
        assertNull(response.getRequestProperty("Referer"));
    }

    @Test public void checksPolicyBeforeOpeningInitialAddress() throws Exception {
        for (String url : Arrays.asList("http://raw.githubusercontent.com/example/game/main/icon.png",
                "https://evil.example/icon.png", "file:///tmp/icon.png", "data:image/png;base64,AAAA", null)) {
            Factory factory = new Factory();
            assertThrows(IOException.class, () -> fetch(new ArtworkClient(factory), url, 128));
            assertTrue(factory.opened.isEmpty());
        }
    }

    @Test public void checksEveryRedirectBeforeOpeningItsAddress() throws Exception {
        for (String location : Arrays.asList("http://raw.githubusercontent.com/example/game/main/icon.png",
                "https://evil.example/icon.png", "https://raw.githubusercontent.com.evil.example/icon.png",
                "https://user@raw.githubusercontent.com/example/game/main/icon.png",
                "https://raw.githubusercontent.com/example/game/main/icon.svg", "data:image/png;base64,AAAA", "")) {
            Response first = new Response(302).header("Location", "first.png");
            Response second = new Response(307).header("Location", location);
            Factory factory = new Factory(first, second);
            assertThrows(IOException.class, () -> fetch(new ArtworkClient(factory), IMAGE, 128));
            assertEquals(2, factory.opened.size());
            assertTrue(first.disconnected);
            assertTrue(second.disconnected);
        }
    }

    @Test public void boundsRedirectLoops() throws Exception {
        Response[] responses = new Response[5];
        for (int i = 0; i < responses.length; i++) responses[i] = new Response(302).header("Location", IMAGE);
        Factory factory = new Factory(responses);
        assertThrows(IOException.class, () -> fetch(new ArtworkClient(factory), IMAGE, 128));
        assertEquals(5, factory.opened.size());
        for (Response response : responses) assertTrue(response.disconnected);
    }

    @Test public void rejectsBadStatusMimeEncodingAndDeclaredLengthWithoutReadingBody() throws Exception {
        for (Response response : Arrays.asList(new Response(404), new Response(206),
                image(new byte[0]).header("Content-Type", "text/html"),
                image(new byte[0]).header("Content-Type", "image/svg+xml"),
                image(new byte[0]).header("Content-Type", "image/bmp"),
                image(new byte[0]).header("Content-Encoding", "br"),
                image(new byte[0]).header("Content-Encoding", "gzip, gzip"),
                image(new byte[0]).header("Content-Length", Integer.toString(ArtworkClient.MAX_BODY_BYTES + 1)))) {
            assertThrows(IOException.class, () -> fetch(new ArtworkClient(new Factory(response)), IMAGE, 128));
            assertFalse(response.requested);
            assertTrue(response.disconnected);
        }
    }

    @Test public void rejectsDocumentsMalformedImagesAndMismatchedImageType() throws Exception {
        for (Response response : Arrays.asList(image("<html>not an image</html>".getBytes(StandardCharsets.UTF_8)),
                image("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8)),
                image(new byte[0]), image(Arrays.copyOf(png(1, 1), 12)),
                image(png(16, 16)).header("Content-Type", "image/jpeg"))) {
            assertThrows(IOException.class, () -> fetch(new ArtworkClient(new Factory(response)), IMAGE, 128));
            assertTrue(response.closed);
            assertTrue(response.disconnected);
        }
    }

    @Test public void rejectsExcessiveDimensionsAndPixelCountsBeforeFullDecode() throws Exception {
        for (int[] dimensions : new int[][] {{8193, 1}, {1, 8193}, {5000, 5000}}) {
            Response response = image(withDimensions(png(1, 1), dimensions[0], dimensions[1]));
            IOException failure = assertThrows(IOException.class,
                    () -> fetch(new ArtworkClient(new Factory(response)), IMAGE, 128));
            assertEquals("Artwork dimensions are too large.", failure.getMessage());
            assertTrue(response.closed);
        }
    }

    @Test public void boundsUnknownLengthBodyAndExpandedGzipBody() throws Exception {
        byte[] large = new byte[ArtworkClient.MAX_BODY_BYTES + 1];
        for (Response response : Arrays.asList(image(large), image(gzip(large)).header("Content-Encoding", "gzip"))) {
            IOException failure = assertThrows(IOException.class,
                    () -> fetch(new ArtworkClient(new Factory(response)), IMAGE, 128));
            assertEquals("Artwork is too large.", failure.getMessage());
            assertTrue(response.closed);
            assertTrue(response.disconnected);
        }
    }

    @Test public void boundsCompressedBytesEvenWhenOnlyGzipHeaderIsLarge() throws Exception {
        byte[] compressed = gzip(png(1, 1));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] header = Arrays.copyOf(compressed, 10);
        header[3] = 16; // FCOMMENT permits arbitrary wire bytes that do not expand into the image body.
        body.write(header);
        byte[] comment = new byte[ArtworkClient.MAX_BODY_BYTES + 1];
        Arrays.fill(comment, (byte) 'x');
        body.write(comment);
        body.write(0);
        body.write(compressed, 10, compressed.length - 10);
        Response response = image(body.toByteArray()).header("Content-Encoding", "gzip");
        IOException failure = assertThrows(IOException.class,
                () -> fetch(new ArtworkClient(new Factory(response)), IMAGE, 128));
        assertEquals("Artwork is too large.", failure.getMessage());
        assertTrue(response.closed);
        assertTrue(response.disconnected);
    }

    @Test public void cancellationBeforeNetworkAndDuringBodyClosesResources() throws Exception {
        Factory untouched = new Factory();
        assertThrows(InterruptedIOException.class, () -> background(() -> {
            Thread.currentThread().interrupt();
            return new ArtworkClient(untouched).fetch(IMAGE, 128);
        }));
        assertTrue(untouched.opened.isEmpty());
        Response response = image(png(32, 16));
        response.interruptOnRead = true;
        assertThrows(InterruptedIOException.class,
                () -> fetch(new ArtworkClient(new Factory(response)), IMAGE, 128));
        assertTrue(response.closed);
        assertTrue(response.disconnected);
    }

    @Test public void refusesNetworkOnMainThread() {
        Factory factory = new Factory();
        IOException failure = assertThrows(IOException.class, () -> new ArtworkClient(factory).fetch(IMAGE, 128));
        assertEquals("Artwork must be loaded in the background.", failure.getMessage());
        assertTrue(factory.opened.isEmpty());
    }

    private static Bitmap fetch(ArtworkClient client, String url, int target) throws Exception {
        return background(() -> client.fetch(url, target));
    }

    private static <T> T background(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call);
        Thread worker = new Thread(task, "artwork-client-test");
        worker.start();
        try {
            return task.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw (Error) failure.getCause();
        } finally {
            worker.interrupt();
            worker.join(1000);
        }
    }

    private static byte[] png(int width, int height) {
        return encodedBitmap(width, height, Bitmap.CompressFormat.PNG);
    }

    private static byte[] encodedBitmap(int width, int height, Bitmap.CompressFormat format) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.RED);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(format, 100, output));
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private static byte[] withDimensions(byte[] png, int width, int height) {
        byte[] bytes = png.clone();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putInt(16, width);
        buffer.putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);
        buffer.putInt(29, (int) crc.getValue());
        return bytes;
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(bytes); }
        return output.toByteArray();
    }

    private static Response image(byte[] body) throws Exception {
        Response response = new Response(200).header("Content-Type", "image/png");
        response.body = body;
        return response;
    }

    private static final class Factory implements ArtworkClient.ConnectionFactory {
        final Deque<Response> responses = new ArrayDeque<>();
        final List<String> opened = new ArrayList<>();
        Factory(Response... responses) { this.responses.addAll(Arrays.asList(responses)); }
        @Override public HttpURLConnection open(URL url) {
            opened.add(url.toString());
            if (responses.isEmpty()) throw new AssertionError("Unexpected network request: " + url);
            return responses.remove();
        }
    }

    private static final class Response extends HttpURLConnection {
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final int status;
        byte[] body = new byte[0];
        boolean requested;
        boolean closed;
        boolean disconnected;
        boolean interruptOnRead;
        Response(int status) throws Exception { super(new URL(IMAGE)); this.status = status; }
        Response header(String name, String value) { headers.put(name, value); return this; }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public InputStream getInputStream() {
            requested = true;
            return new ByteArrayInputStream(body) {
                @Override public synchronized int read(byte[] buffer, int offset, int length) {
                    int count = super.read(buffer, offset, length);
                    if (interruptOnRead) Thread.currentThread().interrupt();
                    return count;
                }
                @Override public void close() throws IOException { closed = true; super.close(); }
            };
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
