package dev.thorshelf.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Pure Java validation shared by the browser and background download queue. */
public final class DownloadPolicy {
    public static final String HOME = "https://hshop.erista.me/";
    private DownloadPolicy() { }

    public static boolean isCatalog(String url) {
        URI uri = parseHttps(url);
        return uri != null && "hshop.erista.me".equalsIgnoreCase(uri.getHost());
    }

    public static boolean isAllowedDownload(String url) {
        URI uri = parseHttps(url);
        if (uri == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("hshop.erista.me") || host.matches("download[0-9]+\\.erista\\.me");
    }

    private static URI parseHttps(String url) {
        if (url == null || url.length() > 16384) return null;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public static String searchUrl(String query) {
        try {
            return HOME + "search/results?q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
                    + "&qt=Text&lgy=false";
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static String safeFilename(String proposed) {
        String name = proposed == null ? "download.cia" : proposed;
        name = name.replaceAll("[\\p{Cntrl}\\p{Cf}\\\\/:*?\"<>|]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty()) name = "download.cia";
        // Leave room for a unique suffix within common filesystem filename limits.
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 && name.length() - dot < 10 ? name.substring(dot) : "";
        String stem = ext.isEmpty() ? name : name.substring(0, dot);
        while (stem.length() + ext.length() > 100
                || (stem + ext).getBytes(StandardCharsets.UTF_8).length > 180) {
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
        }
        return stem + ext;
    }

    public static boolean isContentFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cia") || lower.endsWith(".cci") || lower.endsWith(".cxi")
                || lower.endsWith(".3ds") || lower.endsWith(".3dsx") || lower.endsWith(".nds")
                || lower.endsWith(".zip") || lower.endsWith(".7z");
    }
}
