package dev.thorshelf.app;

/** Stable platform IDs shared by navigation and persisted download records. */
public enum GamePlatform {
    THREE_DS("3ds", "3DS", "Nintendo 3DS", "3ds"),
    DS("nds", "DS", "Nintendo DS", "nds"),
    GBA("gba", "GBA", "Game Boy Advance", "gba");

    public final String id;
    public final String shortLabel;
    public final String label;
    public final String extension;

    GamePlatform(String id, String shortLabel, String label, String extension) {
        this.id = id;
        this.shortLabel = shortLabel;
        this.label = label;
        this.extension = extension;
    }

    public static GamePlatform fromId(String id) {
        for (GamePlatform platform : values()) if (platform.id.equals(id)) return platform;
        return THREE_DS;
    }

    static GamePlatform forCatalog(String url) {
        if (GbaCatalog.isPageUrl(url)) return GBA;
        return NdsCatalog.isPageUrl(url) ? DS : THREE_DS;
    }

    String homeUrl() {
        switch (this) {
            case GBA: return GbaCatalog.HOME;
            case DS: return NdsCatalog.HOME;
            default: return DownloadPolicy.HOME;
        }
    }

    String searchUrl(String query) {
        switch (this) {
            case GBA: return GbaCatalog.searchUrl(query);
            case DS: return NdsCatalog.searchUrl(query);
            default: return DownloadPolicy.searchUrl(query);
        }
    }

    static boolean isCatalogUrl(String url) {
        return CatalogParser.isCatalogPageUrl(url) || NdsCatalog.isPageUrl(url) || GbaCatalog.isPageUrl(url);
    }

    static boolean isEditionSelectionUrl(String url) {
        return NdsCatalog.isEditionSelectionUrl(url) || GbaCatalog.isEditionSelectionUrl(url);
    }
}
