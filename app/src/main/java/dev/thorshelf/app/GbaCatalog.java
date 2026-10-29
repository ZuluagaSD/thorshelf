package dev.thorshelf.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable, bundled GBA editions matched by SHA1 and size to No-Intro metadata. */
public final class GbaCatalog {
    public static final String HOME = "gba://catalog/";
    public static final String SOURCE_URL = "https://archive.org/details/CentralArquivista-GameBoyAdvance";
    public static final String DOWNLOAD_BASE = "https://archive.org/download/CentralArquivista-GameBoyAdvance/";
    public static final String COVER_BASE = "https://raw.githubusercontent.com/libretro-thumbnails/"
            + "Nintendo_-_Game_Boy_Advance/master/Named_Boxarts/";
    static final int BUNDLED_COUNT = 3054;
    static final int PAGE_SIZE = 40;
    static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
    static final int MAX_ROWS = 5000;
    private static volatile GbaCatalog bundled;
    private final Map<String, Rom> roms;
    private final Map<String, List<Rom>> groups;

    private GbaCatalog(Map<String, Rom> values) {
        roms = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        Map<String, List<Rom>> grouped = new LinkedHashMap<>();
        for (Rom rom : roms.values()) {
            grouped.computeIfAbsent(groupId(rom.groupTitle), ignored -> new ArrayList<>()).add(rom);
        }
        Map<String, List<Rom>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<Rom>> entry : grouped.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        groups = Collections.unmodifiableMap(immutable);
    }

    static GbaCatalog bundled() throws IOException {
        GbaCatalog result = bundled;
        if (result != null) return result;
        synchronized (GbaCatalog.class) {
            if (bundled != null) return bundled;
            try (InputStream input = GbaCatalog.class.getResourceAsStream("/gba-catalog.tsv")) {
                if (input == null) throw new IOException("The bundled GBA catalog is missing.");
                result = read(input);
                if (result.roms.size() != BUNDLED_COUNT) throw new IOException("The bundled GBA catalog is incomplete.");
                bundled = result;
                return result;
            }
        }
    }

    /** Returns an exact bundled edition, or null for an unknown or non-detail route. */
    public static Rom lookup(String detailUrl) throws IOException {
        Route route = route(detailUrl);
        return route != null && route.detail ? bundled().roms.get(route.id) : null;
    }

    static GbaCatalog read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int count;
        while ((count = input.read(block)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("GBA catalog loading cancelled.");
            if (count > MAX_CATALOG_BYTES - bytes.size()) throw new IOException("The GBA catalog exceeds its size limit.");
            bytes.write(block, 0, count);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return parse(text);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("The bundled GBA catalog is invalid.", invalid);
        }
    }

    static GbaCatalog parse(String text) {
        if (text == null || text.length() > MAX_CATALOG_BYTES) throw invalid();
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || lines.length > MAX_ROWS + 32
                || !lines[0].matches("# thorshelf-gba-v1\\t[0-9]{1,4}")) throw invalid();
        int expected = Integer.parseInt(lines[0].substring(lines[0].indexOf('\t') + 1));
        if (expected > MAX_ROWS) throw invalid();
        Map<String, Rom> values = new LinkedHashMap<>();
        Set<String> filenames = new HashSet<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.length() > 4096) throw invalid();
            if (line.startsWith("#") || (line.isEmpty() && index == lines.length - 1)) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 9 || !fields[0].matches("[a-f0-9]{40}")
                    || !fields[1].matches("[1-9][0-9]{0,8}") || !filename(fields[2])
                    || !field(fields[3], 256, false) || !field(fields[4], 256, false)
                    || !field(fields[5], 128, true) || !field(fields[6], 128, true)
                    || (!fields[7].isEmpty() && !basename(fields[7]))
                    || !fields[8].matches("(?:[A-Z0-9]{4})?")) throw invalid();
            long size = Long.parseLong(fields[1]);
            if (size < 192 || size > 32L * 1024 * 1024) throw invalid();
            List<String> languages = new ArrayList<>();
            if (!fields[6].isEmpty()) for (String code : fields[6].split(",", -1)) {
                if (code.isEmpty() || !code.equals(CatalogLanguages.normalize(code)) || languages.contains(code)) throw invalid();
                languages.add(code);
            }
            Rom rom = new Rom(fields[0], size, fields[2], fields[3], fields[4], fields[5],
                    languages, fields[7], fields[8]);
            if (values.putIfAbsent(rom.sha1, rom) != null || !filenames.add(rom.filename)
                    || values.size() > expected) throw invalid();
        }
        if (values.size() != expected) throw invalid();
        return new GbaCatalog(values);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid GBA catalog metadata."); }

    public static boolean isPageUrl(String value) { return route(value) != null; }
    public static boolean isDetailUrl(String value) {
        Route route = route(value);
        return route != null && route.detail;
    }
    public static boolean isEditionSelectionUrl(String value) {
        Route route = route(value);
        return route != null && route.group;
    }

    public static String searchUrl(String query) {
        String clean = query == null ? "" : query.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        if (clean.length() > 256) {
            int end = Character.isHighSurrogate(clean.charAt(255)) ? 255 : 256;
            clean = clean.substring(0, end);
        }
        return url(new Route("", clean, 1, "", "", false, false), 1);
    }

    public static String withLanguage(String value, String code) {
        Route route = route(value);
        if (route == null) throw new IllegalArgumentException("Unsupported GBA catalog address.");
        return route.detail ? value : url(new Route(route.id, route.query, route.page,
                CatalogLanguages.normalize(code), route.region, route.group, false), route.page);
    }

    public static String withRegion(String value, String code) {
        Route route = route(value);
        if (route == null) throw new IllegalArgumentException("Unsupported GBA catalog address.");
        return route.detail ? value : url(new Route(route.id, route.query, route.page,
                route.language, CatalogRegions.normalize(code), route.group, false), route.page);
    }

    CatalogPage page(String value) {
        Route route = route(value);
        if (route == null) throw new IllegalArgumentException("Unsupported GBA catalog address.");
        if (route.detail) {
            Rom rom = roms.get(route.id);
            if (rom == null) throw new IllegalArgumentException("This GBA edition is not in the catalog.");
            return new CatalogPage(value, rom.title, "No-Intro edition · Central Arquivista · Saves as .gba",
                    Collections.singletonList(rom.entry), Collections.emptyList(), "", "", rom.downloadUrl, false);
        }
        List<CatalogPage.Entry> entries = new ArrayList<>();
        String title = "Game Boy Advance games";
        if (route.group) {
            List<Rom> editions = groups.get(route.id);
            if (editions == null) throw new IllegalArgumentException("This GBA game is not in the catalog.");
            title = editions.get(0).groupTitle;
            for (Rom rom : editions) if (matches(rom, route)) entries.add(rom.entry);
        } else {
            for (Map.Entry<String, List<Rom>> group : groups.entrySet()) {
                List<Rom> matching = new ArrayList<>();
                for (Rom rom : group.getValue()) if (matches(rom, route)) matching.add(rom);
                if (matching.isEmpty()) continue;
                Rom first = matching.get(0);
                if (matching.size() == 1) {
                    CatalogPage.Entry e = first.entry;
                    entries.add(new CatalogPage.Entry(e.url, first.groupTitle, e.category, e.region, e.size,
                            e.contentType, e.titleId, e.artworkUrl, e.languages));
                } else {
                    LinkedHashSet<String> regions = new LinkedHashSet<>();
                    LinkedHashSet<String> languages = new LinkedHashSet<>();
                    String artwork = "";
                    for (Rom rom : matching) {
                        if (!rom.entry.region.isEmpty()) Collections.addAll(regions, rom.entry.region.split(", "));
                        languages.addAll(rom.entry.languages);
                        if (artwork.isEmpty()) artwork = rom.entry.artworkUrl;
                    }
                    String groupUrl = url(new Route(group.getKey(), "", 1, route.language, route.region, true, false), 1);
                    entries.add(new CatalogPage.Entry(groupUrl, first.groupTitle, "Game Boy Advance",
                            String.join(", ", regions), "", matching.size() + " editions", "", artwork, new ArrayList<>(languages)));
                }
            }
        }
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(route.page, pages);
        int start = (page - 1) * PAGE_SIZE;
        String summary = entries.size() + (route.group ? entries.size() == 1 ? " edition" : " editions"
                : entries.size() == 1 ? " game" : " games") + " · No-Intro · Central Arquivista";
        if (!route.language.isEmpty()) summary += " · " + CatalogLanguages.label(route.language);
        if (!route.region.isEmpty()) summary += " · " + CatalogRegions.label(route.region);
        if (pages > 1) summary += " · Page " + page + " of " + pages;
        return new CatalogPage(url(route, page), title, summary,
                entries.subList(start, Math.min(start + PAGE_SIZE, entries.size())), Collections.emptyList(),
                page < pages ? url(route, page + 1) : "", page > 1 ? url(route, page - 1) : "", "", false);
    }

    private static boolean matches(Rom rom, Route route) {
        if ((!route.language.isEmpty() && !rom.entry.languages.contains(route.language))
                || !CatalogRegions.matches(rom.entry.region, route.region)) return false;
        for (String token : route.queryWords) if (!token.isEmpty() && !rom.searchIndex.contains(token)) return false;
        return true;
    }

    private static String searchWords(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static Route route(String value) {
        if (value == null || value.length() > 2048) return null;
        try {
            URI uri = new URI(value);
            if (!"gba".equals(uri.getScheme()) || !"catalog".equals(uri.getRawAuthority())
                    || uri.getRawFragment() != null) return null;
            String path = uri.getRawPath();
            if (path != null && path.matches("/title/[a-f0-9]{40}")) {
                return uri.getRawQuery() == null ? new Route(path.substring(7), "", 1, "", "", false, true) : null;
            }
            boolean group = path != null && path.matches("/game/[a-f0-9]{24}");
            if (!group && !"/".equals(path)) return null;
            String query = "", language = "", region = "";
            int page = 1;
            Set<String> parameters = new HashSet<>();
            if (uri.getRawQuery() != null) for (String parameter : uri.getRawQuery().split("&", -1)) {
                String[] pair = parameter.split("=", 2);
                if (pair.length != 2 || !parameters.add(pair[0])) return null;
                String decoded = URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name());
                if (!field(decoded, 256, true)) return null;
                switch (pair[0]) {
                    case "q": if (group) return null; query = decoded; break;
                    case "page":
                        if (!decoded.matches("[1-9][0-9]{0,5}")) return null;
                        page = Integer.parseInt(decoded); break;
                    case "lang":
                        language = CatalogLanguages.normalize(decoded);
                        if (!decoded.equals(decoded.trim()) || (!decoded.isEmpty() && language.isEmpty() && !decoded.equalsIgnoreCase("all"))) return null;
                        break;
                    case "region":
                        region = CatalogRegions.normalize(decoded);
                        if (!decoded.equals(decoded.trim()) || (!decoded.isEmpty() && region.isEmpty() && !decoded.equalsIgnoreCase("all"))) return null;
                        break;
                    default: return null;
                }
            }
            return new Route(group ? path.substring(6) : "", query, page, language, region, group, false);
        } catch (URISyntaxException | IllegalArgumentException | java.io.UnsupportedEncodingException invalid) {
            return null;
        }
    }

    private static String url(Route route, int page) {
        List<String> parameters = new ArrayList<>();
        if (!route.group && !route.query.isEmpty()) parameters.add("q=" + encode(route.query));
        if (page > 1) parameters.add("page=" + page);
        if (!route.language.isEmpty()) parameters.add("lang=" + route.language);
        if (!route.region.isEmpty()) parameters.add("region=" + route.region);
        return HOME + (route.group ? "game/" + route.id : "")
                + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
    }

    private static String encode(String text) {
        try { return URLEncoder.encode(text, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private static String groupId(String title) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(title.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(24);
            for (int index = 0; index < 12; index++) result.append(String.format(Locale.ROOT, "%02x", bytes[index] & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static boolean field(String value, int limit, boolean empty) {
        if (value.length() > limit || (!empty && value.trim().isEmpty())) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == '\ufffd') return false;
        }
        return true;
    }

    private static boolean basename(String name) {
        return field(name, 256, false) && !name.contains("/") && !name.contains("\\")
                && !name.startsWith(".") && !name.contains(":") && !name.contains("?") && !name.contains("#");
    }
    private static boolean filename(String name) {
        return basename(name) && name.toLowerCase(Locale.ROOT).endsWith(".gba");
    }

    /** Download identity remains separate from its canonical display title. */
    public static final class Rom {
        public final String sha1, filename, downloadUrl, title;
        public final long sizeBytes;
        final String groupTitle, searchIndex;
        final CatalogPage.Entry entry;
        private Rom(String sha1, long size, String filename, String title, String groupTitle,
                    String region, List<String> languages, String artwork, String serial) {
            this.sha1 = sha1;
            this.sizeBytes = size;
            this.filename = filename;
            this.downloadUrl = DOWNLOAD_BASE + encode(filename);
            this.title = title;
            this.groupTitle = groupTitle;
            this.searchIndex = searchWords(title);
            this.entry = new CatalogPage.Entry(HOME + "title/" + sha1, title, "Game Boy Advance", region,
                    String.format(Locale.ROOT, "%.1f MiB", size / (1024.0 * 1024)), "Game Boy Advance game", serial,
                    artwork.isEmpty() ? "" : COVER_BASE + encode(artwork + ".png"), languages);
        }
    }

    private static final class Route {
        final String id, query, language, region;
        final int page;
        final boolean group, detail;
        final String[] queryWords;
        Route(String id, String query, int page, String language, String region, boolean group, boolean detail) {
            this.id = id; this.query = query; this.page = page; this.language = language;
            this.region = region; this.group = group; this.detail = detail;
            this.queryWords = searchWords(query).split(" ");
        }
    }
}
