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
public class NdsDsiDropCatalogClientTest {
    private static final String API = NdsCatalog.DEFAULT_DATABASE_URL;
    private static final String LEGACY_URL = NdsCatalog.API_BASE_URL + "?page=1&limit=200";
    private static final String LEGACY = "{\"page\":1,\"limit\":200,\"total\":1,\"revision\":123,\"entries\":["
            + "{\"title\":\"Old game\",\"systems\":[\"DS\"],\"downloads\":{\"old.nds\":{"
            + "\"url\":\"https://db-nds-shop.fr/api/v1/download/old.nds\",\"size\":1024}}}]}";

    @Test public void loadsRawGithubTextOnceAndUsesTheCacheForGroupsAndEditions() throws Exception {
        Response response = database(fixture()).header("Content-Type", "text/plain; charset=utf-8");
        Factory factory = new Factory(response);
        NdsCatalogClient client = new NdsCatalogClient(factory);
        CatalogPage games = client.fetch(NdsCatalog.searchUrl("Mario Kart"), "private browser user agent");
        assertEquals(1, games.entries.size());
        CatalogPage editions = client.fetch(games.entries.get(0).url, null);
        assertEquals(4, editions.entries.size());
        assertTrue(client.fetch(editions.entries.get(0).url, null).downloadUrl.startsWith(NdsCatalog.DOWNLOAD_BASE));
        assertEquals(Arrays.asList(API), factory.opened);
        assertEquals("GET", response.getRequestMethod());
        assertNull(response.getRequestProperty("Cookie"));
        assertNull(response.getRequestProperty("Referer"));
        assertNull(response.getRequestProperty("User-Agent"));
        assertFalse(response.getInstanceFollowRedirects());
        assertTrue(response.closed);
        assertTrue(response.disconnected);
    }

    @Test public void acceptsGzipAndOnlyTheExactMetadataEndpointRedirect() throws Exception {
        Response redirect = new Response(302).header("Location", API);
        Response response = database("").header("Content-Encoding", "gzip");
        response.body = gzip(fixture().getBytes(StandardCharsets.UTF_8));
        Factory factory = new Factory(redirect, response);
        assertEquals(5, new NdsCatalogClient(factory).fetch(NdsCatalog.HOME, null).entries.size());
        assertEquals(Arrays.asList(API, API), factory.opened);
        assertTrue(redirect.disconnected);
        assertTrue(response.closed);
    }

    @Test public void rejectsUntrustedRedirectsWithoutOpeningOrFallingBack() throws Exception {
        for (String target : new String[] {API.replace("https:", "http:"), API + "?token=x", API + "#x",
                API.replace("main/", "other/"), API.replace("0x1a4-dev", "attacker"), LEGACY_URL,
                "https://archive.org/download/" + NdsCatalog.ARCHIVE_ITEM + "/Game.zip", ""}) {
            Response redirect = new Response(302).header("Location", target);
            Factory factory = new Factory(redirect);
            assertThrows(IOException.class, () -> new NdsCatalogClient(factory).fetch(NdsCatalog.HOME, null));
            assertEquals(Arrays.asList(API), factory.opened);
            assertTrue(redirect.disconnected);
        }
    }

    @Test public void keepsLegacySavedTitlesWorkingWithoutMixingThemIntoDefaultListings() throws Exception {
        String title = legacyTitle();
        Factory factory = new Factory(database(fixture()), database(LEGACY));
        NdsCatalogClient client = new NdsCatalogClient(factory);
        assertEquals("Old game", client.fetch(title, null).title);
        assertEquals(5, client.fetch(NdsCatalog.HOME, null).entries.size());
        assertTrue(client.fetch(NdsCatalog.searchUrl("Old game"), null).entries.isEmpty());
        assertEquals("Old game", client.fetch(title, null).title);
        assertEquals(Arrays.asList(API, LEGACY_URL), factory.opened);
    }

    @Test public void freshLegacyCacheDoesNotDependOnRefreshingTheDefaultSource() throws Exception {
        long[] clock = {0};
        Factory factory = new Factory(database(fixture()), database(LEGACY));
        NdsCatalogClient client = new NdsCatalogClient(factory, () -> clock[0]);
        client.fetch(NdsCatalog.HOME, null);
        clock[0] = 14L * 60 * 1_000_000_000;
        assertEquals("Old game", client.fetch(legacyTitle(), null).title);
        clock[0] = 16L * 60 * 1_000_000_000;
        assertEquals("Old game", client.fetch(legacyTitle(), null).title);
        assertEquals(Arrays.asList(API, LEGACY_URL), factory.opened);
    }

    @Test public void firstLegacyDetailCanOpenWhenTheNewSourceIsUnavailable() throws Exception {
        Factory factory = new Factory(new Response(503), database(LEGACY));
        NdsCatalogClient client = new NdsCatalogClient(factory);
        assertEquals("Old game", client.fetch(legacyTitle(), null).title);
        assertEquals(Arrays.asList(API, LEGACY_URL), factory.opened);
    }

    @Test public void failedDefaultSnapshotIsNotCachedOrReplacedWithTheLegacyCatalog() throws Exception {
        Factory factory = new Factory(database("{}"), database(fixture()));
        NdsCatalogClient client = new NdsCatalogClient(factory);
        assertThrows(IOException.class, () -> client.fetch(NdsCatalog.HOME, null));
        assertEquals(5, client.fetch(NdsCatalog.HOME, null).entries.size());
        assertEquals(Arrays.asList(API, API), factory.opened);
    }

    @Test public void expiredCacheRefreshFailureDoesNotRenewTheOldSnapshot() throws Exception {
        long[] clock = {0};
        Factory factory = new Factory(database(fixture()), new Response(503), database(fixture()));
        NdsCatalogClient client = new NdsCatalogClient(factory, () -> clock[0]);
        client.fetch(NdsCatalog.HOME, null);
        clock[0] = 15L * 60 * 1_000_000_000;
        assertThrows(IOException.class, () -> client.fetch(NdsCatalog.HOME, null));
        assertEquals(5, client.fetch(NdsCatalog.HOME, null).entries.size());
        assertEquals(Arrays.asList(API, API, API), factory.opened);
    }

    @Test public void rejectsHtmlMalformedUtf8AndOversizedGzipWithClosedResources() throws Exception {
        Response html = database("<html>Unavailable</html>").header("Content-Type", "text/plain");
        Response invalid = database(""); invalid.body = new byte[] {(byte) 0xff};
        Response large = database("").header("Content-Encoding", "gzip");
        large.body = gzip(new byte[NdsCatalog.MAX_DATABASE_BYTES + 1]);
        for (Response response : new Response[] {html, invalid, large}) {
            Factory factory = new Factory(response);
            assertThrows(IOException.class, () -> new NdsCatalogClient(factory).fetch(NdsCatalog.HOME, null));
            assertTrue(response.closed);
            assertTrue(response.disconnected);
            assertEquals(1, factory.opened.size());
        }
    }

    @Test public void cancellationDuringReadDoesNotTriggerLegacyFallback() throws Exception {
        Response response = database(fixture()); response.interruptOnRead = true;
        Factory factory = new Factory(response);
        try {
            assertThrows(InterruptedIOException.class, () -> new NdsCatalogClient(factory).fetch(legacyTitle(), null));
        } finally { Thread.interrupted(); }
        assertEquals(Arrays.asList(API), factory.opened);
        assertTrue(response.closed);
    }

    private static String fixture() throws Exception { return NdsDsiDropCatalogTest.fixture(); }
    private static String legacyTitle() { return NdsCatalog.parseApiPage(LEGACY).catalog.page(NdsCatalog.HOME).entries.get(0).url; }

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
