package dev.thorshelf.app;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.*;

public class CatalogRegionClientTest {
    private static final String SEARCH = "https://hshop.erista.me/search/results?q=Mario&qt=Text";
    private static final String ENGLISH = "0004000000030800";
    private static final String FRENCH = "00040000000A8800";

    @Test public void regionOnlyMatchesCountryAliasesAndNeverLoadsLanguageMetadata() throws Exception {
        Factory factory = new Factory(html(listing(row(1, "North America", "north-america", ENGLISH)
                + row(2, "Canada", "canada", ENGLISH) + row(3, "Europe", "europe", ENGLISH)
                + row(4, "World", "world", ENGLISH) + row(5, "Unknown", "unknown-other", ENGLISH), "", "")));
        CatalogPage page = regionClient(factory).fetch(CatalogFilters.apply(SEARCH, "", "north-america", false), null, null);
        assertEquals(2, page.entries.size());
        assertEquals("North America", page.entries.get(0).title);
        assertEquals("Canada", page.entries.get(1).title);
        assertEquals("2 titles on this page · North America", page.summary);
        assertEquals("north-america", CatalogFilters.selectedRegion(page.url));
        assertFalse(factory.opened.get(0).contains("thorshelf_"));
    }

    @Test public void combinesLanguageAndRegionUsingAndAndKeepsBothOnNavigation() throws Exception {
        Factory factory = new Factory(html(listing(row(1, "English Europe", "europe", ENGLISH)
                + row(2, "French Europe", "france", FRENCH) + row(3, "English America", "north-america", ENGLISH),
                search(100), search(300))));
        CatalogClient client = new CatalogClient(factory, () -> {
            java.util.Map<String, List<String>> metadata = new java.util.HashMap<>();
            metadata.put(ENGLISH, Collections.singletonList("en"));
            metadata.put(FRENCH, Collections.singletonList("fr"));
            return metadata;
        });
        CatalogPage page = client.fetch(CatalogFilters.apply(search(200), "en", "europe", false), null, null);
        assertEquals(1, page.entries.size());
        assertEquals("English Europe", page.entries.get(0).title);
        assertEquals("1 title on this page · English · Europe", page.summary);
        for (String url : Arrays.asList(page.url, page.nextUrl, page.previousUrl)) {
            assertEquals("en", CatalogFilters.selectedLanguage(url));
            assertEquals("europe", CatalogFilters.selectedRegion(url));
        }
        assertTrue(CatalogFilters.backwards(page.previousUrl));
        assertFalse(CatalogFilters.backwards(page.nextUrl));
    }

    @Test public void nextSkipsPagesWithoutRegionMatchesAndPreservesTheNextSourcePage() throws Exception {
        Factory factory = new Factory(html(listing(row(1, "American", "north-america", ENGLISH), "", search(100))),
                html(listing(row(2, "Unknown", "unknown-other", ENGLISH), search(0), search(200))),
                html(listing(row(3, "French", "france", FRENCH), search(100), search(300))));
        CatalogPage page = regionClient(factory).fetch(CatalogFilters.apply(search(0), "", "europe", false), null, null);
        assertEquals(3, factory.opened.size());
        assertEquals("French", page.entries.get(0).title);
        assertTrue(page.url.contains("offset=200"));
        assertTrue(page.nextUrl.contains("offset=300"));
        assertEquals("europe", CatalogFilters.selectedRegion(page.nextUrl));
        for (String url : factory.opened) assertFalse(url.contains("thorshelf_"));
    }

    @Test public void previousRegionScanMovesBackwardsAcrossEmptyPages() throws Exception {
        Factory factory = new Factory(html(listing(row(3, "Japanese", "japan", ENGLISH), search(100), search(300))),
                html(listing(row(2, "American", "north-america", ENGLISH), search(0), search(200))),
                html(listing(row(1, "European", "europe", ENGLISH), "", search(100))));
        CatalogPage page = regionClient(factory).fetch(CatalogFilters.navigation(search(200), "", "europe", true), null, null);
        assertEquals(3, factory.opened.size());
        assertTrue(factory.opened.get(1).contains("offset=100"));
        assertFalse(factory.opened.get(2).contains("offset="));
        assertEquals("European", page.entries.get(0).title);
        assertEquals("", page.previousUrl);
        assertTrue(page.nextUrl.contains("offset=100"));
        assertFalse(CatalogFilters.backwards(page.nextUrl));
    }

    @Test public void regionScanIsBoundedAndLeavesContinuationInsteadOfClaimingTheSearchIsEmpty() throws Exception {
        Response[] responses = new Response[8];
        for (int i = 0; i < responses.length; i++) {
            responses[i] = html(listing(row(1, "Japanese", "japan", ENGLISH),
                    i == 0 ? "" : search((i - 1) * 100), search((i + 1) * 100)));
        }
        Factory factory = new Factory(responses);
        CatalogPage page = regionClient(factory).fetch(CatalogFilters.apply(search(0), "", "europe", false), null, null);
        assertEquals(8, factory.opened.size());
        assertTrue(page.entries.isEmpty());
        assertEquals("0 titles on this page · Europe", page.summary);
        assertTrue(page.nextUrl.contains("offset=800"));
        assertEquals("europe", CatalogFilters.selectedRegion(page.nextUrl));
    }

    @Test public void categoryRootKeepsMatchingCountryEditionsAndBothFilters() throws Exception {
        Factory factory = new Factory(html(listing(category("europe") + category("france") + category("germany")
                + category("north-america") + category("world") + category("unknown-other") + category("digital-demos"), "", "")));
        CatalogPage page = regionClient(factory).fetch(CatalogFilters.apply("https://hshop.erista.me/c/games", "en", "europe", false), null, null);
        assertEquals(3, page.categories.size());
        assertEquals("3 categories on this page · English · Europe", page.summary);
        assertTrue(page.entries.isEmpty());
        for (CatalogPage.Category category : page.categories) {
            assertEquals("europe", CatalogFilters.selectedRegion(category.url));
            assertEquals("en", CatalogFilters.selectedLanguage(category.url));
        }
        assertTrue(page.categories.get(1).url.contains("/s/france"));
    }

    @Test public void changingRegionOnAnOldRegionalPageFetchesTheCategoryRoot() throws Exception {
        String old = CatalogFilters.apply("https://hshop.erista.me/c/games/s/north-america?offset=200", "", "north-america", false);
        String changed = CatalogFilters.apply(old, "", "europe", true);
        Factory factory = new Factory(html(listing(category("france") + category("north-america"), "", "")));
        CatalogPage page = regionClient(factory).fetch(changed, null, null);
        assertEquals(Arrays.asList("https://hshop.erista.me/c/games?count=100"), factory.opened);
        assertEquals(1, page.categories.size());
        assertEquals("1 category on this page · Europe", page.summary);
        assertEquals("france", page.categories.get(0).title);
    }

    @Test public void filteredDetailsKeepTheirIdentityAndNeverExcludeTheChosenGame() throws Exception {
        String detail = "<main><div class='title-name'><h2>Selected game</h2></div>"
                + "<div class='basic-meta'><p>Title ID: <span>" + ENGLISH + "</span></p></div></main>";
        Factory factory = new Factory(html(detail));
        CatalogPage page = regionClient(factory).fetch("https://hshop.erista.me/t/1?thorshelf_region=europe&thorshelf_lang=fr", null, null);
        assertEquals(1, page.entries.size());
        assertEquals("https://hshop.erista.me/t/1", page.url);
        assertEquals(Arrays.asList("https://hshop.erista.me/t/1"), factory.opened);
    }

    @Test public void redirectAlsoStripsInternalFiltersBeforeSendingTheNextHttpRequest() throws Exception {
        Response redirect = html("");
        redirect.status = 302;
        redirect.location = "/search/results?q=Mario&thorshelf_region=japan&thorshelf_lang=fr&thorshelf_direction=previous";
        Factory factory = new Factory(redirect, html(listing(row(1, "European", "europe", ENGLISH), "", "")));
        CatalogPage page = regionClient(factory).fetch(CatalogFilters.apply(SEARCH, "", "europe", false), null, null);
        assertEquals("European", page.entries.get(0).title);
        assertEquals("europe", CatalogFilters.selectedRegion(page.url));
        assertEquals(2, factory.opened.size());
        for (String url : factory.opened) assertFalse(url.contains("thorshelf_"));
    }

    private static CatalogClient regionClient(Factory factory) {
        return new CatalogClient(factory, () -> { throw new AssertionError("Region-only/category/detail navigation loaded language metadata"); });
    }

    private static String search(int offset) { return SEARCH + "&count=100" + (offset == 0 ? "" : "&offset=" + offset); }

    private static String row(int id, String title, String region, String titleId) {
        return "<a href='/t/" + id + "' class='list-entry'><div class='base-info'><h3>" + title
                + "</h3><h4>content in <span>games</span> ➞ <span>" + region + "</span></h4></div>"
                + "<div class='meta'><div class='meta-content'><span>" + titleId
                + "</span><span>Title ID</span></div></div></a>";
    }

    private static String category(String region) {
        return "<a href='/c/games/s/" + region + "' class='list-entry'><div class='base-info'><h3>" + region + "</h3></div></a>";
    }

    private static String listing(String rows, String previous, String next) {
        return "<main><div class='sitedesc'><h1>Search Results</h1></div><div class='list'><div class='next-container'>"
                + "<span class='nospace'>Source count</span><div class='next'>"
                + (previous.isEmpty() ? "" : "<a href='" + previous.replace("&", "&amp;") + "'>show previous 100</a>")
                + (next.isEmpty() ? "" : "<a href='" + next.replace("&", "&amp;") + "'>show next 100</a>")
                + "</div></div><div class='elements'>" + rows + "</div></div></main>";
    }

    private static Response html(String body) throws Exception { return new Response(body); }

    private static final class Factory implements CatalogClient.ConnectionFactory {
        final Deque<Response> responses = new ArrayDeque<>();
        final List<String> opened = new ArrayList<>();
        Factory(Response... responses) { this.responses.addAll(Arrays.asList(responses)); }
        @Override public HttpURLConnection open(URL url) {
            opened.add(url.toString());
            if (responses.isEmpty()) throw new AssertionError("Unexpected HTTP request: " + url);
            return responses.remove();
        }
    }

    private static final class Response extends HttpURLConnection {
        final byte[] body;
        int status = 200;
        String location;
        Response(String body) throws Exception { super(new URL(SEARCH)); this.body = body.getBytes(StandardCharsets.UTF_8); }
        @Override public int getResponseCode() { return status; }
        @Override public String getContentType() { return "text/html; charset=utf-8"; }
        @Override public String getHeaderField(String name) { return "Location".equalsIgnoreCase(name) ? location : null; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
