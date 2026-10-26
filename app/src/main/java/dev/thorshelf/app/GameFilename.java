package dev.thorshelf.app;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Readable game filenames, with catalog bookkeeping removed before filesystem limits apply. */
public final class GameFilename {
    private static final Pattern TITLE_ID = Pattern.compile(
            "^((?:00040000|00040002|0004000e|0004008c|00040010|0004001b|00040030)[0-9a-f]{8})\\s+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCT_CODE = Pattern.compile(
            "\\(CTR-[A-Z]-[A-Z0-9]{4}\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PACKAGING = Pattern.compile(
            "\\.(?:piratelegit|legit)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_HASH = Pattern.compile("-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_AZAHAR = Pattern.compile("\\s+-\\s+Azahar$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile("\\(v\\d+(?:\\.\\d+)*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGION = Pattern.compile("\\((U|E|J|W|K|C|T|A)\\)");
    private static final Pattern UPDATE = Pattern.compile("\\bUpdate\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DLC = Pattern.compile("\\b(?:DLC|Downloadable Content)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_METADATA = Pattern.compile(
            "\\s*(\\((?:v\\d+(?:\\.\\d+)*|USA|Europe|Japan|World|Korea|China|Taiwan|Australia|Update|DLC)\\))$",
            Pattern.CASE_INSENSITIVE);

    private GameFilename() { }

    /** Cleans recognizable catalog metadata while retaining the original file type and title text. */
    public static String clean(String filename) {
        if (filename == null) return DownloadPolicy.safeFilename(null);
        Parts parts = new Parts(filename.trim());
        String stem = parts.stem;
        Matcher id = TITLE_ID.matcher(stem);
        String titleId = id.find() ? id.group(1).toLowerCase(Locale.ROOT) : "";
        boolean hasProductCode = PRODUCT_CODE.matcher(stem).find();
        stem = LEGACY_AZAHAR.matcher(stem).replaceFirst("").trim();
        String withoutHash = LEGACY_HASH.matcher(stem).replaceFirst("");
        boolean hasLegacyHash = !withoutHash.equals(stem);
        boolean hasPackaging = PACKAGING.matcher(withoutHash).find();
        boolean catalog = !titleId.isEmpty() || hasProductCode || hasPackaging;
        if (catalog) stem = withoutHash;
        if (hasLegacyHash && (!titleId.isEmpty() || hasProductCode)) {
            // Earlier downloads truncated the raw catalog stem before adding their random suffix.
            int packagingDot = stem.lastIndexOf('.');
            if (packagingDot >= 0) {
                String fragment = stem.substring(packagingDot + 1).toLowerCase(Locale.ROOT);
                if (!fragment.isEmpty() && ("legit".startsWith(fragment) || "piratelegit".startsWith(fragment))) {
                    stem = stem.substring(0, packagingDot);
                }
            }
        }
        stem = PACKAGING.matcher(stem).replaceFirst("");
        stem = TITLE_ID.matcher(stem).replaceFirst("");
        stem = PRODUCT_CODE.matcher(stem).replaceAll("");

        boolean update = titleId.startsWith("0004000e");
        boolean dlc = titleId.startsWith("0004008c");
        boolean namedExtra = UPDATE.matcher(stem).find() || DLC.matcher(stem).find();
        // Other title categories and ordinary names may use their version to distinguish files.
        boolean base = titleId.startsWith("00040000") || (titleId.isEmpty() && catalog);
        if (base && !namedExtra) stem = VERSION.matcher(stem).replaceAll("");

        Matcher regions = REGION.matcher(stem);
        StringBuffer readable = new StringBuffer();
        while (regions.find()) {
            // Within an ordinary title, a letter in parentheses can be meaningful text.
            boolean regionMetadata = catalog || stem.substring(regions.end()).trim().isEmpty();
            String replacement = regionMetadata ? "(" + regionName(regions.group(1)) + ")" : regions.group();
            regions.appendReplacement(readable, Matcher.quoteReplacement(replacement));
        }
        regions.appendTail(readable);
        stem = readable.toString().replaceAll("\\s+", " ").trim();
        if (update && !UPDATE.matcher(stem).find()) stem += " (Update)";
        if (dlc && !DLC.matcher(stem).find()) stem += " (DLC)";
        if (stem.isEmpty()) stem = "download";
        return bounded(stem, "", parts.extension);
    }

    /** Cleans a name and changes its actual extension (provided without a dot). */
    public static String withExtension(String filename, String extension) {
        if (extension == null || !extension.matches("[A-Za-z0-9]{1,8}")) {
            throw new IllegalArgumentException("Expected a file extension without a dot");
        }
        Parts parts = new Parts(clean(filename));
        return bounded(parts.stem, "", "." + extension);
    }

    /** Number 1 keeps the name; later copies use a readable suffix before the extension. */
    public static String numbered(String cleanFilename, int number) {
        if (number < 1) throw new IllegalArgumentException("File number must be positive");
        String safe = clean(cleanFilename);
        if (number == 1) return safe;
        Parts parts = new Parts(safe);
        return bounded(parts.stem, " (" + number + ")", parts.extension);
    }

    private static String bounded(String stem, String suffix, String extension) {
        String metadata = "";
        Matcher tail = TRAILING_METADATA.matcher(stem);
        while (tail.find()) {
            String candidate = " " + tail.group(1) + metadata;
            // Leave space for the title; unreasonably large metadata follows ordinary truncation.
            if (candidate.length() + suffix.length() + extension.length() >= 100
                    || (candidate + suffix + extension).getBytes(StandardCharsets.UTF_8).length >= 180) break;
            metadata = candidate;
            stem = stem.substring(0, tail.start()).trim();
            tail = TRAILING_METADATA.matcher(stem);
        }
        suffix = metadata + suffix;
        while (stem.length() + suffix.length() + extension.length() > 100
                || (stem + suffix + extension).getBytes(StandardCharsets.UTF_8).length > 180) {
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
        }
        return DownloadPolicy.safeFilename(stem.trim() + suffix + extension);
    }

    private static String regionName(String code) {
        switch (code) {
            case "U": return "USA";
            case "E": return "Europe";
            case "J": return "Japan";
            case "W": return "World";
            case "K": return "Korea";
            case "C": return "China";
            case "T": return "Taiwan";
            case "A": return "Australia";
            default: throw new AssertionError(code);
        }
    }

    private static final class Parts {
        final String stem;
        final String extension;

        Parts(String name) {
            int dot = name.lastIndexOf('.');
            boolean hasExtension = dot >= 0 && name.length() - dot < 10;
            stem = hasExtension ? name.substring(0, dot) : name;
            extension = hasExtension ? name.substring(dot) : "";
        }
    }
}
