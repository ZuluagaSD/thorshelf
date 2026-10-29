package dev.thorshelf.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Reads public catalog pages and checks download headers. Call from a worker thread. */
public class CatalogClient {
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 4;
    private final ConnectionFactory connections;
    private final LanguageSource languageSource;
    private volatile Map<String, List<String>> knownLanguages = Collections.emptyMap();

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    interface LanguageSource {
        Map<String, List<String>> load() throws IOException;
    }

    public CatalogClient() {
        this(url -> (HttpURLConnection) url.openConnection());
    }

    CatalogClient(ConnectionFactory connections) {
        this(connections, new ThreeDsLanguages()::load);
    }

    CatalogClient(ConnectionFactory connections, LanguageSource languageSource) {
        this.connections = connections;
        this.languageSource = languageSource;
    }

    public CatalogPage fetch(String url, String userAgent, String cookie) throws IOException {
        if (!CatalogParser.isCatalogPageUrl(url)) throw new IOException("Unsupported catalog address.");
        String language = CatalogFilters.selectedLanguage(url);
        String region = CatalogFilters.selectedRegion(url);
        boolean backwards = CatalogFilters.backwards(url);
        String current = CatalogFilters.sourceUrl(url);
        Set<String> visited = new HashSet<>();
        // Bound work per click; a remaining Next/Previous control continues a sparse catalog.
        for (int scanned = 0; scanned < 8; scanned++) {
            if (!visited.add(current)) throw new IOException("The catalog repeated a results page.");
            CatalogPage page = fetchPage(current, userAgent, cookie);
            boolean filtering = (!language.isEmpty() || !region.isEmpty()) && !page.isDetail();
            if (filtering && !language.isEmpty() && !page.entries.isEmpty()) knownLanguages = languageSource.load();
            List<CatalogPage.Entry> entries = new ArrayList<>();
            for (CatalogPage.Entry entry : page.entries) {
                // Fan translations and ROM hacks can reuse the original title ID. Cartridge
                // metadata establishes languages only for the regular Games catalog.
                List<String> languages = "games".equalsIgnoreCase(entry.category)
                        ? knownLanguages.get(entry.titleId.toUpperCase(Locale.ROOT)) : null;
                if (languages == null) languages = entry.languages;
                if (!filtering || ((language.isEmpty() || languages.contains(language))
                        && CatalogRegions.matches(entry.region, region))) {
                    entries.add(new CatalogPage.Entry(entry.url, entry.title, entry.category, entry.region,
                            entry.size, entry.contentType, entry.titleId, entry.artworkUrl, languages));
                }
            }
            List<CatalogPage.Category> categories = new ArrayList<>();
            for (CatalogPage.Category category : page.categories) {
                if (CatalogFilters.includesCategory(category.url, region)) {
                    categories.add(new CatalogPage.Category(CatalogFilters.apply(category.url, language, region, false),
                            category.title, category.description));
                }
            }
            String continuation = backwards ? page.previousUrl : page.nextUrl;
            if (filtering && entries.isEmpty() && categories.isEmpty() && !page.verificationRequired
                    && !continuation.isEmpty() && scanned < 7) {
                current = CatalogFilters.sourceUrl(CatalogFilters.apply(continuation, language, region, false));
                continue;
            }
            String filterLabel = language.isEmpty() ? "" : CatalogLanguages.label(language);
            if (!region.isEmpty()) filterLabel += (filterLabel.isEmpty() ? "" : " · ") + CatalogRegions.label(region);
            String summary = filtering && categories.isEmpty() && !page.verificationRequired
                    ? entries.size() + (entries.size() == 1 ? " title" : " titles") + " on this page · "
                    + filterLabel : page.summary;
            if (filtering && !region.isEmpty() && !categories.isEmpty() && !page.verificationRequired) {
                summary = categories.size() + (categories.size() == 1 ? " category" : " categories")
                        + " on this page · " + filterLabel;
            }
            return new CatalogPage(CatalogFilters.navigation(page.url, language, region, backwards),
                    page.title, summary, entries, categories,
                    CatalogFilters.navigation(page.nextUrl, language, region, false),
                    CatalogFilters.navigation(page.previousUrl, language, region, true),
                    page.downloadUrl, page.verificationRequired);
        }
        throw new IOException("The catalog could not finish filtering this page.");
    }

    private CatalogPage fetchPage(String url, String userAgent, String cookie) throws IOException {
        if (!CatalogParser.isCatalogPageUrl(url)) throw new IOException("Unsupported catalog address.");
        validateHeader(userAgent);
        validateHeader(cookie);
        String originalHost = host(url);
        String current = url;
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection connection = open(current, "GET", userAgent,
                    originalHost.equalsIgnoreCase(host(current)) ? cookie : null, null, false);
            try {
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    if (redirects >= MAX_REDIRECTS) throw new IOException("Too many catalog redirects.");
                    current = CatalogFilters.sourceUrl(redirect(current, connection.getHeaderField("Location")));
                    if (!CatalogParser.isCatalogPageUrl(current)
                            || !originalHost.equalsIgnoreCase(host(current))) {
                        throw new IOException("The catalog redirected to an unsupported address.");
                    }
                    continue;
                }
                requireSuccess(connection, status, false);
                String mime = mime(connection.getContentType());
                if (!mime.equals("text/html") && !mime.equals("application/xhtml+xml")) {
                    throw new IOException("The catalog did not return a web page.");
                }
                if (connection.getContentLengthLong() > MAX_HTML_BYTES) {
                    throw new IOException("The catalog page is too large.");
                }
                String encoding = connection.getContentEncoding();
                if (encoding != null && !encoding.trim().isEmpty()
                        && !encoding.equalsIgnoreCase("identity") && !encoding.equalsIgnoreCase("gzip")) {
                    throw new IOException("The catalog returned an unsupported response encoding.");
                }
                try (InputStream raw = connection.getInputStream();
                     InputStream decoded = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(raw) : raw) {
                    String html = readHtml(decoded);
                    try {
                        return CatalogParser.parse(current, html);
                    } catch (IllegalArgumentException invalidPage) {
                        throw new IOException("The catalog page could not be read.", invalidPage);
                    }
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    /** Checks headers only; a minimal range request supports servers that reject HEAD. */
    public DownloadInfo inspectDownload(String url, String userAgent, String cookie, String referer)
            throws IOException {
        return inspectDownload(url, userAgent, cookie, referer, GamePlatform.THREE_DS);
    }

    /** Inspects public DS assets without attaching any browser cookies or referring page. */
    public DownloadInfo inspectNdsDownload(String url, String userAgent) throws IOException {
        return inspectDownload(url, userAgent, null, null, GamePlatform.DS);
    }

    /** Inspects public GBA cartridges without attaching browser cookies or a referring page. */
    public DownloadInfo inspectGbaDownload(String url, String userAgent) throws IOException {
        return inspectDownload(url, userAgent, null, null, GamePlatform.GBA);
    }

    private DownloadInfo inspectDownload(String url, String userAgent, String cookie, String referer,
                                         GamePlatform platform) throws IOException {
        if (!isAllowedDownload(url, platform)) throw new IOException("Unsupported download address.");
        validateHeader(userAgent);
        validateHeader(cookie);
        String originalHost = host(url);
        String safeReferer = DownloadPolicy.isCatalog(referer) ? CatalogFilters.sourceUrl(referer) : null;
        String current = url;
        int redirects = 0;
        boolean rangeRequest = false;
        while (true) {
            HttpURLConnection connection = open(current, rangeRequest ? "GET" : "HEAD", userAgent,
                    originalHost.equalsIgnoreCase(host(current)) ? cookie : null, safeReferer, rangeRequest);
            try {
                if (platform == GamePlatform.GBA) connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                if (!rangeRequest && (status == 405 || status == 501)) {
                    rangeRequest = true;
                    continue;
                }
                if (isRedirect(status)) {
                    if (redirects++ >= MAX_REDIRECTS) throw new IOException("Too many download redirects.");
                    current = redirect(current, connection.getHeaderField("Location"), platform == GamePlatform.GBA);
                    if (!isAllowedRedirect(url, current, platform)) {
                        throw new IOException("The download redirected to an unsupported address.");
                    }
                    continue;
                }
                if (platform != GamePlatform.THREE_DS) {
                    if (status < 200 || status >= 300
                            || "challenge".equalsIgnoreCase(connection.getHeaderField("cf-mitigated"))) {
                        throw new IOException("The " + platform.shortLabel + " download server returned HTTP " + status
                                + ". Please try again later.");
                    }
                } else {
                    requireSuccess(connection, status, true);
                }
                if ((rangeRequest || platform == GamePlatform.GBA) && status != 200 && status != 206) {
                    throw new IOException("The server did not return download metadata.");
                }
                String contentType = connection.getContentType();
                String disposition = connection.getHeaderField("Content-Disposition");
                String name;
                switch (platform) {
                    case GBA: name = DownloadFilename.resolveGba(current, disposition, contentType); break;
                    case DS: name = DownloadFilename.resolveNds(current, disposition, contentType); break;
                    default: name = DownloadFilename.resolve(current, disposition, contentType); break;
                }
                if (name == null) {
                    throw new IOException("The download response is not a supported content file.");
                }
                long size = status == 206 ? rangeTotal(connection.getHeaderField("Content-Range"))
                        : connection.getContentLengthLong();
                if (platform == GamePlatform.GBA) {
                    String encoding = connection.getContentEncoding();
                    if (encoding != null && !encoding.trim().isEmpty() && !encoding.equalsIgnoreCase("identity")) {
                        throw new IOException("The GBA server returned an unsupported response encoding.");
                    }
                    if (size == 0 || (connection.getHeaderField("Content-Length") != null
                            && connection.getContentLengthLong() < 0)) {
                        throw new IOException("The server returned invalid download size metadata.");
                    }
                }
                return new DownloadInfo(current, name, contentType == null ? "" : contentType, size);
            } finally {
                connection.disconnect();
            }
        }
    }

    private static boolean isAllowedDownload(String url, GamePlatform platform) {
        switch (platform) {
            case GBA: return DownloadPolicy.isAllowedGbaDownload(url);
            case DS: return DownloadPolicy.isAllowedNdsDownload(url);
            default: return DownloadPolicy.isAllowedDownload(url);
        }
    }

    private static boolean isAllowedRedirect(String source, String destination, GamePlatform platform) {
        switch (platform) {
            case GBA: return DownloadPolicy.isAllowedGbaRedirect(source, destination);
            case DS: return DownloadPolicy.isAllowedNdsRedirect(source, destination);
            default: return DownloadPolicy.isAllowedDownload(destination);
        }
    }

    private HttpURLConnection open(String url, String method, String userAgent, String cookie,
                                  String referer, boolean rangeRequest) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Catalog request cancelled.");
        HttpURLConnection connection = connections.open(new URL(url));
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", method.equals("GET") && !rangeRequest
                ? "text/html,application/xhtml+xml" : "application/octet-stream,*/*;q=0.8");
        if (rangeRequest) {
            connection.setRequestProperty("Range", "bytes=0-0");
            connection.setRequestProperty("Accept-Encoding", "identity");
        } else if (method.equals("GET")) connection.setRequestProperty("Accept-Encoding", "gzip");
        if (userAgent != null && !userAgent.isEmpty()) connection.setRequestProperty("User-Agent", userAgent);
        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        if (referer != null) connection.setRequestProperty("Referer", referer);
        return connection;
    }

    private static long rangeTotal(String contentRange) throws IOException {
        Matcher range = Pattern.compile("bytes 0-0/([1-9][0-9]*)", Pattern.CASE_INSENSITIVE)
                .matcher(contentRange == null ? "" : contentRange.trim());
        if (range.matches()) {
            try { return Long.parseLong(range.group(1)); }
            catch (NumberFormatException ignored) { /* Reject an overflowing size. */ }
        }
        throw new IOException("The server returned invalid download size metadata.");
    }

    private static void requireSuccess(HttpURLConnection connection, int status, boolean metadataOnly)
            throws IOException {
        if (status == HttpURLConnection.HTTP_FORBIDDEN
                || "challenge".equalsIgnoreCase(connection.getHeaderField("cf-mitigated"))) {
            throw new VerificationRequiredException("hShop requires a security check before continuing.");
        }
        if (status != HttpURLConnection.HTTP_OK && !(metadataOnly && status >= 200 && status < 300)) {
            throw new IOException("hShop returned HTTP " + status + ". Please try again later.");
        }
    }

    private static String readHtml(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Catalog request cancelled.");
            if (count > MAX_HTML_BYTES - output.size()) throw new IOException("The catalog page is too large.");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String redirect(String current, String location) throws IOException {
        return redirect(current, location, false);
    }

    private static String redirect(String current, String location, boolean strictAssetPath) throws IOException {
        if (location == null || location.isEmpty() || location.length() > 16384) {
            throw new IOException("The server returned an invalid redirect.");
        }
        try {
            URI target = new URI(location);
            // Validate before URI.resolve can normalize away traversal in a relative Location.
            if (strictAssetPath && !DownloadPolicy.isSafeAssetPath(target)) {
                throw new IOException("The server returned an invalid redirect.");
            }
            return new URI(current).resolve(target).toString();
        } catch (URISyntaxException | IllegalArgumentException invalidAddress) {
            throw new IOException("The server returned an invalid redirect.", invalidAddress);
        }
    }

    private static String host(String url) {
        return URI.create(url).getHost();
    }

    private static String mime(String contentType) {
        return contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static void validateHeader(String value) throws IOException {
        if (value == null) return;
        if (value.length() > 16384 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\0') >= 0) {
            throw new IOException("Invalid browser session metadata.");
        }
    }

    public static final class DownloadInfo {
        public final String url;
        public final String name;
        public final String mime;
        public final long size;

        DownloadInfo(String url, String name, String mime, long size) {
            this.url = url;
            this.name = name;
            this.mime = mime;
            this.size = size;
        }
    }

    public static final class VerificationRequiredException extends IOException {
        public VerificationRequiredException(String message) { super(message); }
    }
}
