package dev.thorshelf.app;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;

/** Loads DSiDrop metadata; legacy saved title routes can still resolve against NDS-Shop. */
public class NdsCatalogClient {
    private static final long CACHE_NANOS = 15L * 60 * 1_000_000_000;
    private static final int MAX_REDIRECTS = 4;
    static final int REQUEST_LIMIT = 200;
    static final int MAX_PAGES = 100;
    static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    private final ConnectionFactory connections;
    private final LongSupplier nanoTime;
    private NdsCatalog cached;
    private long cachedAt;
    private NdsCatalog legacyCached;
    private long legacyCachedAt;

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    public NdsCatalogClient() {
        this(url -> (HttpURLConnection) url.openConnection());
    }

    NdsCatalogClient(ConnectionFactory connections) {
        this(connections, System::nanoTime);
    }

    NdsCatalogClient(ConnectionFactory connections, LongSupplier nanoTime) {
        this.connections = connections;
        this.nanoTime = nanoTime;
    }

    public synchronized CatalogPage fetch(String url, String userAgent) throws IOException {
        validateRequest(url, userAgent);
        checkCancelled();
        if (NdsCatalog.isDetailUrl(url) && legacyCached != null && legacyCached.containsTitle(url)) {
            return fetchLegacy(url, userAgent);
        }
        if (cached == null || nanoTime.getAsLong() - cachedAt >= CACHE_NANOS) {
            NdsCatalog complete;
            try { complete = loadCurated(); }
            catch (IOException unavailable) {
                checkCancelled();
                if (NdsCatalog.isDetailUrl(url)) {
                    try { return fetchLegacy(url, userAgent); }
                    catch (IOException legacyUnavailable) { unavailable.addSuppressed(legacyUnavailable); }
                }
                throw unavailable;
            }
            checkCancelled();
            cached = complete;
            cachedAt = nanoTime.getAsLong();
        }
        if (NdsCatalog.isDetailUrl(url) && !cached.containsTitle(url)) return fetchLegacy(url, userAgent);
        try { return cached.page(url); }
        catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
    }

    private NdsCatalog loadCurated() throws IOException {
        try { return NdsCatalog.parseDsiDrop(loadText(NdsCatalog.DEFAULT_DATABASE_URL, 0, new Budget())); }
        catch (IllegalArgumentException invalid) {
            checkCancelled();
            throw new IOException(invalid.getMessage(), invalid);
        }
    }

    /** Only old saved edition routes use this source in the application; normal browsing never does. */
    synchronized CatalogPage fetchLegacy(String url, String userAgent) throws IOException {
        validateRequest(url, userAgent);
        checkCancelled();
        if (legacyCached == null || nanoTime.getAsLong() - legacyCachedAt >= CACHE_NANOS) {
            NdsCatalog complete = loadLegacy();
            checkCancelled();
            legacyCached = complete;
            legacyCachedAt = nanoTime.getAsLong();
        }
        try { return legacyCached.page(url); }
        catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
    }

    private static void validateRequest(String url, String userAgent) throws IOException {
        if (!NdsCatalog.isPageUrl(url)) throw new IOException("Unsupported Nintendo DS catalog address.");
        // Kept for the common client API; browser metadata is never sent to either DS source.
        if (userAgent != null && (userAgent.length() > 16384 || userAgent.indexOf('\r') >= 0
                || userAgent.indexOf('\n') >= 0 || userAgent.indexOf('\0') >= 0)) {
            throw new IOException("Invalid request metadata.");
        }
    }

    private NdsCatalog loadLegacy() throws IOException {
        List<NdsCatalog> pages = new ArrayList<>();
        Budget budget = new Budget();
        int expectedTotal = -1;
        int pageCount = 1;
        String revision = null;
        for (int page = 1; page <= pageCount; page++) {
            checkCancelled();
            NdsCatalog.ApiPage result = loadPage(page, budget);
            if (result.page != page || result.limit != REQUEST_LIMIT || result.total < 0
                    || result.total > MAX_PAGES * REQUEST_LIMIT) {
                throw new IOException("The Nintendo DS catalog returned invalid pagination.");
            }
            if (expectedTotal == -1) {
                expectedTotal = result.total;
                revision = result.revision;
                pageCount = Math.max(1, (expectedTotal + REQUEST_LIMIT - 1) / REQUEST_LIMIT);
            } else if (expectedTotal != result.total || !revision.equals(result.revision)) {
                throw new IOException("The Nintendo DS catalog changed while loading. Please try again.");
            }
            int expectedRows = Math.min(REQUEST_LIMIT, expectedTotal - (page - 1) * REQUEST_LIMIT);
            if (result.rowCount != expectedRows) {
                throw new IOException("The Nintendo DS catalog returned an incomplete page.");
            }
            pages.add(result.catalog);
        }
        checkCancelled();
        try {
            // Merge also rejects duplicate titles across pages, including repeated server responses.
            return NdsCatalog.merge(pages);
        } catch (IllegalArgumentException invalid) {
            throw new IOException(invalid.getMessage(), invalid);
        }
    }

    private NdsCatalog.ApiPage loadPage(int page, Budget budget) throws IOException {
        try { return NdsCatalog.parseApiPage(loadText(pageUrl(page), page, budget)); }
        catch (IllegalArgumentException invalid) {
            checkCancelled();
            throw new IOException(invalid.getMessage(), invalid);
        }
    }

    private String loadText(String current, int page, Budget budget) throws IOException {
        for (int redirects = 0; ; redirects++) {
            checkCancelled();
            if (!isApiUrl(current, page)) throw new IOException("Unsupported Nintendo DS catalog endpoint.");
            HttpURLConnection connection = connections.open(new URL(current));
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Accept-Encoding", "gzip");
                int status = connection.getResponseCode();
                checkCancelled();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects >= MAX_REDIRECTS) throw new IOException("Too many Nintendo DS catalog redirects.");
                    current = redirect(current, connection.getHeaderField("Location"), page);
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("The Nintendo DS catalog returned HTTP " + status + ". Please try again later.");
                }
                String mime = connection.getContentType();
                mime = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!mime.equals("application/json") && !(page == 0 && mime.equals("text/plain"))) {
                    throw new IOException("The Nintendo DS source did not return a JSON catalog.");
                }
                long declaredLength = connection.getContentLengthLong();
                if (declaredLength > NdsCatalog.MAX_DATABASE_BYTES
                        || declaredLength > MAX_TOTAL_BYTES - budget.wireBytes) {
                    throw new IOException("The Nintendo DS catalog is too large.");
                }
                String encoding = connection.getContentEncoding();
                encoding = encoding == null ? "" : encoding.trim();
                if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("identity")
                        && !encoding.equalsIgnoreCase("gzip")) {
                    throw new IOException("The Nintendo DS catalog returned an unsupported encoding.");
                }
                try (InputStream raw = new BoundedInputStream(connection.getInputStream(), budget);
                     InputStream decoded = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(raw) : raw) {
                    String json = readText(decoded, budget);
                    checkCancelled();
                    return json;
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    private static String readText(InputStream input, Budget budget) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[8192];
        while (true) {
            checkCancelled();
            int count = input.read(buffer);
            if (count == -1) break;
            if (count > NdsCatalog.MAX_DATABASE_BYTES - output.size()
                    || count > MAX_TOTAL_BYTES - budget.expandedBytes) {
                throw new IOException("The Nintendo DS catalog is too large.");
            }
            budget.expandedBytes += count;
            output.write(buffer, 0, count);
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
    }

    private static String pageUrl(int page) {
        String base = NdsCatalog.API_BASE_URL;
        int query = base.indexOf('?');
        if (query >= 0) base = base.substring(0, query);
        return base + "?page=" + page + "&limit=" + REQUEST_LIMIT;
    }

    private static boolean isApiUrl(String value, int page) {
        if (value == null || value.length() > 2048 || page < 0 || page > MAX_PAGES) return false;
        if (page == 0) return NdsCatalog.DEFAULT_DATABASE_URL.equals(value);
        try {
            URI uri = new URI(value);
            String query = uri.getRawQuery();
            String pageQuery = "page=" + page;
            String limitQuery = "limit=" + REQUEST_LIMIT;
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "db-nds-shop.fr".equalsIgnoreCase(uri.getHost())
                    && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawFragment() == null && "/api/v1/catalog".equals(uri.getRawPath())
                    && ((pageQuery + "&" + limitQuery).equals(query)
                    || (limitQuery + "&" + pageQuery).equals(query));
        } catch (URISyntaxException invalid) {
            return false;
        }
    }

    private static String redirect(String current, String location, int page) throws IOException {
        if (location == null || location.isEmpty() || location.length() > 2048) {
            throw new IOException("The Nintendo DS source returned an invalid redirect.");
        }
        try {
            String target = new URI(current).resolve(new URI(location)).toString();
            if (!isApiUrl(target, page)) {
                throw new IOException("The Nintendo DS source redirected to an unsupported address.");
            }
            return target;
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            throw new IOException("The Nintendo DS source returned an invalid redirect.", invalid);
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Catalog request cancelled.");
    }

    private static final class Budget {
        int wireBytes;
        int expandedBytes;
    }

    /** Enforces both per-response and whole-snapshot wire limits, even inside gzip headers. */
    private static final class BoundedInputStream extends FilterInputStream {
        private final Budget budget;
        private int count;
        BoundedInputStream(InputStream input, Budget budget) { super(input); this.budget = budget; }
        @Override public int read() throws IOException {
            checkCancelled();
            int value = in.read();
            if (value != -1) countBytes(1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkCancelled();
            int remaining = Math.min(NdsCatalog.MAX_DATABASE_BYTES - count, MAX_TOTAL_BYTES - budget.wireBytes);
            int read = in.read(bytes, offset, Math.min(length, remaining + 1));
            if (read > 0) countBytes(read);
            return read;
        }
        private void countBytes(int read) throws IOException {
            count += read;
            budget.wireBytes += read;
            if (count > NdsCatalog.MAX_DATABASE_BYTES || budget.wireBytes > MAX_TOTAL_BYTES) {
                throw new IOException("The Nintendo DS catalog is too large.");
            }
        }
    }
}
