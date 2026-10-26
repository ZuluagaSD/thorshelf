package dev.thorshelf.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Pure Java validation shared by the browser and background download queue. */
public final class DownloadPolicy {
    public static final String HOME = "https://hshop.erista.me/";
    private static final String DS_ARCHIVE_ITEM = "pack-roms-nintendo-ds-eu-usa-jap-rabbits-games";
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

    /** Separate public DS sources; never extends the hosts trusted with hShop sessions. */
    public static boolean isAllowedNdsDownload(String url) {
        URI uri = parseHttps(url);
        if (uri == null || uri.getRawFragment() != null) return false;
        String path = uri.getPath();
        if (path == null || path.indexOf('\\') >= 0 || path.contains("//")) return false;
        for (int index = 0; index < path.length(); index++) {
            if (Character.isISOControl(path.charAt(index))
                    || Character.getType(path.charAt(index)) == Character.FORMAT) return false;
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) return false;
        }
        String rawPath = uri.getRawPath().toLowerCase(Locale.ROOT);
        if (rawPath.contains("%2f") || rawPath.contains("%5c") || rawPath.contains("%25")) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("archive.org") || isArchiveMirror(host)) {
            return ndsArchiveFilename(uri) != null;
        }
        if (host.equals("db-nds-shop.fr")) {
            String prefix = "/api/v1/download/";
            if (url.length() > 2048 || uri.getRawQuery() != null
                    || !uri.getRawPath().startsWith(prefix)) return false;
            String name = path.substring(prefix.length());
            return isNdsShopAssetName(name) && name.toLowerCase(Locale.ROOT).endsWith(".nds");
        }
        if (host.equals("github.com")) {
            return path.matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[^/]+/[^/]+")
                    || path.matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/latest/download/[^/]+");
        }
        if (host.equals("db.universal-team.net")) {
            return path.matches("/assets/files/[^/]+");
        }
        return host.equals("release-assets.githubusercontent.com")
                && path.matches("/github-production-release-asset/[^/]+/[^/]+");
    }

    /** Archive redirects may change their storage host, never their item or ZIP filename. */
    static boolean isAllowedNdsRedirect(String source, String destination) {
        if (!isAllowedNdsDownload(source) || !isAllowedNdsDownload(destination)) return false;
        String original = ndsArchiveFilename(source);
        String redirected = ndsArchiveFilename(destination);
        return original == null ? redirected == null : original.equals(redirected);
    }

    static String ndsArchiveFilename(String url) {
        if (!isAllowedNdsDownload(url)) return null;
        return ndsArchiveFilename(parseHttps(url));
    }

    private static String ndsArchiveFilename(URI uri) {
        if (uri == null || uri.getRawQuery() != null || uri.toString().length() > 2048) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String prefix;
        if (host.equals("archive.org")) prefix = "/download/";
        else if (isArchiveMirror(host)) {
            String path = uri.getRawPath();
            int items = path.indexOf("/items/");
            if (items < 2 || !path.substring(0, items).matches("/[0-9]+")) return null;
            prefix = path.substring(0, items) + "/items/";
        }
        else return null;
        prefix += DS_ARCHIVE_ITEM + "/";
        if (!uri.getRawPath().startsWith(prefix)) return null;
        String filename = uri.getPath().substring(prefix.length());
        return isNdsShopAssetName(filename) && filename.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? filename : null;
    }

    private static boolean isArchiveMirror(String host) {
        return host.matches("(?:ia|dn)[0-9]{6}\\.(?:us|ca)\\.archive\\.org");
    }

    /** A decoded single asset filename, shared with NDS-Shop's public image endpoints. */
    static boolean isNdsShopAssetName(String name) {
        if (name.isEmpty() || name.startsWith(".") || !name.equals(name.trim())
                || name.getBytes(StandardCharsets.UTF_8).length > 255) return false;
        for (int index = 0; index < name.length(); index++) {
            char c = name.charAt(index);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || c == '\uFFFD' || "/\\%?#:;".indexOf(c) >= 0) return false;
        }
        return true;
    }

    public static boolean isNdsContentFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".nds") || lower.endsWith(".zip");
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
