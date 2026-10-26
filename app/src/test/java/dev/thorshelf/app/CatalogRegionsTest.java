package dev.thorshelf.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class CatalogRegionsTest {
    @Test public void exposesCanonicalRegionCodesAndReadableLabels() {
        assertEquals(Arrays.asList("north-america", "europe", "japan", "korea", "china", "taiwan", "australia", "world"), CatalogRegions.codes());
        assertEquals("europe", CatalogRegions.normalize("EUROPE"));
        assertEquals("north-america", CatalogRegions.normalize("North-America"));
        for (String noncanonical : new String[]{null, "", "all", "France", "USA", "North America", "unknown"}) {
            assertEquals("", CatalogRegions.normalize(noncanonical));
        }
        assertEquals("All regions", CatalogRegions.label(""));
        assertEquals("North America", CatalogRegions.label("north-america"));
        assertEquals("Europe", CatalogRegions.label("europe"));
        assertThrows(UnsupportedOperationException.class, () -> CatalogRegions.codes().add("unknown"));
    }

    @Test public void groupsEuropeanCountryEditionsWithoutInferringTheirLanguage() {
        for (String edition : new String[]{"Europe", "europe", "EUR", "France", "Germany", "Spain", "Italy", "UK",
                "United Kingdom", "the-united-kingdom", "Netherlands", "the-netherlands", "Russia", "Portugal"}) {
            assertTrue(edition, CatalogRegions.matches(edition, "europe"));
            assertFalse(edition, CatalogRegions.matches(edition, "north-america"));
        }
        assertEquals(Collections.singletonList("europe"), CatalogRegions.fromMetadata("(France) (Fr)"));
        assertFalse(CatalogRegions.matches("French", "europe"));
        assertFalse(CatalogRegions.matches("English", "north-america"));
    }

    @Test public void groupsNorthAmericanAliasesAndKeepsOtherRegionsDistinct() {
        for (String edition : new String[]{"north-america", "North America", "USA", "US", "United States", "Canada"}) {
            assertTrue(edition, CatalogRegions.matches(edition, "north-america"));
            assertFalse(edition, CatalogRegions.matches(edition, "europe"));
        }
        assertTrue(CatalogRegions.matches("Japan", "japan"));
        assertTrue(CatalogRegions.matches("South Korea", "korea"));
        assertTrue(CatalogRegions.matches("China", "china"));
        assertTrue(CatalogRegions.matches("Taiwan", "taiwan"));
        assertFalse(CatalogRegions.matches("Taiwan", "china"));
        assertTrue(CatalogRegions.matches("Australia", "australia"));
    }

    @Test public void worldMatchesOnlyWorldAndUnknownIsExcludedBySpecificSelections() {
        for (String world : new String[]{"World", "Region Free", "region-free", "All regions"}) {
            assertTrue(world, CatalogRegions.matches(world, "world"));
            for (String code : CatalogRegions.codes()) {
                if (!code.equals("world")) assertFalse(world + ":" + code, CatalogRegions.matches(world, code));
            }
        }
        for (String unknown : new String[]{null, "", "unknown", "other", "digital", "kiosk-demos", "demos", "European adventure"}) {
            assertTrue(CatalogRegions.matches(unknown, ""));
            for (String code : CatalogRegions.codes()) assertFalse(String.valueOf(unknown), CatalogRegions.matches(unknown, code));
        }
        assertFalse(CatalogRegions.matches("Europe", "France"));
    }

    @Test public void recognizedMultiRegionMetadataMatchesEachExplicitRegionOnly() {
        assertEquals(Arrays.asList("north-america", "europe"), CatalogRegions.fromMetadata("USA, Europe"));
        assertEquals(Arrays.asList("north-america", "europe"), CatalogRegions.fromMetadata("(USA) (France) (En,Fr)"));
        assertEquals(Collections.singletonList("europe"), CatalogRegions.fromMetadata("France / Germany"));
        assertTrue(CatalogRegions.matches("USA, Europe", "north-america"));
        assertTrue(CatalogRegions.matches("USA, Europe", "europe"));
        assertFalse(CatalogRegions.matches("USA, Europe", "world"));
        assertFalse(CatalogRegions.matches("USA, Europe", "japan"));
        assertTrue(CatalogRegions.fromMetadata("USA, Unknown").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> CatalogRegions.fromMetadata("USA, Europe").add("japan"));
    }
}
