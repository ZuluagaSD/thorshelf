package dev.thorshelf.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class NdsDownloadPolicyTest {
    private static final String RELEASE = "https://github.com/owner/game/releases/download/v1/Game.nds";
    private static final String ASSET = "https://release-assets.githubusercontent.com/github-production-release-asset/123/abc?token=signed";
    private static final String NDS_SHOP = "https://db-nds-shop.fr/api/v1/download/";
    private static final String ARCHIVE_ITEM = "pack-roms-nintendo-ds-eu-usa-jap-rabbits-games";
    private static final String ARCHIVE = "https://archive.org/download/" + ARCHIVE_ITEM + "/";

    @Test public void archiveZipDownloadsStayWithinThePinnedItemAcrossStorageNodes() {
        String name = "Pokemon%20-%20Black%20Version%20(USA%2C%20Europe)%20(NDSi%20Enhanced).zip";
        for (String base : new String[] {ARCHIVE,
                "https://ia800603.us.archive.org/2/items/" + ARCHIVE_ITEM + "/",
                "https://dn710805.ca.archive.org/0/items/" + ARCHIVE_ITEM + "/",
                "https://ia123456.ca.archive.org/12/items/" + ARCHIVE_ITEM + "/"}) {
            assertTrue(base, DownloadPolicy.isAllowedNdsDownload(base + name));
            assertTrue(DownloadPolicy.isAllowedNdsRedirect(ARCHIVE + name, base + name));
            assertFalse(DownloadPolicy.isAllowedDownload(base + name));
            assertFalse(DownloadPolicy.isCatalog(base + name));
        }
        assertTrue(DownloadPolicy.isAllowedNdsRedirect(ARCHIVE + "Pok%C3%A9mon%20%5Bb%5D.zip",
                "https://dn710805.ca.archive.org/0/items/" + ARCHIVE_ITEM + "/Pok%c3%a9mon%20%5bb%5d.zip"));
        assertEquals("Pokemon - Black Version (USA, Europe) (NDSi Enhanced).zip",
                DownloadFilename.resolveNds(ARCHIVE + name, null, "application/zip"));
    }

    @Test public void archiveRejectsOtherItemsAmbiguousNamesAndUnrecognizedMirrorHosts() {
        for (String invalid : new String[] {
                ARCHIVE.replace(ARCHIVE_ITEM, "another-item") + "Game.zip",
                ARCHIVE.replace("/download/", "/details/") + "Game.zip",
                ARCHIVE + "Game.nds", ARCHIVE + "Game.7z", ARCHIVE + "Game.zip/",
                ARCHIVE + "dir/Game.zip", ARCHIVE + "..%2fGame.zip", ARCHIVE + "dir%5cGame.zip",
                ARCHIVE + "%252e%252e%252fGame.zip", ARCHIVE + "Game%2520Name.zip",
                ARCHIVE + "Game.zip?download=1", ARCHIVE + "Game.zip#download",
                ARCHIVE + "Game%00.zip", ARCHIVE + "Game%3Fredirect.zip", ARCHIVE + "%FF.zip",
                ARCHIVE.replace("https:", "http:") + "Game.zip",
                ARCHIVE.replace("archive.org/", "archive.org.evil.example/") + "Game.zip",
                ARCHIVE.replace("archive.org/", "user@archive.org/") + "Game.zip",
                ARCHIVE.replace("archive.org/", "archive.org:8443/") + "Game.zip",
                "https://ia800603.us.archive.org.evil.example/2/items/" + ARCHIVE_ITEM + "/Game.zip",
                "https://ia800603.us.archive.org./2/items/" + ARCHIVE_ITEM + "/Game.zip",
                "https://ia80060.us.archive.org/2/items/" + ARCHIVE_ITEM + "/Game.zip",
                "https://ia800603.eu.archive.org/2/items/" + ARCHIVE_ITEM + "/Game.zip",
                "https://node800603.us.archive.org/2/items/" + ARCHIVE_ITEM + "/Game.zip",
                "https://ia800603.us.archive.org/x/items/" + ARCHIVE_ITEM + "/Game.zip",
                "https://ia800603.us.archive.org/2/items/another-item/Game.zip",
                "https://ia800603.us.archive.org/2/%69tems/" + ARCHIVE_ITEM + "/Game.zip"}) {
            assertFalse(invalid, DownloadPolicy.isAllowedNdsDownload(invalid));
        }
        assertFalse(DownloadPolicy.isAllowedNdsRedirect(ARCHIVE + "Game.zip", ARCHIVE + "Other.zip"));
        assertFalse(DownloadPolicy.isAllowedNdsRedirect(ARCHIVE + "Game.zip", RELEASE));
        assertFalse(DownloadPolicy.isAllowedNdsRedirect(RELEASE, ARCHIVE + "Game.zip"));
        assertNull(DownloadFilename.resolveNds(ARCHIVE + "Game.zip", "attachment; filename=Other.zip", "application/zip"));
        assertNull(DownloadFilename.resolveNds(ARCHIVE + "Game.zip", "attachment; filename=Game.nds", "application/zip"));
    }

    @Test public void ndsShopAllowsEncodedRomNamesWithoutExtendingHshopTrust() {
        for (String name : new String[] {
                "Welcome%20to%20Animal%20Crossing%20Wild%20World%20(Europe)%20(En%2CFr%2CDe%2CEs%2CIt).nds",
                "Beyblade%20Metal%20Fusion%20Cyber%20Pegasus%20(Europe)%20%5Bb%5D.nds",
                "Mario%20%26%20Luigi%20Bowser's%20Inside%20Story%20(Europe).nds",
                "Pok%C3%A9mon%20Version%20Blanche%20(France)%20(NDSi%20Enhanced).nds",
                "Game.NDS"}) {
            String url = NDS_SHOP + name;
            assertTrue(url, DownloadPolicy.isAllowedNdsDownload(url));
            assertFalse(url, DownloadPolicy.isAllowedDownload(url));
            assertFalse(url, DownloadPolicy.isCatalog(url));
        }
        assertTrue(DownloadPolicy.isAllowedNdsDownload(NDS_SHOP.replace(".fr/", ".fr:443/") + "Game.nds"));
        assertEquals("Pokémon (France) [b].nds", DownloadFilename.resolveNds(
                NDS_SHOP + "Pok%C3%A9mon%20(France)%20%5Bb%5D.nds", null, "application/octet-stream"));
    }

    @Test public void ndsShopRejectsLaunchersAndAnythingOutsideItsBoundedRomEndpoint() {
        for (String url : new String[] {
                NDS_SHOP + "Game.cia", NDS_SHOP + "Game.zip", NDS_SHOP + "Game.nds.html",
                NDS_SHOP + ".nds", NDS_SHOP + "Game.nds/", NDS_SHOP + "dir/Game.nds",
                NDS_SHOP + "../Game.nds", NDS_SHOP + "%2E%2E/Game.nds",
                NDS_SHOP + "..%2fGame.nds", NDS_SHOP + "..%5CGame.nds",
                NDS_SHOP + "%252e%252e%252fGame.nds", NDS_SHOP + "Game%2520Name.nds",
                NDS_SHOP + "Game%00.nds", NDS_SHOP + "Game%E2%80%AE.nds",
                NDS_SHOP + "Game%3Ftoken%3Dsecret.nds", NDS_SHOP + "Game%23fragment.nds",
                NDS_SHOP + "Game%3Bredirect.nds", NDS_SHOP + "%20Game.nds",
                NDS_SHOP + "%FF.nds", NDS_SHOP + "x".repeat(252) + ".nds",
                NDS_SHOP + "%C3%A9".repeat(126) + ".nds",
                NDS_SHOP + "Game.nds?", NDS_SHOP + "Game.nds?url=https://evil.example/Game.nds",
                NDS_SHOP + "Game.nds#fragment", NDS_SHOP.replace("https:", "http:") + "Game.nds",
                NDS_SHOP.replace("db-nds-shop.fr/", "db-nds-shop.fr.evil.example/") + "Game.nds",
                NDS_SHOP.replace("db-nds-shop.fr/", "db-nds-shop.fr./") + "Game.nds",
                NDS_SHOP.replace("db-nds-shop.fr/", "user@db-nds-shop.fr/") + "Game.nds",
                NDS_SHOP.replace("db-nds-shop.fr/", "db-nds-shop.fr:8443/") + "Game.nds",
                NDS_SHOP.replace("/api/v1/", "/api/v2/") + "Game.nds",
                NDS_SHOP.replace("/api/", "/%61pi/") + "Game.nds",
                "https://db-nds-shop.fr/api/v1/assets/boxart/Game.nds.png",
                "https://db-nds-shop.fr/api/v1/downloads/Game.nds"}) {
            assertFalse(url, DownloadPolicy.isAllowedNdsDownload(url));
        }
    }

    @Test public void onlyPublicHomebrewAssetLocationsAreAllowed() {
        for (String url : new String[]{RELEASE,
                "https://github.com/owner/game/releases/latest/download/Game.zip",
                "https://db.universal-team.net/assets/files/colors_1_10bbeta2.zip", ASSET}) {
            assertTrue(url, DownloadPolicy.isAllowedNdsDownload(url));
            assertFalse(url, DownloadPolicy.isAllowedDownload(url));
        }
        assertFalse(DownloadPolicy.isAllowedNdsDownload("https://download2.erista.me/Game.nds"));
    }

    @Test public void hostileAddressesAndNonAssetPagesAreRejected() {
        for (String url : new String[]{null, "", RELEASE.replace("https:", "http:"),
                RELEASE.replace("github.com/", "github.com.evil.example/"),
                RELEASE.replace("github.com/", "github.com./"),
                RELEASE.replace("github.com/", "user:password@github.com/"),
                RELEASE.replace("github.com/", "github.com:8443/"),
                RELEASE + "#fragment", RELEASE + "\r\nCookie: injected",
                RELEASE.replace("/Game.nds", "/../Game.nds"),
                RELEASE.replace("/Game.nds", "/%2e%2e/Game.nds"),
                RELEASE.replace("/Game.nds", "/%2e%2e%2fGame.nds"),
                RELEASE.replace("/Game.nds", "/Game%00.nds"),
                "https://github.com/owner/game", "https://github.com/owner/game/releases",
                "https://github.com/owner/game/blob/main/Game.nds",
                "https://github.com/owner/game/raw/main/Game.nds",
                "https://github.com/owner/game/releases/download/v1/",
                "https://db.universal-team.net/ds/game",
                "https://db.universal-team.net/assets/files/../game.nds",
                "https://db.universal-team.net/assets/files/dir/game.nds",
                "https://release-assets.githubusercontent.com/unrelated/Game.nds",
                "https://release-assets.githubusercontent.com.evil.example/github-production-release-asset/123/abc"}) {
            assertFalse(String.valueOf(url), DownloadPolicy.isAllowedNdsDownload(url));
        }
    }

    @Test public void ndsMetadataSupportsRomAndZipOnly() {
        assertEquals("Game.nds", DownloadFilename.resolveNds(RELEASE, null, "application/octet-stream"));
        assertEquals("Game.zip", DownloadFilename.resolveNds(RELEASE.replace(".nds", ".zip"), null,
                "application/zip"));
        assertEquals("Café Game.nds", DownloadFilename.resolveNds(ASSET,
                "attachment; filename*=UTF-8''Caf%C3%A9%20Game.nds", "application/octet-stream"));
        for (String name : new String[]{"game.cia", "game.3ds", "game.7z", "game.exe", "game.nds.html"}) {
            assertNull(name, DownloadFilename.resolveNds(RELEASE, "attachment; filename=" + name,
                    "application/octet-stream"));
        }
        assertNull(DownloadFilename.resolveNds(RELEASE, null, "text/html"));
        assertNull(DownloadFilename.resolveNds(RELEASE, null, "application/json"));
        assertNull(DownloadFilename.resolveNds(ASSET, null, "application/octet-stream"));
        assertNull(DownloadFilename.resolveNds(RELEASE,
                "attachment; filename=Game.nds; filename=Other.nds", "application/octet-stream"));
        assertNull(DownloadFilename.resolve(RELEASE, null, "application/octet-stream"));
        assertNull(DownloadFilename.resolveNds("https://download2.erista.me/Game.nds", null,
                "application/octet-stream"));
    }
}
