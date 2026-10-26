package dev.thorshelf.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class NdsCatalogLanguagesTest {
    @Test public void explicitSourceLanguagesOverrideAnySupplementOrCountryInference() {
        assertEquals(Arrays.asList("en", "fr", "de"), resolve("Adventure (Japan)", "En,Fr,De"));
        assertEquals(Collections.singletonList("fr"), resolve(
                "Pokemon - White Version (USA, Europe) (NDSi Enhanced)", "Fr"));
        assertTrue(resolve("Pokemon - White Version (USA, Europe) (NDSi Enhanced)", "En,Unknown").isEmpty());
    }

    @Test public void explicitFilenameLanguageGroupsRemainAvailableWithoutSourceField() {
        assertEquals(Arrays.asList("en", "fr", "de", "es", "it"), resolve(
                "Adventure (Europe) (En,Fr,De,Es,It)", ""));
        assertEquals(Collections.singletonList("es"), resolve("Language learning (USA) (Es)", null));
    }

    @Test public void originalAndSequelPokemonEnglishEditionsAreVerifiedIndependently() {
        for (String title : new String[] {"Pokemon - Black Version", "Pokemon - White Version",
                "Pokemon - Black Version 2", "Pokemon - White Version 2"}) {
            assertEquals(title, Collections.singletonList("en"), resolve(
                    title + " (USA, Europe) (NDSi Enhanced)", ""));
            assertTrue(title + " must not lend its languages to an unverified revision", resolve(
                    title + " (USA, Europe) (NDSi Enhanced) (Rev 99)", "").isEmpty());
        }
    }

    @Test public void bundledSupplementCoversUnrelatedExactUsAndEuropeanEditions() {
        assertNotNull("The offline metadata resource must ship with the application",
                NdsCatalogLanguages.class.getResource("/nds-languages.tsv"));
        assertEquals(Collections.singletonList("en"), resolve(
                "Ace Attorney Investigations - Miles Edgeworth (Europe)", ""));
        assertEquals(Collections.singletonList("en"), resolve("Apollo Justice - Ace Attorney (USA)", ""));
        assertEquals(Collections.singletonList("en"), resolve("Super Mario 64 DS (USA)", ""));
        assertEquals(Arrays.asList("en", "fr", "es"), resolve("Club Penguin - Elite Penguin Force (USA)", ""));
    }

    @Test public void frenchPokemonEditionNeverBorrowsLanguagesFromEnglishEditionOrTranslatedDescriptions() {
        assertEquals(Collections.singletonList("fr"), resolve(
                "Pokemon - Version Blanche (France) (NDSi Enhanced)", ""));
        assertEquals(Collections.singletonList("fr"), resolve(
                "Pokemon - Version Noire (France) (NDSi Enhanced)", ""));
    }

    @Test public void japaneseCountryEditionIsNotExpandedToEnglishBySupplementalMetadata() {
        // GameTDB's AJUJ record lists EN,JA; the source index has no explicit languages.
        assertEquals(Collections.singletonList("ja"), resolve("Jump Ultimate Stars (Japan)", ""));
        assertEquals(Collections.singletonList("ja"), resolve("Super Mario 64 DS (Japan)", ""));
        // An explicit source list is still stronger evidence than a country-edition tag.
        assertEquals(Arrays.asList("en", "ja"), resolve("Super Mario 64 DS (Japan)", "En,Ja"));
        assertEquals(Collections.singletonList("fr"), resolve("Uncatalogued adventure (France)", ""));
    }

    @Test public void unknownBroadRegionsAndSimilarTitlesRemainUnknown() {
        for (String region : new String[] {"USA", "Europe", "World", "USA, Europe"}) {
            assertTrue(resolve("Uncatalogued adventure (" + region + ")", "").isEmpty());
        }
        assertTrue(resolve("Pokemon - White Version Deluxe (USA, Europe) (NDSi Enhanced)", "").isEmpty());
        assertTrue(resolve("Pokemon - White Version (USA, Europe) (NDSi Enhanced) (Beta)", "").isEmpty());
    }

    @Test public void conservativeSingleLocaleFallbackStillWorksForUncataloguedEditions() {
        assertEquals(Collections.singletonList("fr"), resolve("Uncatalogued adventure (France)", ""));
        assertEquals(Collections.singletonList("ja"), resolve("Uncatalogued adventure (Japan)", ""));
        assertTrue(resolve("Uncatalogued adventure (France) (Japan)", "").isEmpty());
        assertTrue(NdsCatalogLanguages.resolve(null, null, null, null).isEmpty());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void supportedLanguagesCannotBeMutatedByCallers() {
        resolve("Ace Attorney Investigations - Miles Edgeworth (Europe)", "").add("fr");
    }

    private static List<String> resolve(String name, String languages) {
        return NdsCatalogLanguages.resolve(name.replaceFirst(" \\(.*", ""), name, languages,
                Collections.emptyList());
    }
}
