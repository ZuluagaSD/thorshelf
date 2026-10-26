package dev.thorshelf.app;

/** Compatibility helpers for callers changing language while preserving the region selection. */
final class CatalogLanguageFilter {
    private CatalogLanguageFilter() { }

    static String apply(String url, String code, boolean resetPage) {
        return CatalogFilters.apply(url, code, CatalogFilters.selectedRegion(url), resetPage);
    }

    static String selected(String url) { return CatalogFilters.selectedLanguage(url); }

    static String sourceUrl(String url) { return CatalogFilters.sourceUrl(url); }

    static boolean backwards(String url) { return CatalogFilters.backwards(url); }

    static String navigation(String url, String language, boolean previous) {
        return CatalogFilters.navigation(url, language, CatalogFilters.selectedRegion(url), previous);
    }
}
