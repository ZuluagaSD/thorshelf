package dev.thorshelf.app;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The public catalog metadata used to render a native shelf. */
final class CatalogPage {
    final String url;
    final String title;
    final String summary;
    final List<Entry> entries;
    final List<Category> categories;
    final String nextUrl;
    final String previousUrl;
    final String downloadUrl;
    final boolean verificationRequired;

    CatalogPage(String url, String title, String summary, List<Entry> entries,
                List<Category> categories, String nextUrl, String previousUrl,
                String downloadUrl, boolean verificationRequired) {
        this.url = url;
        this.title = title;
        this.summary = summary;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
        this.nextUrl = nextUrl;
        this.previousUrl = previousUrl;
        this.downloadUrl = downloadUrl;
        this.verificationRequired = verificationRequired;
    }

    boolean isDetail() {
        return NdsCatalog.isDetailUrl(url) || GbaCatalog.isDetailUrl(url) || (CatalogParser.isCatalogPageUrl(url)
                && URI.create(url).getRawPath().matches("/t/[0-9]{1,12}/?"));
    }

    static final class Entry {
        final String url;
        final String title;
        final String category;
        final String region;
        final String size;
        final String contentType;
        final String titleId;
        final String artworkUrl;
        final List<String> languages;

        Entry(String url, String title, String category, String region, String size,
              String contentType, String titleId) {
            this(url, title, category, region, size, contentType, titleId, "");
        }

        Entry(String url, String title, String category, String region, String size,
              String contentType, String titleId, String artworkUrl) {
            this(url, title, category, region, size, contentType, titleId, artworkUrl, Collections.emptyList());
        }

        Entry(String url, String title, String category, String region, String size,
              String contentType, String titleId, String artworkUrl, List<String> languages) {
            this.url = url;
            this.title = title;
            this.category = category;
            this.region = region;
            this.size = size;
            this.contentType = contentType;
            this.titleId = titleId;
            this.artworkUrl = ArtworkPolicy.isAllowed(artworkUrl) ? artworkUrl : "";
            List<String> normalizedLanguages = new ArrayList<>();
            if (languages != null) for (String language : languages) {
                String code = CatalogLanguages.normalize(language);
                if (!code.isEmpty() && !normalizedLanguages.contains(code)) normalizedLanguages.add(code);
            }
            this.languages = Collections.unmodifiableList(normalizedLanguages);
        }
    }

    static final class Category {
        final String url;
        final String title;
        final String description;

        Category(String url, String title, String description) {
            this.url = url;
            this.title = title;
            this.description = description;
        }
    }
}
