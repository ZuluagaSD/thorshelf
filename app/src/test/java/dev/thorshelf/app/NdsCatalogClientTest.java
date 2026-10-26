package dev.thorshelf.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NdsCatalogClientTest {
    private static final String API = "https://db-nds-shop.fr/api/v1/catalog";
    private static final String DATABASE = page(1, 1, 1234, 1, 1);

    @Test public void loadsJsonOnceAndSearchAndDetailsUseCacheWithoutBrowserMetadata() throws Exception {
        Response response = database(DATABASE);
        Factory factory = new Factory(response);
        NdsCatalogClient client = new NdsCatalogClient(factory);
        CatalogPage page = client.fetchLegacy(NdsCatalog.HOME, "Browser metadata should stay local");
        assertEquals(1, page.entries.size());
        assertEquals("Demo 1", client.fetchLegacy(page.entries.get(0).url, "ThorShelf").title);
        assertTrue(client.fetchLegacy(NdsCatalog.searchUrl("absent"), "ThorShelf").entries.isEmpty());
        assertEquals(Arrays.asList(API + "?page=1&limit=200"), factory.opened);
        assertEquals("GET", response.getRequestMethod());
        assertEquals("application/json", response.getRequestProperty("Accept"));
        assertNull(response.getRequestProperty("User-Agent"));
        assertNull(response.getRequestProperty("Cookie"));
        assertNull(response.getRequestProperty("Referer"));
        assertFalse(response.getInstanceFollowRedirects());
        assertFalse(response.getUseCaches());
        assertTrue(response.getConnectTimeout() > 0);
        assertTrue(response.getReadTimeout() > 0);
        assertTrue(response.disconnected);
        assertTrue(response.closed);
    }

    @Test public void combinesEveryApiPageBeforeServingLocalSearchAndDetails() throws Exception {
        Response first = database(page(1, 201, 1234, 1, 200));
        Response second = database(page(2, 201, 1234, 201, 1));
        Factory factory = new Factory(first, second);
        NdsCatalogClient client = new NdsCatalogClient(factory);
        CatalogPage result = client.fetchLegacy(NdsCatalog.searchUrl("Demo 201"), null);
        assertEquals(1, result.entries.size());
        CatalogPage detail = client.fetchLegacy(result.entries.get(0).url, null);
        assertEquals("Demo 201", detail.title);
        assertEquals("https://db-nds-shop.fr/api/v1/download/demo201.nds", detail.downloadUrl);
        assertEquals(NdsCatalog.PAGE_SIZE, client.fetchLegacy(NdsCatalog.HOME, null).entries.size());
        assertEquals(Arrays.asList(API + "?page=1&limit=200", API + "?page=2&limit=200"), factory.opened);
        assertTrue(first.closed);
        assertTrue(second.closed);
    }

    @Test public void acceptsAnEmptyCompleteCatalog() throws Exception {
        Factory factory = new Factory(database(page(1, 0, 1234, 1, 0)));
        NdsCatalogClient client = new NdsCatalogClient(factory);
        assertTrue(client.fetchLegacy(NdsCatalog.HOME, null).entries.isEmpty());
        assertTrue(client.fetchLegacy(NdsCatalog.searchUrl("anything"), null).entries.isEmpty());
        assertEquals(1, factory.opened.size());
    }

    @Test public void acceptsGzipJsonAndSameEndpointRedirectWithUnchangedPagination() throws Exception {
        Response redirect = new Response(302).header("Location", "/api/v1/catalog?limit=200&page=1");
        Response response = database("").header("Content-Encoding", "gzip");
        response.body = gzip(DATABASE.getBytes(StandardCharsets.UTF_8));
        Factory factory = new Factory(redirect, response);
        assertEquals(1, new NdsCatalogClient(factory).fetchLegacy(NdsCatalog.HOME, "private browser agent").entries.size());
        assertEquals(2, factory.opened.size());
        assertTrue(redirect.disconnected);
        assertTrue(response.closed);
        assertNull(redirect.getRequestProperty("User-Agent"));
        assertNull(response.getRequestProperty("User-Agent"));
        assertNull(response.getRequestProperty("Cookie"));
        assertNull(response.getRequestProperty("Referer"));
    }

    @Test public void rejectsUnsafeOrChangedPaginationRedirectsBeforeContactingDestination() throws Exception {
        for (String location : Arrays.asList("https://evil.example/database", "http://db-nds-shop.fr/api/v1/catalog?page=1&limit=200",
                "https://db-nds-shop.fr.evil.example/api/v1/catalog?page=1&limit=200",
                "https://db-nds-shop.fr:444/api/v1/catalog?page=1&limit=200",
                "https://user@db-nds-shop.fr/api/v1/catalog?page=1&limit=200",
                API + "?page=2&limit=200", API + "?page=1&limit=100", API + "?page=1&limit=200&token=secret",
                API + "?page=1&limit=200&page=1", API + "?page=1&limit=200#x", API + "?page=%31&limit=200",
                "https://db-nds-shop.fr/api/v1/download/demo.nds", "https://db-nds-shop.fr/db.txt", "")) {
            Response response = new Response(302).header("Location", location);
            Factory factory = new Factory(response);
            assertThrows(IOException.class, () -> new NdsCatalogClient(factory).fetchLegacy(NdsCatalog.HOME, null));
            assertEquals(1, factory.opened.size());
            assertTrue(response.disconnected);
        }
    }

    @Test public void limitsRedirectLoops() throws Exception {
        Response[] redirects = new Response[5];
        for (int i = 0; i < redirects.length; i++) redirects[i] = new Response(302).header("Location", API + "?page=1&limit=200");
        Factory factory = new Factory(redirects);
        assertThrows(IOException.class, () -> new NdsCatalogClient(factory).fetchLegacy(NdsCatalog.HOME, null));
        assertEquals(5, factory.opened.size());
        for (Response response : redirects) assertTrue(response.disconnected);
    }

    @Test public void rejectsHtmlErrorsAndExcessiveLengthWithoutReadingBody() throws Exception {
        for (Response response : Arrays.asList(new Response(503), database("<html>").header("Content-Type", "text/html"),
                database(DATABASE).header("Content-Type", "text/plain"),
                database(DATABASE).header("Content-Length", Integer.toString(NdsCatalog.MAX_DATABASE_BYTES + 1)),
                database(DATABASE).header("Content-Encoding", "br"),
                database(DATABASE).header("Content-Encoding", "gzip, gzip"))) {
            assertThrows(IOException.class, () -> new NdsCatalogClient(new Factory(response)).fetchLegacy(NdsCatalog.HOME, null));
            assertFalse(response.requested);
            assertTrue(response.disconnected);
        }
    }

    @Test public void rejectsMalformedJsonAndLegacyTextAndRetriesWithoutFallback() throws Exception {
        for (String invalid : Arrays.asList("<!doctype html>\n<html>", "{", "{}", "1\n|\nDemo|nds|World|1|Author")) {
            Response malformed = database(invalid);
            Factory factory = new Factory(malformed, database(DATABASE));
            NdsCatalogClient client = new NdsCatalogClient(factory);
            assertThrows(IOException.class, () -> client.fetchLegacy(NdsCatalog.HOME, null));
            assertEquals(1, client.fetchLegacy(NdsCatalog.HOME, null).entries.size());
            assertEquals(Arrays.asList(API + "?page=1&limit=200", API + "?page=1&limit=200"), factory.opened);
            assertTrue(malformed.closed);
        }
    }

    @Test public void rejectsWrongPageLimitIncompletePageAndExcessivePageCount() throws Exception {
        for (String invalid : Arrays.asList(page(2, 201, 1234, 201, 1), DATABASE.replace("\"limit\":200", "\"limit\":100"),
                page(1, 201, 1234, 1, 199), page(1, NdsCatalogClient.MAX_PAGES * 200 + 1, 1234, 1, 200))) {
            Factory factory = new Factory(database(invalid));
            assertThrows(IOException.class, () -> new NdsCatalogClient(factory).fetchLegacy(NdsCatalog.HOME, null));
            assertEquals(1, factory.opened.size());
        }
    }

    @Test public void rejectsChangedRevisionTotalsRepeatedPagesAndMissingTail() throws Exception {
        for (String second : Arrays.asList(page(2, 201, 5678, 201, 1), page(2, 202, 1234, 201, 2),
                page(1, 201, 1234, 1, 200), page(2, 201, 1234, 201, 0), page(2, 201, 1234, 1, 1))) {
            Factory factory = new Factory(database(page(1, 201, 1234, 1, 200)), database(second));
            assertThrows(IOException.class, () -> new NdsCatalogClient(factory).fetchLegacy(NdsCatalog.HOME, null));
            assertEquals(2, factory.opened.size());
        }
    }

    @Test public void neverCachesPartialCatalogAndRetriesFromPageOne() throws Exception {
        Factory factory = new Factory(database(page(1, 201, 1234, 1, 200)), new Response(503),
                database(page(1, 201, 1234, 1, 200)), database(page(2, 201, 1234, 201, 1)));
        NdsCatalogClient client = new NdsCatalogClient(factory);
        assertThrows(IOException.class, () -> client.fetchLegacy(NdsCatalog.HOME, null));
        assertEquals(1, client.fetchLegacy(NdsCatalog.searchUrl("Demo 201"), null).entries.size());
        assertEquals(NdsCatalog.PAGE_SIZE, client.fetchLegacy(NdsCatalog.HOME, null).entries.size());
        assertEquals(Arrays.asList(API + "?page=1&limit=200", API + "?page=2&limit=200",
                API + "?page=1&limit=200", API + "?page=2&limit=200"), factory.opened);
    }

    @Test public void expiredSnapshotRefreshFailureDoesNotRenewStaleCache() throws Exception {
        long[] clock = {0};
        Factory factory = new Factory(database(DATABASE), new Response(503), database(page(1, 1, 5678, 2, 1)));
        NdsCatalogClient client = new NdsCatalogClient(factory, () -> clock[0]);
        assertEquals("Demo 1", client.fetchLegacy(NdsCatalog.HOME, null).entries.get(0).title);
        clock[0] = 15L * 60 * 1_000_000_000;
        assertThrows(IOException.class, () -> client.fetchLegacy(NdsCatalog.HOME, null));
        assertEquals("Demo 2", client.fetchLegacy(NdsCatalog.HOME, null).entries.get(0).title);
        assertEquals("Demo 2", client.fetchLegacy(NdsCatalog.HOME, null).entries.get(0).title);
        assertEquals(3, factory.opened.size());
    }

    @Test public void boundsUnknownLengthAndExpandedBodiesAndRejectsMalformedUtf8() throws Exception {
        byte[] excessive = new byte[NdsCatalog.MAX_DATABASE_BYTES + 1];
        Response raw = database("");
        raw.body = excessive;
        Response gzip = database("").header("Content-Encoding", "gzip");
        gzip.body = gzip(excessive);
        Response invalid = database("");
        invalid.body = new byte[] {(byte) 0xff};
        for (Response response : Arrays.asList(raw, gzip, invalid)) {
            assertThrows(IOException.class, () -> new NdsCatalogClient(new Factory(response)).fetchLegacy(NdsCatalog.HOME, null));
            assertTrue(response.closed);
            assertTrue(response.disconnected);
        }
    }

    @Test public void boundsWireBytesEvenWhenOnlyGzipHeaderIsLarge() throws Exception {
        byte[] compressed = gzip(DATABASE.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] header = Arrays.copyOf(compressed, 10);
        header[3] = 16;
        body.write(header);
        byte[] comment = new byte[NdsCatalog.MAX_DATABASE_BYTES + 1];
        Arrays.fill(comment, (byte) 'x');
        body.write(comment);
        body.write(0);
        body.write(compressed, 10, compressed.length - 10);
        Response response = database("").header("Content-Encoding", "gzip");
        response.body = body.toByteArray();
        IOException failure = assertThrows(IOException.class,
                () -> new NdsCatalogClient(new Factory(response)).fetchLegacy(NdsCatalog.HOME, null));
        assertEquals("The Nintendo DS catalog is too large.", failure.getMessage());
        assertTrue(response.closed);
        assertTrue(response.disconnected);
    }

    @Test public void boundsExpandedBytesAcrossTheWholePaginatedSnapshot() throws Exception {
        Response[] pages = new Response[5];
        for (int page = 1; page <= pages.length; page++) {
            String json = page(page, 801, 1234, (page - 1) * 200 + 1, page == 5 ? 1 : 200);
            if (page < 5) json += " ".repeat(NdsCatalog.MAX_DATABASE_BYTES - json.length());
            pages[page - 1] = database("").header("Content-Encoding", "gzip");
            pages[page - 1].body = gzip(json.getBytes(StandardCharsets.UTF_8));
        }
        Factory factory = new Factory(pages);
        IOException failure = assertThrows(IOException.class,
                () -> new NdsCatalogClient(factory).fetchLegacy(NdsCatalog.HOME, null));
        assertEquals("The Nintendo DS catalog is too large.", failure.getMessage());
        assertEquals(5, factory.opened.size());
        for (Response response : pages) {
            assertTrue(response.closed);
            assertTrue(response.disconnected);
        }
    }

    @Test public void cancellationBeforeNetworkAndDuringBodyClosesResources() throws Exception {
        Factory untouched = new Factory();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> new NdsCatalogClient(untouched).fetchLegacy(NdsCatalog.HOME, null));
        } finally { Thread.interrupted(); }
        assertTrue(untouched.opened.isEmpty());
        Response response = database(DATABASE);
        response.interruptOnRead = true;
        try {
            assertThrows(InterruptedIOException.class,
                    () -> new NdsCatalogClient(new Factory(response)).fetchLegacy(NdsCatalog.HOME, null));
        } finally { Thread.interrupted(); }
        assertTrue(response.closed);
        assertTrue(response.disconnected);
    }

    @Test public void rejectsInvalidRouteAndHeadersBeforeNetwork() {
        Factory factory = new Factory();
        NdsCatalogClient client = new NdsCatalogClient(factory);
        assertThrows(IOException.class, () -> client.fetchLegacy("https://evil.example/", null));
        assertThrows(IOException.class, () -> client.fetchLegacy(NdsCatalog.HOME, "agent\r\nCookie:secret"));
        assertTrue(factory.opened.isEmpty());
    }

    private static String page(int page, int total, int revision, int firstId, int count) {
        StringBuilder json = new StringBuilder("{\"page\":").append(page)
                .append(",\"limit\":200,\"total\":").append(total).append(",\"revision\":").append(revision)
                .append(",\"generatedAt\":\"2026-10-26T00:00:00Z\",\"entries\":[");
        for (int i = firstId; i < firstId + count; i++) {
            if (i > firstId) json.append(',');
            json.append("{\"title\":\"Demo ").append(i).append("\",\"systems\":[\"DS\"],\"downloads\":{\"demo")
                    .append(i).append(".nds\":{\"url\":\"https://db-nds-shop.fr/api/v1/download/demo")
                    .append(i).append(".nds\",\"size\":1024}}}");
        }
        return json.append("]}").toString();
    }

    private static Response database(String body) throws Exception {
        Response response = new Response(200).header("Content-Type", "application/json; charset=utf-8");
        response.body = body.getBytes(StandardCharsets.UTF_8);
        return response;
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(bytes); }
        return output.toByteArray();
    }

    private static final class Factory implements NdsCatalogClient.ConnectionFactory {
        final Deque<Response> queue = new ArrayDeque<>();
        final List<String> opened = new ArrayList<>();
        Factory(Response... responses) { queue.addAll(Arrays.asList(responses)); }
        @Override public HttpURLConnection open(URL url) {
            opened.add(url.toString());
            if (queue.isEmpty()) throw new AssertionError("Unexpected network request: " + url);
            return queue.remove();
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
        Response(int status) throws Exception { super(new URL(API + "?page=1&limit=200")); this.status = status; }
        Response header(String key, String value) { headers.put(key, value); return this; }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public InputStream getInputStream() {
            requested = true;
            return new ByteArrayInputStream(body) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    int read = super.read(bytes, offset, length);
                    if (interruptOnRead) Thread.currentThread().interrupt();
                    return read;
                }
                @Override public void close() throws IOException { closed = true; super.close(); }
            };
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
