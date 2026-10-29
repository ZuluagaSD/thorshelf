package dev.thorshelf.app;

import org.junit.Test;
import java.io.IOException;
import java.io.InterruptedIOException;

import static org.junit.Assert.*;

public class GbaCatalogClientTest {
    @Test public void loadsBundledMetadataWithoutNetworkAndUnknownDetailsFailClearly() throws Exception {
        GbaCatalogClient client = new GbaCatalogClient();
        assertFalse(client.fetch(GbaCatalog.HOME, "ThorShelf test").entries.isEmpty());
        assertThrows(IOException.class, () -> client.fetch(GbaCatalog.HOME + "title/" + "0".repeat(40), null));
        assertThrows(IOException.class, () -> client.fetch("https://archive.org/", null));
        assertThrows(IOException.class, () -> client.fetch(GbaCatalog.HOME, "bad\r\nAgent"));
    }

    @Test public void cancellationDoesNotProduceCatalogResults() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> new GbaCatalogClient().fetch(GbaCatalog.HOME, null));
        } finally { Thread.interrupted(); }
    }

    @Test public void fixtureClientResolvesTheChosenEditionOnly() throws Exception {
        GbaCatalog catalog = GbaCatalog.parse(GbaCatalogTest.document(
                GbaCatalogTest.row(1, "Tiny (EU).gba", "Tiny (Europe) (En)", "Tiny", "Europe", "en")));
        GbaCatalogClient client = new GbaCatalogClient(catalog);
        CatalogPage listing = client.fetch(GbaCatalog.HOME, null);
        CatalogPage detail = client.fetch(listing.entries.get(0).url, null);
        assertEquals("Tiny (Europe) (En)", detail.title);
        assertEquals(GbaCatalog.DOWNLOAD_BASE + "Tiny%20%28EU%29.gba", detail.downloadUrl);
    }
}
