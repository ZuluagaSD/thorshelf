package dev.thorshelf.app;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

/** Small metadata-only examples following the public 3dsdb release schema; no live requests. */
public class ThreeDsLanguagesTest {
    private static final String RUBY = "000400000011C400";
    private static final String MARIO = "0004000000030800";
    private static final String FRENCH = "00040000000A8800";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><releases>"
            + release(RUBY.toLowerCase(), "en,fr,de,it,es,ja,ko")
            + release(MARIO, "en") + release(MARIO, "en,fr,es") + release(FRENCH, "fr") + "</releases>";

    @Test public void readsExplicitLanguagesByExactTitleIdAndUnionsRevisionMetadata() throws Exception {
        Response response = xml(XML);
        Factory factory = new Factory(response);
        ThreeDsLanguages source = new ThreeDsLanguages(factory);
        Map<String, List<String>> languages = source.load();
        assertEquals(3, languages.size());
        assertEquals(Arrays.asList("en", "fr", "de", "it", "es", "ja", "ko"), languages.get(RUBY));
        assertEquals(Arrays.asList("en", "fr", "es"), languages.get(MARIO));
        assertEquals(Arrays.asList("fr"), languages.get(FRENCH));
        assertSame(languages, source.load());
        assertThrows(UnsupportedOperationException.class, () -> languages.clear());
        assertThrows(UnsupportedOperationException.class, () -> languages.get(RUBY).add("nl"));
        assertEquals(Arrays.asList(ThreeDsLanguages.DATABASE_URL), factory.opened);
        assertNull(response.getRequestProperty("User-Agent"));
        assertNull(response.getRequestProperty("Cookie"));
        assertNull(response.getRequestProperty("Referer"));
        assertFalse(response.getInstanceFollowRedirects());
        assertFalse(response.getUseCaches());
        assertTrue(response.getConnectTimeout() > 0);
        assertTrue(response.getReadTimeout() > 0);
        assertTrue(response.closed);
        assertTrue(response.disconnected);
    }

    @Test public void ignoresUnknownLanguagesAndInvalidIdsWithoutInferringFromRegionOrName() throws Exception {
        String text = "<releases>" + release(RUBY, "en") + release(MARIO, "unknown")
                + release("invalid-title-id", "fr")
                + "<release><titleid>00040000000A8800</titleid><region>FRA</region><name>French edition</name></release>"
                + "<release><titleid>0004000000030700</titleid><languages>Europe</languages></release></releases>";
        Map<String, List<String>> languages = new ThreeDsLanguages(new Factory(xml(text))).load();
        assertEquals(1, languages.size());
        assertEquals(Arrays.asList("en"), languages.get(RUBY));
    }

    @Test public void acceptsXmlEscapingAndCdataWithoutReadingOtherFieldsAsLanguages() throws Exception {
        String text = "<releases><release><name>Mario &amp; Luigi</name><publisher><![CDATA[French publisher]]></publisher>"
                + "<titleid>" + MARIO + "</titleid><languages>EN, FR, ES</languages></release></releases>";
        assertEquals(Arrays.asList("en", "fr", "es"), new ThreeDsLanguages(new Factory(xml(text))).load().get(MARIO));
    }

    @Test public void followsOnlySameEndpointRedirectAndDecodesGzipWithoutMetadata() throws Exception {
        Response redirect = new Response(302).header("Location", "/xml.php");
        Response response = xml("").header("Content-Encoding", "gzip");
        response.body = gzip(XML.getBytes(StandardCharsets.UTF_8));
        Factory factory = new Factory(redirect, response);
        assertEquals(3, new ThreeDsLanguages(factory).load().size());
        assertEquals(2, factory.opened.size());
        assertTrue(redirect.disconnected);
        assertTrue(response.closed);
        assertNull(redirect.getRequestProperty("Cookie"));
        assertNull(response.getRequestProperty("Cookie"));
        assertNull(response.getRequestProperty("Referer"));
        assertNull(response.getRequestProperty("User-Agent"));
    }

    @Test public void rejectsRedirectsToOtherHostsPathsSchemesAndQueriesBeforeOpeningThem() throws Exception {
        for (String location : Arrays.asList("http://3dsdb.com/xml.php", "https://evil.example/xml.php",
                "https://3dsdb.com.evil.example/xml.php", "https://www.3dsdb.com/xml.php",
                "https://3dsdb.com:444/xml.php", "https://user@3dsdb.com/xml.php",
                "https://3dsdb.com/other.xml", "https://3dsdb.com/xml.php?language=en",
                "https://3dsdb.com/xml.php#anything", "")) {
            Response redirect = new Response(302).header("Location", location);
            Factory factory = new Factory(redirect);
            assertThrows(IOException.class, () -> new ThreeDsLanguages(factory).load());
            assertEquals(1, factory.opened.size());
            assertTrue(redirect.disconnected);
        }
    }

    @Test public void limitsRedirectLoops() throws Exception {
        Response[] redirects = new Response[5];
        for (int i = 0; i < redirects.length; i++) redirects[i] = new Response(302).header("Location", "/xml.php");
        Factory factory = new Factory(redirects);
        assertThrows(IOException.class, () -> new ThreeDsLanguages(factory).load());
        assertEquals(5, factory.opened.size());
        for (Response response : redirects) assertTrue(response.disconnected);
    }

    @Test public void rejectsHtmlHttpErrorsUnsupportedEncodingAndDeclaredLengthBeforeBody() throws Exception {
        for (Response response : Arrays.asList(new Response(503), xml(XML).header("Content-Type", "text/html"),
                xml(XML).header("Content-Type", "application/json"), xml(XML).header("Content-Encoding", "br"),
                xml(XML).header("Content-Encoding", "gzip, gzip"),
                xml(XML).header("Content-Length", Integer.toString(ThreeDsLanguages.MAX_BYTES + 1)))) {
            assertThrows(IOException.class, () -> new ThreeDsLanguages(new Factory(response)).load());
            assertFalse(response.requested);
            assertTrue(response.disconnected);
        }
    }

    @Test public void rejectsDoctypeExternalEntitiesAndEntityExpansionBeforeSax() throws Exception {
        for (String declaration : Arrays.asList("<!DOCTYPE releases SYSTEM 'https://evil.example/external.dtd'>",
                "<!DOCTYPE releases [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>",
                "<!DOCTYPE releases [<!ENTITY a 'abc'><!ENTITY b '&a;&a;&a;&a;'>]>",
                "<!DOCTYPE releases [<!ENTITY % x SYSTEM 'https://evil.example/ext.dtd'>%x;]>",
                "<!ENTITY x SYSTEM 'https://evil.example/entity'>")) {
            Response response = xml(declaration + "<releases>" + release(RUBY, "en") + "</releases>");
            IOException failure = assertThrows(IOException.class, () -> new ThreeDsLanguages(new Factory(response)).load());
            assertEquals("The 3DS language database contains unsupported XML declarations.", failure.getMessage());
            assertTrue(response.closed);
            assertTrue(response.disconnected);
        }
    }

    @Test public void rejectsMalformedWrongRootNestedFieldsDuplicateMetadataAndEmptyDatabase() throws Exception {
        for (String text : Arrays.asList("<html><body>error</body></html>", "<releases>", "<releases/>",
                "<releases>" + release(RUBY, "unknown") + "</releases>",
                "<releases><release><name><nested/></name></release></releases>",
                "<releases><release><titleid>" + RUBY + "</titleid><titleid>" + MARIO
                        + "</titleid><languages>en</languages></release></releases>",
                "<releases><release><titleid>" + RUBY + "</titleid><languages>en</languages><languages>fr</languages></release></releases>",
                XML + "<releases/>")) {
            assertThrows(IOException.class, () -> new ThreeDsLanguages(new Factory(xml(text))).load());
        }
    }

    @Test public void boundsEachFieldRowCountAndFieldsPerRelease() throws Exception {
        for (String text : Arrays.asList("<releases><release><name>" + "x".repeat(257) + "</name></release></releases>",
                "<releases>" + "<release/>".repeat(ThreeDsLanguages.MAX_ROWS + 1) + "</releases>",
                "<releases><release>" + "<name>x</name>".repeat(33) + "</release></releases>")) {
            assertThrows(IOException.class, () -> new ThreeDsLanguages(new Factory(xml(text))).load());
        }
    }

    @Test public void boundsRawAndExpandedBodiesAndRejectsInvalidUtf8() throws Exception {
        byte[] excessive = new byte[ThreeDsLanguages.MAX_BYTES + 1];
        Response raw = xml(""); raw.body = excessive;
        Response gzip = xml("").header("Content-Encoding", "gzip"); gzip.body = gzip(excessive);
        Response invalid = xml(""); invalid.body = new byte[]{(byte) 0xff};
        for (Response response : Arrays.asList(raw, gzip, invalid)) {
            assertThrows(IOException.class, () -> new ThreeDsLanguages(new Factory(response)).load());
            assertTrue(response.closed);
            assertTrue(response.disconnected);
        }
    }

    @Test public void boundsGzipWireBytesEvenWhenExpandedBodyIsTiny() throws Exception {
        byte[] compressed = gzip(XML.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] header = Arrays.copyOf(compressed, 10); header[3] = 16;
        body.write(header);
        byte[] comment = new byte[ThreeDsLanguages.MAX_BYTES + 1]; Arrays.fill(comment, (byte) 'x');
        body.write(comment); body.write(0); body.write(compressed, 10, compressed.length - 10);
        Response response = xml("").header("Content-Encoding", "gzip"); response.body = body.toByteArray();
        IOException failure = assertThrows(IOException.class, () -> new ThreeDsLanguages(new Factory(response)).load());
        assertEquals("The 3DS language database is too large.", failure.getMessage());
        assertTrue(response.closed);
        assertTrue(response.disconnected);
    }

    @Test public void cachesForTwentyFourHoursAndRetriesFailedRefreshWithoutRenewingStaleCache() throws Exception {
        long[] clock = {0};
        Factory factory = new Factory(xml(XML), new Response(503), xml("<releases>" + release(RUBY, "es") + "</releases>"));
        ThreeDsLanguages source = new ThreeDsLanguages(factory, () -> clock[0]);
        Map<String, List<String>> first = source.load();
        clock[0] = 24L * 60 * 60 * 1_000_000_000 - 1;
        assertSame(first, source.load());
        clock[0]++;
        assertThrows(IOException.class, source::load);
        Map<String, List<String>> refreshed = source.load();
        assertEquals(Arrays.asList("es"), refreshed.get(RUBY));
        assertSame(refreshed, source.load());
        assertEquals(3, factory.opened.size());
    }

    @Test public void malformedTailNeverPublishesPartiallyParsedMap() throws Exception {
        Factory factory = new Factory(xml("<releases>" + release(RUBY, "en") + "<release>"), xml(XML));
        ThreeDsLanguages source = new ThreeDsLanguages(factory);
        assertThrows(IOException.class, source::load);
        assertEquals(3, source.load().size());
        assertEquals(2, factory.opened.size());
    }

    @Test public void cancellationBeforeNetworkAndDuringBodyClosesResources() throws Exception {
        Factory untouched = new Factory();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> new ThreeDsLanguages(untouched).load());
        } finally { Thread.interrupted(); }
        assertTrue(untouched.opened.isEmpty());
        Response response = xml(XML); response.interruptOnRead = true;
        try {
            assertThrows(InterruptedIOException.class, () -> new ThreeDsLanguages(new Factory(response)).load());
        } finally { Thread.interrupted(); }
        assertTrue(response.closed);
        assertTrue(response.disconnected);
    }

    private static String release(String titleId, String languages) {
        return "<release><id>1</id><name>Example &amp; Title</name><publisher>Example</publisher><region>EUR</region>"
                + "<languages>" + languages + "</languages><serial>CTR-EXMP</serial><titleid>" + titleId
                + "</titleid><type>1</type></release>";
    }

    private static Response xml(String text) throws Exception {
        Response response = new Response(200).header("Content-Type", "application/xml; charset=UTF-8");
        response.body = text.getBytes(StandardCharsets.UTF_8);
        return response;
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(bytes); }
        return output.toByteArray();
    }

    private static final class Factory implements ThreeDsLanguages.ConnectionFactory {
        final Deque<Response> queue = new ArrayDeque<>();
        final List<String> opened = new ArrayList<>();
        Factory(Response... responses) { queue.addAll(Arrays.asList(responses)); }
        @Override public HttpURLConnection open(URL url) {
            opened.add(url.toString());
            if (queue.isEmpty()) throw new AssertionError("Unexpected network request: " + url);
            return queue.remove();
        }
    }

    private static final class Response extends HttpURLConnection {
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final int status;
        byte[] body = new byte[0];
        boolean requested;
        boolean closed;
        boolean disconnected;
        boolean interruptOnRead;
        Response(int status) throws Exception { super(new URL(ThreeDsLanguages.DATABASE_URL)); this.status = status; }
        Response header(String key, String value) { headers.put(key, value); return this; }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public InputStream getInputStream() {
            requested = true;
            return new ByteArrayInputStream(body) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    int read = super.read(bytes, offset, length);
                    if (interruptOnRead) Thread.currentThread().interrupt();
                    return read;
                }
                @Override public void close() throws IOException { closed = true; super.close(); }
            };
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
