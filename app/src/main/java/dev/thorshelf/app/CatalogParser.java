package dev.thorshelf.app;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads public hShop HTML as data; no page scripts are executed. */
final class CatalogParser {
    private static final int MAX_HTML_CHARS = 2 * 1024 * 1024;
    private static final int MAX_ROWS = 100;

    private CatalogParser() { }

    static boolean isCatalogPageUrl(String url) {
        if (!DownloadPolicy.isCatalog(url)) return false;
        String path = URI.create(url).getRawPath();
        return path != null && (path.matches("/t/[0-9]{1,12}/?")
                || path.matches("/c/[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*/?")
                || path.matches("/search/results/?"));
    }

    static CatalogPage parse(String url, String html) {
        if (!isCatalogPageUrl(url)) throw new IllegalArgumentException("Unsupported catalog URL");
        if (html == null || html.length() > MAX_HTML_CHARS) {
            throw new IllegalArgumentException("Catalog response is missing or too large");
        }
        Document doc = Jsoup.parse(html);
        List<CatalogPage.Entry> entries = new ArrayList<>();
        List<CatalogPage.Category> categories = new ArrayList<>();
        boolean detail = URI.create(url).getRawPath().matches("/t/[0-9]{1,12}/?");
        String title = text(doc.selectFirst(detail ? ".title-name h2" : "main .sitedesc h1"), 512);
        String summary = "";
        String nextUrl = "";
        String previousUrl = "";
        String downloadUrl = "";

        if (detail) {
            Map<String, String> metadata = new HashMap<>();
            for (Element row : doc.select(".basic-meta > p")) {
                metadata.put(label(row.ownText()), text(row.selectFirst("span"), 128));
            }
            Elements location = doc.select(".title-name h3 a");
            if (!title.isEmpty()) {
                String size = value(metadata, "size");
                int blocks = size.indexOf(", ");
                if (blocks >= 0) size = size.substring(0, blocks);
                String category = location.size() > 0 ? text(location.get(0), 128) : "";
                String region = location.size() > 1 ? text(location.get(1), 128) : "";
                entries.add(new CatalogPage.Entry(url, title, category, region,
                        size, value(metadata, "content type"), value(metadata, "title id"),
                        ThreeDsArtwork.url(category, region, value(metadata, "product code"))));
                downloadUrl = directDownload(doc, url);
            }
            summary = text(doc.selectFirst(".description-container"), 4096);
        } else {
            // Category and region pages also contain rankings with incomplete metadata.
            Element list = doc.selectFirst("main .list:not(.top-list)");
            if (list != null) {
                summary = text(list.selectFirst(".next-container > span.nospace"), 512);
                Set<String> seen = new HashSet<>();
                for (Element row : list.select(".elements > a.list-entry[href]")) {
                    if (entries.size() + categories.size() >= MAX_ROWS) break;
                    String target = catalogLink(url, row.attr("href"));
                    String rowTitle = text(row.selectFirst(".base-info h3"), 512);
                    if (target.isEmpty() || rowTitle.isEmpty() || !seen.add(target)) continue;
                    String path = URI.create(target).getRawPath();
                    if (path.startsWith("/c/")) {
                        categories.add(new CatalogPage.Category(target, rowTitle,
                                text(row.selectFirst(".base-info h4"), 1024)));
                    } else if (path.startsWith("/t/")) {
                        Map<String, String> metadata = metadata(row);
                        Elements location = row.select(".base-info h4 span");
                        String category = location.size() > 0 ? text(location.get(0), 128) : "";
                        String region = location.size() > 1 ? text(location.get(1), 128) : "";
                        entries.add(new CatalogPage.Entry(target, rowTitle, category, region,
                                value(metadata, "size"), value(metadata, "content type"),
                                value(metadata, "title id"),
                                ThreeDsArtwork.url(category, region, value(metadata, "product code"))));
                    }
                }
                for (Element link : list.select(".next a[href]")) {
                    String target = catalogLink(url, link.attr("href"));
                    if (target.isEmpty() || URI.create(target).getRawPath().startsWith("/t/")) continue;
                    String caption = text(link, 128).toLowerCase(Locale.ROOT);
                    if (caption.startsWith("show next ")) nextUrl = target;
                    if (caption.startsWith("show previous ")) previousUrl = target;
                }
            } else {
                summary = text(doc.selectFirst("main .err-container .err h3"), 512);
            }
        }
        boolean verification = downloadUrl.isEmpty() && hasVerification(doc);
        boolean recognized = detail ? !entries.isEmpty()
                : doc.selectFirst("main .list:not(.top-list), main .err-container .err h3") != null;
        if (!recognized && !verification) throw new IllegalArgumentException("Catalog response was not recognized");
        if (title.isEmpty()) title = detail ? "Title details" : "Catalog";
        return new CatalogPage(url, title, summary, entries, categories, nextUrl, previousUrl,
                downloadUrl, verification);
    }

    private static Map<String, String> metadata(Element row) {
        Map<String, String> values = new HashMap<>();
        for (Element field : row.select(".meta > .meta-content")) {
            Elements spans = field.children();
            if (spans.size() >= 2) {
                values.put(label(spans.last().text()), text(spans.first(), 128));
            }
        }
        return values;
    }

    private static String directDownload(Document doc, String pageUrl) {
        String id = URI.create(pageUrl).getRawPath().replaceFirst("^/t/", "").replace("/", "");
        for (Element link : doc.select("#landing-box a[href]")) {
            if (!text(link, 128).toLowerCase(Locale.ROOT).startsWith("direct download")) continue;
            String target = resolve(pageUrl, link.attr("href"));
            if (!DownloadPolicy.isAllowedDownload(target)) continue;
            URI uri = URI.create(target);
            if (("/content/" + id).equals(uri.getRawPath())) return target;
        }
        return "";
    }

    private static boolean hasVerification(Document doc) {
        if (doc.selectFirst(".cf-turnstile, .g-recaptcha, .h-captcha, #challenge-form, [id^=cf-chl], "
                + "input[name=cf-turnstile-response], iframe[src*=challenges.cloudflare.com], "
                + "script[src*=challenges.cloudflare.com], script[src*=challenge-platform]") != null) return true;
        String body = doc.text().toLowerCase(Locale.ROOT);
        return body.contains("complete this security check") || body.contains("verify you are human")
                || body.contains("verify that you are human")
                || ("Just a moment!".equals(doc.title()) && doc.html().contains("_cf_chl_opt"));
    }

    private static String catalogLink(String base, String href) {
        String target = resolve(base, href);
        return isCatalogPageUrl(target) ? target : "";
    }

    private static String resolve(String base, String href) {
        if (href == null || href.isEmpty() || href.length() > 16384) return "";
        try {
            URI resolved = new URI(base).resolve(new URI(href));
            // A document-supplied <base> never changes our trusted source origin.
            return resolved.toString();
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String label(String raw) {
        return raw.replace(":", "").trim().toLowerCase(Locale.ROOT);
    }

    private static String value(Map<String, String> fields, String name) {
        String value = fields.get(name);
        return value == null ? "" : value;
    }

    private static String text(Element element, int limit) {
        if (element == null) return "";
        String text = element.text().replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end);
    }
}
