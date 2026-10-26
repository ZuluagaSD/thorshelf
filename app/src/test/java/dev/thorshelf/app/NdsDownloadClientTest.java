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

public class NdsDownloadClientTest {
    private static final String RELEASE = "https://github.com/owner/game/releases/download/v1/Game.nds";
    private static final String ASSET = "https://release-assets.githubusercontent.com/github-production-release-asset/123/abc?token=signed";
    private static final String NDS_SHOP = "https://db-nds-shop.fr/api/v1/download/";
    private static final String ARCHIVE_ITEM = "pack-roms-nintendo-ds-eu-usa-jap-rabbits-games";
    private static final String ARCHIVE = "https://archive.org/download/" + ARCHIVE_ITEM + "/";
    private static final String MIRROR = "https://ia800603.us.archive.org/2/items/" + ARCHIVE_ITEM + "/";
    private static final String SECOND_MIRROR = "https://dn710805.ca.archive.org/0/items/" + ARCHIVE_ITEM + "/";

    @Test public void archiveZipInspectionFollowsMatchingFilenameAcrossMirrorsWithoutBrowserHeaders() throws Exception {
        String encoded = "Pokemon%20-%20Black%20Version%20(USA%2C%20Europe)%20(NDSi%20Enhanced).zip";
        FakeConnection canonical = new FakeConnection(302).header("Location", MIRROR + encoded);
        FakeConnection mirror = new FakeConnection(307).header("Location", SECOND_MIRROR + encoded);
        FakeConnection file = new FakeConnection(200).header("Content-Type", "application/zip")
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded)
                .header("Content-Length", "110705538");
        QueueFactory factory = new QueueFactory(canonical, mirror, file);

        CatalogClient.DownloadInfo result = new CatalogClient(factory).inspectNdsDownload(ARCHIVE + encoded, "agent");

        assertEquals(SECOND_MIRROR + encoded, result.url);
        assertEquals("Pokemon - Black Version (USA, Europe) (NDSi Enhanced).zip", result.name);
        assertEquals(110705538L, result.size);
        assertEquals("application/zip", result.mime);
        assertEquals(Arrays.asList(ARCHIVE + encoded, MIRROR + encoded, SECOND_MIRROR + encoded), factory.opened);
        for (FakeConnection response : new FakeConnection[] {canonical, mirror, file}) {
            assertEquals("HEAD", response.getRequestMethod());
            assertEquals("agent", response.getRequestProperty("User-Agent"));
            assertNull(response.getRequestProperty("Cookie"));
            assertNull(response.getRequestProperty("Referer"));
            assertNull(response.getRequestProperty("Authorization"));
            assertFalse(response.getInstanceFollowRedirects());
            assertTrue(response.disconnected);
        }
    }

    @Test public void archiveRangeFallbackRetainsFilenameAndHeaderBoundaries() throws Exception {
        FakeConnection head = new FakeConnection(405);
        FakeConnection redirect = new FakeConnection(302).header("Location", MIRROR + "Game.zip");
        FakeConnection file = new FakeConnection(206).header("Content-Type", "application/zip")
                .header("Content-Length", "1").header("Content-Range", "bytes 0-0/110705538");
        CatalogClient.DownloadInfo result = new CatalogClient(new QueueFactory(head, redirect, file))
                .inspectNdsDownload(ARCHIVE + "Game.zip", "agent");
        assertEquals(MIRROR + "Game.zip", result.url);
        assertEquals("Game.zip", result.name);
        assertEquals(110705538L, result.size);
        for (FakeConnection response : new FakeConnection[] {redirect, file}) {
            assertEquals("GET", response.getRequestMethod());
            assertEquals("bytes=0-0", response.getRequestProperty("Range"));
            assertEquals("identity", response.getRequestProperty("Accept-Encoding"));
            assertNull(response.getRequestProperty("Cookie"));
            assertNull(response.getRequestProperty("Referer"));
            assertNull(response.getRequestProperty("Authorization"));
            assertTrue(response.disconnected);
        }
    }

    @Test public void archiveRejectsChangedFilesAndOtherItemsBeforeFollowingRedirect() throws Exception {
        for (String destination : new String[] {MIRROR + "Other.zip",
                MIRROR.replace(ARCHIVE_ITEM, "another-item") + "Game.zip",
                MIRROR.replace(".archive.org/", ".archive.org.evil.example/") + "Game.zip",
                MIRROR + "Game.zip?redirect=1", RELEASE}) {
            FakeConnection redirect = new FakeConnection(302).header("Location", destination);
            QueueFactory factory = new QueueFactory(redirect);
            assertThrows(destination, IOException.class,
                    () -> new CatalogClient(factory).inspectNdsDownload(ARCHIVE + "Game.zip", "agent"));
            assertEquals(Arrays.asList(ARCHIVE + "Game.zip"), factory.opened);
            assertTrue(redirect.disconnected);
        }
    }

    @Test public void cancelledArchiveInspectionNeverOpensAConnection() {
        QueueFactory factory = new QueueFactory();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> new CatalogClient(factory).inspectNdsDownload(ARCHIVE + "Game.zip", "agent"));
            assertTrue(factory.opened.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void ndsShopHeaderOnlyInspectionDecodesItsActualExtendedFilenameHeader() throws Exception {
        String encoded = "Pokemon%20Version%20Blanche%20(France)%20(NDSi%20Enhanced).nds";
        FakeConnection response = new FakeConnection(200).header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded)
                .header("Content-Length", "268435456");
        QueueFactory factory = new QueueFactory(response);
        CatalogClient.DownloadInfo info = new CatalogClient(factory).inspectNdsDownload(NDS_SHOP + encoded, "agent");
        assertEquals(NDS_SHOP + encoded, info.url);
        assertEquals("Pokemon Version Blanche (France) (NDSi Enhanced).nds", info.name);
        assertEquals(268435456L, info.size);
        assertEquals("HEAD", response.getRequestMethod());
        assertEquals("agent", response.getRequestProperty("User-Agent"));
        assertNull(response.getRequestProperty("Cookie"));
        assertNull(response.getRequestProperty("Referer"));
        assertNull(response.getRequestProperty("Authorization"));
        assertFalse(response.getInstanceFollowRedirects());
        assertTrue(response.disconnected);
    }

    @Test public void ndsShopRangeFallbackAndRelativeRedirectKeepHeadersIsolated() throws Exception {
        String encoded = "Pok%C3%A9mon%20(France)%20%5Bb%5D.nds";
        FakeConnection head = new FakeConnection(405);
        FakeConnection redirect = new FakeConnection(307).header("Location", encoded);
        FakeConnection response = new FakeConnection(206).header("Content-Type", "application/octet-stream")
                .header("Content-Length", "1").header("Content-Range", "bytes 0-0/268435456");
        QueueFactory factory = new QueueFactory(head, redirect, response);
        CatalogClient.DownloadInfo info = new CatalogClient(factory).inspectNdsDownload(NDS_SHOP + "Game.nds", "agent");
        assertEquals(NDS_SHOP + encoded, info.url);
        assertEquals("Pokémon (France) [b].nds", info.name);
        assertEquals(268435456L, info.size);
        assertEquals(Arrays.asList(NDS_SHOP + "Game.nds", NDS_SHOP + "Game.nds", NDS_SHOP + encoded), factory.opened);
        for (FakeConnection connection : new FakeConnection[] {redirect, response}) {
            assertEquals("GET", connection.getRequestMethod());
            assertEquals("bytes=0-0", connection.getRequestProperty("Range"));
            assertEquals("identity", connection.getRequestProperty("Accept-Encoding"));
            assertNull(connection.getRequestProperty("Cookie"));
            assertNull(connection.getRequestProperty("Referer"));
            assertNull(connection.getRequestProperty("Authorization"));
            assertTrue(connection.disconnected);
        }
    }

    @Test public void ndsShopRedirectsRejectLaunchersQueriesAndOtherCatalogSessions() throws Exception {
        for (String destination : new String[] {
                "Game.cia", "Game.nds?url=https://evil.example/Game.nds", "%252e%252e%252fGame.nds",
                "/api/v1/assets/boxart/Game.nds.png", "https://hshop.erista.me/t/1",
                "https://download2.erista.me/Game.nds", "https://evil.example/Game.nds"}) {
            FakeConnection response = new FakeConnection(302).header("Location", destination);
            QueueFactory factory = new QueueFactory(response);
            assertThrows(destination, IOException.class,
                    () -> new CatalogClient(factory).inspectNdsDownload(NDS_SHOP + "Game.nds", "agent"));
            assertEquals(Arrays.asList(NDS_SHOP + "Game.nds"), factory.opened);
            assertNull(response.getRequestProperty("Cookie"));
            assertNull(response.getRequestProperty("Referer"));
            assertTrue(response.disconnected);
        }
        QueueFactory factory = new QueueFactory();
        assertThrows(IOException.class,
                () -> new CatalogClient(factory).inspectNdsDownload(NDS_SHOP + "Game.cia", "agent"));
        assertTrue(factory.opened.isEmpty());
    }

    @Test public void githubRedirectInspectsMetadataWithoutBrowserHeadersOrBody() throws Exception {
        FakeConnection redirect = new FakeConnection(302).header("Location", ASSET);
        FakeConnection file = file(200);
        QueueFactory factory = new QueueFactory(redirect, file);
        CatalogClient.DownloadInfo info = new CatalogClient(factory).inspectNdsDownload(RELEASE, "ThorShelf test");
        assertEquals(ASSET, info.url);
        assertEquals("Game.nds", info.name);
        assertEquals(1024, info.size);
        for (FakeConnection response : new FakeConnection[]{redirect, file}) {
            assertEquals("HEAD", response.getRequestMethod());
            assertEquals("ThorShelf test", response.getRequestProperty("User-Agent"));
            assertNull(response.getRequestProperty("Cookie"));
            assertNull(response.getRequestProperty("Referer"));
            assertFalse(response.getInstanceFollowRedirects());
            assertTrue(response.disconnected);
        }
    }

    @Test public void dsRangeFallbackRetainsRedirectChecksAndHeaderSeparation() throws Exception {
        FakeConnection head = new FakeConnection(405);
        FakeConnection redirect = new FakeConnection(307).header("Location", ASSET);
        FakeConnection file = file(206).header("Content-Range", "bytes 0-0/4096")
                .header("Content-Length", "1");
        QueueFactory factory = new QueueFactory(head, redirect, file);
        CatalogClient.DownloadInfo info = new CatalogClient(factory).inspectNdsDownload(RELEASE, "agent");
        assertEquals(4096, info.size);
        for (FakeConnection response : new FakeConnection[]{redirect, file}) {
            assertEquals("GET", response.getRequestMethod());
            assertEquals("bytes=0-0", response.getRequestProperty("Range"));
            assertEquals("identity", response.getRequestProperty("Accept-Encoding"));
            assertNull(response.getRequestProperty("Cookie"));
            assertNull(response.getRequestProperty("Referer"));
            assertTrue(response.disconnected);
        }
    }

    @Test public void everyRedirectIsValidatedBeforeContactingDestination() throws Exception {
        for (String destination : new String[]{"https://evil.example/Game.nds",
                "https://download2.erista.me/Game.nds", RELEASE.replace("https:", "http:"),
                RELEASE.replace("github.com", "github.com:8443"), RELEASE + "#fragment",
                RELEASE.replace("github.com", "user@github.com"),
                "https://github.com/owner/game/blob/main/Game.nds"}) {
            FakeConnection first = new FakeConnection(302).header("Location", ASSET);
            FakeConnection second = new FakeConnection(302).header("Location", destination);
            QueueFactory factory = new QueueFactory(first, second);
            assertThrows(destination, IOException.class,
                    () -> new CatalogClient(factory).inspectNdsDownload(RELEASE, null));
            assertEquals(Arrays.asList(RELEASE, ASSET), factory.opened);
            assertTrue(first.disconnected);
            assertTrue(second.disconnected);
        }
    }

    @Test public void dsFailuresNeverInvokeHshopVerification() throws Exception {
        for (FakeConnection response : new FakeConnection[]{new FakeConnection(403),
                file(200).header("cf-mitigated", "challenge"), new FakeConnection(500)}) {
            IOException failure = assertThrows(IOException.class,
                    () -> new CatalogClient(new QueueFactory(response)).inspectNdsDownload(RELEASE, null));
            assertFalse(failure instanceof CatalogClient.VerificationRequiredException);
            assertFalse(failure.getMessage().contains("hShop"));
        }
    }

    @Test public void dsMetadataRejectsNonDsPayloadsAndDocuments() throws Exception {
        for (FakeConnection response : new FakeConnection[]{file(200).header("Content-Type", "text/html"),
                file(200).header("Content-Disposition", "attachment; filename=Game.cia"),
                file(200).header("Content-Disposition", "attachment; filename=Game.7z")}) {
            assertThrows(IOException.class,
                    () -> new CatalogClient(new QueueFactory(response)).inspectNdsDownload(RELEASE, null));
            assertTrue(response.disconnected);
        }
    }

    @Test public void rejectedInitialUrlAndHeaderNeverOpenAConnection() {
        QueueFactory factory = new QueueFactory();
        CatalogClient client = new CatalogClient(factory);
        assertThrows(IOException.class, () -> client.inspectNdsDownload("https://hshop.erista.me/Game.nds", null));
        assertThrows(IOException.class, () -> client.inspectNdsDownload(RELEASE, "agent\nCookie: secret"));
        assertTrue(factory.opened.isEmpty());
    }

    @Test public void dsRedirectLoopsAreBounded() throws Exception {
        QueueFactory factory = new QueueFactory(new FakeConnection(302).header("Location", RELEASE),
                new FakeConnection(302).header("Location", RELEASE), new FakeConnection(302).header("Location", RELEASE),
                new FakeConnection(302).header("Location", RELEASE), new FakeConnection(302).header("Location", RELEASE));
        assertThrows(IOException.class, () -> new CatalogClient(factory).inspectNdsDownload(RELEASE, null));
        assertEquals(5, factory.opened.size());
    }

    private static FakeConnection file(int status) throws Exception {
        return new FakeConnection(status).header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=Game.nds")
                .header("Content-Length", "1024");
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

        FakeConnection(int status) throws Exception {
            super(new URL(RELEASE));
            this.status = status;
        }

        FakeConnection header(String name, String value) { headers.put(name, value); return this; }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public InputStream getInputStream() { throw new AssertionError("Do not download the body"); }
        @Override public InputStream getErrorStream() { throw new AssertionError("Do not download error bodies"); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
