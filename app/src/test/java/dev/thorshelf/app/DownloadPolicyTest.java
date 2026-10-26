package dev.thorshelf.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DownloadPolicyTest {
    @Test public void allowsOnlyHttpsCatalogAndNumberedDownloadHosts() {
        assertTrue(DownloadPolicy.isCatalog("https://hshop.erista.me/t/1"));
        assertTrue(DownloadPolicy.isAllowedDownload("https://download2.erista.me/path?token=a%2Fb"));
        assertTrue(DownloadPolicy.isAllowedDownload("https://download12.erista.me:443/path"));
        for (String url : new String[] { "http://hshop.erista.me/t/1", "javascript:alert(1)",
                "file:///sdcard/game.cia", "https://hshop.erista.me.evil.example/game.cia",
                "https://hshop.erista.me@evil.example/game.cia", "https://evil.example@hshop.erista.me/x",
                "https://download2.erista.me:8443/a", "https://evil.erista.me/a", "https://download2.erista.me./a",
                "https://download2.erista.me/a\nHeader:x", "https://hshop.erista.me\\@evil.example/a" }) {
            assertFalse(url, DownloadPolicy.isAllowedDownload(url));
        }
        assertFalse(DownloadPolicy.isCatalog("https://download2.erista.me/x"));
        assertFalse(DownloadPolicy.isAllowedDownload(null));
    }

    @Test public void filenamesCannotEscapeDestinationOrHideExtensions() {
        assertEquals("_.._game.cia", DownloadPolicy.safeFilename("../../game.cia"));
        assertEquals("game_3ds.cia", DownloadPolicy.safeFilename("game\n3ds.cia"));
        assertEquals("game_cia.exe", DownloadPolicy.safeFilename("game\u202Ecia.exe"));
        assertEquals("download.cia", DownloadPolicy.safeFilename(".."));
        String longName = "x".repeat(300) + ".cia";
        assertTrue(DownloadPolicy.safeFilename(longName).length() <= 100);
        assertTrue(DownloadPolicy.safeFilename(longName).endsWith(".cia"));
        String unicodeName = DownloadPolicy.safeFilename("遊戯🎮".repeat(100) + ".cia");
        assertTrue(unicodeName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 180);
        assertTrue(unicodeName.endsWith(".cia"));
        assertFalse(Character.isHighSurrogate(unicodeName.charAt(unicodeName.length() - 5)));
        assertFalse(DownloadPolicy.isContentFile("game.cia.exe"));
        assertFalse(DownloadPolicy.isContentFile("error.html"));
    }

    @Test public void searchCannotInjectAdditionalParameters() {
        String url = DownloadPolicy.searchUrl("  A & B? # + é  ");
        assertEquals("https://hshop.erista.me/search/results?q=A+%26+B%3F+%23+%2B+%C3%A9&qt=Text&lgy=false", url);
    }
}
