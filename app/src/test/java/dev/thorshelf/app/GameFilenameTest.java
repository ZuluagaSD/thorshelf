package dev.thorshelf.app;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class GameFilenameTest {
    @Test public void cleansOriginalAndPreviouslyPreparedCatalogNames() {
        String original = "000400000011C400 Pokemon Omega Ruby (CTR-P-ECRA) (v3.3.0) (W).legit";
        assertEquals("Pokemon Omega Ruby (World).cia", GameFilename.clean(original + ".cia"));
        assertEquals("Pokemon Omega Ruby (World).cia",
                GameFilename.clean(original + "-a3bfbe6f17f0 - Azahar.cia"));
        assertEquals("Professor Layton and the Miracle Mask (USA).cia", GameFilename.clean(
                "00040000000A8500 Professor Layton and the Miracle Mask (CTR-P-AKKE) "
                        + "(v0.2.0) (U).piratelegit.cia"));
    }

    @Test public void keepsUnicodePunctuationAndUnknownEditionMetadata() {
        assertEquals("Pokémon + Friends — Let's Go! (Special Edition) (Japan).3ds", GameFilename.clean(
                "000400000011C400 Pokémon + Friends — Let's Go! (Special Edition) "
                        + "(CTR-P-ECRA) (v3.3.0) (J).legit.3ds"));
        assertEquals("B-side (Revision 2) (Beta) (v1.0).cia",
                GameFilename.clean("B-side (Revision 2) (Beta) (v1.0).cia"));
        assertEquals("Pokemon.cia", GameFilename.clean("Pokemon.cia"));
        assertEquals("Plan (A) — Special Edition.cia", GameFilename.clean("Plan (A) — Special Edition.cia"));
    }

    @Test public void makesRegionsReadableAndPreservesExistingRegionNames() {
        String[][] regions = {{"U", "USA"}, {"E", "Europe"}, {"J", "Japan"}, {"W", "World"},
                {"K", "Korea"}, {"C", "China"}, {"T", "Taiwan"}, {"A", "Australia"}};
        for (String[] region : regions) {
            String expected = "Game (" + region[1] + ").cia";
            assertEquals(expected, GameFilename.clean("Game (" + region[0] + ").cia"));
            assertEquals(expected, GameFilename.clean(expected));
        }
        assertEquals("Game (Unknown Region).cia", GameFilename.clean("Game (Unknown Region).cia"));
    }

    @Test public void updateAndDlcRemainDistinctAndKeepVersions() {
        assertEquals("Game (v1.2.0) (Europe) (Update).cia", GameFilename.clean(
                "0004000E0011C400 Game (CTR-U-ECRA) (v1.2.0) (E).legit.cia"));
        assertEquals("Game (v2.0) (USA) (DLC).cia", GameFilename.clean(
                "0004008c0011c400 Game (CTR-M-ECRA) (v2.0) (U).piratelegit.cia"));
        assertEquals("Game Update (v1.2) (Europe).cia", GameFilename.clean(
                "0004000E0011C400 Game Update (CTR-U-ECRA) (v1.2) (E).legit.cia"));
        assertEquals("Game - DLC (v2) (USA).cia", GameFilename.clean(
                "0004008C0011C400 Game - DLC (v2) (U).legit.cia"));
        assertEquals("Game Update (v1.2) (USA).cia", GameFilename.clean(
                "Game Update (CTR-U-ECRA) (v1.2) (U).legit.cia"));
    }

    @Test public void avoidsTreatingOtherTitleCategoriesAsBaseGames() {
        assertEquals("Game Demo (v1.2) (USA).cia", GameFilename.clean(
                "000400020011C400 Game Demo (CTR-N-ECRA) (v1.2) (U).legit.cia"));
        assertEquals("0123456789abcdef Game (v1.2).cia",
                GameFilename.clean("0123456789abcdef Game (v1.2).cia"));
    }

    @Test public void removesLegacyRandomSuffixOnlyWithCatalogEvidence() {
        assertEquals("My Game-123456789abc.cia", GameFilename.clean("My Game-123456789abc.cia"));
        assertEquals("My Game-123456789abc.cia",
                GameFilename.clean("My Game-123456789abc - Azahar.cia"));
        assertEquals("Game.cia", GameFilename.clean("Game.legit-123456789abc.cia"));
        assertEquals("Game.cia", GameFilename.clean("000400000011C400 Game-123456789abc.cia"));
        assertEquals("Game.cia", GameFilename.clean("Game (CTR-P-ECRA)-123456789abc.cia"));
        assertEquals("Game-1234.cia", GameFilename.clean("Game-1234.cia"));
    }

    @Test public void cleansPackagingFragmentsTruncatedByTheOldRandomFilenameScheme() {
        String oldName = DownloadPolicy.safeFilename("00040000000A8500 Professor Layton and the Miracle Mask "
                + "(CTR-P-AKKE) (v0.2.0) (U).piratelegit.cia");
        String oldStem = oldName.substring(0, oldName.length() - 4);
        while (oldStem.length() > 83 || oldStem.getBytes(StandardCharsets.UTF_8).length > 163) {
            oldStem = oldStem.substring(0, oldStem.offsetByCodePoints(oldStem.length(), -1));
        }
        assertTrue(oldStem.endsWith(".pi"));
        assertEquals("Professor Layton and the Miracle Mask (USA).cia",
                GameFilename.clean(oldStem + "-123456789abc.cia"));
        assertEquals("Professor Layton and the Miracle Mask (USA).3ds",
                GameFilename.withExtension(oldStem + "-123456789abc - Azahar.cia", "3ds"));
        assertEquals("Game.pi-123456789abc.cia", GameFilename.clean("Game.pi-123456789abc.cia"));
        assertEquals("Game.pi.cia", GameFilename.clean("Game.pi.cia"));
    }

    @Test public void keepsActualExtensionAndOnlyRemovesTerminalPackagingTags() {
        for (String extension : new String[] {"cia", "CCI", "cxi", "3ds", "3dsx", "nds", "zip", "7z"}) {
            assertEquals("Game (World)." + extension,
                    GameFilename.clean("Game (W).piratelegit." + extension));
        }
        assertEquals("The.legit.Adventure.cia", GameFilename.clean("The.legit.Adventure.cia"));
        assertEquals("Game.cia.zip", GameFilename.clean("Game.cia.zip"));
    }

    @Test public void cleansBeforeTruncationSoMetadataDoesNotConsumeTheTitleBudget() {
        String title = "A".repeat(70);
        assertEquals(title + " (World).cia", GameFilename.clean(
                "000400000011C400 " + title + " (CTR-P-ECRA) (v3.3.0) (W).legit.cia"));
    }

    @Test public void longTitlesKeepTheirRegionAndExtraContentVersion() {
        String update = GameFilename.clean("0004000E0011C400 " + "遊戯🎮".repeat(100)
                + " (CTR-U-ECRA) (v1.2.0) (E).legit.cia");
        assertTrue(update.endsWith(" (v1.2.0) (Europe) (Update).cia"));
        assertEquals(update, GameFilename.clean(update));
        assertSafe(update);
        String numbered = GameFilename.numbered(update, 2);
        assertTrue(numbered.endsWith(" (v1.2.0) (Europe) (Update) (2).cia"));
        assertSafe(numbered);
        String changed = GameFilename.withExtension(update, "3dsx");
        assertTrue(changed.endsWith(" (v1.2.0) (Europe) (Update).3dsx"));
        assertSafe(changed);
    }

    @Test public void namesAreSafeBoundedAndIdempotent() {
        for (String input : new String[] {null, "", "../../Game.cia", "Game\u202e.cia", "Game\n.cia",
                "遊戯🎮".repeat(100) + ".cia", "🎮".repeat(100) + ".zip",
                "000400000011C400 Pokemon (CTR-P-ECRA) (v3.3.0) (W).legit-123456789abc - Azahar.cia",
                "0004000E0011C400 Game (v1.2) (U).legit.cia"}) {
            String name = GameFilename.clean(input);
            assertEquals(name, GameFilename.clean(name));
            assertSafe(name);
        }
    }

    @Test public void changesExtensionWithoutAppendingItToTheOldOne() {
        assertEquals("Pokemon Omega Ruby (World).3ds", GameFilename.withExtension(
                "000400000011C400 Pokemon Omega Ruby (CTR-P-ECRA) (v3.3.0) (W).legit.cia", "3ds"));
        assertEquals("Game (Update).cia", GameFilename.withExtension("Game (Update).3ds", "cia"));
        String longName = GameFilename.withExtension("遊戯🎮".repeat(100) + ".cia", "3dsx");
        assertTrue(longName.endsWith(".3dsx"));
        assertSafe(longName);
        for (String invalid : new String[] {null, "", ".cia", "../cia", "cia/zip", "0123456789"}) {
            assertThrows(IllegalArgumentException.class, () -> GameFilename.withExtension("Game.cia", invalid));
        }
    }

    @Test public void numberedCopiesRemainReadableAndBoundedWithoutLosingTheirSuffix() {
        assertEquals("Game.cia", GameFilename.numbered("Game.cia", 1));
        assertEquals("Game (2).cia", GameFilename.numbered("Game.cia", 2));
        assertEquals("Game (World) (20).3ds", GameFilename.numbered("Game (World).3ds", 20));
        for (String name : new String[] {"x".repeat(300) + ".cia", "遊戯🎮".repeat(100) + ".cia",
                "🎮".repeat(100) + ".zip", "Game"}) {
            String numbered = GameFilename.numbered(name, Integer.MAX_VALUE);
            assertTrue(numbered.contains(" (2147483647)"));
            assertSafe(numbered);
        }
        assertThrows(IllegalArgumentException.class, () -> GameFilename.numbered("Game.cia", 0));
    }

    private static void assertSafe(String name) {
        assertEquals(name, DownloadPolicy.safeFilename(name));
        assertTrue(name.length() <= 100);
        assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= 180);
        assertEquals(name, new String(name.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }
}
