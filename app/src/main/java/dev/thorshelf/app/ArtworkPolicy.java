package dev.thorshelf.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Public catalog image locations, independent of download/session trust. */
final class ArtworkPolicy {
    private ArtworkPolicy() { }

    static boolean isAllowed(String value) {
        if (value == null || value.length() > 2048) return false;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            String path = uri.getPath();
            if (path == null || path.contains("\\") || path.contains("//")) return false;
            String rawPath = uri.getRawPath().toLowerCase(Locale.ROOT);
            if (rawPath.contains("%2f") || rawPath.contains("%5c") || rawPath.contains("%25")) return false;
            for (int i = 0; i < path.length(); i++) {
                if (Character.isISOControl(path.charAt(i)) || Character.getType(path.charAt(i)) == Character.FORMAT) return false;
            }
            for (String segment : path.split("/")) {
                if (segment.equals(".") || segment.equals("..")) return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("art.gametdb.com")) {
                return uri.getRawQuery() == null && uri.getRawPath().matches(
                        "/3ds/coverM/(US|EN|JA|FR|DE|ES|IT|NL|PT|RU|KO|ZHCN|ZHTW|AU)/[A-Z0-9]{4}\\.jpg");
            }
            if (host.equals("avatars.githubusercontent.com")) return path.matches("/u/[0-9]+");
            if (!path.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif)")) return false;
            if (host.equals("db-nds-shop.fr")) {
                if (uri.getRawQuery() != null || !uri.getRawPath().startsWith("/api/v1/assets/")) return false;
                String[] segments = path.substring("/api/v1/assets/".length()).split("/", -1);
                if (segments.length < 2 || segments.length > 3) return false;
                boolean screenshot = segments[0].equals("screenshots");
                if (!screenshot && !segments[0].equals("boxart") && !segments[0].equals("icons")) return false;
                if (!screenshot && segments.length != 2) return false;
                for (int i = 1; i < segments.length; i++) {
                    if (!DownloadPolicy.isNdsShopAssetName(segments[i])) return false;
                }
                return true;
            }
            if (host.equals("db.universal-team.net")) return path.startsWith("/assets/images/");
            if (host.equals("raw.githubusercontent.com")) {
                return path.matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/[^/]+/.+");
            }
            if (host.equals("github.com")) {
                return path.matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/raw/[^/]+/.+");
            }
            return host.equals("i.imgur.com") && path.matches("/[A-Za-z0-9]+\\.(?i:png|jpe?g|webp|gif)");
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            return false;
        }
    }
}
