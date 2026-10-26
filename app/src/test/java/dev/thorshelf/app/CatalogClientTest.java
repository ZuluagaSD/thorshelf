package dev.thorshelf.app;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

public class CatalogClientTest {
    private static final String SEARCH = "https://hshop.erista.me/search/results?q=Pokemon&qt=Text&lgy=false";
    private static final String TITLE = "https://hshop.erista.me/t/2476";
    private static final String DIRECT = "https://download4.erista.me/content/2476?token=a%2Bb%3D";
    private static final String HTML = "<main><div class='sitedesc'><h1>Pokémon search</h1></div>"
            + "<div class='list'><div class='elements'></div></div></main>";

    @Test public void fetchReadsUtf8HtmlWithBoundedNetworkSettings() throws Exception {
        FakeConnection response = html(HTML);
        QueueFactory factory = new QueueFactory(response);
        CatalogPage page = new CatalogClient(factory).fetch(SEARCH, "ThorShelf WebView", "session=example");

        assertEquals("Pokémon search", page.title);
        assertEquals(SEARCH, page.url);
        assertEquals("GET", response.getRequestMethod());
        assertEquals("gzip", response.getRequestProperty("Accept-Encoding"));
        assertEquals("ThorShelf WebView", response.getRequestProperty("User-Agent"));
        assertEquals("session=example", response.getRequestProperty("Cookie"));
        assertEquals(10_000, response.getConnectTimeout());
        assertEquals(15_000, response.getReadTimeout());
        assertFalse(response.getInstanceFollowRedirects());
        assertFalse(response.getUseCaches());
        assertTrue(response.streamClosed);
        assertTrue(response.disconnected);
    }

    @Test public void fetchDecodesGzipBeforeParsing() throws Exception {
        FakeConnection response = html("");
        response.body = gzip(HTML.getBytes(StandardCharsets.UTF_8));
        response.header("Content-Encoding", "gzip");
        CatalogPage page = new CatalogClient(new QueueFactory(response)).fetch(SEARCH, null, null);
        assertEquals("Pokémon search", page.title);
        assertTrue(response.streamClosed);
    }

    @Test public void fetchRejectsUnsupportedInitialAddressesBeforeOpeningConnection() {
        QueueFactory factory = new QueueFactory();
        CatalogClient client = new CatalogClient(factory);
        for (String url : Arrays.asList(DownloadPolicy.HOME, "https://other.example/search/results",
                "http://hshop.erista.me/search/results", "https://hshop.erista.me/wiki",
                "https://user:password@hshop.erista.me/t/1", DIRECT)) {
            assertThrows(IOException.class, () -> client.fetch(url, null, null));
        }
        assertTrue(factory.opened.isEmpty());
    }

    @Test public void fetchFollowsOnlyValidatedRelativeCatalogRedirects() throws Exception {
        FakeConnection redirect = redirect("/c/games/s/europe?page=2");
        FakeConnection response = html(HTML);
        QueueFactory factory = new QueueFactory(redirect, response);
        CatalogPage page = new CatalogClient(factory).fetch(SEARCH, "agent", "session=example");
        assertEquals("https://hshop.erista.me/c/games/s/europe?page=2", page.url);
        assertEquals("session=example", response.getRequestProperty("Cookie"));
        assertTrue(redirect.disconnected);
        assertEquals(2, factory.opened.size());
    }

    @Test public void fetchNeverFollowsOffHostOrNonCatalogRedirects() throws Exception {
        for (String target : Arrays.asList("https://other.example/t/1", DIRECT,
                "https://hshop.erista.me/wiki", "http://hshop.erista.me/t/1",
                "https://hshop.erista.me.evil.example/t/1", "https://hshop.erista.me:444/t/1")) {
            FakeConnection response = redirect(target);
            QueueFactory factory = new QueueFactory(response);
            assertThrows(IOException.class, () -> new CatalogClient(factory).fetch(SEARCH, null, "secret=yes"));
            assertEquals(1, factory.opened.size());
            assertTrue(response.disconnected);
        }
    }

    @Test public void fetchStopsAfterFourRedirects() throws Exception {
        QueueFactory factory = new QueueFactory(redirect(TITLE), redirect(TITLE), redirect(TITLE),
                redirect(TITLE), redirect(TITLE));
        assertThrows(IOException.class, () -> new CatalogClient(factory).fetch(SEARCH, null, null));
        assertEquals(5, factory.opened.size());
        for (FakeConnection response : factory.used) assertTrue(response.disconnected);
    }

    @Test public void fetchRejectsBinaryResponseWithoutReadingIt() throws Exception {
        FakeConnection response = html(HTML).header("Content-Type", "application/octet-stream");
        assertThrows(IOException.class, () -> new CatalogClient(new QueueFactory(response)).fetch(SEARCH, null, null));
        assertFalse(response.bodyRequested);
        assertTrue(response.disconnected);
    }

    @Test public void fetchRejectsExcessiveDeclaredLengthBeforeReading() throws Exception {
        FakeConnection response = html(HTML).header("Content-Length", "2097153");
        assertThrows(IOException.class, () -> new CatalogClient(new QueueFactory(response)).fetch(SEARCH, null, null));
        assertFalse(response.bodyRequested);
        assertTrue(response.disconnected);
    }

    @Test public void fetchCapsDecompressedHtmlDespiteSmallCompressedLength() throws Exception {
        FakeConnection response = html("");
        byte[] excessive = new byte[2 * 1024 * 1024 + 1];
        Arrays.fill(excessive, (byte) 'x');
        response.body = gzip(excessive);
        response.header("Content-Encoding", "gzip");
        response.header("Content-Length", Integer.toString(response.body.length));
        assertTrue(response.body.length < 10000);
        assertThrows(IOException.class, () -> new CatalogClient(new QueueFactory(response)).fetch(SEARCH, null, null));
        assertTrue(response.streamClosed);
        assertTrue(response.disconnected);
    }

    @Test public void fetchDoesNotRetryServerErrorsOrReadErrorBodies() throws Exception {
        FakeConnection response = new FakeConnection(503);
        QueueFactory factory = new QueueFactory(response);
        assertThrows(IOException.class, () -> new CatalogClient(factory).fetch(SEARCH, null, null));
        assertEquals(1, factory.opened.size());
        assertFalse(response.bodyRequested);
        assertTrue(response.disconnected);
    }

    @Test public void explicitChallengesRequestVisibleVerification() throws Exception {
        FakeConnection forbidden = new FakeConnection(403);
        assertThrows(CatalogClient.VerificationRequiredException.class,
                () -> new CatalogClient(new QueueFactory(forbidden)).fetch(SEARCH, null, null));
        FakeConnection cloudflare = html(HTML).header("cf-mitigated", "challenge");
        assertThrows(CatalogClient.VerificationRequiredException.class,
                () -> new CatalogClient(new QueueFactory(cloudflare)).fetch(SEARCH, null, null));
        assertFalse(forbidden.bodyRequested);
        assertFalse(cloudflare.bodyRequested);
    }

    @Test public void inspectDownloadUsesHeadAndNeverOpensContentBody() throws Exception {
        FakeConnection response = content();
        CatalogClient.DownloadInfo info = new CatalogClient(new QueueFactory(response))
                .inspectDownload(DIRECT, "agent", "session=example", TITLE);
        assertEquals(DIRECT, info.url);
        assertEquals("Pokemon Omega Ruby.cia", info.name);
        assertEquals("application/octet-stream", info.mime);
        assertEquals(123456789L, info.size);
        assertEquals("HEAD", response.getRequestMethod());
        assertEquals("agent", response.getRequestProperty("User-Agent"));
        assertEquals("session=example", response.getRequestProperty("Cookie"));
        assertEquals(TITLE, response.getRequestProperty("Referer"));
        assertFalse(response.bodyRequested);
        assertTrue(response.disconnected);
    }

    @Test public void inspectRedirectNeverLeaksOriginalHostCookie() throws Exception {
        FakeConnection first = redirect("https://download2.erista.me/content/2476?token=next%2Btoken");
        FakeConnection second = content();
        CatalogClient.DownloadInfo info = new CatalogClient(new QueueFactory(first, second))
                .inspectDownload(DIRECT, "agent", "session=secret", TITLE);
        assertEquals("https://download2.erista.me/content/2476?token=next%2Btoken", info.url);
        assertEquals("session=secret", first.getRequestProperty("Cookie"));
        assertNull(second.getRequestProperty("Cookie"));
        assertEquals("HEAD", first.getRequestMethod());
        assertEquals("HEAD", second.getRequestMethod());
        assertEquals("agent", second.getRequestProperty("User-Agent"));
        assertEquals(TITLE, second.getRequestProperty("Referer"));
        assertFalse(first.bodyRequested);
        assertFalse(second.bodyRequested);
    }

    @Test public void inspectSameHostRedirectKeepsCookieButRejectsUntrustedReferer() throws Exception {
        FakeConnection first = redirect("/content/2476?token=fresh");
        FakeConnection second = content();
        new CatalogClient(new QueueFactory(first, second))
                .inspectDownload(DIRECT, null, "session=example", "https://untrusted.example/");
        assertEquals("session=example", second.getRequestProperty("Cookie"));
        assertNull(first.getRequestProperty("Referer"));
        assertNull(second.getRequestProperty("Referer"));
    }

    @Test public void inspectRejectsUnsafeRedirectsBeforeContactingDestination() throws Exception {
        for (String target : Arrays.asList("http://download4.erista.me/content/2476",
                "https://download4.erista.me.evil.example/content/2476", "https://example.com/title.cia",
                "https://user@download4.erista.me/content/2476")) {
            FakeConnection response = redirect(target);
            QueueFactory factory = new QueueFactory(response);
            assertThrows(IOException.class, () -> new CatalogClient(factory).inspectDownload(DIRECT, null, null, TITLE));
            assertEquals(1, factory.opened.size());
            assertFalse(response.bodyRequested);
            assertTrue(response.disconnected);
        }
    }

    @Test public void inspectStopsRedirectLoopsAndNeverFallsBackToGet() throws Exception {
        QueueFactory factory = new QueueFactory(redirect(DIRECT), redirect(DIRECT), redirect(DIRECT),
                redirect(DIRECT), redirect(DIRECT));
        assertThrows(IOException.class, () -> new CatalogClient(factory).inspectDownload(DIRECT, null, null, TITLE));
        assertEquals(5, factory.opened.size());
        for (FakeConnection response : factory.used) {
            assertEquals("HEAD", response.getRequestMethod());
            assertFalse(response.bodyRequested);
            assertTrue(response.disconnected);
        }
    }

    @Test public void inspectRejectsHtmlAndUnsupportedFilenamesWithoutReadingBodies() throws Exception {
        for (FakeConnection response : Arrays.asList(
                content().header("Content-Type", "text/html; charset=UTF-8"),
                content().header("Content-Disposition", "attachment; filename=installer.apk"),
                content().header("Content-Disposition", null))) {
            assertThrows(IOException.class, () -> new CatalogClient(new QueueFactory(response))
                    .inspectDownload(DIRECT, null, null, TITLE));
            assertFalse(response.bodyRequested);
        }
    }

    @Test public void inspectDoesNotDownloadForbiddenOrServerErrorResponses() throws Exception {
        FakeConnection forbidden = new FakeConnection(403);
        assertThrows(CatalogClient.VerificationRequiredException.class,
                () -> new CatalogClient(new QueueFactory(forbidden)).inspectDownload(DIRECT, null, null, TITLE));
        FakeConnection unsupported = new FakeConnection(500);
        assertThrows(IOException.class,
                () -> new CatalogClient(new QueueFactory(unsupported)).inspectDownload(DIRECT, null, null, TITLE));
        assertFalse(forbidden.bodyRequested);
        assertFalse(unsupported.bodyRequested);
    }

    @Test public void headUnsupportedFallsBackOnceToMinimalRangeAndReadsOnlyHeaders() throws Exception {
        for (int headStatus : new int[] {405, 501}) {
            FakeConnection head = new FakeConnection(headStatus);
            FakeConnection range = content(206).header("Content-Range", "bytes 0-0/123456789")
                    .header("Content-Length", "1");
            QueueFactory factory = new QueueFactory(head, range);
            CatalogClient.DownloadInfo info = new CatalogClient(factory)
                    .inspectDownload(DIRECT, "agent", "session=example", TITLE);
            assertEquals(123456789L, info.size);
            assertEquals("HEAD", head.getRequestMethod());
            assertEquals("GET", range.getRequestMethod());
            assertEquals("bytes=0-0", range.getRequestProperty("Range"));
            assertEquals("identity", range.getRequestProperty("Accept-Encoding"));
            assertEquals("session=example", range.getRequestProperty("Cookie"));
            assertEquals(TITLE, range.getRequestProperty("Referer"));
            assertEquals(2, factory.opened.size());
            assertFalse(head.bodyRequested);
            assertFalse(range.bodyRequested);
            assertTrue(head.disconnected);
            assertTrue(range.disconnected);
        }
    }

    @Test public void ignoredRangeUsesFullLengthWithoutReadingTheResponse() throws Exception {
        FakeConnection response = content();
        CatalogClient.DownloadInfo info = new CatalogClient(new QueueFactory(new FakeConnection(405), response))
                .inspectDownload(DIRECT, null, null, TITLE);
        assertEquals(123456789L, info.size);
        assertEquals("GET", response.getRequestMethod());
        assertFalse(response.bodyRequested);
        assertTrue(response.disconnected);
    }

    @Test public void rangeRedirectsKeepMinimalRequestAndNeverLeakCookies() throws Exception {
        FakeConnection redirect = redirect("https://download2.erista.me/content/2476?token=next");
        FakeConnection range = content(206).header("Content-Range", "bytes 0-0/500");
        QueueFactory factory = new QueueFactory(new FakeConnection(405), redirect, range);
        CatalogClient.DownloadInfo info = new CatalogClient(factory)
                .inspectDownload(DIRECT, "agent", "secret=yes", TITLE);
        assertEquals(500L, info.size);
        assertEquals("GET", redirect.getRequestMethod());
        assertEquals("GET", range.getRequestMethod());
        assertEquals("bytes=0-0", range.getRequestProperty("Range"));
        assertEquals("identity", range.getRequestProperty("Accept-Encoding"));
        assertNull(range.getRequestProperty("Cookie"));
        assertFalse(redirect.bodyRequested);
        assertFalse(range.bodyRequested);

        QueueFactory forbidden = new QueueFactory(new FakeConnection(405), redirect("https://evil.example/game.cia"));
        assertThrows(IOException.class, () -> new CatalogClient(forbidden).inspectDownload(DIRECT, null, null, TITLE));
        assertEquals(2, forbidden.opened.size());
    }

    @Test public void malformedPartialContentRangesAreRejectedWithoutReadingBody() throws Exception {
        for (String header : Arrays.asList(null, "", "bytes 0-0/*", "bytes 1-1/500",
                "bytes 0-499/500", "bytes 0-0/0", "bytes 0-0/9999999999999999999999")) {
            FakeConnection range = content(206).header("Content-Range", header);
            QueueFactory factory = new QueueFactory(new FakeConnection(405), range);
            assertThrows(IOException.class, () -> new CatalogClient(factory).inspectDownload(DIRECT, null, null, TITLE));
            assertFalse(range.bodyRequested);
            assertTrue(range.disconnected);
        }
    }

    @Test public void unsupportedRangeDoesNotRetryOrReadTheBody() throws Exception {
        FakeConnection range = new FakeConnection(405);
        QueueFactory factory = new QueueFactory(new FakeConnection(405), range);
        assertThrows(IOException.class, () -> new CatalogClient(factory).inspectDownload(DIRECT, null, null, TITLE));
        assertEquals(2, factory.opened.size());
        assertEquals("GET", range.getRequestMethod());
        assertFalse(range.bodyRequested);
    }

    @Test public void rangeFallbackStillLimitsRedirectsToFour() throws Exception {
        QueueFactory factory = new QueueFactory(new FakeConnection(405), redirect(DIRECT), redirect(DIRECT),
                redirect(DIRECT), redirect(DIRECT), redirect(DIRECT));
        assertThrows(IOException.class, () -> new CatalogClient(factory).inspectDownload(DIRECT, null, null, TITLE));
        assertEquals(6, factory.opened.size());
        for (FakeConnection response : factory.used) {
            assertFalse(response.bodyRequested);
            assertTrue(response.disconnected);
        }
    }

    @Test public void invalidSessionHeadersAreRejectedBeforeOpeningConnection() {
        QueueFactory factory = new QueueFactory();
        CatalogClient client = new CatalogClient(factory);
        assertThrows(IOException.class, () -> client.fetch(SEARCH, "agent\r\nCookie: leaked", null));
        assertThrows(IOException.class, () -> client.inspectDownload(DIRECT, null, "cookie\nInjected: yes", TITLE));
        assertTrue(factory.opened.isEmpty());
    }

    private static FakeConnection html(String body) throws Exception {
        FakeConnection response = new FakeConnection(200).header("Content-Type", "text/html; charset=UTF-8");
        response.body = body.getBytes(StandardCharsets.UTF_8);
        return response;
    }

    private static FakeConnection content() throws Exception {
        return content(200);
    }

    private static FakeConnection content(int status) throws Exception {
        return new FakeConnection(status)
                .header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"Pokemon Omega Ruby.cia\"")
                .header("Content-Length", "123456789");
    }

    private static FakeConnection redirect(String location) throws Exception {
        return new FakeConnection(302).header("Location", location);
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(compressed)) {
            output.write(body);
        }
        return compressed.toByteArray();
    }

    private static final class QueueFactory implements CatalogClient.ConnectionFactory {
        final Deque<FakeConnection> queued = new ArrayDeque<>();
        final List<String> opened = new ArrayList<>();
        final List<FakeConnection> used = new ArrayList<>();

        QueueFactory(FakeConnection... responses) { queued.addAll(Arrays.asList(responses)); }

        @Override public HttpURLConnection open(URL url) {
            opened.add(url.toString());
            if (queued.isEmpty()) throw new AssertionError("Unexpected connection: " + url);
            FakeConnection response = queued.remove();
            used.add(response);
            return response;
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final int status;
        byte[] body = new byte[0];
        boolean disconnected;
        boolean bodyRequested;
        boolean streamClosed;

        FakeConnection(int status) throws Exception {
            super(new URL(SEARCH));
            this.status = status;
        }

        FakeConnection header(String name, String value) {
            if (value == null) headers.remove(name);
            else headers.put(name, value);
            return this;
        }

        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public InputStream getInputStream() {
            bodyRequested = true;
            return new ByteArrayInputStream(body) {
                @Override public void close() throws IOException {
                    streamClosed = true;
                    super.close();
                }
            };
        }
        @Override public InputStream getErrorStream() {
            throw new AssertionError("Error responses must not be downloaded");
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
