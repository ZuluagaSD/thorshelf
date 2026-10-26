package dev.thorshelf.app;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Exercises real catalog HTML parsing against synthetic HTTP and language metadata sources. */
public class CatalogLanguageClientTest {
    private static final String SEARCH = "https://hshop.erista.me/search/results?q=Pokemon&qt=Text&lgy=false";
    private static final String MULTI = "0004000000ABCD00";
    private static final String FRENCH = "0004000000123500";
    private static final String UNKNOWN = "0004000000123600";

    @Test public void filtersByExactTitleLanguageAndExcludesFrenchOnlyAndUnknown() throws Exception {
        FakeConnection response = html(listing(row(1, "Multilingual", MULTI.toLowerCase(java.util.Locale.ROOT))
                + row(2, "French edition", FRENCH) + row(3, "Unknown edition", UNKNOWN), "", ""));
        QueueFactory factory = new QueueFactory(response);
        Metadata metadata = new Metadata();
        CatalogPage page = new CatalogClient(factory, metadata).fetch(
                CatalogLanguageFilter.apply(SEARCH, "en", false), "catalog-agent", "session=private");
        assertEquals(1, page.entries.size());
        assertEquals("Multilingual", page.entries.get(0).title);
        assertEquals(Arrays.asList("en", "fr", "de"), page.entries.get(0).languages);
        assertEquals("https://hshop.erista.me/t/1", page.entries.get(0).url);
        assertEquals("1 title on this page · English", page.summary);
        assertEquals("en", CatalogLanguageFilter.selected(page.url));
        assertEquals(1, metadata.loads);
        assertEquals("session=private", response.getRequestProperty("Cookie"));
        assertEquals("catalog-agent", response.getRequestProperty("User-Agent"));
        assertFalse(factory.opened.get(0).contains("thorshelf_"));
        assertTrue(response.closed);
        assertTrue(response.disconnected);
    }

    @Test public void unfilteredBrowsingNeverLoadsLanguageMetadata() throws Exception {
        QueueFactory factory = new QueueFactory(html(listing(row(1, "Multilingual", MULTI)
                + row(2, "French edition", FRENCH) + row(3, "Unknown edition", UNKNOWN), "", "")));
        CatalogPage page = new CatalogClient(factory, () -> {
            throw new AssertionError("Unfiltered browsing requested language metadata");
        }).fetch(SEARCH, null, null);
        assertEquals(3, page.entries.size());
        assertEquals("Unfiltered result count", page.summary);
        assertEquals(SEARCH, page.url);
    }

    @Test public void extrasWithReusedGameIdsNeverInheritOriginalGameLanguages() throws Exception {
        String japaneseId = "0004000000123700";
        String translation = row(4, "English fan translation", japaneseId)
                .replace("<span>games</span>", "<span>extras</span>");
        QueueFactory factory = new QueueFactory(
                html(listing(row(1, "Original Japanese game", japaneseId) + translation, "", "")),
                html(listing(translation, "", "")));
        CatalogClient client = new CatalogClient(factory,
                () -> Collections.singletonMap(japaneseId, Collections.singletonList("ja")));
        CatalogPage filtered = client.fetch(CatalogLanguageFilter.apply(SEARCH, "ja", false), null, null);
        assertEquals(1, filtered.entries.size());
        assertEquals("Original Japanese game", filtered.entries.get(0).title);
        assertEquals(Collections.singletonList("ja"), filtered.entries.get(0).languages);
        // Even an already cached language map must not label an Extras title sharing that ID.
        CatalogPage unfiltered = client.fetch(SEARCH, null, null);
        assertEquals(1, unfiltered.entries.size());
        assertEquals("English fan translation", unfiltered.entries.get(0).title);
        assertEquals("extras", unfiltered.entries.get(0).category);
        assertTrue(unfiltered.entries.get(0).languages.isEmpty());
    }

    @Test public void nextSkipsPagesWithoutMatchingLanguagesAndPreservesContinuation() throws Exception {
        QueueFactory factory = new QueueFactory(
                html(listing(row(2, "French edition", FRENCH), "", search(100))),
                html(listing(row(3, "Unknown edition", UNKNOWN), search(0), search(200))),
                html(listing(row(1, "Multilingual", MULTI), search(100), search(300))));
        CatalogPage page = new CatalogClient(factory, new Metadata()).fetch(
                CatalogLanguageFilter.apply(search(0), "en", false), null, null);
        assertEquals(3, factory.opened.size());
        assertTrue(factory.opened.get(1).contains("offset=100"));
        assertTrue(factory.opened.get(2).contains("offset=200"));
        assertEquals(1, page.entries.size());
        assertTrue(page.url.contains("offset=200"));
        assertEquals("en", CatalogLanguageFilter.selected(page.nextUrl));
        assertTrue(page.nextUrl.contains("offset=300"));
        assertFalse(CatalogLanguageFilter.backwards(page.nextUrl));
        assertTrue(page.previousUrl.contains("offset=100"));
        assertTrue(CatalogLanguageFilter.backwards(page.previousUrl));
        for (String opened : factory.opened) assertFalse(opened.contains("thorshelf_"));
    }

    @Test public void previousSkipsEmptyFilteredPagesBackwardsInsteadOfGoingForward() throws Exception {
        QueueFactory factory = new QueueFactory(
                html(listing(row(2, "French edition", FRENCH), search(100), search(300))),
                html(listing(row(3, "Unknown edition", UNKNOWN), search(0), search(200))),
                html(listing(row(1, "Multilingual", MULTI), "", search(100))));
        CatalogPage page = new CatalogClient(factory, new Metadata()).fetch(
                CatalogLanguageFilter.navigation(search(200), "en", true), null, null);
        assertEquals(3, factory.opened.size());
        assertTrue(factory.opened.get(0).contains("offset=200"));
        assertTrue(factory.opened.get(1).contains("offset=100"));
        assertFalse(factory.opened.get(2).contains("offset="));
        assertEquals(1, page.entries.size());
        assertEquals("", page.previousUrl);
        assertTrue(page.nextUrl.contains("offset=100"));
        assertEquals("en", CatalogLanguageFilter.selected(page.nextUrl));
        assertFalse(CatalogLanguageFilter.backwards(page.nextUrl));
        for (String opened : factory.opened) assertFalse(opened.contains("thorshelf_"));
    }

    @Test public void categoriesKeepSelectionWithoutFetchingUnneededMetadata() throws Exception {
        String categories = "<a href='/c/games/s/world' class='list-entry'><div class='base-info'>"
                + "<h3>World</h3><h4>Worldwide games</h4></div></a>";
        QueueFactory factory = new QueueFactory(html(listing(categories, "", "")));
        CatalogPage page = new CatalogClient(factory, () -> {
            throw new AssertionError("Category navigation requested language metadata");
        }).fetch(CatalogLanguageFilter.apply("https://hshop.erista.me/c/games", "fr", false), null, null);
        assertEquals(1, page.categories.size());
        assertTrue(page.entries.isEmpty());
        assertEquals("World", page.categories.get(0).title);
        assertEquals("fr", CatalogLanguageFilter.selected(page.categories.get(0).url));
        assertTrue(CatalogLanguageFilter.sourceUrl(page.categories.get(0).url).startsWith(
                "https://hshop.erista.me/c/games/s/world"));
        assertEquals("Unfiltered result count", page.summary);
    }

    @Test public void metadataFailureDoesNotSilentlyShowUnfilteredGames() throws Exception {
        FakeConnection response = html(listing(row(1, "Multilingual", MULTI), "", ""));
        IOException failure = new IOException("Language metadata unavailable");
        CatalogClient client = new CatalogClient(new QueueFactory(response), () -> { throw failure; });
        assertSame(failure, assertThrows(IOException.class, () -> client.fetch(
                CatalogLanguageFilter.apply(SEARCH, "en", false), null, null)));
        assertTrue(response.closed);
        assertTrue(response.disconnected);
    }

    @Test public void sparseCatalogStopsAfterEightPagesAndOffersAnotherNext() throws Exception {
        FakeConnection[] responses = new FakeConnection[8];
        for (int i = 0; i < responses.length; i++) {
            responses[i] = html(listing(row(2, "French edition", FRENCH),
                    i == 0 ? "" : search((i - 1) * 100), search((i + 1) * 100)));
        }
        QueueFactory factory = new QueueFactory(responses);
        CatalogPage page = new CatalogClient(factory, new Metadata()).fetch(
                CatalogLanguageFilter.apply(search(0), "en", false), null, null);
        assertEquals(8, factory.opened.size());
        assertTrue(page.entries.isEmpty());
        assertEquals("0 titles on this page · English", page.summary);
        assertTrue(page.nextUrl.contains("offset=800"));
        assertEquals("en", CatalogLanguageFilter.selected(page.nextUrl));
    }

    @Test public void repeatedEmptyPageIsRejectedBeforeAnotherRequest() throws Exception {
        QueueFactory factory = new QueueFactory(html(listing(row(2, "French edition", FRENCH), "", search(0))));
        CatalogClient client = new CatalogClient(factory, new Metadata());
        assertThrows(IOException.class, () -> client.fetch(
                CatalogLanguageFilter.apply(search(0), "en", false), null, null));
        assertEquals(1, factory.opened.size());
    }

    @Test public void verificationAndDirectDetailsNeverTriggerMetadataLookup() throws Exception {
        String detail = "<main><div class='title-name'><h2>French edition</h2></div>"
                + "<div class='basic-meta'><p>Title ID: <span>" + FRENCH + "</span></p></div></main>";
        QueueFactory factory = new QueueFactory(html("<main><div class='cf-turnstile'></div></main>"), html(detail));
        CatalogClient client = new CatalogClient(factory, () -> {
            throw new AssertionError("Verification/detail requested language metadata");
        });
        CatalogPage verification = client.fetch(CatalogLanguageFilter.apply(SEARCH, "en", false), null, null);
        assertTrue(verification.verificationRequired);
        CatalogPage title = client.fetch("https://hshop.erista.me/t/2?thorshelf_lang=en", null, null);
        assertEquals(1, title.entries.size());
        assertEquals("French edition", title.title);
        assertEquals("https://hshop.erista.me/t/2", title.url);
        assertEquals("https://hshop.erista.me/t/2", factory.opened.get(1));
    }

    private static String search(int offset) {
        return SEARCH + "&count=100" + (offset == 0 ? "" : "&offset=" + offset);
    }

    private static String row(int id, String title, String titleId) {
        return "<a href='/t/" + id + "' class='list-entry block-link'><div class='base-info'><h3>" + title
                + "</h3><h4>content in <span>games</span> ➞ <span>world</span></h4></div>"
                + "<div class='meta'><div class='meta-content'><span>" + titleId
                + "</span><span>Title ID</span></div></div></a>";
    }

    private static String listing(String rows, String previous, String next) {
        return "<main><div class='sitedesc'><h1>Search Results</h1></div><div class='list'>"
                + "<div class='next-container'><span class='nospace'>Unfiltered result count</span><div class='next'>"
                + (previous.isEmpty() ? "" : "<a href='" + previous.replace("&", "&amp;") + "'>show previous 100</a>")
                + (next.isEmpty() ? "" : "<a href='" + next.replace("&", "&amp;") + "'>show next 100</a>")
                + "</div></div><div class='elements'>" + rows + "</div></div></main>";
    }

    private static FakeConnection html(String body) throws Exception { return new FakeConnection(body); }

    private static final class Metadata implements CatalogClient.LanguageSource {
        int loads;
        @Override public Map<String, List<String>> load() {
            loads++;
            Map<String, List<String>> values = new LinkedHashMap<>();
            values.put(MULTI, Arrays.asList("en", "fr", "de"));
            values.put(FRENCH, Collections.singletonList("fr"));
            return values;
        }
    }

    private static final class QueueFactory implements CatalogClient.ConnectionFactory {
        final Deque<FakeConnection> responses = new ArrayDeque<>();
        final List<String> opened = new ArrayList<>();
        QueueFactory(FakeConnection... responses) { this.responses.addAll(Arrays.asList(responses)); }
        @Override public HttpURLConnection open(URL url) {
            opened.add(url.toString());
            if (responses.isEmpty()) throw new AssertionError("Unexpected HTTP request: " + url);
            return responses.removeFirst();
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        final byte[] body;
        boolean disconnected;
        boolean closed;
        FakeConnection(String body) throws Exception {
            super(new URL(SEARCH));
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }
        @Override public int getResponseCode() { return 200; }
        @Override public String getContentType() { return "text/html; charset=utf-8"; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(body) {
                @Override public void close() throws IOException { closed = true; super.close(); }
            };
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
