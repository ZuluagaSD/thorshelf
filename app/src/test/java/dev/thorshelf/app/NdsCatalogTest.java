package dev.thorshelf.app;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class NdsCatalogTest {
    private static final String DOWNLOAD = "https://github.com/example/game/releases/download/v1/demo.nds";

    @Test public void readsVersionOneDatabaseAndProducesDownloadDetail() {
        NdsCatalog catalog = NdsCatalog.parse("\uFEFF1\r\n\t\r\n" + row("Pokémon demo", "demo.nds", DOWNLOAD) + "\r\n");
        CatalogPage page = catalog.page(NdsCatalog.HOME);
        assertEquals("Nintendo DS homebrew", page.title);
        assertEquals(1, page.entries.size());
        assertTrue(page.summary.contains("Universal-DB / Kekatsu DS"));
        CatalogPage.Entry entry = page.entries.get(0);
        assertEquals("Pokémon demo", entry.title);
        assertEquals("Nintendo DS", entry.category);
        assertEquals("All regions", entry.region);
        assertEquals("2.0 MiB", entry.size);
        assertTrue(NdsCatalog.isDetailUrl(entry.url));
        CatalogPage detail = catalog.page(entry.url);
        assertEquals(DOWNLOAD, detail.downloadUrl);
        assertEquals(entry.title, detail.title);
        assertTrue(detail.summary.contains("By Example author"));
        assertTrue(detail.summary.contains("Version 1.0"));
        assertFalse(detail.verificationRequired);
    }

    @Test public void acceptsCustomDelimiterAndEmptyOptionalFields() {
        NdsCatalog catalog = NdsCatalog.parse("1\n|\nDemo|nds||||" + DOWNLOAD + "|demo.nds|0|\n");
        CatalogPage.Entry entry = catalog.page(NdsCatalog.HOME).entries.get(0);
        assertEquals("", entry.region);
        assertEquals("", entry.size);
        assertEquals("", entry.titleId);
    }

    @Test public void zipMappingAndArtworkNeverBecomeNavigationOrDownloads() {
        String zip = "https://db.universal-team.net/assets/files/demo.zip";
        NdsCatalog catalog = NdsCatalog.parse("1\n\t\n" + row("Zip game", "demo.zip", zip)
                + "\t../../escape.nds\t/absolute/path.nds\n");
        CatalogPage.Entry entry = catalog.page(NdsCatalog.HOME).entries.get(0);
        CatalogPage detail = catalog.page(entry.url);
        assertEquals(zip, detail.downloadUrl);
        assertEquals("Nintendo DS ZIP", entry.contentType);
        assertTrue(detail.summary.contains("one .nds file"));
        assertFalse(detail.summary.contains("escape"));
    }

    @Test public void excludesUnsupportedPlatformsFormatsHostsAndMalformedRecords() {
        StringBuilder text = new StringBuilder("1\n\t\n");
        text.append("too\tfew\tfields\n");
        text.append(row("Odd pairs", "demo.nds", DOWNLOAD)).append("\tmissingPair\n");
        text.append(row("Other platform", "demo.nds", DOWNLOAD).replace("\tnds\t", "\tgba\t")).append('\n');
        for (String name : Arrays.asList("demo.dsi", "demo.7z", "demo.cia", "demo.apk", "../demo.nds", "a\\demo.nds")) {
            text.append(row("Unsupported", name, DOWNLOAD)).append('\n');
        }
        for (String url : Arrays.asList("https://evil.example/demo.nds", "http://github.com/example/game/releases/download/v1/demo.nds",
                "https://github.com/example/game/raw/main/demo.nds")) {
            text.append(row("Unsupported", "demo.nds", url)).append('\n');
        }
        text.append(row("Good", "demo.nds", DOWNLOAD)).append('\n');
        CatalogPage page = NdsCatalog.parse(text.toString()).page(NdsCatalog.HOME);
        assertEquals(1, page.entries.size());
        assertEquals("Good", page.entries.get(0).title);
    }

    @Test public void ignoresInvalidSizesAndKeepsUnknownSizes() {
        StringBuilder text = new StringBuilder("1\n\t\n");
        for (String size : Arrays.asList("-1", "1.5", "9223372036854775808", "unknown")) {
            text.append(row("Bad", "demo.nds", DOWNLOAD).replace("\t2097152\t", "\t" + size + "\t")).append('\n');
        }
        text.append(row("Unknown", "demo.nds", DOWNLOAD).replace("\t2097152\t", "\t\t"));
        CatalogPage page = NdsCatalog.parse(text.toString()).page(NdsCatalog.HOME);
        assertEquals(1, page.entries.size());
        assertEquals("", page.entries.get(0).size);
    }

    @Test public void stableIdsSurviveSortingAndDuplicateRows() {
        String first = row("First", "demo.nds", DOWNLOAD);
        String second = row("Second", "second.nds", DOWNLOAD.replace("demo.nds", "second.nds"));
        NdsCatalog original = NdsCatalog.parse("1\n\t\n" + first + "\n" + second + "\n" + first);
        NdsCatalog reordered = NdsCatalog.parse("1\n\t\n" + second + "\n" + first);
        assertEquals(2, original.page(NdsCatalog.HOME).entries.size());
        String firstUrl = original.page(NdsCatalog.HOME).entries.get(0).url;
        assertEquals("First", reordered.page(firstUrl).title);
    }

    @Test public void searchIgnoresCaseAndAccentsAndPaginatesWithinMatches() {
        StringBuilder text = new StringBuilder("1\n\t\n");
        for (int i = 0; i < 85; i++) text.append(row("Pokémon " + i, "game" + i + ".nds",
                DOWNLOAD.replace("demo.nds", "game" + i + ".nds"))).append('\n');
        NdsCatalog catalog = NdsCatalog.parse(text.toString());
        CatalogPage first = catalog.page(NdsCatalog.searchUrl("POKEMON"));
        assertEquals(40, first.entries.size());
        assertEquals("", first.previousUrl);
        CatalogPage second = catalog.page(first.nextUrl);
        assertEquals("Pokémon 40", second.entries.get(0).title);
        assertEquals(first.url, second.previousUrl);
        CatalogPage third = catalog.page(second.nextUrl);
        assertEquals(5, third.entries.size());
        assertEquals("", third.nextUrl);
        assertEquals(third.url, catalog.page(NdsCatalog.HOME + "?q=POKEMON&page=999999").url);
        assertTrue(catalog.page(NdsCatalog.searchUrl("missing")).entries.isEmpty());
    }

    @Test public void validatesLocalRoutesAndBoundsSearch() {
        assertEquals(NdsCatalog.HOME, NdsCatalog.searchUrl(null));
        assertTrue(NdsCatalog.isPageUrl(NdsCatalog.searchUrl("x".repeat(500))));
        assertEquals(NdsCatalog.HOME + "?q=demo+game", NdsCatalog.searchUrl("  demo\ngame  "));
        assertTrue(NdsCatalog.isPageUrl(NdsCatalog.HOME + "?q=caf%C3%A9&page=2"));
        for (String url : Arrays.asList(null, "https://catalog/", "nds://evil/", "nds://user@catalog/",
                "nds://catalog:443/", "nds://catalog/other", "nds://catalog/title/abc", "nds://catalog/%2e%2e/",
                NdsCatalog.HOME + "?q=hi&q=bye", NdsCatalog.HOME + "?page=0", NdsCatalog.HOME + "?page=-1",
                NdsCatalog.HOME + "?page=9999999", NdsCatalog.HOME + "?unknown=yes", NdsCatalog.HOME + "#fragment",
                NdsCatalog.HOME + "?q=%0A%0A", NdsCatalog.HOME + "?q=" + "x".repeat(257))) {
            assertFalse(String.valueOf(url), NdsCatalog.isPageUrl(url));
        }
    }

    @Test public void rejectsHtmlVersionsOversizedLinesAndDocuments() {
        for (String text : Arrays.asList(null, "", "<!doctype html>\n<html>", "2\n\t\n", "1\n\n", "1\n||\n",
                "1\n\t\n" + "x".repeat(8193), "x".repeat(NdsCatalog.MAX_DATABASE_BYTES + 1),
                "1\n\t\n" + "\n".repeat(50001))) {
            assertThrows(IllegalArgumentException.class, () -> NdsCatalog.parse(text));
        }
    }

    @Test public void missingDetailIsAnErrorAndListsCannotBeMutated() {
        NdsCatalog catalog = NdsCatalog.parse("1\n\t\n" + row("Demo", "demo.nds", DOWNLOAD));
        assertThrows(IllegalArgumentException.class, () -> catalog.page(NdsCatalog.HOME + "title/000000000000000000000000"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.page(NdsCatalog.HOME).entries.clear());
    }

    static String row(String title, String fileName, String url) {
        return title + "\tnds\tANY\t1.0\tExample author\t" + url + "\t" + fileName
                + "\t2097152\thttps://untrusted.example/image.png";
    }
}
