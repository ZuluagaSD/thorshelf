package dev.thorshelf.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class ArtworkPolicyTest {
    @Test public void permitsOnlyExactPublicGameTdbCoverPaths() {
        String valid = "https://art.gametdb.com/3ds/coverM/US/BRME.jpg";
        assertTrue(ArtworkPolicy.isAllowed(valid));
        assertFalse(DownloadPolicy.isAllowedDownload(valid));
        assertFalse(DownloadPolicy.isAllowedNdsDownload(valid));
        assertFalse(DownloadPolicy.isCatalog(valid));
        for (String url : new String[] {valid.replace("https:", "http:"), valid + "?token=secret",
                valid + "#fragment", valid.replace(".com/", ".com.evil.example/"),
                valid.replace("art.gametdb.com", "user@art.gametdb.com"), valid.replace(".com/", ".com:8443/"),
                valid.replace("/3ds/", "/wii/"), valid.replace("/US/", "/other/"),
                valid.replace("coverM", "coverfullHQ"), valid.replace(".jpg", ".svg"),
                valid.replace("BRME", "BRME-extra"), valid.replace("BRME", "%42RME"),
                valid.replace("/US/", "/US/../US/"), valid.replace("/US/", "/US%2f/"),
                valid.replace("/US/", "/US/%252e%252e/"), valid.replace(".jpg", ".jpg.exe")}) {
            assertFalse(url, ArtworkPolicy.isAllowed(url));
        }
    }

    @Test public void acceptsNdsShopBoxartIconsAndBoundedScreenshotPaths() {
        for (String path : new String[] {
                "boxart/Welcome%20to%20Animal%20Crossing%20Wild%20World%20(Europe)%20(En%2CFr%2CDe%2CEs%2CIt).nds.png",
                "boxart/Pok%C3%A9mon%20(France)%20%5Bb%5D.nds.png",
                "icons/animalcrossingwildworld.png", "screenshots/animalcrossingwildworld/1.png",
                "screenshots/Game%20Screenshot.png"}) {
            String url = "https://db-nds-shop.fr/api/v1/assets/" + path;
            assertTrue(url, ArtworkPolicy.isAllowed(url));
            assertFalse(url, DownloadPolicy.isAllowedNdsDownload(url));
            assertFalse(url, DownloadPolicy.isAllowedDownload(url));
            assertFalse(url, DownloadPolicy.isCatalog(url));
        }
    }

    @Test public void ndsShopArtworkRejectsLaunchersQueriesAndUnexpectedAssetPaths() {
        String assets = "https://db-nds-shop.fr/api/v1/assets/";
        for (String url : new String[] {
                assets + "boxart/Game.cia", assets + "boxart/Game.nds", assets + "boxart/Game.svg",
                assets + "boxart/.png", assets + "boxart/Game.png/", assets + "boxart/dir/Game.png",
                assets + "icons/dir/icon.png", assets + "screenshots/game/extra/1.png",
                assets + "screenshots/../1.png", assets + "qr/game.png", assets + "files/game.png",
                assets + "boxart/Game.png?", assets + "boxart/Game.png?url=https://evil.example/Game.png",
                assets + "boxart/Game.png#fragment", assets + "boxart/%2e%2e/Game.png",
                assets + "boxart/%252e%252e%252fGame.png", assets + "boxart/Game%2fName.png",
                assets + "boxart/Game%5CName.png", assets + "boxart/Game%00.png",
                assets + "boxart/Game%E2%80%AE.png", assets + "boxart/Game%3Furl.png",
                assets + "boxart/%FF.png", assets + "boxart/" + "x".repeat(252) + ".png",
                assets.replace("https:", "http:") + "boxart/Game.png",
                assets.replace(".fr/", ".fr.evil.example/") + "boxart/Game.png",
                assets.replace(".fr/", ".fr./") + "boxart/Game.png",
                assets.replace("db-nds-shop.fr/", "user@db-nds-shop.fr/") + "boxart/Game.png",
                assets.replace(".fr/", ".fr:8443/") + "boxart/Game.png",
                assets.replace("/api/", "/%61pi/") + "boxart/Game.png",
                assets.replace("/v1/", "/v2/") + "boxart/Game.png",
                "https://db-nds-shop.fr/api/v1/download/Game.png"}) {
            assertFalse(url, ArtworkPolicy.isAllowed(url));
        }
    }

    @Test public void acceptsTheFeedsRasterImagesIncludingEncodedSpacesAndAvatarQueries() {
        for (String url : new String[] {
                "https://db.universal-team.net/assets/images/images/ntr-launcher.png",
                "https://db.universal-team.net/assets/images/icons/wordle-ds.gif",
                "https://raw.githubusercontent.com/AzizBgBoss/TerrariaDS/refs/heads/main/media/logo.png",
                "https://raw.githubusercontent.com/My-name-is-TJ/blimp-chicken-ds/main/Blimp%20Chicken%20DS%20banner.png",
                "https://avatars.githubusercontent.com/u/83554824?v=4&size=128",
                "https://github.com/cavv-dev/Kekatsu-DS/raw/main/icon.png",
                "https://i.imgur.com/BFIu7xX.png"}) {
            assertTrue(url, ArtworkPolicy.isAllowed(url));
        }
    }

    @Test public void rejectsUnknownHostsDocumentsAndAmbiguousAddresses() {
        for (String url : new String[] {null, "", "https://evil.example/icon.png",
                "http://db.universal-team.net/assets/images/icon.png",
                "https://db.universal-team.net.evil.example/assets/images/icon.png",
                "https://evil.example@db.universal-team.net/assets/images/icon.png",
                "https://db.universal-team.net:444/assets/images/icon.png",
                "https://db.universal-team.net/assets/images/icon.png#fragment",
                "https://db.universal-team.net/assets/images/../files/icon.png",
                "https://db.universal-team.net/assets/images/%2e%2e/icon.png",
                "https://db.universal-team.net/assets/images/%252e%252e/icon.png",
                "https://db.universal-team.net/assets/images/a%2fb.png",
                "https://db.universal-team.net/assets/images/a%5cb.png",
                "https://db.universal-team.net/assets/images/a%0Ab.png",
                "https://db.universal-team.net/assets/images/icon.svg",
                "https://db.universal-team.net/assets/images/icon.png.exe",
                "https://db.universal-team.net/assets/files/game.nds",
                "https://github.com/example/game/blob/main/icon.png",
                "https://avatars.githubusercontent.com/other/42.png",
                "https://hshop.erista.me/t/1",
                "https://download4.erista.me/content/1/qr?token=private",
                "file:///sdcard/icon.png", "content://images/icon.png", "data:image/png;base64,AA=="}) {
            assertFalse(String.valueOf(url), ArtworkPolicy.isAllowed(url));
        }
    }

    @Test public void artworkIsOptionalMetadataAndNeverBecomesTheGameDownload() {
        String download = "https://github.com/example/game/releases/download/v1/demo.nds";
        String artwork = "https://db.universal-team.net/assets/images/icons/demo.png";
        String row = "Demo|nds|World|1|Example|" + download + "|demo.nds|1024|";
        NdsCatalog catalog = NdsCatalog.parse("1\n|\n" + row + artwork);
        CatalogPage.Entry entry = catalog.page(NdsCatalog.HOME).entries.get(0);
        assertEquals(artwork, entry.artworkUrl);
        CatalogPage detail = catalog.page(entry.url);
        assertEquals(artwork, detail.entries.get(0).artworkUrl);
        assertEquals(download, detail.downloadUrl);
        assertEquals("", NdsCatalog.parse("1\n|\n" + row + "https://evil.example/icon.png")
                .page(NdsCatalog.HOME).entries.get(0).artworkUrl);
        assertEquals("", NdsCatalog.parse("1\n|\n" + row)
                .page(NdsCatalog.HOME).entries.get(0).artworkUrl);
        assertEquals("", new CatalogPage.Entry("https://hshop.erista.me/t/1", "Game", "games",
                "World", "", "", "000400000011C400").artworkUrl);
    }
}
