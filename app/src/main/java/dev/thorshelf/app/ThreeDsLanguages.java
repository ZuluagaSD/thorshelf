package dev.thorshelf.app;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/** Runtime-only 3dsdb language metadata, keyed by exact title ID; no game files are requested. */
final class ThreeDsLanguages {
    static final String DATABASE_URL = "https://3dsdb.com/xml.php";
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final int MAX_ROWS = 20_000;
    private static final int MAX_REDIRECTS = 4;
    private static final long CACHE_NANOS = 24L * 60 * 60 * 1_000_000_000;
    private final ConnectionFactory connections;
    private final LongSupplier nanoTime;
    private Map<String, List<String>> cached;
    private long cachedAt;

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    ThreeDsLanguages() { this(url -> (HttpURLConnection) url.openConnection()); }

    ThreeDsLanguages(ConnectionFactory connections) { this(connections, System::nanoTime); }

    ThreeDsLanguages(ConnectionFactory connections, LongSupplier nanoTime) {
        this.connections = connections;
        this.nanoTime = nanoTime;
    }

    synchronized Map<String, List<String>> load() throws IOException {
        checkCancelled();
        if (cached != null && nanoTime.getAsLong() - cachedAt < CACHE_NANOS) return cached;
        Map<String, List<String>> complete = fetch();
        checkCancelled();
        cached = complete;
        cachedAt = nanoTime.getAsLong();
        return cached;
    }

    private Map<String, List<String>> fetch() throws IOException {
        String current = DATABASE_URL;
        for (int redirects = 0; ; redirects++) {
            checkCancelled();
            if (!allowed(current)) throw new IOException("Unsupported 3DS language database address.");
            HttpURLConnection connection = connections.open(new URL(current));
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/xml,text/xml;q=0.9");
                connection.setRequestProperty("Accept-Encoding", "gzip");
                int status = connection.getResponseCode();
                checkCancelled();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects >= MAX_REDIRECTS) throw new IOException("Too many 3DS language database redirects.");
                    current = redirect(current, connection.getHeaderField("Location"));
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("The 3DS language database returned HTTP " + status + ". Please try again later.");
                }
                String mime = connection.getContentType();
                mime = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!mime.equals("application/xml") && !mime.equals("text/xml")) {
                    throw new IOException("The 3DS language source did not return an XML database.");
                }
                if (connection.getContentLengthLong() > MAX_BYTES) throw tooLarge();
                String encoding = connection.getContentEncoding();
                encoding = encoding == null ? "" : encoding.trim();
                if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("identity") && !encoding.equalsIgnoreCase("gzip")) {
                    throw new IOException("The 3DS language database returned an unsupported encoding.");
                }
                try (InputStream wire = new BoundedInputStream(connection.getInputStream());
                     InputStream expanded = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(wire) : wire) {
                    return parse(readUtf8(expanded));
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[8192];
        while (true) {
            checkCancelled();
            int count = input.read(buffer);
            if (count == -1) break;
            if (count > MAX_BYTES - output.size()) throw tooLarge();
            output.write(buffer, 0, count);
        }
        checkCancelled();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
    }

    private static Map<String, List<String>> parse(String text) throws IOException {
        // Check before SAX sees the document, including declarations in internal subsets. XML markup
        // is case-sensitive, and input has already been decoded as strict UTF-8 (not UTF-16 bytes).
        if (text.contains("<!DOCTYPE") || text.contains("<!ENTITY") || text.indexOf('\0') >= 0) {
            throw new IOException("The 3DS language database contains unsupported XML declarations.");
        }
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External XML resources are not allowed.");
            });
            LanguageHandler handler = new LanguageHandler();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            // SAX visits each small field without constructing an unbounded document object tree.
            reader.parse(new InputSource(new StringReader(text)));
            checkCancelled();
            if (handler.depth != 0 || handler.languages.isEmpty()) {
                throw new IOException("The 3DS language database did not contain usable language metadata.");
            }
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (Map.Entry<String, LinkedHashSet<String>> entry : handler.languages.entrySet()) {
                result.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
            return Collections.unmodifiableMap(result);
        } catch (ParserConfigurationException | SAXException invalid) {
            checkCancelled();
            throw new IOException("The 3DS language database is invalid.", invalid);
        }
    }

    private static boolean allowed(String value) {
        if (value == null || value.length() > 2048) return false;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && "3dsdb.com".equalsIgnoreCase(uri.getHost())
                    && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && "/xml.php".equals(uri.getRawPath());
        } catch (URISyntaxException invalid) { return false; }
    }

    private static String redirect(String current, String location) throws IOException {
        if (location == null || location.isEmpty() || location.length() > 2048) {
            throw new IOException("The 3DS language database returned an invalid redirect.");
        }
        try {
            String target = new URI(current).resolve(new URI(location)).toString();
            if (!allowed(target)) throw new IOException("The 3DS language database redirected to an unsupported address.");
            return target;
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            throw new IOException("The 3DS language database returned an invalid redirect.", invalid);
        }
    }

    private static IOException tooLarge() { return new IOException("The 3DS language database is too large."); }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Language lookup cancelled.");
    }

    private static final class LanguageHandler extends DefaultHandler {
        final Map<String, LinkedHashSet<String>> languages = new LinkedHashMap<>();
        int depth;
        int rows;
        int fields;
        String titleId;
        String languageList;
        String field;
        StringBuilder value;

        @Override public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            cancelled();
            if (attributes.getLength() > 0) throw new SAXException("Unexpected language database attributes.");
            if (++depth == 1) {
                if (!"releases".equals(qName)) throw new SAXException("Unexpected language database root.");
            } else if (depth == 2) {
                if (!"release".equals(qName) || ++rows > MAX_ROWS) throw new SAXException("Invalid release count or element.");
                fields = 0;
                titleId = null;
                languageList = null;
            } else if (depth == 3) {
                if (++fields > 32 || qName.length() > 64 || !qName.matches("[A-Za-z][A-Za-z0-9_]*")) {
                    throw new SAXException("Invalid release field.");
                }
                field = qName;
                value = new StringBuilder();
            } else throw new SAXException("Nested release fields are unsupported.");
        }

        @Override public void characters(char[] chars, int start, int length) throws SAXException {
            cancelled();
            if (depth == 3) {
                if (length > 256 - value.length()) throw new SAXException("Language database field is too long.");
                value.append(chars, start, length);
            } else {
                for (int i = start; i < start + length; i++) {
                    if (!Character.isWhitespace(chars[i])) throw new SAXException("Unexpected language database text.");
                }
            }
        }

        @Override public void endElement(String uri, String localName, String qName) throws SAXException {
            cancelled();
            if (depth == 3) {
                if (field.equals("titleid")) {
                    if (titleId != null) throw new SAXException("Duplicate title ID field.");
                    titleId = value.toString().trim();
                } else if (field.equals("languages")) {
                    if (languageList != null) throw new SAXException("Duplicate language field.");
                    languageList = value.toString().trim();
                }
                value = null;
                field = null;
            } else if (depth == 2 && titleId != null && titleId.matches("[0-9a-fA-F]{16}")) {
                List<String> codes = CatalogLanguages.parse(languageList);
                if (!codes.isEmpty()) {
                    languages.computeIfAbsent(titleId.toUpperCase(Locale.ROOT), ignored -> new LinkedHashSet<>()).addAll(codes);
                }
            }
            depth--;
        }

        private static void cancelled() throws SAXException {
            try { checkCancelled(); }
            catch (InterruptedIOException cancelled) { throw new SAXException(cancelled); }
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private int count;
        BoundedInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            checkCancelled();
            int value = in.read();
            if (value != -1 && ++count > MAX_BYTES) throw tooLarge();
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkCancelled();
            int read = in.read(bytes, offset, Math.min(length, MAX_BYTES - count + 1));
            if (read > 0) {
                count += read;
                if (count > MAX_BYTES) throw tooLarge();
            }
            return read;
        }
    }
}
