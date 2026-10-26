package dev.thorshelf.app;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class DownloadFilenameTest {
    private static final String OPAQUE_URL = "https://download4.erista.me/opaque-token?expires=123";
    private static final String BINARY = "application/octet-stream";

    @Test public void resolvesHshopHeaderWithBothFilenameParameters() {
        String name = "00040000000A8500 Professor Layton and the Miracle Mask (CTR-P-AKKE) "
                + "(v0.2.0) (U).piratelegit.cia";
        String header = "attachment; filename=\"" + name + "\"; filename*=UTF-8''"
                + "00040000000A8500%20Professor%20Layton%20and%20the%20Miracle%20Mask%20"
                + "%28CTR-P-AKKE%29%20%28v0.2.0%29%20%28U%29.piratelegit.cia";
        String result = DownloadFilename.resolve(OPAQUE_URL, header, BINARY);
        assertEquals("Professor Layton and the Miracle Mask (USA).cia", result);
        assertTrue(result.endsWith(".cia"));
    }

    @Test public void prefersExtendedFilenameAndSupportsUtf8LanguageAndLiteralPlus() {
        assertEquals("Pokémon+Test.cia", DownloadFilename.resolve(OPAQUE_URL,
                "attachment; filename=\"fallback.cia\"; filename*=UTF-8'en'Pok%C3%A9mon+Test.cia", BINARY));
        assertEquals("日本語.cia", DownloadFilename.resolve(OPAQUE_URL,
                "attachment; filename*=UTF-8''%E6%97%A5%E6%9C%AC%E8%AA%9E.cia", BINARY));
        assertEquals("Café.cia", DownloadFilename.resolve(OPAQUE_URL,
                "attachment; FILENAME*=ISO-8859-1''Caf%E9.cia", BINARY));
    }

    @Test public void removesCatalogMetadataBeforeLengthLimitingTheTitle() {
        String title = "A".repeat(70);
        assertEquals(title + " (World).cia", DownloadFilename.resolve(OPAQUE_URL,
                "attachment; filename=\"000400000011C400 " + title
                        + " (CTR-P-ECRA) (v3.3.0) (W).legit.cia\"", BINARY));
    }

    @Test public void parsesQuotedSemicolonsEscapesAndExtraParameters() {
        assertEquals("A; B _special_.cia", DownloadFilename.resolve(OPAQUE_URL,
                "attachment; size=123; filename=\"A; B \\\"special\\\".cia\"; creation-date=ignored", BINARY));
        assertEquals("Game.CIA", DownloadFilename.resolve(OPAQUE_URL,
                "ATTACHMENT; FILENAME = Game.CIA ; size=12", BINARY));
    }

    @Test public void invalidExtendedEncodingFallsBackToPlainFilename() {
        for (String value : new String[] { "UTF-8''bad%ZZ.cia", "UTF-8''bad%C0%AF.cia",
                "UTF-8''bad%.cia", "UTF-8'missing-quote.cia", "unknown''bad.cia", "UTF-8''" }) {
            assertEquals(value, "fallback.cia", DownloadFilename.resolve(OPAQUE_URL,
                    "attachment; filename=\"fallback.cia\"; filename*=" + value, BINARY));
        }
    }

    @Test public void usesDecodedUrlPathWithoutInferringATypeFromMimeOrQuery() {
        assertEquals("Game+Name.cia", DownloadFilename.resolve(
                "https://download2.erista.me/Game+Name.cia?token=xyz#ignored", null, BINARY));
        assertEquals("Café.cia", DownloadFilename.resolve(
                "https://download2.erista.me/Caf%C3%A9.cia?token=xyz", "attachment", null));
        assertNull(DownloadFilename.resolve(OPAQUE_URL, null, BINARY));
        assertNull(DownloadFilename.resolve("https://download2.erista.me/?name=game.cia", null, BINARY));
        assertNull(DownloadFilename.resolve("https://download2.erista.me/", null, "application/zip"));
    }

    @Test public void preservesSupportedExtensionsInsteadOfRewritingThemForGenericBinaryMime() {
        for (String extension : new String[] { "cia", "CCI", "cxi", "3ds", "3dsx", "nds", "zip", "7z" }) {
            String name = "Game." + extension;
            assertEquals(name, DownloadFilename.resolve(OPAQUE_URL,
                    "attachment; filename=\"" + name + "\"", BINARY));
        }
    }

    @Test public void rejectsDocumentResponsesEvenWithGameFilenames() {
        for (String mime : new String[] { "text/html", "Text/HTML; charset=UTF-8", "text/plain",
                "application/json", "application/problem+json", "application/xml",
                "application/xhtml+xml", "application/javascript" }) {
            assertNull(mime, DownloadFilename.resolve("https://download2.erista.me/game.cia",
                    "attachment; filename=\"game.cia\"", mime));
        }
    }

    @Test public void explicitUnsupportedOrInvalidFilenameCannotFallBackToGameUrl() {
        String url = "https://download2.erista.me/game.cia";
        for (String header : new String[] { "attachment; filename=\"error.html\"",
                "attachment; filename=\"game.cia.exe\"", "attachment; filename=\"..\"",
                "attachment; filename=\"\"", "attachment; filename=\"unterminated.cia",
                "attachment; filename*=UTF-8''invalid%ZZ.cia",
                "attachment; filename=\"safe.cia\"; filename*=UTF-8''error.html",
                "attachment; filename=\"one.cia\"; filename=\"two.cia\"" }) {
            assertNull(header, DownloadFilename.resolve(url, header, BINARY));
        }
    }

    @Test public void sanitizesTraversalAndBoundsUnicodeFilenames() {
        String name = DownloadFilename.resolve(OPAQUE_URL,
                "attachment; filename*=UTF-8''..%2F..%2Fgame.cia", BINARY);
        assertEquals("_.._game.cia", name);
        String unicode = DownloadFilename.resolve(OPAQUE_URL,
                "attachment; filename=\"" + "遊戯🎮".repeat(100) + ".cia\"", BINARY);
        assertNotNull(unicode);
        assertTrue(unicode.getBytes(StandardCharsets.UTF_8).length <= 180);
        assertTrue(unicode.endsWith(".cia"));
    }

    @Test public void retainsTrustedHostRestrictionAndBoundsMetadata() {
        for (String url : new String[] { "http://download4.erista.me/game.cia",
                "https://download4.erista.me.evil.example/game.cia",
                "https://download4.erista.me@evil.example/game.cia", "file:///game.cia", null }) {
            assertNull(DownloadFilename.resolve(url, "attachment; filename=\"game.cia\"", BINARY));
        }
        assertNull(DownloadFilename.resolve(OPAQUE_URL,
                "attachment; filler=" + "x".repeat(17000) + "; filename=\"game.cia\"", BINARY));
        assertNull(DownloadFilename.resolve(OPAQUE_URL,
                "attachment; filename=\"game.cia\"", "x".repeat(1025)));
    }
}
