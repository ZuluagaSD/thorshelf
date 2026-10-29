package dev.thorshelf.app;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class GbaCatalogTest {
    @Test public void bundledCatalogExposesEveryMatchedEditionAndExactDownloadIdentity() throws Exception {
        GbaCatalog catalog = GbaCatalog.bundled();
        Set<String> editions = new HashSet<>();
        String next = GbaCatalog.HOME;
        do {
            CatalogPage page = catalog.page(next);
            for (CatalogPage.Entry game : page.entries) {
                if (GbaCatalog.isEditionSelectionUrl(game.url)) {
                    String more = game.url;
                    do {
                        CatalogPage variants = catalog.page(more);
                        for (CatalogPage.Entry edition : variants.entries) checkEdition(catalog, edition, editions);
                        more = variants.nextUrl;
                    } while (!more.isEmpty());
                } else checkEdition(catalog, game, editions);
            }
            next = page.nextUrl;
        } while (!next.isEmpty());
        assertEquals(3054, editions.size());
        assertNull(GbaCatalog.lookup(GbaCatalog.HOME));
        assertNull(GbaCatalog.lookup(GbaCatalog.HOME + "title/" + "0".repeat(40)));
    }

    private static void checkEdition(GbaCatalog catalog, CatalogPage.Entry edition, Set<String> editions) throws Exception {
        assertTrue(GbaCatalog.isDetailUrl(edition.url));
        assertTrue("Each edition appears exactly once", editions.add(edition.url));
        GbaCatalog.Rom rom = GbaCatalog.lookup(edition.url);
        assertNotNull(rom);
        assertEquals(GbaCatalog.HOME + "title/" + rom.sha1, edition.url);
        assertTrue(rom.sizeBytes >= 192 && rom.sizeBytes <= 32L * 1024 * 1024);
        assertEquals("/download/CentralArquivista-GameBoyAdvance/" + rom.filename, URI.create(rom.downloadUrl).getPath());
        assertEquals(rom.downloadUrl, catalog.page(edition.url).downloadUrl);
    }

    @Test public void canonicalTitlesAndSourceFilenamesRemainSeparateAndUnknownLanguageStaysUnknown() throws Exception {
        GbaCatalog.Rom emerald = GbaCatalog.lookup(GbaCatalog.HOME + "title/f3ae088181bf583e55daf962a92bb46f4f1d07b7");
        assertNotNull(emerald);
        assertEquals("Pokemon - Emerald Version (USA, Europe)", emerald.title);
        assertTrue(emerald.filename.contains("US - EU"));
        assertEquals(16777216L, emerald.sizeBytes);
        assertTrue(emerald.entry.languages.isEmpty());
        CatalogPage accented = GbaCatalog.bundled().page(GbaCatalog.searchUrl("Pokémon Emerald"));
        assertFalse(accented.entries.isEmpty());
        CatalogPage english = GbaCatalog.bundled().page(GbaCatalog.withLanguage(GbaCatalog.searchUrl("Pokemon Emerald"), "en"));
        assertTrue("No filename language tag means unknown, including USA", english.entries.isEmpty());
    }

    @Test public void regionalRevisionsGroupAndFiltersChooseOnlyMatchingEditions() {
        String usa = row(1, "Tiny (US).gba", "Tiny (USA)", "Tiny", "USA", "");
        String europe = row(2, "Tiny (EU).gba", "Tiny (Europe) (En,Fr)", "Tiny", "Europe", "en,fr");
        String revision = row(3, "Tiny (EU) (Rev1).gba", "Tiny (Europe) (En,Fr) (Rev 1)", "Tiny", "Europe", "en,fr");
        GbaCatalog catalog = GbaCatalog.parse(document(usa, europe, revision));
        CatalogPage.Entry group = catalog.page(GbaCatalog.searchUrl("Tiny")).entries.get(0);
        assertTrue(GbaCatalog.isEditionSelectionUrl(group.url));
        assertEquals(3, catalog.page(group.url).entries.size());
        CatalogPage filtered = catalog.page(GbaCatalog.withRegion(GbaCatalog.withLanguage(group.url, "fr"), "europe"));
        assertEquals(2, filtered.entries.size());
        for (CatalogPage.Entry entry : filtered.entries) {
            assertEquals("Europe", entry.region);
            assertEquals(Arrays.asList("en", "fr"), entry.languages);
        }
        assertTrue(catalog.page(GbaCatalog.withRegion(GbaCatalog.withLanguage(group.url, "en"), "north-america")).entries.isEmpty());
        String before = group.url;
        assertEquals(before, GbaCatalog.parse(document(revision, usa, europe)).page(GbaCatalog.HOME).entries.get(0).url);
        assertEquals("", catalog.page(group.url).downloadUrl);
    }

    @Test public void bundledSharedAustraliaGreeceEditionMatchesBothRegionalFilters() throws Exception {
        GbaCatalog catalog = GbaCatalog.bundled();
        for (String region : new String[] {"australia", "europe"}) {
            CatalogPage page = catalog.page(GbaCatalog.withRegion(GbaCatalog.searchUrl("Ratatouille"), region));
            assertTrue(region, page.entries.stream().anyMatch(entry -> entry.region.contains("Greece")));
        }
    }

    @Test public void paginationCountsGroupsAndCarriesBothFilters() {
        String[] rows = new String[162];
        for (int index = 0; index < 81; index++) {
            rows[index * 2] = row(index * 2 + 1, "Game " + index + " (EU).gba", "Game " + index + " (Europe) (En)", "Game " + index, "Europe", "en");
            rows[index * 2 + 1] = row(index * 2 + 2, "Game " + index + " (EU Rev1).gba", "Game " + index + " (Europe) (En) (Rev 1)", "Game " + index, "Europe", "en");
        }
        GbaCatalog catalog = GbaCatalog.parse(document(rows));
        String url = GbaCatalog.withRegion(GbaCatalog.withLanguage(GbaCatalog.HOME, "en"), "europe");
        CatalogPage first = catalog.page(url), second = catalog.page(first.nextUrl), third = catalog.page(second.nextUrl);
        assertEquals(40, first.entries.size());
        assertEquals(40, second.entries.size());
        assertEquals(1, third.entries.size());
        assertTrue(first.summary.startsWith("81 games"));
        assertTrue(second.url.contains("lang=en&region=europe"));
        assertEquals(first.url, second.previousUrl);
        assertEquals("", third.nextUrl);
        assertEquals(third.url, catalog.page(url + "&page=999999").url);
    }

    @Test public void rejectsMalformedRoutesWithoutInterpretingNetworkAddresses() {
        for (String url : new String[] {"https://archive.org/", "gba://catalog.evil/", "gba://user@catalog/",
                "gba://catalog:443/", "gba://catalog/%2e%2e/", "gba://catalog/#fragment", "gba://catalog/?q=%0a",
                "gba://catalog/?q=%FF", "gba://catalog/?q=one&q=two", "gba://catalog/?lang=en&lang=fr",
                "gba://catalog/?region=usa", "gba://catalog/?page=0", "gba://catalog/?page=1000000",
                "gba://catalog/?unexpected=1", GbaCatalog.HOME + "title/" + "a".repeat(40) + "?lang=en",
                GbaCatalog.HOME + "game/" + "a".repeat(24) + "?q=Tiny"}) {
            assertFalse(url, GbaCatalog.isPageUrl(url));
        }
        assertTrue(GbaCatalog.isPageUrl(GbaCatalog.searchUrl("Fire & Ice + Pokémon")));
        assertEquals("en", URI.create(GbaCatalog.withLanguage(GbaCatalog.HOME, "en")).getQuery().substring(5));
    }

    @Test public void parserRejectsDuplicateIdentityPathsUnknownLanguagesAndCountMismatch() {
        String valid = row(1, "Tiny.gba", "Tiny (USA)", "Tiny", "USA", "");
        assertThrows(IllegalArgumentException.class, () -> GbaCatalog.parse(document(valid, valid)));
        assertThrows(IllegalArgumentException.class, () -> GbaCatalog.parse(document(valid).replace("v1\t1", "v1\t2")));
        for (String filename : new String[] {"../Tiny.gba", "dir/Tiny.gba", "dir\\Tiny.gba", "Tiny.gba?x", ".hidden.gba", "Tiny.zip"}) {
            assertThrows(filename, IllegalArgumentException.class,
                    () -> GbaCatalog.parse(document(row(1, filename, "Tiny", "Tiny", "USA", ""))));
        }
        assertThrows(IllegalArgumentException.class,
                () -> GbaCatalog.parse(document(row(1, "Tiny.gba", "Tiny", "Tiny", "USA", "xx"))));
        assertThrows(IllegalArgumentException.class,
                () -> GbaCatalog.parse(document(valid).replace("\t1048576\t", "\t99999999\t")));
    }

    @Test public void streamLoaderBoundsBytesAndRejectsMalformedUtf8() {
        assertThrows(IOException.class, () -> GbaCatalog.read(new ByteArrayInputStream(new byte[GbaCatalog.MAX_CATALOG_BYTES + 1])));
        assertThrows(IOException.class, () -> GbaCatalog.read(new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28})));
    }

    static String row(int id, String filename, String title, String group, String region, String languages) {
        return String.format(java.util.Locale.ROOT, "%040x", id) + "\t1048576\t" + filename + "\t" + title + "\t" + group
                + "\t" + region + "\t" + languages + "\t" + title.replace('&', '_') + "\t";
    }
    static String document(String... rows) { return "# thorshelf-gba-v1\t" + rows.length + "\n" + String.join("\n", rows) + "\n"; }
}
