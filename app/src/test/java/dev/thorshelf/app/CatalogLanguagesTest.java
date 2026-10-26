package dev.thorshelf.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class CatalogLanguagesTest {
    @Test public void exposesStableCanonicalCodesAndReadableNames() {
        assertTrue(CatalogLanguages.codes().containsAll(Arrays.asList("en", "fr", "de", "es", "it", "ja", "ko", "zh", "nl", "pt", "ru")));
        assertFalse(CatalogLanguages.codes().contains(""));
        assertEquals("en", CatalogLanguages.normalize(" EN "));
        for (String unknown : new String[]{null, "", "all", "unknown", "xx", "English", "en-US"}) {
            assertEquals("", CatalogLanguages.normalize(unknown));
        }
        assertEquals("English", CatalogLanguages.label("EN"));
        assertEquals("French", CatalogLanguages.label("fr"));
        assertEquals("All languages", CatalogLanguages.label(""));
        assertThrows(UnsupportedOperationException.class, () -> CatalogLanguages.codes().add("xx"));
    }

    @Test public void parsesExplicitListsAndFullNamesWithoutMatchingRegionsOrTitleWords() {
        assertEquals(Arrays.asList("en", "fr", "de"), CatalogLanguages.parse("En,Fr,De"));
        assertEquals(Arrays.asList("en", "fr", "it"), CatalogLanguages.parse("Languages: English, French and Italian"));
        assertEquals(Arrays.asList("en", "fr", "nl"), CatalogLanguages.parse("(Europe) (En,Fr,Nl) (Rev 1)"));
        assertEquals(Arrays.asList("ja", "ko", "zh"), CatalogLanguages.parse("Japanese / Korean / Traditional Chinese"));
        assertEquals(Collections.singletonList("zh"), CatalogLanguages.parse("Chinese (Simplified)"));
        assertEquals(Arrays.asList("en", "fr"), CatalogLanguages.parse("EN;fr;En;FR"));
        assertEquals(Arrays.asList("en", "no", "sv"), CatalogLanguages.parse("en,nb,sv,no"));
        for (String unknown : new String[]{null, "", "Europe", "France", "USA", "World", "French Alps",
                "English Training (Europe).nds", "Made in Japan", "(Europe) (Rev 1)", "En,unknown"}) {
            assertTrue(String.valueOf(unknown), CatalogLanguages.parse(unknown).isEmpty());
        }
    }

    @Test public void dsExplicitLanguagesWinAndLocalizedEditionsRemainConservative() {
        assertEquals(Arrays.asList("en", "fr", "de", "es", "it"), CatalogLanguages.nds(
                "(Europe) (En,Fr,De,Es,It)", "Animal Crossing (Europe).nds", "ADMP"));
        assertEquals(Arrays.asList("en", "fr"), CatalogLanguages.nds(
                "(France)", "Game (France) (En,Fr).nds", "ABCF"));
        assertEquals(Collections.singletonList("fr"), CatalogLanguages.nds(
                "(France)", "Pokemon Version Blanche (France).nds", "IRAF"));
        assertEquals(Collections.singletonList("ja"), CatalogLanguages.nds("(Japan)", "Game.nds", "ABCJ"));
        assertEquals(Collections.singletonList("de"), CatalogLanguages.nds("", "Game (Germany).nds", "ABCD"));
        assertEquals(Collections.singletonList("es"), CatalogLanguages.nds("(Spain)", "Game.nds", "ABCS"));
        assertEquals(Collections.singletonList("it"), CatalogLanguages.nds("(Italy)", "Game.nds", "ABCI"));
        for (String version : new String[]{null, "", "(USA)", "(Europe)", "(World)", "(Australia)"}) {
            assertTrue(String.valueOf(version), CatalogLanguages.nds(version, "Game.nds", "AC9E").isEmpty());
        }
        assertTrue(CatalogLanguages.nds("(France)", "Game (Japan).nds", "IRAF").isEmpty());
        assertTrue(CatalogLanguages.nds("(France) (Japan)", "Game (France).nds", "IRAF").isEmpty());
    }

    @Test public void entryLanguagesAreNormalizedImmutableAndOptionalForLegacyCallers() {
        List<String> metadata = new ArrayList<>(Arrays.asList("EN", "fr", "en", "xx", null));
        CatalogPage.Entry entry = new CatalogPage.Entry("nds://catalog/", "Game", "Nintendo DS", "Europe", "", "", "", "", metadata);
        metadata.clear();
        assertEquals(Arrays.asList("en", "fr"), entry.languages);
        assertThrows(UnsupportedOperationException.class, () -> entry.languages.add("de"));
        assertTrue(new CatalogPage.Entry("", "", "", "", "", "", "").languages.isEmpty());
        assertTrue(new CatalogPage.Entry("", "", "", "", "", "", "", "").languages.isEmpty());
        assertTrue(new CatalogPage.Entry("", "", "", "", "", "", "", "", null).languages.isEmpty());
    }
}
