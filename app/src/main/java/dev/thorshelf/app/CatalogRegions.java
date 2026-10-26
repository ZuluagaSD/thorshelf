package dev.thorshelf.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Catalog release regions, kept separate from the languages a game supports. */
final class CatalogRegions {
    private static final Map<String, String> LABELS;
    private static final Map<String, String> ALIASES;
    private static final List<String> CODES;
    private static final Pattern GROUP = Pattern.compile("[\\(\\[]([^()\\[\\]]{1,512})[\\)\\]]");

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("north-america", "North America");
        labels.put("europe", "Europe");
        labels.put("japan", "Japan");
        labels.put("korea", "Korea");
        labels.put("china", "China");
        labels.put("taiwan", "Taiwan");
        labels.put("australia", "Australia");
        labels.put("world", "World");
        LABELS = Collections.unmodifiableMap(labels);
        CODES = Collections.unmodifiableList(new ArrayList<>(labels.keySet()));

        Map<String, String> aliases = new LinkedHashMap<>();
        for (String code : CODES) alias(aliases, code, code, labels.get(code));
        alias(aliases, "north-america", "USA", "US", "U.S.", "U.S.A.", "United States",
                "United States of America", "Canada");
        alias(aliases, "europe", "EUR", "EU", "France", "Germany", "Spain", "Italy", "UK",
                "United Kingdom", "The United Kingdom", "Great Britain", "England", "Ireland",
                "Netherlands", "The Netherlands", "Belgium", "Luxembourg", "Austria", "Switzerland",
                "Portugal", "Denmark", "Sweden", "Norway", "Finland", "Iceland", "Poland",
                "Czech Republic", "Czechia", "Hungary", "Greece", "Russia");
        alias(aliases, "japan", "JPN", "JP");
        alias(aliases, "korea", "South Korea", "KOR", "KR");
        alias(aliases, "china", "CHN", "CN");
        alias(aliases, "taiwan", "TWN", "TW");
        alias(aliases, "australia", "AUS", "AU", "New Zealand");
        alias(aliases, "world", "Worldwide", "Region Free", "All regions");
        ALIASES = Collections.unmodifiableMap(aliases);
    }

    private CatalogRegions() { }

    static List<String> codes() { return CODES; }

    /** UI and route values use canonical codes; country aliases belong only to metadata parsing. */
    static String normalize(String code) {
        if (code == null) return "";
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return LABELS.containsKey(normalized) ? normalized : "";
    }

    static String label(String code) {
        String normalized = normalize(code);
        return normalized.isEmpty() ? "All regions" : LABELS.get(normalized);
    }

    static boolean matches(String rawRegion, String selectedCode) {
        if (selectedCode == null || selectedCode.trim().isEmpty() || selectedCode.equalsIgnoreCase("all")) return true;
        String selected = normalize(selectedCode);
        return !selected.isEmpty() && fromMetadata(rawRegion).contains(selected);
    }

    /** Returns explicit release groups only; World and unknown never imply a specific region. */
    static List<String> fromMetadata(String raw) {
        if (raw == null || raw.length() > 1024) return Collections.emptyList();
        List<String> complete = regionList(raw.trim());
        if (!complete.isEmpty()) return complete;
        List<String> result = new ArrayList<>();
        Matcher groups = GROUP.matcher(raw);
        while (groups.find()) {
            for (String code : regionList(groups.group(1))) {
                if (!result.contains(code)) result.add(code);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> regionList(String text) {
        if (text.isEmpty()) return Collections.emptyList();
        String[] parts = text.split("(?i)\\s*(?:[,;/|+&]|\\band\\b)\\s*", -1);
        if (parts.length > 32) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String code = ALIASES.get(key(part));
            if (code == null) return Collections.emptyList();
            if (!result.contains(code)) result.add(code);
        }
        return Collections.unmodifiableList(result);
    }

    private static void alias(Map<String, String> aliases, String code, String... names) {
        for (String name : names) aliases.put(key(name), code);
    }

    private static String key(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", " ");
    }
}
