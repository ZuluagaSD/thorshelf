package dev.thorshelf.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads DSiDrop's public metadata and the legacy NDS-Shop/Kekatsu catalogs. */
public final class NdsCatalog {
    public static final String HOME = "nds://catalog/";
    static final String API_BASE_URL = "https://db-nds-shop.fr/api/v1/catalog";
    public static final String DEFAULT_DATABASE_URL =
            "https://raw.githubusercontent.com/0x1a4-dev/DSiDrop/main/public/data/nds.json";
    static final String ARCHIVE_ITEM = "pack-roms-nintendo-ds-eu-usa-jap-rabbits-games";
    static final String DOWNLOAD_BASE = "https://archive.org/download/" + ARCHIVE_ITEM + "/";
    static final String COVER_BASE = "https://raw.githubusercontent.com/libretro-thumbnails/"
            + "Nintendo_-_Nintendo_DS/master/Named_Boxarts/";
    static final int MAX_DATABASE_BYTES = 4 * 1024 * 1024;
    static final int PAGE_SIZE = 40;
    private static final int MAX_ROWS = 50_000;
    private static final int MAX_LINE_LENGTH = 8192;
    private static final int MAX_FIELDS = 65;
    private static final int MAX_JSON_STRUCTURAL_TOKENS = 100_000;
    private final Map<String, Item> items;
    private final boolean apiCatalog;
    private final boolean curated;
    private final Map<String, List<Item>> groups;

    private NdsCatalog(Map<String, Item> items) {
        this(items, false);
    }

    private NdsCatalog(Map<String, Item> items, boolean apiCatalog) {
        this(items, apiCatalog, false);
    }

    private NdsCatalog(Map<String, Item> items, boolean apiCatalog, boolean curated) {
        this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        this.apiCatalog = apiCatalog;
        this.curated = curated;
        this.groups = new LinkedHashMap<>();
        if (curated) for (Item item : items.values()) {
            groups.computeIfAbsent(stableId("dsidrop-game", normalized(item.groupTitle)), ignored -> new ArrayList<>()).add(item);
        }
    }

    public static boolean isPageUrl(String url) {
        return route(url) != null;
    }

    public static boolean isDetailUrl(String url) {
        Route route = route(url);
        return route != null && !route.id.isEmpty() && !route.group;
    }

    public static boolean isEditionSelectionUrl(String url) {
        Route route = route(url);
        return route != null && route.group;
    }

    public static String searchUrl(String query) {
        return listingUrl(clean(query, 256), 1);
    }

    static String withLanguage(String url, String code) {
        Route route = route(url);
        if (route == null) throw new IllegalArgumentException("Unsupported Nintendo DS catalog address.");
        if (route.group) return groupUrl(route.id, route.page, CatalogLanguages.normalize(code), route.region);
        if (!route.id.isEmpty()) return url;
        return listingUrl(route.query, route.page, CatalogLanguages.normalize(code), route.region);
    }

    static String withRegion(String url, String code) {
        Route route = route(url);
        if (route == null) throw new IllegalArgumentException("Unsupported Nintendo DS catalog address.");
        if (route.group) return groupUrl(route.id, route.page, route.language, CatalogRegions.normalize(code));
        if (!route.id.isEmpty()) return url;
        return listingUrl(route.query, route.page, route.language, CatalogRegions.normalize(code));
    }

    static NdsCatalog parse(String text) {
        if (text == null || text.length() > MAX_DATABASE_BYTES) {
            throw new IllegalArgumentException("The Nintendo DS database is too large or missing.");
        }
        Map<String, Item> items = new LinkedHashMap<>();
        try (BufferedReader input = new BufferedReader(new StringReader(text))) {
            String version = input.readLine();
            if (version != null && version.startsWith("\uFEFF")) version = version.substring(1);
            if (!"1".equals(version)) {
                throw new IllegalArgumentException("The Nintendo DS database has an unsupported format.");
            }
            String delimiterLine = input.readLine();
            if (delimiterLine == null || delimiterLine.length() != 1
                    || (delimiterLine.charAt(0) != '\t'
                    && (delimiterLine.charAt(0) < 32 || delimiterLine.charAt(0) > 126))) {
                throw new IllegalArgumentException("The Nintendo DS database delimiter is invalid.");
            }
            String delimiter = Pattern.quote(delimiterLine);
            String line;
            int rows = 0;
            while ((line = input.readLine()) != null) {
                if (++rows > MAX_ROWS || line.length() > MAX_LINE_LENGTH) {
                    throw new IllegalArgumentException("The Nintendo DS database exceeds its limits.");
                }
                if (line.trim().isEmpty()) continue;
                String[] fields = line.split(delimiter, MAX_FIELDS + 1);
                if (fields.length < 9 || fields.length > MAX_FIELDS || (fields.length - 9) % 2 != 0) continue;
                if (!"nds".equalsIgnoreCase(fields[1].trim())) continue;
                String title = clean(fields[0], 256);
                String fileName = fields[6].trim();
                String lowerName = fileName.toLowerCase(Locale.ROOT);
                String download = fields[5].trim();
                if (title.isEmpty() || fileName.length() > 255 || fileName.contains("/")
                        || fileName.contains("\\") || hasControls(fileName)) continue;
                if (!lowerName.endsWith(".nds") && !lowerName.endsWith(".zip")) continue;
                if (!DownloadPolicy.isAllowedNdsDownload(download)) continue;
                long bytes;
                try {
                    String size = fields[7].trim();
                    if (!size.isEmpty() && !size.matches("[0-9]{1,19}")) continue;
                    bytes = size.isEmpty() ? 0 : Long.parseLong(size);
                } catch (NumberFormatException badSize) {
                    continue;
                }
                String id = stableId(download, fileName);
                String region = clean(fields[2], 80);
                if (region.equalsIgnoreCase("ANY")) region = "All regions";
                CatalogPage.Entry entry = new CatalogPage.Entry(HOME + "title/" + id,
                        title, "Nintendo DS", region, humanSize(bytes),
                        lowerName.endsWith(".zip") ? "Nintendo DS ZIP" : "Nintendo DS game", "", fields[8].trim());
                // Artwork is validated separately; archive destination paths are never applied.
                items.putIfAbsent(id, new Item(entry, download, clean(fields[4], 160), clean(fields[3], 80)));
            }
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return new NdsCatalog(items);
    }

    /** Uses only the vetted archive and artwork roots; source metadata cannot redirect downloads. */
    static NdsCatalog parseDsiDrop(String text) {
        if (text == null || text.length() > MAX_DATABASE_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_DATABASE_BYTES) {
            throw new IllegalArgumentException("The Nintendo DS catalog is too large or missing.");
        }
        checkJsonLimits(text, 300_000);
        try {
            JSONTokener tokener = new JSONTokener(text);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0) {
                throw new IllegalArgumentException("The Nintendo DS catalog response is invalid.");
            }
            JSONObject root = (JSONObject) parsed;
            JSONObject meta = root.optJSONObject("meta");
            JSONArray rows = root.optJSONArray("roms");
            if (meta == null || rows == null || rows.length() > 20_000
                    || !"nds".equals(apiText(meta, "id", 16))
                    || !"zip".equals(apiText(meta, "ext", 16))
                    || !"zip".equals(apiText(meta, "arch", 16))
                    || !"nds".equals(apiText(meta, "out", 16))
                    || !"md5".equals(apiText(meta, "checksum", 16))
                    || !("https://archive.org/details/" + ARCHIVE_ITEM).equals(apiText(meta, "source", 512))
                    || !DOWNLOAD_BASE.substring(0, DOWNLOAD_BASE.length() - 1).equals(apiText(meta, "download", 512))
                    || !"https://github.com/libretro-thumbnails/Nintendo_-_Nintendo_DS".equals(apiText(meta, "coverSource", 512))
                    || !COVER_BASE.equals(apiText(meta, "coverBase", 512))
                    || integer(meta.opt("total"), 0, 20_000) != rows.length()) {
                throw new IllegalArgumentException("The Nintendo DS catalog source metadata is invalid.");
            }
            Map<String, Item> items = new LinkedHashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("Catalog parsing cancelled.");
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Item item;
                try { item = dsiDropItem(row); }
                catch (IllegalArgumentException malformed) { continue; }
                if (item == null) continue;
                String id = item.entry.url.substring((HOME + "title/").length());
                if (items.putIfAbsent(id, item) != null) {
                    throw new IllegalArgumentException("The Nintendo DS catalog contains duplicate downloads.");
                }
            }
            return new NdsCatalog(items, false, true);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("The Nintendo DS catalog response is invalid.", malformed);
        }
    }

    private static Item dsiDropItem(JSONObject row) {
        String name = strictName(row, "n");
        String title = apiText(row, "t", 256);
        if (name.isEmpty() || title.isEmpty() || row.length() > 16) return null;
        List<String> tags = stringList(row, "g", 32, 128);
        for (String tag : tags) if (excludedTag(tag)) return null;
        java.util.regex.Matcher annotations = Pattern.compile("[\\(\\[]([^()\\[\\]]+)[\\)\\]]").matcher(name);
        while (annotations.find()) if (excludedTag(annotations.group(1))) return null;
        long bytes = integer(row.opt("s"), 1, 2L * 1024 * 1024 * 1024);
        if (!apiText(row, "m", 32).matches("[0-9a-fA-F]{32}")
                || !apiText(row, "h", 40).matches("[0-9a-fA-F]{40}")) return null;
        List<String> regions = stringList(row, "a", 16, 80);
        if (regions.isEmpty()) {
            String region = apiText(row, "r", 80);
            if (!region.isEmpty()) regions.add(region);
        }
        List<String> displayRegions = new ArrayList<>();
        for (String region : regions) {
            String display = region.equalsIgnoreCase("usa") ? "USA" : region;
            if (!displayRegions.contains(display)) displayRegions.add(display);
        }
        String download = DOWNLOAD_BASE + encodedPath(name + ".zip");
        if (!DownloadPolicy.isAllowedNdsDownload(download)) return null;
        String artwork = "";
        if (row.opt("c") != null && row.opt("c") != JSONObject.NULL) {
            String cover = strictName(row, "c");
            if (!cover.isEmpty()) artwork = COVER_BASE + encodedPath(cover + ".png");
        }
        List<String> languages = NdsCatalogLanguages.resolve(title, name, apiText(row, "l", 128), displayRegions);
        String id = stableId(download, name + ".zip");
        CatalogPage.Entry entry = new CatalogPage.Entry(HOME + "title/" + id, name, "Nintendo DS",
                String.join(", ", displayRegions), humanSize(bytes), "Nintendo DS ZIP", "", artwork, languages);
        return new Item(entry, download, "", "", new String[0], false, title);
    }

    private static List<String> stringList(JSONObject row, String field, int count, int length) {
        List<String> values = new ArrayList<>();
        if (!row.has(field)) return values;
        JSONArray list = row.optJSONArray(field);
        if (list == null || list.length() > count) throw new IllegalArgumentException("Invalid catalog list.");
        for (int i = 0; i < list.length(); i++) {
            Object value = list.opt(i);
            if (!(value instanceof String) || ((String) value).length() > length || hasControls((String) value)) {
                throw new IllegalArgumentException("Invalid catalog list value.");
            }
            String clean = ((String) value).trim();
            if (!clean.isEmpty() && !values.contains(clean)) values.add(clean);
        }
        return values;
    }

    private static String strictName(JSONObject row, String field) {
        Object value = row.opt(field);
        if (!(value instanceof String)) return "";
        String name = (String) value;
        if (name.isEmpty() || name.length() > 240 || !name.equals(name.trim()) || hasControls(name)
                || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) return "";
        return name;
    }

    private static boolean excludedTag(String value) {
        String tag = value.trim().toLowerCase(Locale.ROOT);
        return tag.matches("(?:bad dump|b|demo|proto(?:type)?|beta|kiosk|save data|bios|program|video|promo|nfr|not for resale)(?:\\b.*)?");
    }

    private static String encodedPath(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    /** Parses one bounded API page; rowCount counts source rows, including unsupported entries. */
    static ApiPage parseApiPage(String text) {
        if (text == null || text.length() > MAX_DATABASE_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_DATABASE_BYTES) {
            throw new IllegalArgumentException("The Nintendo DS catalog page is too large or missing.");
        }
        checkJsonLimits(text);
        try {
            JSONTokener tokener = new JSONTokener(text);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0) {
                throw new IllegalArgumentException("The Nintendo DS catalog response is invalid.");
            }
            JSONObject root = (JSONObject) parsed;
            int page = (int) integer(root.opt("page"), 1, MAX_ROWS);
            int limit = (int) integer(root.opt("limit"), 1, 200);
            int total = (int) integer(root.opt("total"), 0, MAX_ROWS);
            Object revisionValue = root.opt("revision");
            String revision;
            if (revisionValue instanceof String) {
                revision = (String) revisionValue;
                if (revision.isEmpty() || revision.length() > 128 || hasControls(revision)) {
                    throw new IllegalArgumentException("The Nintendo DS catalog revision is invalid.");
                }
            } else revision = Long.toString(integer(revisionValue, 0, Long.MAX_VALUE));
            JSONArray rows = root.optJSONArray("entries");
            long offset = (long) (page - 1) * limit;
            if (rows == null || rows.length() > limit || offset > total
                    || (total == 0 && page != 1) || rows.length() > total - offset) {
                throw new IllegalArgumentException("The Nintendo DS catalog pagination is invalid.");
            }
            Map<String, Item> items = new LinkedHashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                List<Item> parsedItems;
                try {
                    parsedItems = apiItems(row);
                } catch (IllegalArgumentException malformedRow) {
                    continue;
                }
                for (Item item : parsedItems) {
                    String id = item.entry.url.substring((HOME + "title/").length());
                    if (items.putIfAbsent(id, item) != null) {
                        throw new IllegalArgumentException("The Nintendo DS catalog contains duplicate downloads.");
                    }
                }
            }
            return new ApiPage(new NdsCatalog(items, true), page, limit, total, rows.length(), revision);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("The Nintendo DS catalog response is invalid.", malformed);
        }
    }

    /** Combines a complete API snapshot without allowing repeated downloads across pages. */
    static NdsCatalog merge(List<NdsCatalog> catalogs) {
        if (catalogs == null || catalogs.isEmpty() || catalogs.size() > MAX_ROWS) {
            throw new IllegalArgumentException("The Nintendo DS catalog snapshot is incomplete.");
        }
        Map<String, Item> merged = new LinkedHashMap<>();
        for (NdsCatalog catalog : catalogs) {
            if (catalog == null || !catalog.apiCatalog) {
                throw new IllegalArgumentException("The Nintendo DS catalog snapshot is invalid.");
            }
            for (Map.Entry<String, Item> item : catalog.items.entrySet()) {
                if (merged.putIfAbsent(item.getKey(), item.getValue()) != null || merged.size() > MAX_ROWS) {
                    throw new IllegalArgumentException("The Nintendo DS catalog snapshot repeats or exceeds its downloads.");
                }
            }
        }
        return new NdsCatalog(merged, true);
    }

    private static List<Item> apiItems(JSONObject row) {
        String title = apiText(row, "title", 256);
        JSONArray systems = row.optJSONArray("systems");
        if (title.isEmpty() || systems == null || systems.length() == 0 || systems.length() > 16) {
            return Collections.emptyList();
        }
        boolean ds = false;
        for (int i = 0; i < systems.length(); i++) {
            Object system = systems.opt(i);
            if (!(system instanceof String) || ((String) system).length() > 32) return Collections.emptyList();
            ds |= "DS".equalsIgnoreCase(((String) system).trim());
        }
        if (!ds) return Collections.emptyList();
        String author = apiText(row, "author", 160);
        String version = apiText(row, "version", 160);
        String titleId = apiText(row, "titleId", 32);
        if (!titleId.isEmpty() && !titleId.matches("[A-Za-z0-9]{4,16}")) return Collections.emptyList();
        String region = apiRegion(version);
        String artwork = "";
        for (String field : new String[]{"boxart", "image", "icon"}) {
            Object candidate = row.opt(field);
            if (candidate instanceof String && ArtworkPolicy.isAllowed((String) candidate)) {
                artwork = (String) candidate;
                break;
            }
        }
        JSONObject downloads = row.optJSONObject("downloads");
        if (downloads == null || downloads.length() > 32) return Collections.emptyList();
        List<Item> result = new ArrayList<>();
        for (Iterator<String> names = downloads.keys(); names.hasNext();) {
            String name = names.next();
            if (name.isEmpty() || name.length() > 255 || hasControls(name) || name.contains("/")
                    || name.contains("\\") || !name.toLowerCase(Locale.ROOT).endsWith(".nds")) continue;
            JSONObject download = downloads.optJSONObject(name);
            if (download == null) continue;
            try {
                String url = apiText(download, "url", 2048);
                if (!DownloadPolicy.isAllowedNdsDownload(url)) continue;
                String path = URI.create(url).getPath();
                // The explicit NDS file and its URL must agree; ignore CIA forwarders and scripts.
                if (path == null || !name.equals(path.substring(path.lastIndexOf('/') + 1))) continue;
                long bytes = integer(download.opt("size"), 0, Long.MAX_VALUE);
                String id = stableId(url, name);
                CatalogPage.Entry entry = new CatalogPage.Entry(HOME + "title/" + id,
                        title, "Nintendo DS", region, humanSize(bytes), "Nintendo DS game", titleId, artwork,
                        CatalogLanguages.nds(version, name, titleId));
                result.add(new Item(entry, url, author, version, englishAliases(title, titleId), true));
            } catch (IllegalArgumentException malformedDownload) {
                // One malformed optional download must not hide another valid NDS file.
            }
        }
        return result;
    }

    private static String apiText(JSONObject object, String field, int limit) {
        Object value = object.opt(field);
        if (value == null || value == JSONObject.NULL) return "";
        if (!(value instanceof String) || ((String) value).length() > limit) {
            throw new IllegalArgumentException("Invalid Nintendo DS catalog field.");
        }
        return clean((String) value, limit);
    }

    private static long integer(Object value, long minimum, long maximum) {
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException("Invalid Nintendo DS catalog number.");
        }
        long number = ((Number) value).longValue();
        if (number < minimum || number > maximum) throw new IllegalArgumentException("Nintendo DS catalog number exceeds its limits.");
        return number;
    }

    private static String apiRegion(String version) {
        List<String> regions = new ArrayList<>();
        java.util.regex.Matcher groups = Pattern.compile("\\(([^()]{1,160})\\)").matcher(version);
        while (groups.find()) {
            String group = groups.group(1).trim();
            if (!CatalogRegions.fromMetadata(group).isEmpty() && !regions.contains(group)) regions.add(group);
        }
        if (!regions.isEmpty()) return String.join(", ", regions);
        return CatalogRegions.fromMetadata(version).isEmpty() ? "" : version;
    }

    private static String[] englishAliases(String title, String titleId) {
        String nativeTitle = normalized(title);
        if ("IRAF".equals(titleId) && nativeTitle.equals("pokemon version blanche")) {
            return new String[]{"pokemon white", "pokemon white version"};
        }
        if ("IRBF".equals(titleId) && nativeTitle.equals("pokemon version noire")) {
            return new String[]{"pokemon black", "pokemon black version"};
        }
        if ("IRDF".equals(titleId) && nativeTitle.equals("pokemon version blanche 2")) {
            return new String[]{"pokemon white 2", "pokemon white2", "pokemon white version 2"};
        }
        if ("IREF".equals(titleId) && nativeTitle.equals("pokemon version noire 2")) {
            return new String[]{"pokemon black 2", "pokemon black2", "pokemon black version 2"};
        }
        return new String[0];
    }

    private static void checkJsonLimits(String text) {
        checkJsonLimits(text, MAX_JSON_STRUCTURAL_TOKENS);
    }

    private static void checkJsonLimits(String text, int maxTokens) {
        int depth = 0;
        int structuralTokens = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (!quoted && "{}[],:".indexOf(character) >= 0
                    && ++structuralTokens > maxTokens) {
                // Count ignored fields too, before JSONTokener allocates their arrays and objects.
                throw new IllegalArgumentException("The Nintendo DS catalog contains too many JSON elements.");
            }
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') quoted = true;
            else if (character == '\'' || character == '/' || character == '#') {
                // Reject lenient strings/comments so the depth check agrees with the JSON parser.
                throw new IllegalArgumentException("The Nintendo DS catalog is not valid JSON.");
            } else if (character == '{' || character == '[') {
                if (++depth > 32) throw new IllegalArgumentException("The Nintendo DS catalog is nested too deeply.");
            } else if (character == '}' || character == ']') {
                if (--depth < 0) throw new IllegalArgumentException("The Nintendo DS catalog is not valid JSON.");
            }
        }
        if (quoted || depth != 0) throw new IllegalArgumentException("The Nintendo DS catalog is not valid JSON.");
    }

    CatalogPage page(String url) {
        Route route = route(url);
        if (route == null) throw new IllegalArgumentException("Unsupported Nintendo DS catalog address.");
        if (curated && (route.group || route.id.isEmpty())) return curatedPage(route);
        if (!route.id.isEmpty()) {
            Item item = items.get(route.id);
            if (item == null) throw new IllegalArgumentException("This Nintendo DS title is no longer in the catalog.");
            String summary = curated ? "From DSiDrop. Cover art: libretro."
                    : item.apiSource ? "From NDS-Shop." : "Homebrew from Universal-DB / Kekatsu DS.";
            if (!item.author.isEmpty()) summary += " By " + item.author + ".";
            if (!item.version.isEmpty()) summary += " Version " + item.version + ".";
            if (item.entry.contentType.endsWith("ZIP")) {
                summary += " ZIP downloads must contain one .nds file; additional files are not installed.";
            }
            return new CatalogPage(url, item.entry.title, summary, Collections.singletonList(item.entry),
                    Collections.emptyList(), "", "", item.downloadUrl, false);
        }
        String query = normalized(route.query);
        List<CatalogPage.Entry> matches = new ArrayList<>();
        for (Item item : items.values()) {
            if (item.matches(query) && (route.language.isEmpty() || item.entry.languages.contains(route.language))
                    && CatalogRegions.matches(item.entry.region, route.region)) {
                matches.add(item.entry);
            }
        }
        int totalPages = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(route.page, totalPages);
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, matches.size());
        String noun = matches.size() == 1 ? " title" : " titles";
        String summary = matches.size() + (apiCatalog ? noun + " · NDS-Shop"
                : " homebrew" + noun + " · Universal-DB / Kekatsu DS");
        if (!route.language.isEmpty()) summary += " · " + CatalogLanguages.label(route.language);
        if (!route.region.isEmpty()) summary += " · " + CatalogRegions.label(route.region);
        if (totalPages > 1) summary += " · Page " + page + " of " + totalPages;
        return new CatalogPage(listingUrl(route.query, page, route.language, route.region), apiCatalog ? "Nintendo DS games" : "Nintendo DS homebrew", summary,
                matches.subList(start, end), Collections.emptyList(),
                page < totalPages ? listingUrl(route.query, page + 1, route.language, route.region) : "",
                page > 1 ? listingUrl(route.query, page - 1, route.language, route.region) : "", "", false);
    }

    boolean containsTitle(String url) {
        Route route = route(url);
        return route != null && !route.group && !route.id.isEmpty() && items.containsKey(route.id);
    }

    private CatalogPage curatedPage(Route route) {
        List<CatalogPage.Entry> entries = new ArrayList<>();
        String title = "Nintendo DS games";
        if (route.group) {
            List<Item> editions = groups.get(route.id);
            if (editions == null) throw new IllegalArgumentException("This Nintendo DS game is no longer in the catalog.");
            title = editions.get(0).groupTitle;
            for (Item edition : editions) if (matchesFilters(edition, route)) entries.add(edition.entry);
        } else {
            for (Map.Entry<String, List<Item>> group : groups.entrySet()) {
                List<Item> matching = new ArrayList<>();
                for (Item item : group.getValue()) if (item.matches(normalized(route.query)) && matchesFilters(item, route)) matching.add(item);
                if (matching.isEmpty()) continue;
                Item first = matching.get(0);
                if (matching.size() == 1) {
                    CatalogPage.Entry e = first.entry;
                    entries.add(new CatalogPage.Entry(e.url, first.groupTitle, e.category, e.region, e.size,
                            e.contentType, e.titleId, e.artworkUrl, e.languages));
                } else {
                    LinkedHashSet<String> regions = new LinkedHashSet<>();
                    LinkedHashSet<String> languages = new LinkedHashSet<>();
                    String artwork = "";
                    for (Item item : matching) {
                        Collections.addAll(regions, item.entry.region.split(", "));
                        languages.addAll(item.entry.languages);
                        if (artwork.isEmpty()) artwork = item.entry.artworkUrl;
                    }
                    regions.remove("");
                    entries.add(new CatalogPage.Entry(groupUrl(group.getKey(), 1, route.language, route.region), first.groupTitle,
                            "Nintendo DS", String.join(", ", regions), "", matching.size() + " editions", "", artwork,
                            new ArrayList<>(languages)));
                }
            }
        }
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(route.page, pages);
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, entries.size());
        String summary = entries.size() + (route.group ? entries.size() == 1 ? " edition" : " editions"
                : entries.size() == 1 ? " game" : " games") + " · DSiDrop";
        if (!route.language.isEmpty()) summary += " · " + CatalogLanguages.label(route.language);
        if (!route.region.isEmpty()) summary += " · " + CatalogRegions.label(route.region);
        if (pages > 1) summary += " · Page " + page + " of " + pages;
        return new CatalogPage(curatedUrl(route, page), title, summary, entries.subList(start, end),
                Collections.emptyList(), page < pages ? curatedUrl(route, page + 1) : "",
                page > 1 ? curatedUrl(route, page - 1) : "", "", false);
    }

    private static boolean matchesFilters(Item item, Route route) {
        return (route.language.isEmpty() || item.entry.languages.contains(route.language))
                && CatalogRegions.matches(item.entry.region, route.region);
    }

    private static String curatedUrl(Route route, int page) {
        return route.group ? groupUrl(route.id, page, route.language, route.region)
                : listingUrl(route.query, page, route.language, route.region);
    }

    private static Route route(String value) {
        if (value == null || value.length() > 2048) return null;
        try {
            URI uri = new URI(value);
            if (!"nds".equals(uri.getScheme()) || !"catalog".equals(uri.getRawAuthority())
                    || uri.getRawFragment() != null) return null;
            String path = uri.getRawPath();
            if (path != null && path.matches("/title/[a-f0-9]{24}")) {
                return uri.getRawQuery() == null ? new Route(path.substring(7), "", 1, "", "") : null;
            }
            boolean group = path != null && path.matches("/game/[a-f0-9]{24}");
            if (!group && !"/".equals(path)) return null;
            String query = "";
            String language = "";
            String region = "";
            int page = 1;
            boolean hasQuery = false;
            boolean hasPage = false;
            boolean hasLanguage = false;
            boolean hasRegion = false;
            if (uri.getRawQuery() != null) {
                for (String parameter : uri.getRawQuery().split("&", -1)) {
                    String[] pair = parameter.split("=", 2);
                    if (pair.length != 2) return null;
                    if (pair[0].equals("q") && !hasQuery && !group) {
                        query = URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name());
                        if (query.length() > 256 || hasControls(query)) return null;
                        hasQuery = true;
                    } else if (pair[0].equals("page") && !hasPage && pair[1].matches("[1-9][0-9]{0,5}")) {
                        page = Integer.parseInt(pair[1]);
                        hasPage = true;
                    } else if (pair[0].equals("lang") && !hasLanguage) {
                        String decoded = URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name());
                        language = CatalogLanguages.normalize(decoded);
                        if (decoded.length() > 16 || hasControls(decoded) || !decoded.equals(decoded.trim())
                                || (language.isEmpty() && !decoded.isEmpty() && !decoded.equalsIgnoreCase("all"))) return null;
                        hasLanguage = true;
                    } else if (pair[0].equals("region") && !hasRegion) {
                        String decoded = URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name());
                        region = CatalogRegions.normalize(decoded);
                        if (decoded.length() > 32 || hasControls(decoded) || !decoded.equals(decoded.trim())
                                || (region.isEmpty() && !decoded.isEmpty() && !decoded.equalsIgnoreCase("all"))) return null;
                        hasRegion = true;
                    } else return null;
                }
            }
            return new Route(group ? path.substring(6) : "", query, page, language, region, group);
        } catch (URISyntaxException | IllegalArgumentException | java.io.UnsupportedEncodingException invalid) {
            return null;
        }
    }

    private static String listingUrl(String query, int page) {
        return listingUrl(query, page, "", "");
    }

    private static String listingUrl(String query, int page, String language, String region) {
        try {
            List<String> parameters = new ArrayList<>();
            if (!query.isEmpty() || page > 1) parameters.add("q=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name()));
            if (page > 1) parameters.add("page=" + page);
            if (!language.isEmpty()) parameters.add("lang=" + language);
            if (!region.isEmpty()) parameters.add("region=" + region);
            return HOME + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String groupUrl(String id, int page, String language, String region) {
        List<String> parameters = new ArrayList<>();
        if (page > 1) parameters.add("page=" + page);
        if (!language.isEmpty()) parameters.add("lang=" + language);
        if (!region.isEmpty()) parameters.add("region=" + region);
        return HOME + "game/" + id + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
    }

    private static String stableId(String url, String fileName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((url + "\n" + fileName).getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder(24);
            for (int i = 0; i < 12; i++) id.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String clean(String text, int limit) {
        if (text == null) return "";
        String clean = text.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
        if (clean.length() <= limit) return clean;
        int end = Character.isHighSurrogate(clean.charAt(limit - 1)) ? limit - 1 : limit;
        return clean.substring(0, end).trim();
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean hasControls(String value) {
        return Pattern.compile("[\\p{Cntrl}\\p{Cf}]").matcher(value).find();
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    private static final class Route {
        final String id;
        final String query;
        final int page;
        final String language;
        final String region;
        final boolean group;
        Route(String id, String query, int page, String language, String region) {
            this(id, query, page, language, region, false);
        }
        Route(String id, String query, int page, String language, String region, boolean group) {
            this.id = id;
            this.query = query;
            this.page = page;
            this.language = language;
            this.region = region;
            this.group = group;
        }
    }

    static final class ApiPage {
        final NdsCatalog catalog;
        final int page;
        final int limit;
        final int total;
        final int rowCount;
        final String revision;

        ApiPage(NdsCatalog catalog, int page, int limit, int total, int rowCount, String revision) {
            this.catalog = catalog;
            this.page = page;
            this.limit = limit;
            this.total = total;
            this.rowCount = rowCount;
            this.revision = revision;
        }
    }

    private static final class Item {
        final CatalogPage.Entry entry;
        final String downloadUrl;
        final String author;
        final String version;
        final String[] aliases;
        final boolean apiSource;
        final String groupTitle;
        Item(CatalogPage.Entry entry, String downloadUrl, String author, String version) {
            this(entry, downloadUrl, author, version, new String[0], false);
        }

        Item(CatalogPage.Entry entry, String downloadUrl, String author, String version,
             String[] aliases, boolean apiSource) {
            this(entry, downloadUrl, author, version, aliases, apiSource, "");
        }

        Item(CatalogPage.Entry entry, String downloadUrl, String author, String version,
             String[] aliases, boolean apiSource, String groupTitle) {
            this.entry = entry;
            this.downloadUrl = downloadUrl;
            this.author = author;
            this.version = version;
            this.aliases = aliases;
            this.apiSource = apiSource;
            this.groupTitle = groupTitle;
        }

        boolean matches(String query) {
            if (!groupTitle.isEmpty()) {
                String title = searchWords(groupTitle);
                for (String word : searchWords(query).split(" ")) if (!word.isEmpty() && !title.contains(word)) return false;
                return true;
            }
            if (query.isEmpty() || normalized(entry.title).contains(query)) return true;
            for (String alias : aliases) if (alias.contains(query)) return true;
            return false;
        }
    }

    private static String searchWords(String value) {
        return normalized(value).replaceAll("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
