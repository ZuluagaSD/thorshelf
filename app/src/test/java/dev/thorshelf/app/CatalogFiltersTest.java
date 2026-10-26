package dev.thorshelf.app;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.*;

public class CatalogFiltersTest {
    private static final String SEARCH = "https://hshop.erista.me/search/results?q=Mario+Kart&qt=Text&count=25&offset=200";

    @Test public void combinedSearchKeepsQueryAndCarriesBothFiltersAndPagination() {
        String url = CatalogFilters.apply(SEARCH, "en", "europe", false);
        assertEquals("en", CatalogFilters.selectedLanguage(url));
        assertEquals("europe", CatalogFilters.selectedRegion(url));
        assertTrue(url.contains("q=Mario+Kart&qt=Text"));
        assertTrue(url.contains("offset=200"));
        assertTrue(url.contains("count=100"));
        assertFalse(url.contains("count=25"));
        String source = CatalogFilters.sourceUrl(url);
        assertFalse(source.contains("thorshelf_"));
        assertTrue(source.contains("q=Mario+Kart&qt=Text"));
        assertTrue(source.contains("offset=200"));
    }

    @Test public void clearingOnlyOneFilterPreservesTheOtherAndResetsPagination() {
        String both = CatalogFilters.navigation(SEARCH, "fr", "europe", true);
        String regionOnly = CatalogLanguageFilter.apply(both, "", true);
        assertEquals("", CatalogFilters.selectedLanguage(regionOnly));
        assertEquals("europe", CatalogFilters.selectedRegion(regionOnly));
        assertFalse(regionOnly.contains("offset="));
        assertFalse(CatalogFilters.backwards(regionOnly));
        String languageOnly = CatalogFilters.apply(both, "fr", "", true);
        assertEquals("fr", CatalogLanguageFilter.selected(languageOnly));
        assertEquals("", CatalogFilters.selectedRegion(languageOnly));
        assertFalse(languageOnly.contains("offset="));
        String all = CatalogFilters.apply(both, "", "", true);
        assertFalse(all.contains("thorshelf_"));
        assertFalse(all.contains("offset="));
    }

    @Test public void changingRegionLeavesOldGeographicalScopeAndKeepsSorting() {
        String original = "https://hshop.erista.me/c/games/s/north-america?sb=name&sd=ascending&offset=100";
        String europe = CatalogFilters.apply(original, "en", "europe", true);
        assertEquals("/c/games", URI.create(europe).getRawPath());
        assertEquals("europe", CatalogFilters.selectedRegion(europe));
        assertEquals("en", CatalogFilters.selectedLanguage(europe));
        assertTrue(europe.contains("sb=name&sd=ascending"));
        assertFalse(europe.contains("offset="));
        String france = CatalogFilters.apply("https://hshop.erista.me/c/games/s/france", "en", "europe", false);
        String allRegions = CatalogFilters.apply(france, "en", "", true);
        assertEquals("/c/games", URI.create(allRegions).getRawPath());
        assertEquals("en", CatalogFilters.selectedLanguage(allRegions));
    }

    @Test public void languageChangesAndPaginationKeepExistingRegionalScope() {
        String france = CatalogFilters.apply("https://hshop.erista.me/c/games/s/france?offset=100", "fr", "europe", false);
        String english = CatalogFilters.apply(france, "en", "europe", true);
        assertEquals("/c/games/s/france", URI.create(english).getRawPath());
        String previous = CatalogFilters.navigation(france, "en", "europe", true);
        assertEquals("/c/games/s/france", URI.create(previous).getRawPath());
        assertTrue(previous.contains("offset=100"));
        assertTrue(CatalogFilters.backwards(previous));
    }

    @Test public void regionOnlyPreviousAndNextKeepSelectionAndCorrectScanDirection() {
        String previous = CatalogFilters.navigation(SEARCH, "", "japan", true);
        assertTrue(CatalogFilters.backwards(previous));
        assertEquals("japan", CatalogFilters.selectedRegion(previous));
        String next = CatalogFilters.navigation(previous, "", "japan", false);
        assertFalse(CatalogFilters.backwards(next));
        assertEquals("japan", CatalogFilters.selectedRegion(next));
        assertFalse(CatalogFilters.sourceUrl(previous).contains("thorshelf_"));
        assertEquals("", CatalogFilters.navigation("", "en", "japan", true));
    }

    @Test public void geographicCategoryFilteringIncludesCountryEditionsAndExcludesUnknownRegions() {
        assertTrue(CatalogFilters.includesCategory("https://hshop.erista.me/c/games/s/europe", "europe"));
        assertTrue(CatalogFilters.includesCategory("https://hshop.erista.me/c/games/s/france", "europe"));
        assertTrue(CatalogFilters.includesCategory("https://hshop.erista.me/c/games/s/germany", "europe"));
        assertTrue(CatalogFilters.includesCategory("https://hshop.erista.me/c/games/s/canada", "north-america"));
        assertFalse(CatalogFilters.includesCategory("https://hshop.erista.me/c/games/s/north-america", "europe"));
        assertFalse(CatalogFilters.includesCategory("https://hshop.erista.me/c/games/s/world", "europe"));
        assertFalse(CatalogFilters.includesCategory("https://hshop.erista.me/c/games/s/unknown-other", "europe"));
        assertFalse(CatalogFilters.includesCategory("https://hshop.erista.me/c/games/s/digital-demos", "europe"));
        assertTrue(CatalogFilters.includesCategory("https://hshop.erista.me/c/extras/s/hb", "europe"));
        assertTrue(CatalogFilters.includesCategory("https://hshop.erista.me/c/games/s/japan", ""));
    }

    @Test public void dsFiltersPreserveQueryBothSelectionsAndLocalPage() {
        String original = NdsCatalog.HOME + "?q=Mario&page=3&lang=en&region=europe";
        String kept = CatalogFilters.apply(original, "fr", "japan", false);
        assertTrue(NdsCatalog.isPageUrl(kept));
        assertTrue(kept.contains("q=Mario&page=3"));
        assertEquals("fr", CatalogLanguageFilter.selected(kept));
        assertEquals("japan", CatalogFilters.selectedRegion(kept));
        String reset = CatalogFilters.apply(original, "en", "north-america", true);
        assertFalse(reset.contains("page="));
        assertEquals("en", CatalogFilters.selectedLanguage(reset));
        assertEquals("north-america", CatalogFilters.selectedRegion(reset));
        String clearedLanguage = CatalogLanguageFilter.apply(original, "", true);
        assertEquals("europe", CatalogFilters.selectedRegion(clearedLanguage));
        assertEquals("", CatalogFilters.selectedLanguage(clearedLanguage));
        assertTrue(NdsCatalog.isPageUrl(CatalogFilters.navigation(kept, "fr", "japan", true)));
    }

    @Test public void detailsKeepIdentityAndEncodedQueryValuesAreNeverAltered() {
        String detail = "https://hshop.erista.me/t/2476";
        assertEquals(detail, CatalogFilters.apply(detail + "?thorshelf_lang=en&thorshelf_region=europe&thorshelf_direction=previous",
                "fr", "japan", true));
        String dsDetail = NdsCatalog.HOME + "title/0123456789abcdef01234567";
        assertEquals(dsDetail, CatalogFilters.apply(dsDetail, "en", "europe", true));
        String source = "https://hshop.erista.me/search/results?q=Game%26thorshelf_region%3Djapan&qt=Text";
        String clean = CatalogFilters.sourceUrl(CatalogFilters.apply(source, "en", "europe", false));
        assertTrue(clean.contains("q=Game%26thorshelf_region%3Djapan"));
        assertFalse(clean.contains("&thorshelf_"));
        assertEquals(detail, CatalogFilters.sourceUrl(detail + "?thorshelf%5Fregion=europe&thorshelf_lang=en"));
    }
}
