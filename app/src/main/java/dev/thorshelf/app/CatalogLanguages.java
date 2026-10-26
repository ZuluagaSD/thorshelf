package dev.thorshelf.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared language labels and conservative parsing of explicit catalog metadata. */
final class CatalogLanguages {
    private static final Map<String, String> LABELS;
    private static final List<String> CODES;
    private static final Pattern GROUP = Pattern.compile("[\\(\\[]([^()\\[\\]]{1,512})[\\)\\]]");

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("en", "English");
        labels.put("fr", "French");
        labels.put("de", "German");
        labels.put("es", "Spanish");
        labels.put("it", "Italian");
        labels.put("ja", "Japanese");
        labels.put("ko", "Korean");
        labels.put("zh", "Chinese");
        labels.put("nl", "Dutch");
        labels.put("pt", "Portuguese");
        labels.put("ru", "Russian");
        labels.put("pl", "Polish");
        labels.put("sv", "Swedish");
        labels.put("da", "Danish");
        labels.put("no", "Norwegian");
        labels.put("fi", "Finnish");
        labels.put("cs", "Czech");
        labels.put("hu", "Hungarian");
        labels.put("tr", "Turkish");
        labels.put("el", "Greek");
        labels.put("ar", "Arabic");
        labels.put("he", "Hebrew");
        labels.put("uk", "Ukrainian");
        labels.put("ca", "Catalan");
        LABELS = Collections.unmodifiableMap(labels);
        CODES = Collections.unmodifiableList(new ArrayList<>(labels.keySet()));
    }

    private CatalogLanguages() { }

    static List<String> codes() { return CODES; }

    static String label(String code) {
        String normalized = normalize(code);
        return normalized.isEmpty() ? "All languages" : LABELS.get(normalized);
    }

    static String normalize(String code) {
        if (code == null) return "";
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return LABELS.containsKey(normalized) ? normalized : "";
    }

    /** Accepts a language list or explicit parenthesized lists, never words inside a title. */
    static List<String> parse(String text) {
        if (text == null || text.length() > 4096) return Collections.emptyList();
        String value = text.trim().replaceFirst("(?i)^languages?\\s*:\\s*", "");
        List<String> complete = languageList(value);
        if (!complete.isEmpty()) return complete;
        List<String> result = new ArrayList<>();
        Matcher groups = GROUP.matcher(value);
        while (groups.find()) {
            for (String code : languageList(groups.group(1))) {
                if (!result.contains(code)) result.add(code);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Explicit language groups override locale tags; broad regions and game codes stay unknown. */
    static List<String> nds(String version, String filename, String titleId) {
        List<String> explicit = parse(version);
        if (!explicit.isEmpty()) return explicit;
        explicit = parse(filename);
        if (!explicit.isEmpty()) return explicit;
        String versionLocale = editionLocale(version, true);
        String filenameLocale = editionLocale(filename, false);
        if (versionLocale == null || filenameLocale == null) return Collections.emptyList();
        if (!versionLocale.isEmpty() && !filenameLocale.isEmpty() && !versionLocale.equals(filenameLocale)) {
            return Collections.emptyList();
        }
        String language = versionLocale.isEmpty() ? filenameLocale : versionLocale;
        // A four-character game code identifies an edition, but does not enumerate its languages.
        // In particular, an E suffix or a USA/Europe/World tag alone is not proof of English support.
        return language.isEmpty() ? Collections.emptyList() : Collections.singletonList(language);
    }

    private static List<String> languageList(String text) {
        if (text.isEmpty()) return Collections.emptyList();
        String[] parts = text.split("(?i)\\s*(?:[,;/|+&]|\\band\\b)\\s*", -1);
        if (parts.length > CODES.size()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String token = part.trim();
            String code = normalize(token);
            // 3dsdb uses Bokmål's code in its explicit lists; the selector groups Norwegian editions.
            if (code.isEmpty() && token.equalsIgnoreCase("nb")) code = "no";
            if (code.isEmpty()) {
                for (Map.Entry<String, String> label : LABELS.entrySet()) {
                    if (label.getValue().equalsIgnoreCase(token)) {
                        code = label.getKey();
                        break;
                    }
                }
            }
            if (code.isEmpty()) {
                if (token.equalsIgnoreCase("Chinese (Simplified)")
                        || token.equalsIgnoreCase("Chinese (Traditional)")
                        || token.equalsIgnoreCase("Simplified Chinese")
                        || token.equalsIgnoreCase("Traditional Chinese")) code = "zh";
            }
            if (code.isEmpty()) return Collections.emptyList();
            if (!result.contains(code)) result.add(code);
        }
        return Collections.unmodifiableList(result);
    }

    private static String editionLocale(String text, boolean allowBareLocale) {
        if (text == null || text.length() > 4096) return "";
        String found = allowBareLocale ? localeLanguage(text.trim()) : "";
        Matcher groups = GROUP.matcher(text);
        while (groups.find()) {
            String language = localeLanguage(groups.group(1).trim());
            if (language.isEmpty()) continue;
            if (!found.isEmpty() && !found.equals(language)) return null;
            found = language;
        }
        return found;
    }

    private static String localeLanguage(String locale) {
        switch (locale.toLowerCase(Locale.ROOT)) {
            case "france": return "fr";
            case "germany": return "de";
            case "spain": return "es";
            case "italy": return "it";
            case "japan": return "ja";
            case "korea": return "ko";
            case "china": return "zh";
            default: return "";
        }
    }
}
