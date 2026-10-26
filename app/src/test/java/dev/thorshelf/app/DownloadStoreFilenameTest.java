package dev.thorshelf.app;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class DownloadStoreFilenameTest {
    @Test public void uniqueDestinationSurvivesPersistedNameValidation() {
        for (String proposed : new String[]{"game.cia", "x".repeat(300) + ".cia",
                "遊戯🎮".repeat(100) + ".cia", "🎮".repeat(100) + ".zip",
                "a." + "x".repeat(90), "../../game.cia"}) {
            List<String> allocated = new ArrayList<>();
            for (int copy = 0; copy < 12; copy++) {
                String name = DownloadStore.uniqueFilename(proposed, allocated);
                assertFalse(allocated.contains(name));
                allocated.add(name);
                assertEquals(name, DownloadPolicy.safeFilename(name));
                assertTrue(name.length() <= 100);
                assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= 180);
                assertEquals(name, new String(name.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
                if (proposed.endsWith(".cia")) assertTrue(name.endsWith(".cia"));
                if (proposed.endsWith(".zip")) assertTrue(name.endsWith(".zip"));
            }
        }
    }

    @Test public void firstDownloadHasNoSuffixAndDuplicatesUseReadableNumbers() {
        assertEquals("game.cia", DownloadStore.uniqueFilename("game.cia", Collections.emptyList()));
        assertEquals("game (2).cia", DownloadStore.uniqueFilename("game.cia", Collections.singletonList("game.cia")));
        assertEquals("game (3).cia", DownloadStore.uniqueFilename("game.cia", Arrays.asList("game.cia", "game (2).cia")));
    }

    @Test public void collisionsAreCaseInsensitiveAndDifferentExtensionsRemainDistinct() {
        assertEquals("Game (3).cia", DownloadStore.uniqueFilename("Game.cia",
                Arrays.asList("GAME.CIA", "game (2).CIA", "Game (3).3ds")));
        assertEquals("Game.3ds", DownloadStore.uniqueFilename("Game.3ds", Collections.singletonList("Game.cia")));
    }
}
