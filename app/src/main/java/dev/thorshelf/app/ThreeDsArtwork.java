package dev.thorshelf.app;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves public box art using a game's exact product code, never its display title. */
final class ThreeDsArtwork {
    private static final Pattern PRODUCT = Pattern.compile("(?:CTR|KTR)-(?:[PN]-)?([A-Z0-9]{4})");
    private static final String COVERS = "https://art.gametdb.com/3ds/coverM/";

    private ThreeDsArtwork() { }

    static String url(String category, String region, String productCode) {
        // Hacks and translations can reuse another game's product code. Do not assign the
        // original game's cover to those titles, updates, DLC or DSiWare.
        if (!"games".equalsIgnoreCase(category) || productCode == null) return "";
        Matcher product = PRODUCT.matcher(productCode.trim().toUpperCase(Locale.ROOT));
        if (!product.matches()) return "";
        String code = product.group(1);
        String coverRegion = coverRegion(region, code);
        return coverRegion.isEmpty() ? "" : COVERS + coverRegion + "/" + code + ".jpg";
    }

    private static String coverRegion(String region, String productCode) {
        String location = region == null ? "" : region.trim().toLowerCase(Locale.ROOT).replace('-', ' ');
        // Country editions use GameTDB's matching cover language, instead of assuming every
        // European release has an English cover. This is artwork selection, not game language.
        switch (location) {
            case "france": return "FR";
            case "germany": return "DE";
            case "italy": return "IT";
            case "spain": return "ES";
            case "netherlands": case "the netherlands": return "NL";
            case "portugal": return "PT";
            case "russia": return "RU";
        }
        List<String> regions = CatalogRegions.fromMetadata(region);
        if (regions.size() == 1) {
            switch (regions.get(0)) {
                case "north-america": return "US";
                case "europe": return "EN";
                case "japan": return "JA";
                case "korea": return "KO";
                case "china": return "ZHCN";
                case "taiwan": return "ZHTW";
                case "australia": return "AU";
                case "world": return "US";
            }
        }
        // Some public detail pages omit the region. The final product-code character identifies
        // the cover edition for these common codes; unknown editions retain their local fallback.
        switch (productCode.charAt(3)) {
            case 'E': case 'A': return "US";
            case 'P': return "EN";
            case 'J': return "JA";
            case 'K': return "KO";
            case 'C': return "ZHCN";
            case 'T': return "ZHTW";
            case 'F': return "FR";
            case 'D': return "DE";
            case 'I': return "IT";
            case 'S': return "ES";
            case 'H': return "NL";
            default: return "";
        }
    }
}
