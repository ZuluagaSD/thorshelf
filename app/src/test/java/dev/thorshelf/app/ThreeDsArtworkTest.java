package dev.thorshelf.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class ThreeDsArtworkTest {
    @Test public void usesExactCodesForDifferentGamesAndRegionalEditions() {
        assertEquals(cover("US", "BRME"), ThreeDsArtwork.url("games", "north-america", "CTR-P-BRME"));
        assertEquals(cover("EN", "BRMP"), ThreeDsArtwork.url("games", "europe", "CTR-P-BRMP"));
        assertEquals(cover("JA", "BRMJ"), ThreeDsArtwork.url("games", "japan", "CTR-P-BRMJ"));
        assertEquals(cover("KO", "BRMK"), ThreeDsArtwork.url("games", "korea", "CTR-P-BRMK"));
        assertEquals(cover("US", "AMKE"), ThreeDsArtwork.url("games", "north-america", "CTR-P-AMKE"));
        assertEquals(cover("US", "ECRA"), ThreeDsArtwork.url("games", "world", "CTR-P-ECRA"));
        assertEquals(cover("FR", "AKKF"), ThreeDsArtwork.url("Games", "France", "CTR-P-AKKF"));
        assertEquals(cover("US", "AJ1E"), ThreeDsArtwork.url("games", "north-america", "KTR-N-AJ1E"));
        assertEquals(cover("US", "BRME"), ThreeDsArtwork.url("games", "", " ctr-brme "));
    }

    @Test public void keepsCountryCoversSeparateFromTheLanguageFilter() {
        String[][] regions = {{"Germany", "DE"}, {"Italy", "IT"}, {"Spain", "ES"},
                {"The Netherlands", "NL"}, {"Russia", "RU"}, {"The United Kingdom", "EN"},
                {"Canada", "US"}, {"Australia", "AU"}, {"Korea", "KO"},
                {"China", "ZHCN"}, {"Taiwan", "ZHTW"}};
        for (String[] region : regions) {
            String url = ThreeDsArtwork.url("games", region[0], "CTR-P-ABCA");
            assertEquals(region[0], cover(region[1], "ABCA"), url);
            assertTrue(ArtworkPolicy.isAllowed(url));
        }
    }

    @Test public void neverGuessesArtworkFromModifiedContentOrMalformedIdentifiers() {
        for (String category : new String[] {null, "", "extras", "updates", "dlc", "dsiware"}) {
            assertEquals("", ThreeDsArtwork.url(category, "north-america", "CTR-P-BRME"));
        }
        for (String product : new String[] {null, "", "BRME", "CTR-P-BRM", "CTR-P-BRMEE", "CTR-U-BRME",
                "TWL-P-BRME", "CTR-P-../E", "CTR-P-BRME?x=1", "CTR-P-BRME/", "CTR-P-BRMÉ",
                "https://evil.example/BRME.jpg"}) {
            assertEquals(String.valueOf(product), "", ThreeDsArtwork.url("games", "north-america", product));
        }
        assertEquals("", ThreeDsArtwork.url("games", "unknown", "CTR-P-ABCQ"));
    }

    private static String cover(String region, String code) {
        return "https://art.gametdb.com/3ds/coverM/" + region + "/" + code + ".jpg";
    }
}
