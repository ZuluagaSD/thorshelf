package dev.thorshelf.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class GbaDownloadPolicyTest {
    private static final String ITEM = "CentralArquivista-GameBoyAdvance";
    private static final String ARCHIVE = "https://archive.org/download/" + ITEM + "/";
    private static final String MIRROR = "https://ia800603.us.archive.org/2/items/" + ITEM + "/";
    private static final String NDS_ITEM = "pack-roms-nintendo-ds-eu-usa-jap-rabbits-games";
    private static final String BINARY = "application/octet-stream";

    @Test public void pinnedRawCartridgeWorksAcrossKnownStorageHostPatterns() {
        String filename = "Pok%C3%A9mon%20-%20Emerald%20Version%20(USA%2C%20Europe).gba";
        for (String base : new String[] {ARCHIVE, MIRROR,
                "https://dn710805.ca.archive.org/0/items/" + ITEM + "/",
                "https://ia123456.ca.archive.org/12/items/" + ITEM + "/",
                ARCHIVE.replace("archive.org/", "archive.org:443/")}) {
            assertTrue(base, DownloadPolicy.isAllowedGbaDownload(base + filename));
            assertTrue(base, DownloadPolicy.isAllowedGbaRedirect(ARCHIVE + filename, base + filename));
            assertFalse(base, DownloadPolicy.isAllowedNdsDownload(base + filename));
            assertFalse(base, DownloadPolicy.isAllowedDownload(base + filename));
            assertFalse(base, DownloadPolicy.isCatalog(base + filename));
        }
        assertEquals("Pokémon - Emerald Version (USA, Europe).gba",
                DownloadPolicy.gbaArchiveFilename(ARCHIVE + filename));
        assertTrue(DownloadPolicy.isAllowedGbaRedirect(ARCHIVE + "Pok%C3%A9mon%20%5Bb%5D.gba",
                MIRROR + "Pok%c3%a9mon%20%5bb%5d.gba"));
    }

    @Test public void rejectsOtherItemsPlatformsAndMalformedAssetPaths() {
        for (String url : new String[] {null, "", ARCHIVE,
                ARCHIVE.replace(ITEM, "another-item") + "Game.gba",
                ARCHIVE.replace(ITEM, NDS_ITEM) + "Game.gba",
                ARCHIVE.replace("/download/", "/details/") + "Game.gba",
                ARCHIVE.replace(ITEM, "%43entralArquivista-GameBoyAdvance") + "Game.gba",
                ARCHIVE + "Game.nds", ARCHIVE + "Game.cia", ARCHIVE + "Game.zip", ARCHIVE + "Game.7z",
                ARCHIVE + ".gba", ARCHIVE + "Game.gba/", ARCHIVE + "Game.gba.exe",
                ARCHIVE + "dir/Game.gba", ARCHIVE + "../Game.gba", ARCHIVE + "%2e%2e/Game.gba",
                ARCHIVE + "dir%2fGame.gba", ARCHIVE + "dir%5cGame.gba", ARCHIVE + "Game%2520Name.gba",
                ARCHIVE + "%252e%252e%252fGame.gba", ARCHIVE + "Game%00.gba", ARCHIVE + "Game%E2%80%AE.gba",
                ARCHIVE + "%FF.gba", ARCHIVE + "Game%C0%AF.gba", ARCHIVE + "%20Game.gba",
                ARCHIVE + "Game.gba%20", ARCHIVE + "Game%3Fredirect.gba", ARCHIVE + "Game%23fragment.gba",
                ARCHIVE + "Game%3Bredirect.gba", ARCHIVE + "Game.gba?", ARCHIVE + "Game.gba?download=1",
                ARCHIVE + "Game.gba#fragment", ARCHIVE + "x".repeat(252) + ".gba",
                ARCHIVE + "%C3%A9".repeat(126) + ".gba",
                ARCHIVE.replace("https:", "http:") + "Game.gba",
                ARCHIVE.replace("archive.org/", "archive.org.evil.example/") + "Game.gba",
                ARCHIVE.replace("archive.org/", "user@archive.org/") + "Game.gba",
                ARCHIVE.replace("archive.org/", "archive.org:8443/") + "Game.gba",
                "https://github.com/owner/game/releases/download/v1/Game.gba",
                "https://download2.erista.me/Game.gba",
                MIRROR.replace("ia800603", "ia80060") + "Game.gba",
                MIRROR.replace("ia800603", "node800603") + "Game.gba",
                MIRROR.replace(".us.", ".eu.") + "Game.gba",
                MIRROR.replace(".org/", ".org./") + "Game.gba",
                MIRROR.replace("/2/items/", "/x/items/") + "Game.gba",
                MIRROR.replace("/2/items/", "/2/%69tems/") + "Game.gba"}) {
            assertFalse(String.valueOf(url), DownloadPolicy.isAllowedGbaDownload(url));
            assertNull(String.valueOf(url), DownloadPolicy.gbaArchiveFilename(url));
        }
    }

    @Test public void redirectsCannotChangeTheItemEditionOrPlatformEvenWithSameFilename() {
        String original = ARCHIVE + "Game.gba";
        for (String destination : new String[] {MIRROR + "Other.gba", MIRROR + "game.gba",
                MIRROR.replace(ITEM, "other-item") + "Game.gba",
                MIRROR.replace(ITEM, NDS_ITEM) + "Game.gba",
                MIRROR + "Game.zip", "https://download2.erista.me/Game.gba"}) {
            assertFalse(destination, DownloadPolicy.isAllowedGbaRedirect(original, destination));
        }
        assertFalse(DownloadPolicy.isAllowedNdsRedirect(ARCHIVE + "Game.gba", MIRROR + "Game.gba"));
    }

    @Test public void metadataUsesExactDecodedFilenameBeforeCleaningAndSupportsUnicode() {
        String url = ARCHIVE + "Caf%C3%A9%2BGame%20(Europe).gba";
        assertEquals("Café+Game (Europe).gba", DownloadFilename.resolveGba(url, null, BINARY));
        assertEquals("Café+Game (Europe).gba", DownloadFilename.resolveGba(url,
                "attachment; filename*=UTF-8''Caf%C3%A9%2BGame%20(Europe).gba", "application/x-gba-rom"));
        assertEquals("Game.GBA", DownloadFilename.resolveGba(ARCHIVE + "Game.GBA", null, null));
        assertNull(DownloadFilename.resolveNds(url, null, BINARY));
        assertNull(DownloadFilename.resolve(url, null, BINARY));
        String longStem = "A".repeat(110);
        assertNull(DownloadFilename.resolveGba(ARCHIVE + longStem + "One.gba",
                "attachment; filename=" + longStem + "Two.gba", BINARY));
        assertNull(DownloadFilename.resolveGba(ARCHIVE + "Game%20(U).gba",
                "attachment; filename=Game (USA).gba", BINARY));
    }

    @Test public void metadataRejectsDocumentsArchivesOtherEditionsAndAmbiguousHeaders() {
        String url = ARCHIVE + "Game.gba";
        for (String mime : new String[] {"text/html", "text/plain", "application/json",
                "application/problem+json", "application/xml", "application/xhtml+xml"}) {
            assertNull(mime, DownloadFilename.resolveGba(url, "attachment; filename=Game.gba", mime));
        }
        for (String header : new String[] {"attachment; filename=Other.gba", "attachment; filename=game.gba",
                "attachment; filename=Game.nds", "attachment; filename=Game.zip", "attachment; filename=Game.7z",
                "attachment; filename=Game.gba; filename=Other.gba", "attachment; filename=\"\"",
                "attachment; filename*=UTF-8''bad%ZZ.gba", "attachment; filename=\"unterminated.gba",
                "attachment; filename=Game.gba; filename*=UTF-8''error.html"}) {
            assertNull(header, DownloadFilename.resolveGba(url, header, BINARY));
        }
        assertNull(DownloadFilename.resolveGba(url,
                "attachment; filler=" + "x".repeat(16384) + "; filename=Game.gba", BINARY));
    }
}
