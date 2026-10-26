package dev.thorshelf.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PreparationTextTest {
    @Test public void summarizesCurrentGameAndPackageSuccessMessages() {
        assertEquals("Pokémon Omega Ruby (World)", PreparationText.summary(
                "Saved Pokémon Omega Ruby (World).3ds. Refresh Azahar's game list to see it. "
                        + "Add this folder to Azahar's Games Folder once if needed.", true));
        assertEquals("Game Update (v1.2) (USA)", PreparationText.summary(
                "Saved Game Update (v1.2) (USA).cia. Use Install CIA in Azahar to add this prepared package.", true));
    }

    @Test public void cleansLegacyPreparedNames() {
        assertEquals("Pokemon Omega Ruby (World)", PreparationText.summary(
                "Ready for Azahar: 000400000011C400 Pokemon Omega Ruby (CTR-P-ECRA) (v3.3.0) "
                        + "(W).legit-a3bfbe6f17f0 - Azahar.cia. In Azahar, choose Install CIA and select this file.", true));
    }

    @Test public void keepsPeriodsInsideNamesAndVersions() {
        assertEquals("Dr. Mario - Miracle Cure (USA)", PreparationText.summary(
                "Saved Dr. Mario - Miracle Cure (USA).3ds. Refresh Azahar's game list to see it.", true));
        assertEquals("Dr. Game Update (v1.2.3)", PreparationText.summary(
                "Saved Dr. Game Update (v1.2.3).cia. Use Install CIA in Azahar to add this prepared package.", true));
    }

    @Test public void preservesErrorsProgressAndAnyNonReadyMessageExactly() {
        String error = "Preparation failed. The original download is safe; try again.";
        assertEquals(error, PreparationText.summary(error, false));
        assertEquals(error, PreparationText.summary(error, true));
        String progress = "Saving game to folder · 94%";
        assertEquals(progress, PreparationText.summary(progress, false));
        String saved = "Saved Game.cia. Use Install CIA in Azahar to add this prepared package.";
        assertEquals(saved, PreparationText.summary(saved, false));
    }

    @Test public void preservesUnrecognizedAndIncompleteReadyMessages() {
        for (String status : new String[] {"", "Ready: Game.cia", "Saved Game.cia.",
                "Saved . Use Install CIA in Azahar.", "Ready for Azahar: Game.cia. Read the documentation."}) {
            assertEquals(status, PreparationText.summary(status, true));
        }
        assertNull(PreparationText.summary(null, true));
    }
}
