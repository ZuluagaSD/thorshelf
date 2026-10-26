package dev.thorshelf.app;

import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class DownloadStoreFilenameTest {
    @Test public void uniqueDestinationSurvivesPersistedNameValidation() throws Exception {
        for (String proposed : new String[]{"game.cia", "x".repeat(300) + ".cia",
                "遊戯🎮".repeat(100) + ".cia", "🎮".repeat(100) + ".zip",
                "a." + "x".repeat(90), "../../game.cia"}) {
            String name = uniqueFilename(DownloadPolicy.safeFilename(proposed));
            assertEquals(name, DownloadPolicy.safeFilename(name));
            assertTrue(name.length() <= 100);
            assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= 180);
            assertEquals(name, new String(name.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            if (proposed.endsWith(".cia")) assertTrue(name.endsWith(".cia"));
            if (proposed.endsWith(".zip")) assertTrue(name.endsWith(".zip"));
        }
    }

    @Test public void repeatedDownloadsGetDifferentDestinations() throws Exception {
        assertNotEquals(uniqueFilename("game.cia"), uniqueFilename("game.cia"));
    }

    private static String uniqueFilename(String name) throws Exception {
        Method method = DownloadStore.class.getDeclaredMethod("uniqueFilename", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, name);
    }
}
