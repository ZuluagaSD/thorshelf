package dev.thorshelf.app;

import org.junit.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class CatalogLanguageFilterTest {
    private static final String SEARCH = "https://hshop.erista.me/search/results?q=Pok%C3%A9mon+Ruby&qt=Text&lgy=false";

    @Test public void threeDsFilterPreservesSearchAndOffsetAndNeverChangesSourceQueryValues() throws Exception {
        String filtered = CatalogLanguageFilter.apply(SEARCH + "&count=25&offset=50", "EN", false);
        assertEquals("en", CatalogLanguageFilter.selected(filtered));
        assertEquals("Pokémon Ruby", parameter(filtered, "q"));
        assertEquals("Text", parameter(filtered, "qt"));
        assertEquals("false", parameter(filtered, "lgy"));
        assertEquals("50", parameter(filtered, "offset"));
        assertEquals("100", parameter(filtered, "count"));
        String source = CatalogLanguageFilter.sourceUrl(filtered);
        assertFalse(source.contains("thorshelf_"));
        assertEquals("Pokémon Ruby", parameter(source, "q"));
        assertEquals("50", parameter(source, "offset"));
        String encodedValue = "https://hshop.erista.me/search/results?q=Game%26thorshelf_lang%3Dfr";
        assertEquals("Game&thorshelf_lang=fr", parameter(CatalogLanguageFilter.sourceUrl(
                CatalogLanguageFilter.apply(encodedValue, "en", false)), "q"));
    }

    @Test public void changingOrClearingThreeDsLanguageResetsPaginationWhenRequested() throws Exception {
        String previous = CatalogLanguageFilter.navigation(SEARCH + "&offset=200", "fr", true);
        assertTrue(CatalogLanguageFilter.backwards(previous));
        String changed = CatalogLanguageFilter.apply(previous, "en", true);
        assertEquals("en", CatalogLanguageFilter.selected(changed));
        assertEquals("", parameter(changed, "offset"));
        assertFalse(CatalogLanguageFilter.backwards(changed));
        assertEquals("Pokémon Ruby", parameter(changed, "q"));
        String all = CatalogLanguageFilter.apply(previous, "", true);
        assertEquals("", CatalogLanguageFilter.selected(all));
        assertEquals("", parameter(all, "offset"));
        assertFalse(all.contains("thorshelf_"));
        assertEquals("Pokémon Ruby", parameter(all, "q"));
    }

    @Test public void dsFilterPreservesQueryAndCanResetOrKeepItsPage() throws Exception {
        String source = NdsCatalog.HOME + "?q=Pokemon+White&page=3&lang=fr";
        String kept = CatalogLanguageFilter.apply(source, "en", false);
        assertEquals("en", CatalogLanguageFilter.selected(kept));
        assertEquals("3", parameter(kept, "page"));
        assertEquals("Pokemon White", parameter(kept, "q"));
        String reset = CatalogLanguageFilter.apply(source, "en", true);
        assertEquals(NdsCatalog.HOME + "?q=Pokemon+White&lang=en", reset);
        assertTrue(NdsCatalog.isPageUrl(reset));
        assertEquals(NdsCatalog.searchUrl("Pokemon White"), CatalogLanguageFilter.apply(source, "", true));
        assertEquals(NdsCatalog.HOME + "?lang=en", CatalogLanguageFilter.apply(NdsCatalog.HOME, "en", true));
    }

    @Test public void previousAndNextLinksKeepLanguageButOnlyPreviousScansBackwards() {
        String previous = CatalogLanguageFilter.navigation(SEARCH + "&offset=100", "en", true);
        assertEquals("en", CatalogLanguageFilter.selected(previous));
        assertTrue(CatalogLanguageFilter.backwards(previous));
        String next = CatalogLanguageFilter.navigation(previous, "en", false);
        assertEquals("en", CatalogLanguageFilter.selected(next));
        assertFalse(CatalogLanguageFilter.backwards(next));
        assertFalse(CatalogLanguageFilter.sourceUrl(previous).contains("thorshelf_"));
        assertEquals("", CatalogLanguageFilter.navigation("", "en", true));
        assertFalse(CatalogLanguageFilter.backwards(CatalogLanguageFilter.navigation(previous, "", true)));
    }

    @Test public void categoryLinksCarryFiltersAndDetailLinksKeepTheirIdentity() {
        String category = "https://hshop.erista.me/c/games/s/world";
        assertEquals("en", CatalogLanguageFilter.selected(CatalogLanguageFilter.apply(category, "en", false)));
        String detail = "https://hshop.erista.me/t/2476";
        assertEquals(detail, CatalogLanguageFilter.apply(detail, "en", true));
        assertEquals(detail, CatalogLanguageFilter.apply(detail + "?thorshelf_lang=en&thorshelf_direction=previous", "fr", false));
        String dsDetail = NdsCatalog.HOME + "title/0123456789abcdef01234567";
        assertEquals(dsDetail, CatalogLanguageFilter.apply(dsDetail, "en", true));
        assertEquals("", CatalogLanguageFilter.apply(null, "en", false));
        assertEquals("https://evil.example/", CatalogLanguageFilter.apply("https://evil.example/", "en", false));
    }

    private static String parameter(String url, String key) throws Exception {
        String query = URI.create(url).getRawQuery();
        if (query == null) return "";
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name()).equals(key)) {
                return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()) : "";
            }
        }
        return "";
    }
}
