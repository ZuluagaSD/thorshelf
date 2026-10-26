package dev.thorshelf.app;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Carries local language/region choices through navigation without sending them to hShop. */
final class CatalogFilters {
    private static final String LANGUAGE = "thorshelf_lang";
    private static final String REGION = "thorshelf_region";
    private static final String DIRECTION = "thorshelf_direction";
    private static final Pattern SUBCATEGORY = Pattern.compile("^/c/([A-Za-z0-9_-]+)/s/([A-Za-z0-9_-]+)/?$");

    private CatalogFilters() { }

    static String apply(String url, String languageCode, String regionCode, boolean resetPage) {
        if (url == null || url.isEmpty()) return "";
        String language = CatalogLanguages.normalize(languageCode);
        String region = CatalogRegions.normalize(regionCode);
        if (NdsCatalog.isPageUrl(url)) {
            if (NdsCatalog.isDetailUrl(url)) return url;
            String result = resetPage ? without(url, "page") : url;
            return NdsCatalog.withRegion(NdsCatalog.withLanguage(result, language), region);
        }
        if (!CatalogParser.isCatalogPageUrl(url)) return url;
        if (URI.create(url).getRawPath().startsWith("/t/")) return sourceUrl(url);
        String result = without(without(url, LANGUAGE), REGION);
        if (resetPage) {
            result = without(without(result, "offset"), DIRECTION);
            // A broad region includes country editions. Return to this category's region choices
            // instead of locking the new filter to the old country or only /s/europe.
            if (!selectedRegion(url).equals(region)) result = leaveGeographicSubcategory(result);
        }
        if (language.isEmpty() && region.isEmpty()) return without(result, DIRECTION);
        result = append(without(result, "count"), "count=100");
        if (!language.isEmpty()) result = append(result, LANGUAGE + "=" + language);
        if (!region.isEmpty()) result = append(result, REGION + "=" + region);
        return result;
    }

    static String selectedLanguage(String url) {
        return CatalogLanguages.normalize(parameter(url, NdsCatalog.isPageUrl(url) ? "lang" : LANGUAGE));
    }

    static String selectedRegion(String url) {
        return CatalogRegions.normalize(parameter(url, NdsCatalog.isPageUrl(url) ? "region" : REGION));
    }

    static String sourceUrl(String url) { return without(without(without(url, LANGUAGE), REGION), DIRECTION); }

    static boolean backwards(String url) { return "previous".equals(parameter(url, DIRECTION)); }

    static String navigation(String url, String language, String region, boolean previous) {
        if (url == null || url.isEmpty()) return "";
        String result = without(apply(url, language, region, false), DIRECTION);
        boolean filtering = !CatalogLanguages.normalize(language).isEmpty() || !CatalogRegions.normalize(region).isEmpty();
        return previous && filtering && !NdsCatalog.isPageUrl(result)
                ? append(result, DIRECTION + "=previous") : result;
    }

    /** hShop lists geographic subcategories alongside demos; hide unknown locations under a region filter. */
    static boolean includesCategory(String url, String selectedRegion) {
        String region = CatalogRegions.normalize(selectedRegion);
        if (region.isEmpty() || !CatalogParser.isCatalogPageUrl(url)) return true;
        Matcher subcategory = SUBCATEGORY.matcher(URI.create(url).getRawPath());
        if (!subcategory.matches()) return true;
        String slug = subcategory.group(2);
        if (!CatalogRegions.fromMetadata(slug).isEmpty()) return CatalogRegions.matches(slug, region);
        switch (subcategory.group(1)) {
            case "games": case "updates": case "dlc": case "dsiware":
            case "videos": case "virtual-console": case "themes":
                return false;
            default:
                return true;
        }
    }

    private static String leaveGeographicSubcategory(String url) {
        URI uri = URI.create(url);
        Matcher subcategory = SUBCATEGORY.matcher(uri.getRawPath());
        if (!subcategory.matches() || CatalogRegions.fromMetadata(subcategory.group(2)).isEmpty()) return url;
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String fragment = uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment();
        return uri.getScheme() + "://" + uri.getRawAuthority() + "/c/" + subcategory.group(1) + query + fragment;
    }

    private static String parameter(String url, String name) {
        if (url == null || url.isEmpty()) return "";
        try {
            String query = URI.create(url).getRawQuery();
            if (query == null) return "";
            for (String part : query.split("&")) {
                int equals = part.indexOf('=');
                if (decode(equals < 0 ? part : part.substring(0, equals)).equals(name)) {
                    return equals < 0 ? "" : decode(part.substring(equals + 1));
                }
            }
        } catch (IllegalArgumentException ignored) { /* The client validates URLs before fetching. */ }
        return "";
    }

    private static String without(String url, String name) {
        if (url == null || url.isEmpty()) return "";
        URI uri = URI.create(url);
        String query = uri.getRawQuery();
        if (query == null) return url;
        List<String> parts = new ArrayList<>();
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            if (!decode(equals < 0 ? part : part.substring(0, equals)).equals(name)) parts.add(part);
        }
        int question = url.indexOf('?');
        String fragment = uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment();
        return url.substring(0, question) + (parts.isEmpty() ? "" : "?" + String.join("&", parts)) + fragment;
    }

    private static String append(String url, String field) {
        int hash = url.indexOf('#');
        String base = hash < 0 ? url : url.substring(0, hash);
        return base + (base.contains("?") ? "&" : "?") + field + (hash < 0 ? "" : url.substring(hash));
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8.name()); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
