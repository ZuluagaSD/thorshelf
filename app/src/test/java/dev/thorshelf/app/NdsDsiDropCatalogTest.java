package dev.thorshelf.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NdsDsiDropCatalogTest {
    @Test public void realPokemonEnglishEditionsAreSearchableWithoutPunctuationOrVersion() throws Exception {
        NdsCatalog catalog = NdsCatalog.parseDsiDrop(fixture());
        for (String query : new String[] {"Pokemon Black", "Pokémon Black", "pokemon white"}) {
            CatalogPage page = catalog.page(NdsCatalog.withLanguage(NdsCatalog.searchUrl(query), "en"));
            assertEquals(2, page.entries.size());
            for (CatalogPage.Entry entry : page.entries) {
                assertEquals(Arrays.asList("en"), entry.languages);
                assertEquals("USA, Europe", entry.region);
                assertTrue(CatalogRegions.matches(entry.region, "north-america"));
                assertTrue(CatalogRegions.matches(entry.region, "europe"));
                assertTrue(entry.artworkUrl.startsWith(NdsCatalog.COVER_BASE));
                CatalogPage detail = catalog.page(entry.url);
                assertTrue(detail.downloadUrl.startsWith(NdsCatalog.DOWNLOAD_BASE));
                assertTrue(detail.downloadUrl.endsWith(".zip"));
                assertTrue(detail.summary.contains("DSiDrop"));
                assertTrue(detail.summary.contains("one .nds"));
            }
        }
        assertEquals("Pokemon - Black Version 2", catalog.page(NdsCatalog.searchUrl("Pokemon Black 2")).entries.get(0).title);
        assertEquals(1, catalog.page(NdsCatalog.searchUrl("Pokemon White2")).entries.size());
    }

    @Test public void groupsRegionalEditionsAndKeepsEveryDownloadDistinct() throws Exception {
        NdsCatalog catalog = NdsCatalog.parseDsiDrop(fixture());
        CatalogPage listing = catalog.page(NdsCatalog.searchUrl("Mario Kart"));
        assertEquals(1, listing.entries.size());
        CatalogPage.Entry game = listing.entries.get(0);
        assertEquals("Mario Kart DS", game.title);
        assertTrue(NdsCatalog.isEditionSelectionUrl(game.url));
        assertFalse(NdsCatalog.isDetailUrl(game.url));
        CatalogPage editions = catalog.page(game.url);
        assertEquals(4, editions.entries.size());
        assertEquals("", editions.downloadUrl);
        assertTrue(editions.summary.contains("4 editions"));
        Set<String> ids = new HashSet<>();
        Set<String> downloads = new HashSet<>();
        for (CatalogPage.Entry edition : editions.entries) {
            assertTrue(NdsCatalog.isDetailUrl(edition.url));
            assertTrue(ids.add(edition.url));
            assertTrue(downloads.add(catalog.page(edition.url).downloadUrl));
            assertFalse(edition.title.contains("Demo"));
        }
    }

    @Test public void filtersEditionsBeforeSelectingCoverOrGroupingAndPreservesGroupFilters() throws Exception {
        NdsCatalog catalog = NdsCatalog.parseDsiDrop(fixture());
        String group = catalog.page(NdsCatalog.searchUrl("Mario Kart")).entries.get(0).url;
        String frenchEurope = NdsCatalog.withRegion(NdsCatalog.withLanguage(group, "fr"), "europe");
        assertTrue(NdsCatalog.isEditionSelectionUrl(frenchEurope));
        CatalogPage variants = catalog.page(frenchEurope);
        assertEquals(1, variants.entries.size());
        assertTrue(variants.entries.get(0).title.contains("Europe"));
        assertEquals("fr", CatalogLanguageFilter.selected(variants.url));
        assertEquals("europe", CatalogFilters.selectedRegion(variants.url));
        String korea = NdsCatalog.withRegion(NdsCatalog.searchUrl("Mario Kart"), "korea");
        CatalogPage.Entry korean = catalog.page(korea).entries.get(0);
        assertFalse(NdsCatalog.isEditionSelectionUrl(korean.url));
        assertTrue(korean.artworkUrl.contains("Korea"));
        assertEquals("Korea", korean.region);
        assertTrue(catalog.page(NdsCatalog.withLanguage(frenchEurope, "ko")).entries.isEmpty());
    }

    @Test public void excludesNonRetailFilesFromBothTagsAndFilenameAnnotations() throws Exception {
        for (String tag : new String[] {"Bad Dump", "Demo", "Proto 2", "Beta", "Kiosk, Multiplayer",
                "Save Data", "BIOS", "Program", "Video", "NFR", "Promo"}) {
            JSONObject row = row("Safe Game", "Safe Game (USA)").put("g", new JSONArray().put(tag));
            assertTrue(tag, NdsCatalog.parseDsiDrop(document(new JSONArray().put(row))).page(NdsCatalog.HOME).entries.isEmpty());
            row.remove("g"); row.put("n", "Safe Game (USA) (" + tag + ")");
            assertTrue(tag, NdsCatalog.parseDsiDrop(document(new JSONArray().put(row))).page(NdsCatalog.HOME).entries.isEmpty());
        }
        JSONObject revision = row("Safe Game", "Safe Game (USA) (Rev 1)").put("g", new JSONArray().put("Rev 1"));
        assertEquals(1, NdsCatalog.parseDsiDrop(document(new JSONArray().put(revision))).page(NdsCatalog.HOME).entries.size());
    }

    @Test public void pinsSourceMetadataAndDoesNotAcceptSourceControlledDownloadRoots() throws Exception {
        for (String field : new String[] {"source", "download", "coverSource", "coverBase", "id", "ext", "arch", "out", "checksum"}) {
            JSONObject source = new JSONObject(fixture());
            source.getJSONObject("meta").put(field, "https://evil.example/");
            assertThrows(field, IllegalArgumentException.class, () -> NdsCatalog.parseDsiDrop(source.toString()));
        }
        JSONObject mismatch = new JSONObject(fixture());
        mismatch.getJSONObject("meta").put("total", 1);
        assertThrows(IllegalArgumentException.class, () -> NdsCatalog.parseDsiDrop(mismatch.toString()));
    }

    @Test public void malformedRowsCannotInjectPathsAndOptionalBadArtworkKeepsGame() throws Exception {
        for (String name : new String[] {"../game", "dir/game", "dir\\game", "game\nname", "x".repeat(241)}) {
            JSONArray rows = new JSONArray().put(row("Bad", name)).put(row("Good", "Good (USA)"));
            CatalogPage page = NdsCatalog.parseDsiDrop(document(rows)).page(NdsCatalog.HOME);
            assertEquals(1, page.entries.size());
            assertEquals("Good", page.entries.get(0).title);
        }
        JSONObject row = row("Good", "Good (USA)").put("c", "https://evil.example/cover");
        CatalogPage.Entry game = NdsCatalog.parseDsiDrop(document(new JSONArray().put(row))).page(NdsCatalog.HOME).entries.get(0);
        assertEquals("", game.artworkUrl);
        assertTrue(game.languages.isEmpty());
    }

    @Test public void duplicateFilesAreRejectedAndIdentifiersSurviveCatalogReordering() throws Exception {
        JSONObject one = row("Same Game", "Same Game (USA)");
        assertThrows(IllegalArgumentException.class,
                () -> NdsCatalog.parseDsiDrop(document(new JSONArray().put(one).put(one))));
        JSONObject two = row("Same Game", "Same Game (Europe)").put("r", "Europe");
        NdsCatalog before = NdsCatalog.parseDsiDrop(document(new JSONArray().put(one).put(two)));
        NdsCatalog after = NdsCatalog.parseDsiDrop(document(new JSONArray().put(two).put(one)));
        String group = before.page(NdsCatalog.HOME).entries.get(0).url;
        assertEquals(group, after.page(NdsCatalog.HOME).entries.get(0).url);
        String edition = before.page(group).entries.get(0).url;
        assertEquals(before.page(edition).downloadUrl, after.page(edition).downloadUrl);
    }

    @Test public void paginatesGamesAfterGroupingAndKeepsFiltersOnEveryPage() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 81; i++) {
            rows.put(row("Game " + i, "Game " + i + " (Europe) (En,Fr)").put("r", "Europe").put("l", "En,Fr"));
            rows.put(row("Game " + i, "Game " + i + " (USA) (En)").put("l", "En"));
        }
        NdsCatalog catalog = NdsCatalog.parseDsiDrop(document(rows));
        String url = NdsCatalog.withLanguage(NdsCatalog.searchUrl("Game"), "en");
        CatalogPage first = catalog.page(url);
        assertEquals(40, first.entries.size());
        assertTrue(first.summary.startsWith("81 games"));
        CatalogPage second = catalog.page(first.nextUrl);
        assertEquals(40, second.entries.size());
        assertEquals("en", CatalogLanguageFilter.selected(second.url));
        assertEquals(1, catalog.page(second.nextUrl).entries.size());
        assertEquals(first.url, second.previousUrl);
    }

    @Test public void boundsJsonBeforeAllocationAndRejectsMalformedRoutes() throws Exception {
        for (String text : new String[] {fixture() + "{}", "[0]", "{", "{\"ignored\":[" + "0,".repeat(300_001) + "0]}"}) {
            assertThrows(IllegalArgumentException.class, () -> NdsCatalog.parseDsiDrop(text));
        }
        for (String route : new String[] {NdsCatalog.HOME + "game/not-a-hash", NdsCatalog.HOME + "game/" + "a".repeat(24) + "?q=test",
                NdsCatalog.HOME + "game/" + "a".repeat(24) + "?lang=en&lang=fr"}) assertFalse(route, NdsCatalog.isPageUrl(route));
    }

    static String fixture() throws Exception {
        try (InputStream input = NdsDsiDropCatalogTest.class.getResourceAsStream("/nds/dsidrop.json")) {
            if (input == null) throw new IllegalStateException("Missing DSiDrop fixture");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static JSONObject row(String title, String name) throws Exception {
        return new JSONObject().put("n", name).put("t", title).put("r", "Usa").put("s", 1024)
                .put("m", "a".repeat(32)).put("h", "b".repeat(40)).put("c", name);
    }

    static String document(JSONArray rows) throws Exception {
        JSONObject root = new JSONObject(fixture());
        root.put("roms", rows); root.getJSONObject("meta").put("total", rows.length());
        return root.toString();
    }
}
