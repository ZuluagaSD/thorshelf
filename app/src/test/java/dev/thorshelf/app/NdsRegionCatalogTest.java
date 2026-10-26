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
import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NdsRegionCatalogTest {
    @Test public void europeanRegionAndLanguagesFilterIndependentlyWithoutRenamingFrenchPokemon() throws Exception {
        JSONObject response;
        try (InputStream input = getClass().getResourceAsStream("/nds/api-pokemon.json")) {
            assertNotNull(input);
            response = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        JSONArray rows = response.getJSONArray("entries");
        rows.put(row("Animal Crossing", "animal", "(Europe) (En,Fr)"));
        rows.put(row("American game", "american", "(USA) (En)"));
        rows.put(row("World game", "world", "(World) (En)"));
        rows.put(row("Unknown game", "unknown", "(En)"));
        rows.put(row("British edition", "british", "(The United Kingdom) (En)"));
        rows.put(row("Two-region game", "two-region", "(USA, Europe) (En)"));
        response.put("total", rows.length());
        NdsCatalog catalog = NdsCatalog.parseApiPage(response.toString()).catalog;
        assertEquals(10, catalog.page(NdsCatalog.HOME).entries.size());
        String europe = NdsCatalog.withRegion(NdsCatalog.HOME, "europe");
        assertEquals(7, catalog.page(europe).entries.size());
        assertEquals(3, catalog.page(NdsCatalog.withLanguage(europe, "en")).entries.size());
        assertEquals(5, catalog.page(NdsCatalog.withLanguage(europe, "fr")).entries.size());
        String white = NdsCatalog.withRegion(NdsCatalog.searchUrl("Pokemon White"), "europe");
        CatalogPage french = catalog.page(NdsCatalog.withLanguage(white, "fr"));
        assertEquals(2, french.entries.size());
        assertEquals("Pokémon Version Blanche", french.entries.get(0).title);
        assertEquals("France", french.entries.get(0).region);
        assertEquals(Collections.singletonList("fr"), french.entries.get(0).languages);
        assertTrue(catalog.page(NdsCatalog.withLanguage(white, "en")).entries.isEmpty());
        assertTrue(french.summary.contains("French"));
        assertTrue(french.summary.contains("Europe"));
        assertEquals(2, catalog.page(NdsCatalog.withRegion(NdsCatalog.HOME, "north-america")).entries.size());
        CatalogPage worldwide = catalog.page(NdsCatalog.withRegion(NdsCatalog.HOME, "world"));
        assertEquals(1, worldwide.entries.size());
        assertEquals("World game", worldwide.entries.get(0).title);
        for (String region : CatalogRegions.codes()) {
            assertTrue(catalog.page(NdsCatalog.withRegion(NdsCatalog.searchUrl("Unknown"), region)).entries.isEmpty());
        }
    }

    @Test public void recognizesCountryAndMultipleRegionTagsWithoutLosingSourceLabels() throws Exception {
        JSONArray rows = new JSONArray();
        for (String region : Arrays.asList("Germany", "Italy", "The Netherlands", "Russia", "The United Kingdom", "Canada")) {
            rows.put(row(region, "game" + rows.length(), "(" + region + ")"));
        }
        rows.put(row("Two separate tags", "two", "(USA) (Europe) (En,Fr)"));
        rows.put(row("Unknown tag", "unknown", "(France, Unknown) (Fr)"));
        NdsCatalog catalog = NdsCatalog.parseApiPage(envelope(rows).toString()).catalog;
        CatalogPage all = catalog.page(NdsCatalog.HOME);
        assertEquals("The Netherlands", all.entries.get(2).region);
        assertEquals("The United Kingdom", all.entries.get(4).region);
        assertEquals("Canada", all.entries.get(5).region);
        assertEquals("USA, Europe", all.entries.get(6).region);
        assertEquals("", all.entries.get(7).region);
        assertEquals(6, catalog.page(NdsCatalog.withRegion(NdsCatalog.HOME, "europe")).entries.size());
        assertEquals(2, catalog.page(NdsCatalog.withRegion(NdsCatalog.HOME, "north-america")).entries.size());
    }

    @Test public void combinesBothFiltersBeforePaginationAndPreservesBothOnEveryPage() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 150; i++) {
            String version = i % 3 == 0 ? "(France)" : i % 3 == 1 ? "(Europe) (En,Fr)" : "(USA) (En)";
            rows.put(row("Game " + i, "game" + i, version));
        }
        NdsCatalog catalog = NdsCatalog.parseApiPage(envelope(rows).toString()).catalog;
        String url = NdsCatalog.withRegion(NdsCatalog.withLanguage(NdsCatalog.searchUrl("Game"), "en"), "europe");
        CatalogPage first = catalog.page(url);
        assertEquals(40, first.entries.size());
        assertEquals("Game 1", first.entries.get(0).title);
        assertEquals("Game 118", first.entries.get(39).title);
        assertTrue(first.summary.startsWith("50 titles"));
        assertEquals(NdsCatalog.HOME + "?q=Game&page=2&lang=en&region=europe", first.nextUrl);
        CatalogPage second = catalog.page(first.nextUrl);
        assertEquals(10, second.entries.size());
        assertEquals("Game 121", second.entries.get(0).title);
        assertEquals(first.url, second.previousUrl);
        assertEquals("", second.nextUrl);
        assertEquals(second.url, catalog.page(NdsCatalog.HOME + "?q=Game&page=999&lang=en&region=europe").url);
    }

    @Test public void changingEitherFilterPreservesTheOtherQueryAndPageAndResetRemainsCompatible() {
        String original = NdsCatalog.HOME + "?region=europe&q=Pokemon+White&page=3&lang=fr";
        assertEquals(NdsCatalog.HOME + "?q=Pokemon+White&page=3&lang=en&region=europe", NdsCatalog.withLanguage(original, "EN"));
        assertEquals(NdsCatalog.HOME + "?q=Pokemon+White&page=3&lang=fr&region=north-america", NdsCatalog.withRegion(original, "North-America"));
        assertEquals(NdsCatalog.HOME + "?q=Pokemon+White&page=3&lang=fr", NdsCatalog.withRegion(original, "all"));
        assertEquals(NdsCatalog.HOME + "?q=Pokemon+White&page=3&region=europe", NdsCatalog.withLanguage(original, ""));
        assertEquals(NdsCatalog.HOME + "?q=Pokemon+White&lang=en&region=europe", CatalogLanguageFilter.apply(original, "en", true));
        String onlyRegion = NdsCatalog.withRegion(NdsCatalog.HOME, "europe");
        assertEquals(NdsCatalog.HOME + "?region=europe", onlyRegion);
        assertEquals(NdsCatalog.HOME + "?lang=en&region=europe", NdsCatalog.withLanguage(onlyRegion, "en"));
        assertEquals(NdsCatalog.HOME, NdsCatalog.withRegion(onlyRegion, "unknown"));
        String detail = NdsCatalog.HOME + "title/0123456789abcdef01234567";
        assertEquals(detail, NdsCatalog.withRegion(detail, "europe"));
        assertEquals(detail, NdsCatalog.withLanguage(detail, "en"));
    }

    @Test public void validatesRegionRoutesAndCanonicalizesKnownCodes() throws Exception {
        NdsCatalog catalog = NdsCatalog.parseApiPage(envelope(new JSONArray()).toString()).catalog;
        assertEquals(NdsCatalog.HOME + "?region=europe", catalog.page(NdsCatalog.HOME + "?region=EUROPE").url);
        assertTrue(NdsCatalog.isPageUrl(NdsCatalog.HOME + "?region=north%2Damerica&lang=en"));
        assertEquals(NdsCatalog.HOME, catalog.page(NdsCatalog.HOME + "?region=all").url);
        assertEquals(NdsCatalog.HOME, catalog.page(NdsCatalog.HOME + "?region=").url);
        for (String query : new String[]{"?region=France", "?region=USA", "?region=unknown", "?region=europe&region=japan",
                "?region=%0Aeurope", "?region=+europe", "?region=europe%26lang%3Den", "?region=europe#fragment"}) {
            assertFalse(query, NdsCatalog.isPageUrl(NdsCatalog.HOME + query));
        }
        assertFalse(NdsCatalog.isDetailUrl(NdsCatalog.HOME + "title/0123456789abcdef01234567?region=europe"));
        assertThrows(IllegalArgumentException.class, () -> NdsCatalog.withRegion("https://evil.example/", "europe"));
    }

    private static JSONObject row(String title, String name, String version) throws Exception {
        return new JSONObject().put("title", title).put("systems", new JSONArray().put("DS"))
                .put("version", version).put("downloads", new JSONObject().put(name + ".nds", new JSONObject()
                        .put("url", "https://db-nds-shop.fr/api/v1/download/" + name + ".nds").put("size", 1024)));
    }

    private static JSONObject envelope(JSONArray rows) throws Exception {
        return new JSONObject().put("page", 1).put("limit", 200).put("total", rows.length())
                .put("revision", 1).put("entries", rows);
    }
}
