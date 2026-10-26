package dev.thorshelf.app;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class CatalogParserTest {
    private static final String SEARCH = DownloadPolicy.searchUrl("Mario");
    private static final String TITLE = "https://hshop.erista.me/t/2476";

    @Test public void readsSearchMetadataEntitiesAndBothPaginationDirections() throws Exception {
        CatalogPage page = CatalogParser.parse(SEARCH, fixture("search"));
        assertFalse(page.isDetail());
        assertEquals("Search Results", page.title);
        assertEquals("showing 26 - 50 of 358", page.summary);
        assertEquals(2, page.entries.size());
        CatalogPage.Entry game = page.entries.get(0);
        assertEquals(TITLE, game.url);
        assertEquals("Pokemon Omega Ruby", game.title);
        assertEquals("games", game.category);
        assertEquals("world", game.region);
        assertEquals("1.77 GiB", game.size);
        assertEquals("Legit", game.contentType);
        assertEquals("000400000011C400", game.titleId);
        assertEquals("Mario & Luigi's Update", page.entries.get(1).title);
        assertEquals("updates", page.entries.get(1).category);
        assertEquals("https://hshop.erista.me/search/results?q=Mario&qt=Text&count=25&offset=50", page.nextUrl);
        assertEquals("https://hshop.erista.me/search/results?q=Mario&qt=Text&count=25", page.previousUrl);
        assertFalse(page.verificationRequired);
    }

    @Test public void readsCategoriesWithoutMixingIncompleteRankingRows() throws Exception {
        CatalogPage page = CatalogParser.parse("https://hshop.erista.me/c/games", fixture("category"));
        assertEquals(2, page.categories.size());
        assertTrue(page.entries.isEmpty());
        assertEquals("North America", page.categories.get(0).title);
        assertEquals("Games released in North America.", page.categories.get(0).description);
        assertEquals("https://hshop.erista.me/c/games/s/north-america", page.categories.get(0).url);
        assertEquals("", page.nextUrl);
    }

    @Test public void readsExactProductCodesIntoArtworkForSearchAndDetailPages() {
        String metadata = "<div class='meta'><div class='meta-content'><span>CTR-P-BRME</span>"
                + "<span>Product Code</span></div></div>";
        String row = "<a class='list-entry' href='/t/1922'><div class='base-info'>"
                + "<h3>Mario &amp; Luigi - Superstar Saga + Bowser's Minions</h3>"
                + "<h4>content in <span>games</span> ➞ <span>north-america</span></h4></div>" + metadata + "</a>";
        String html = "<main><div class='list'><div class='elements'>" + row + "</div></div></main>";
        CatalogPage.Entry entry = CatalogParser.parse(SEARCH, html).entries.get(0);
        String expected = "https://art.gametdb.com/3ds/coverM/US/BRME.jpg";
        assertEquals(expected, entry.artworkUrl);
        assertEquals("https://hshop.erista.me/t/1922", entry.url);
        String detail = "<div class='title-name'><h2>Mario &amp; Luigi</h2><h3>"
                + "<a href='/c/games'>games</a><a href='/c/games/s/north-america'>north-america</a></h3></div>"
                + "<div class='basic-meta'><p>Product Code: <span>CTR-P-BRME</span></p></div>";
        assertEquals(expected, CatalogParser.parse(entry.url, detail).entries.get(0).artworkUrl);
        assertEquals("", CatalogParser.parse(SEARCH, html.replace("<span>games</span>", "<span>extras</span>"))
                .entries.get(0).artworkUrl);
        assertEquals("", CatalogParser.parse(SEARCH, html.replace("CTR-P-BRME", "https://evil.example/BRME.jpg"))
                .entries.get(0).artworkUrl);
    }

    @Test public void readsRegionTitlesUsingTheSameFullMetadataSchema() throws Exception {
        CatalogPage page = CatalogParser.parse("https://hshop.erista.me/c/games/s/world", fixture("search")
                .replace("class=\"list\"", "class=\"list pre-top\""));
        assertEquals(2, page.entries.size());
        assertEquals("world", page.entries.get(0).region);
    }

    @Test public void readsTitleMetadataAndFreshDirectLinkWithoutSelectingQr() throws Exception {
        CatalogPage page = CatalogParser.parse(TITLE, fixture("title"));
        assertTrue(page.isDetail());
        assertEquals(1, page.entries.size());
        assertEquals("1.77 GiB", page.entries.get(0).size);
        assertEquals("games", page.entries.get(0).category);
        assertEquals("000400000011C400", page.entries.get(0).titleId);
        assertEquals("https://download4.erista.me/content/2476?token=fixture-only", page.downloadUrl);
        assertFalse(page.verificationRequired);
    }

    @Test public void distinguishesEmptyResultsMissingFieldsAndVerification() {
        CatalogPage empty = CatalogParser.parse(SEARCH,
                "<main><div class=sitedesc><h1>Search Results</h1></div><div class=err-container>"
                + "<div class=err><h3>Your search terms did not match any titles in the Database.</h3></div></div></main>");
        assertTrue(empty.entries.isEmpty());
        assertTrue(empty.summary.startsWith("Your search terms"));
        assertFalse(empty.verificationRequired);
        CatalogPage partial = CatalogParser.parse(TITLE, "<div class=title-name><h2>Demo</h2></div>");
        assertEquals("", partial.entries.get(0).size);
        assertEquals("", partial.entries.get(0).region);
        assertEquals("", partial.downloadUrl);
        assertFalse(partial.verificationRequired);
        CatalogPage check = CatalogParser.parse(TITLE, "<div class=title-name><h2>Demo</h2></div>"
                + "<div id=landing-box>Please complete this security check to view the QR code and download link.</div>");
        assertTrue(check.verificationRequired);
        assertEquals("Demo", check.entries.get(0).title);
        assertTrue(CatalogParser.parse(SEARCH, "<title>Just a moment!</title>"
                + "<script src='/cdn-cgi/challenge-platform/a.js'></script>").verificationRequired);
    }

    @Test public void rejectsExternalSchemesCredentialsAndUnexpectedNavigationPaths() {
        StringBuilder rows = new StringBuilder();
        for (String href : new String[] { "https://evil.example/t/1", "http://hshop.erista.me/t/1",
                "javascript:alert(1)", "//download4.erista.me/content/1", "file:///t/1",
                "https://evil@hshop.erista.me/t/1", "/donate", "/t/random", "/t/1/extra",
                "/c/../donate", "/c/%2e%2e/donate", "https://hshop.erista.me:8443/t/1" }) {
            rows.append("<a class=list-entry href=\"").append(href)
                    .append("\"><div class=base-info><h3>Unsafe</h3></div></a>");
        }
        rows.append("<a class=list-entry href='/t/1'><div class=base-info><h3>Safe</h3></div></a>");
        CatalogPage page = CatalogParser.parse(SEARCH, "<base href='https://evil.example/'>"
                + "<main><div class=list><div class=elements>" + rows + "</div>"
                + "<div class=next><a href='https://evil.example/search/results'>show next 25</a></div></div></main>");
        assertEquals(1, page.entries.size());
        assertEquals("https://hshop.erista.me/t/1", page.entries.get(0).url);
        assertEquals("", page.nextUrl);
    }

    @Test public void rejectsUnrelatedDownloadUrlsEvenInsideDownloadWidget() throws Exception {
        String original = fixture("title");
        for (String target : new String[] { "https://evil.example/content/2476?token=x",
                "http://download4.erista.me/content/2476", "https://download4.erista.me/content/999",
                "https://download4.erista.me/content/2476/qr", "javascript:alert(1)",
                "https://evil@download4.erista.me/content/2476", "https://download4.erista.me:8443/content/2476" }) {
            assertEquals(target, "", CatalogParser.parse(TITLE, original.replace(
                    "https://download4.erista.me/content/2476?token=fixture-only", target)).downloadUrl);
        }
        assertEquals("", CatalogParser.parse(TITLE, original.replace("id=\"landing-box\"", "id=\"elsewhere\"")).downloadUrl);
    }

    @Test public void rejectsInvalidInputAndBoundsPublicContent() {
        for (String invalid : new String[] { null, "https://evil.example/search/results", DownloadPolicy.HOME,
                "https://hshop.erista.me/t/random", "https://hshop.erista.me/c/%2e%2e/admin" }) {
            assertThrows(IllegalArgumentException.class, () -> CatalogParser.parse(invalid, ""));
        }
        assertThrows(IllegalArgumentException.class, () -> CatalogParser.parse(SEARCH, null));
        assertThrows(IllegalArgumentException.class, () -> CatalogParser.parse(SEARCH, " ".repeat(2 * 1024 * 1024 + 1)));
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 110; i++) rows.append("<a class=list-entry href='/t/").append(i)
                .append("'><div class=base-info><h3>").append("x".repeat(700)).append("</h3></div></a>");
        CatalogPage bounded = CatalogParser.parse(SEARCH, "<main><div class=list><div class=elements>"
                + rows + "</div></div></main>");
        assertEquals(100, bounded.entries.size());
        assertEquals(512, bounded.entries.get(0).title.length());
    }

    @Test public void pagesOwnImmutableCopiesOfTheirRows() {
        List<CatalogPage.Entry> input = new ArrayList<>();
        CatalogPage page = new CatalogPage(SEARCH, "", "", input, Collections.emptyList(), "", "", "", false);
        input.add(new CatalogPage.Entry(TITLE, "Demo", "", "", "", "", ""));
        assertTrue(page.entries.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> page.entries.add(input.get(0)));
    }

    @Test public void unrecognizedOrBlankDocumentsAreErrorsInsteadOfEmptySearches() {
        for (String html : new String[] {"", "<html><body>Temporarily under maintenance</body></html>"}) {
            assertThrows(IllegalArgumentException.class, () -> CatalogParser.parse(SEARCH, html));
            assertThrows(IllegalArgumentException.class, () -> CatalogParser.parse(TITLE, html));
        }
    }

    private static String fixture(String name) throws IOException {
        try (InputStream input = CatalogParserTest.class.getResourceAsStream("/catalog/" + name + ".html")) {
            if (input == null) throw new IOException("Missing catalog fixture: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
