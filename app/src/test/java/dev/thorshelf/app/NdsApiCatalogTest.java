package dev.thorshelf.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Reduced public metadata and synthetic rows only; no game files are downloaded. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NdsApiCatalogTest {
    private static final String DOWNLOAD = "https://db-nds-shop.fr/api/v1/download/";
    private static final String ART = "https://db-nds-shop.fr/api/v1/assets/";

    @Test public void readsOriginalFrenchEditionsAndOnlyTheirNdsFiles() throws Exception {
        NdsCatalog.ApiPage response = NdsCatalog.parseApiPage(fixture());
        assertEquals(1, response.page);
        assertEquals(200, response.limit);
        assertEquals(4, response.total);
        assertEquals(4, response.rowCount);
        assertEquals("1787729999", response.revision);
        CatalogPage listing = response.catalog.page(NdsCatalog.HOME);
        assertEquals("Nintendo DS games", listing.title);
        assertEquals("4 titles · NDS-Shop", listing.summary);
        assertEquals(4, listing.entries.size());
        CatalogPage.Entry white = listing.entries.get(0);
        assertEquals("Pokémon Version Blanche", white.title);
        assertEquals("France", white.region);
        assertEquals("IRAF", white.titleId);
        assertEquals("256.0 MiB", white.size);
        assertTrue(white.artworkUrl.startsWith(ART + "boxart/"));
        CatalogPage detail = response.catalog.page(white.url);
        assertEquals(white.title, detail.title);
        assertTrue(detail.downloadUrl.endsWith(".nds"));
        assertTrue(detail.summary.contains("NDS-Shop"));
        assertTrue(detail.summary.contains("Version (France)"));
        assertFalse(detail.summary.contains("Homebrew"));
        assertFalse(detail.summary.contains("installCia"));
        assertFalse(detail.summary.contains("untrusted"));
    }

    @Test public void englishAliasesFindFrenchBlackAndWhiteWithoutRenamingThem() throws Exception {
        NdsCatalog catalog = NdsCatalog.parseApiPage(fixture()).catalog;
        for (String query : new String[]{"Pokemon White", "Pokémon White", "POKEMON WHITE", "pokemon white version"}) {
            List<CatalogPage.Entry> matches = catalog.page(NdsCatalog.searchUrl(query)).entries;
            assertEquals(query, 2, matches.size());
            assertEquals("Pokémon Version Blanche", matches.get(0).title);
            assertEquals("Pokémon Version Blanche 2", matches.get(1).title);
        }
        assertEquals(2, catalog.page(NdsCatalog.searchUrl("Pokemon Black")).entries.size());
        assertEquals("Pokémon Version Noire 2", catalog.page(NdsCatalog.searchUrl("pokemon black 2")).entries.get(0).title);
        assertEquals(1, catalog.page(NdsCatalog.searchUrl("pokemon black 2")).entries.size());
        assertEquals("Pokémon Version Blanche 2", catalog.page(NdsCatalog.searchUrl("pokemon white2")).entries.get(0).title);
        assertEquals(1, catalog.page(NdsCatalog.searchUrl("pokemon white version 2")).entries.size());
        assertEquals(2, catalog.page(NdsCatalog.searchUrl("pokemon version blanche")).entries.size());
    }

    @Test public void aliasRequiresBothKnownTitleAndGameCode() throws Exception {
        JSONObject rows = new JSONObject(fixture());
        rows.getJSONArray("entries").getJSONObject(0).put("titleId", "ABCD");
        rows.getJSONArray("entries").getJSONObject(2).put("title", "White Space DS");
        NdsCatalog catalog = NdsCatalog.parseApiPage(rows.toString()).catalog;
        assertTrue(catalog.page(NdsCatalog.searchUrl("pokemon white")).entries.isEmpty());
        assertEquals(1, catalog.page(NdsCatalog.searchUrl("pokemon version blanche")).entries.size());
    }

    @Test public void artworkPrefersBoxartThenSafeImageThenSafeIcon() throws Exception {
        JSONObject row = row("demo").put("boxart", ART + "boxart/demo.png")
                .put("image", ART + "icons/image.png").put("icon", ART + "icons/icon.png");
        assertEquals(ART + "boxart/demo.png", only(row).artworkUrl);
        row.put("boxart", "https://evil.example/cover.png");
        assertEquals(ART + "icons/image.png", only(row).artworkUrl);
        row.put("image", "https://db-nds-shop.fr/api/v1/assets/qr/demo.png");
        assertEquals(ART + "icons/icon.png", only(row).artworkUrl);
        row.put("icon", "file:///tmp/cover.png");
        assertEquals("", only(row).artworkUrl);
    }

    @Test public void missingOptionalFieldsStillProduceAnExplicitDsGame() throws Exception {
        CatalogPage.Entry entry = only(row("demo"));
        assertEquals("", entry.region);
        assertEquals("", entry.titleId);
        assertEquals("", entry.artworkUrl);
        assertEquals("Nintendo DS game", entry.contentType);
        assertEquals("Europe", only(row("europe").put("version", "(Europe) (En,Fr,De)")).region);
    }

    @Test public void malformedRowsAndForwardersNeverBecomeGames() throws Exception {
        JSONArray rows = new JSONArray().put("not an object");
        rows.put(row("other").put("systems", new JSONArray().put("3DS")));
        rows.put(row("missing-system").put("systems", JSONObject.NULL));
        rows.put(row("bad-title").put("title", 123));
        rows.put(row("long-title").put("title", "x".repeat(257)));
        rows.put(row("bad-code").put("titleId", "../IRAF"));
        for (String name : Arrays.asList("game.cia", "game.zip", "../game.nds", "game.nds.exe")) {
            rows.put(row("wrong-file").put("downloads", new JSONObject().put(name,
                    new JSONObject().put("url", DOWNLOAD + "game.nds").put("size", 512))));
        }
        JSONObject scriptsOnly = row("scripts-only").put("downloads", new JSONObject())
                .put("scripts", new JSONArray().put(new JSONObject().put("type", "downloadFile")
                        .put("file", DOWNLOAD + "game.nds")));
        rows.put(scriptsOnly);
        rows.put(row("valid"));
        NdsCatalog.ApiPage page = NdsCatalog.parseApiPage(envelope(rows).toString());
        assertEquals(rows.length(), page.rowCount);
        assertEquals(1, page.catalog.page(NdsCatalog.HOME).entries.size());
        assertEquals("valid", page.catalog.page(NdsCatalog.HOME).entries.get(0).title);
    }

    @Test public void rejectsInvalidDownloadMetadataButKeepsAnotherValidFile() throws Exception {
        JSONArray rows = new JSONArray();
        for (Object invalidSize : new Object[]{-1, 1.5, "512", JSONObject.NULL}) {
            JSONObject row = row("bad-size");
            row.getJSONObject("downloads").getJSONObject("bad-size.nds").put("size", invalidSize);
            rows.put(row);
        }
        for (String url : Arrays.asList("http://db-nds-shop.fr/api/v1/download/unsafe.nds",
                "https://evil.example/unsafe.nds", DOWNLOAD + "other.nds", DOWNLOAD + "unsafe.cia")) {
            JSONObject row = row("unsafe");
            row.getJSONObject("downloads").getJSONObject("unsafe.nds").put("url", url);
            rows.put(row);
        }
        JSONObject valid = row("valid");
        valid.getJSONObject("downloads").put("invalid.nds", new JSONObject().put("url", DOWNLOAD + "invalid.nds").put("size", -1));
        rows.put(valid);
        CatalogPage page = NdsCatalog.parseApiPage(envelope(rows).toString()).catalog.page(NdsCatalog.HOME);
        assertEquals(1, page.entries.size());
        assertEquals("valid", page.entries.get(0).title);
    }

    @Test public void validatesEnvelopeTypesBoundsAndCompleteJson() throws Exception {
        for (String field : Arrays.asList("page", "limit", "total", "revision")) {
            for (Object invalid : new Object[]{JSONObject.NULL, true, 1.5, -1}) {
                JSONObject response = envelope(new JSONArray());
                response.put(field, invalid);
                assertThrows(field + "=" + invalid, IllegalArgumentException.class,
                        () -> NdsCatalog.parseApiPage(response.toString()));
            }
        }
        for (JSONObject response : Arrays.asList(
                envelope(new JSONArray()).put("page", 0),
                envelope(new JSONArray()).put("limit", 201),
                envelope(new JSONArray()).put("total", 50001),
                envelope(new JSONArray()).put("entries", new JSONObject()),
                envelope(new JSONArray().put(row("demo"))).put("total", 0),
                envelope(new JSONArray()).put("page", 2),
                envelope(new JSONArray()).put("revision", "x".repeat(129)))) {
            assertThrows(IllegalArgumentException.class, () -> NdsCatalog.parseApiPage(response.toString()));
        }
        for (String invalid : new String[]{null, "", "[]", "<!doctype html>", "{}{}",
                envelope(new JSONArray()) + " trailing", "x".repeat(NdsCatalog.MAX_DATABASE_BYTES + 1),
                "[".repeat(33) + "]".repeat(33)}) {
            assertThrows(IllegalArgumentException.class, () -> NdsCatalog.parseApiPage(invalid));
        }
        assertTrue(NdsCatalog.parseApiPage(envelope(new JSONArray()).toString()).catalog.page(NdsCatalog.HOME).entries.isEmpty());
    }

    @Test public void stableDetailIdsSurvivePageReorderingAndMergesRejectDuplicates() throws Exception {
        NdsCatalog first = NdsCatalog.parseApiPage(envelope(new JSONArray().put(row("first"))).toString()).catalog;
        NdsCatalog second = NdsCatalog.parseApiPage(envelope(new JSONArray().put(row("second"))).toString()).catalog;
        String firstUrl = first.page(NdsCatalog.HOME).entries.get(0).url;
        NdsCatalog combined = NdsCatalog.merge(Arrays.asList(second, first));
        assertEquals(2, combined.page(NdsCatalog.HOME).entries.size());
        assertEquals("first", combined.page(firstUrl).title);
        assertEquals(DOWNLOAD + "first.nds", combined.page(firstUrl).downloadUrl);
        assertThrows(IllegalArgumentException.class, () -> NdsCatalog.merge(Arrays.asList(first, first)));
        assertThrows(IllegalArgumentException.class, () -> NdsCatalog.merge(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> NdsCatalog.parseApiPage(
                envelope(new JSONArray().put(row("first")).put(row("first"))).toString()));
    }

    @Test public void rejectsShallowOversizedStructuresBeforeJsonAllocation() throws Exception {
        String validEnvelope = envelope(new JSONArray()).toString();
        String prefix = validEnvelope.substring(0, validEnvelope.length() - 1) + ",\"ignored\":";
        String array = prefix + "[" + "0,".repeat(100_001) + "0]}";
        StringBuilder object = new StringBuilder(prefix).append('{');
        for (int i = 0; i < 50_001; i++) {
            if (i > 0) object.append(',');
            object.append('"').append(i).append("\":0");
        }
        object.append("}}");
        for (String oversized : new String[]{array, object.toString()}) {
            assertTrue(oversized.length() < NdsCatalog.MAX_DATABASE_BYTES);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> NdsCatalog.parseApiPage(oversized));
            assertEquals("The Nintendo DS catalog contains too many JSON elements.", failure.getMessage());
        }
        // Punctuation inside ordinary metadata strings does not create JSON objects or arrays.
        JSONObject quoted = envelope(new JSONArray()).put("ignored", "{}[],:".repeat(20_000));
        assertEquals(0, NdsCatalog.parseApiPage(quoted.toString()).rowCount);
        assertEquals(4, NdsCatalog.parseApiPage(fixture()).catalog.page(NdsCatalog.HOME).entries.size());
    }

    private static CatalogPage.Entry only(JSONObject row) throws Exception {
        return NdsCatalog.parseApiPage(envelope(new JSONArray().put(row)).toString())
                .catalog.page(NdsCatalog.HOME).entries.get(0);
    }

    private static JSONObject row(String title) throws Exception {
        return new JSONObject().put("title", title).put("systems", new JSONArray().put("DS"))
                .put("downloads", new JSONObject().put(title + ".nds", new JSONObject()
                        .put("url", DOWNLOAD + title + ".nds").put("size", 1024)));
    }

    private static JSONObject envelope(JSONArray entries) throws Exception {
        return new JSONObject().put("page", 1).put("limit", 200).put("total", entries.length())
                .put("revision", 1787729999).put("entries", entries);
    }

    private static String fixture() throws Exception {
        try (InputStream input = NdsApiCatalogTest.class.getResourceAsStream("/nds/api-pokemon.json")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
