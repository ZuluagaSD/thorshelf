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
public class NdsLanguageCatalogTest {
    @Test public void europeanMultilanguageGamesMatchEnglishButFrenchPokemonAndUnknownDoNot() throws Exception {
        JSONObject response;
        try (InputStream input = getClass().getResourceAsStream("/nds/api-pokemon.json")) {
            assertNotNull(input);
            response = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        JSONArray rows = response.getJSONArray("entries");
        rows.put(row("Animal Crossing", "animal", "(Europe) (En,Fr,De,Es,It)"));
        rows.put(row("Unknown game", "unknown", "(Europe)"));
        response.put("total", rows.length());
        NdsCatalog catalog = NdsCatalog.parseApiPage(response.toString()).catalog;
        CatalogPage english = catalog.page(NdsCatalog.withLanguage(NdsCatalog.HOME, "en"));
        assertEquals(1, english.entries.size());
        assertEquals("Animal Crossing", english.entries.get(0).title);
        assertEquals("Europe", english.entries.get(0).region);
        assertEquals(Arrays.asList("en", "fr", "de", "es", "it"), english.entries.get(0).languages);
        assertTrue(english.summary.contains("English"));
        assertEquals(6, catalog.page(NdsCatalog.HOME).entries.size());
        assertEquals(5, catalog.page(NdsCatalog.withLanguage(NdsCatalog.HOME, "fr")).entries.size());
        assertTrue(catalog.page(NdsCatalog.withLanguage(NdsCatalog.searchUrl("Pokemon White"), "en")).entries.isEmpty());
        CatalogPage frenchWhite = catalog.page(NdsCatalog.withLanguage(NdsCatalog.searchUrl("Pokemon White"), "fr"));
        assertEquals(2, frenchWhite.entries.size());
        assertEquals("Pokémon Version Blanche", frenchWhite.entries.get(0).title);
        assertEquals("France", frenchWhite.entries.get(0).region);
        assertEquals(Collections.singletonList("fr"), frenchWhite.entries.get(0).languages);
        assertEquals(Collections.singletonList("fr"), catalog.page(frenchWhite.entries.get(0).url).entries.get(0).languages);
        assertTrue(catalog.page(NdsCatalog.withLanguage(NdsCatalog.searchUrl("Unknown"), "en")).entries.isEmpty());
    }

    @Test public void appliesLanguageBeforePaginationAndKeepsFilterInEveryPageLink() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 85; i++) rows.put(row("Game " + i, "game" + i,
                i % 2 == 0 ? "(Europe) (En,De)" : "(France)"));
        NdsCatalog catalog = NdsCatalog.parseApiPage(envelope(rows).toString()).catalog;
        String search = NdsCatalog.withLanguage(NdsCatalog.searchUrl("Game"), "en");
        CatalogPage first = catalog.page(search);
        assertEquals(40, first.entries.size());
        assertEquals("Game 78", first.entries.get(39).title);
        assertTrue(first.summary.startsWith("43 titles"));
        assertTrue(first.summary.contains("English"));
        assertEquals("", first.previousUrl);
        assertEquals(NdsCatalog.HOME + "?q=Game&page=2&lang=en", first.nextUrl);
        CatalogPage second = catalog.page(first.nextUrl);
        assertEquals(3, second.entries.size());
        assertEquals("Game 80", second.entries.get(0).title);
        assertEquals(search, second.previousUrl);
        assertEquals("", second.nextUrl);
        assertEquals(second.url, catalog.page(NdsCatalog.HOME + "?q=Game&page=999&lang=en").url);
    }

    @Test public void languageRoutesAreValidatedCanonicalAndPreserveQueryAndPage() {
        assertEquals(NdsCatalog.HOME + "?lang=en", NdsCatalog.withLanguage(NdsCatalog.HOME, "EN"));
        String input = NdsCatalog.HOME + "?lang=fr&page=2&q=Pok%C3%A9mon+White";
        assertEquals(NdsCatalog.HOME + "?q=Pok%C3%A9mon+White&page=2&lang=en", NdsCatalog.withLanguage(input, "en"));
        assertEquals(NdsCatalog.HOME + "?q=Pok%C3%A9mon+White&page=2", NdsCatalog.withLanguage(input, "all"));
        assertEquals(NdsCatalog.HOME, NdsCatalog.withLanguage(NdsCatalog.HOME + "?lang=EN", "unknown"));
        assertTrue(NdsCatalog.isPageUrl(NdsCatalog.HOME + "?lang=%65%6e"));
        assertTrue(NdsCatalog.isPageUrl(NdsCatalog.HOME + "?lang="));
        assertTrue(NdsCatalog.isPageUrl(NdsCatalog.HOME + "?lang=all"));
        String detail = NdsCatalog.HOME + "title/0123456789abcdef01234567";
        assertEquals(detail, NdsCatalog.withLanguage(detail, "en"));
        for (String invalid : new String[]{"?lang=xx", "?lang=English", "?lang=en&lang=fr",
                "?lang=%0Aen", "?lang=+en", "?lang=en%26page%3D2", "?lang=en#fragment"}) {
            assertFalse(invalid, NdsCatalog.isPageUrl(NdsCatalog.HOME + invalid));
        }
        assertFalse(NdsCatalog.isDetailUrl(detail + "?lang=en"));
        assertThrows(IllegalArgumentException.class, () -> NdsCatalog.withLanguage("https://evil.example/", "en"));
    }

    @Test public void legacyRecordsKeepUnknownLanguageAndAreExcludedBySpecificFilters() {
        String row = "Demo|nds|ANY|1.0|Author|https://github.com/owner/game/releases/download/v1/demo.nds|demo.nds|1024|";
        NdsCatalog catalog = NdsCatalog.parse("1\n|\n" + row);
        assertEquals(1, catalog.page(NdsCatalog.HOME).entries.size());
        assertTrue(catalog.page(NdsCatalog.HOME).entries.get(0).languages.isEmpty());
        assertTrue(catalog.page(NdsCatalog.withLanguage(NdsCatalog.HOME, "en")).entries.isEmpty());
    }

    private static JSONObject row(String title, String name, String version) throws Exception {
        return new JSONObject().put("title", title).put("systems", new JSONArray().put("DS"))
                .put("version", version).put("downloads", new JSONObject().put(name + ".nds", new JSONObject()
                        .put("url", "https://db-nds-shop.fr/api/v1/download/" + name + ".nds").put("size", 1024)));
    }

    private static JSONObject envelope(JSONArray entries) throws Exception {
        return new JSONObject().put("page", 1).put("limit", 200).put("total", entries.length())
                .put("revision", 1).put("entries", entries);
    }
}
