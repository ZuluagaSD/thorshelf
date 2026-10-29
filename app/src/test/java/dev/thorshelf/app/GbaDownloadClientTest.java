package dev.thorshelf.app;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

public class GbaDownloadClientTest {
    private static final String ITEM = "CentralArquivista-GameBoyAdvance";
    private static final String ARCHIVE = "https://archive.org/download/" + ITEM + "/";
    private static final String MIRROR = "https://ia800603.us.archive.org/2/items/" + ITEM + "/";
    private static final String SECOND_MIRROR = "https://dn710805.ca.archive.org/0/items/" + ITEM + "/";

    @Test public void inspectsRawRomAcrossMirrorsWithoutBrowserCredentialsOrReadingABody() throws Exception {
        String encoded = "Game%20(USA%2C%20Europe).gba";
        FakeConnection canonical = new FakeConnection(302).header("Location", MIRROR + encoded);
        FakeConnection mirror = new FakeConnection(307).header("Location", SECOND_MIRROR + encoded);
        FakeConnection file = file(200).header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        QueueFactory factory = new QueueFactory(canonical, mirror, file);

        CatalogClient.DownloadInfo result = new CatalogClient(factory).inspectGbaDownload(ARCHIVE + encoded, "agent");

        assertEquals(SECOND_MIRROR + encoded, result.url);
        assertEquals("Game (USA, Europe).gba", result.name);
        assertEquals(8388608L, result.size);
        assertEquals("application/octet-stream", result.mime);
        assertEquals(Arrays.asList(ARCHIVE + encoded, MIRROR + encoded, SECOND_MIRROR + encoded), factory.opened);
        for (FakeConnection response : new FakeConnection[] {canonical, mirror, file}) {
            assertEquals("HEAD", response.getRequestMethod());
            assertEquals("agent", response.getRequestProperty("User-Agent"));
            assertIsolated(response);
        }
    }

    @Test public void rangeFallbackKeepsSourceChecksAndUsesFullFileSize() throws Exception {
        FakeConnection head = new FakeConnection(405);
        FakeConnection redirect = new FakeConnection(302).header("Location", MIRROR + "Game.gba");
        FakeConnection file = file(206).header("Content-Length", "1").header("Content-Range", "bytes 0-0/8388608");
        QueueFactory factory = new QueueFactory(head, redirect, file);

        CatalogClient.DownloadInfo result = new CatalogClient(factory).inspectGbaDownload(ARCHIVE + "Game.gba", "agent");

        assertEquals(8388608L, result.size);
        assertEquals(MIRROR + "Game.gba", result.url);
        for (FakeConnection response : new FakeConnection[] {redirect, file}) {
            assertEquals("GET", response.getRequestMethod());
            assertEquals("bytes=0-0", response.getRequestProperty("Range"));
            assertIsolated(response);
        }
        assertTrue(head.disconnected);
    }

    @Test public void redirectsCannotSwitchEditionItemPlatformOrCredentials() throws Exception {
        for (String destination : new String[] {MIRROR + "Other.gba",
                MIRROR.replace(ITEM, "another-item") + "Game.gba",
                MIRROR.replace(ITEM, "pack-roms-nintendo-ds-eu-usa-jap-rabbits-games") + "Game.gba",
                MIRROR + "Game.nds", MIRROR + "Game.gba?download=1",
                MIRROR.replace(".archive.org/", ".archive.org.evil.example/") + "Game.gba",
                MIRROR.replace("https://", "https://user@") + "Game.gba",
                "../" + ITEM + "/Game.gba", "./Game.gba", "..%2f" + ITEM + "%2fGame.gba",
                "https://hshop.erista.me/Game.gba", "https://download2.erista.me/Game.gba",
                "https://github.com/owner/game/releases/download/v1/Game.gba"}) {
            FakeConnection response = new FakeConnection(302).header("Location", destination);
            QueueFactory factory = new QueueFactory(response);
            assertThrows(destination, IOException.class,
                    () -> new CatalogClient(factory).inspectGbaDownload(ARCHIVE + "Game.gba", "agent"));
            assertEquals(Arrays.asList(ARCHIVE + "Game.gba"), factory.opened);
            assertIsolated(response);
        }
    }

    @Test public void fallbackDoesNotRelaxRedirectPolicy() throws Exception {
        FakeConnection head = new FakeConnection(501);
        FakeConnection redirect = new FakeConnection(302).header("Location", MIRROR + "Other.gba");
        QueueFactory factory = new QueueFactory(head, redirect);
        assertThrows(IOException.class,
                () -> new CatalogClient(factory).inspectGbaDownload(ARCHIVE + "Game.gba", null));
        assertEquals(Arrays.asList(ARCHIVE + "Game.gba", ARCHIVE + "Game.gba"), factory.opened);
    }

    @Test public void relativeRedirectMayRetainTheSameDecodedFilename() throws Exception {
        FakeConnection redirect = new FakeConnection(302).header("Location", "Game.gba");
        QueueFactory factory = new QueueFactory(redirect, file(200));
        assertEquals(ARCHIVE + "Game.gba", new CatalogClient(factory)
                .inspectGbaDownload(ARCHIVE + "Game.gba", null).url);
    }

    @Test public void wrongContentAndSizeMetadataFailWithoutHshopVerification() throws Exception {
        for (FakeConnection response : new FakeConnection[] {new FakeConnection(403), new FakeConnection(500),
                new FakeConnection(204), file(200).header("cf-mitigated", "challenge"),
                file(200).header("Content-Type", "text/html"), file(200).header("Content-Type", "application/json"),
                file(200).header("Content-Disposition", "attachment; filename=Other.gba"),
                file(200).header("Content-Disposition", "attachment; filename=Game.nds"),
                file(200).header("Content-Disposition", "attachment; filename=Game.zip"),
                file(200).header("Content-Length", "0"), file(200).header("Content-Length", "not-a-number"),
                file(200).header("Content-Length", "-3"), file(200).header("Content-Encoding", "gzip"),
                file(206).header("Content-Range", "bytes 1-1/8388608"),
                file(206).header("Content-Range", "bytes 0-0/*")}) {
            IOException failure = assertThrows(IOException.class,
                    () -> new CatalogClient(new QueueFactory(response)).inspectGbaDownload(ARCHIVE + "Game.gba", null));
            assertFalse(failure instanceof CatalogClient.VerificationRequiredException);
            assertFalse(failure.getMessage().contains("hShop"));
            assertTrue(response.disconnected);
        }
    }

    @Test public void absentSizeRemainsUnknownForCatalogToResolve() throws Exception {
        FakeConnection response = new FakeConnection(200).header("Content-Type", "application/x-gba-rom");
        CatalogClient.DownloadInfo result = new CatalogClient(new QueueFactory(response))
                .inspectGbaDownload(ARCHIVE + "Game.gba", null);
        assertEquals(-1, result.size);
        assertEquals("Game.gba", result.name);
    }

    @Test public void invalidSourceOrInjectedHeaderNeverOpensAConnection() {
        QueueFactory factory = new QueueFactory();
        CatalogClient client = new CatalogClient(factory);
        assertThrows(IOException.class, () -> client.inspectGbaDownload("https://hshop.erista.me/Game.gba", null));
        assertThrows(IOException.class, () -> client.inspectGbaDownload(ARCHIVE + "Game.zip", null));
        assertThrows(IOException.class, () -> client.inspectGbaDownload(ARCHIVE + "Game.gba", "agent\nCookie: secret"));
        assertTrue(factory.opened.isEmpty());
    }

    @Test public void cancelledInspectionNeverOpensAConnection() {
        QueueFactory factory = new QueueFactory();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> new CatalogClient(factory).inspectGbaDownload(ARCHIVE + "Game.gba", null));
            assertTrue(factory.opened.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void redirectLoopsRemainBounded() throws Exception {
        FakeConnection[] responses = new FakeConnection[5];
        for (int i = 0; i < responses.length; i++) responses[i] = new FakeConnection(302).header("Location", "Game.gba");
        QueueFactory factory = new QueueFactory(responses);
        assertThrows(IOException.class,
                () -> new CatalogClient(factory).inspectGbaDownload(ARCHIVE + "Game.gba", null));
        assertEquals(5, factory.opened.size());
        for (FakeConnection response : responses) assertTrue(response.disconnected);
    }

    private static void assertIsolated(FakeConnection response) {
        assertNull(response.getRequestProperty("Cookie"));
        assertNull(response.getRequestProperty("Referer"));
        assertNull(response.getRequestProperty("Authorization"));
        assertEquals("identity", response.getRequestProperty("Accept-Encoding"));
        assertFalse(response.getInstanceFollowRedirects());
        assertTrue(response.disconnected);
    }

    private static FakeConnection file(int status) throws Exception {
        return new FakeConnection(status).header("Content-Type", "application/octet-stream")
                .header("Content-Length", "8388608");
    }

    private static final class QueueFactory implements CatalogClient.ConnectionFactory {
        final Deque<FakeConnection> responses = new ArrayDeque<>();
        final List<String> opened = new ArrayList<>();

        QueueFactory(FakeConnection... responses) { this.responses.addAll(Arrays.asList(responses)); }

        @Override public HttpURLConnection open(URL url) {
            opened.add(url.toString());
            if (responses.isEmpty()) throw new AssertionError("Unexpected connection: " + url);
            return responses.remove();
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final int status;
        boolean disconnected;

        FakeConnection(int status) throws Exception { super(new URL(ARCHIVE + "Game.gba")); this.status = status; }
        FakeConnection header(String name, String value) { headers.put(name, value); return this; }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public InputStream getInputStream() { throw new AssertionError("Do not download ROM bodies"); }
        @Override public InputStream getErrorStream() { throw new AssertionError("Do not read error bodies"); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
