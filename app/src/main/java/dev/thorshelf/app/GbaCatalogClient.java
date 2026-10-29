package dev.thorshelf.app;

import java.io.IOException;
import java.io.InterruptedIOException;

/** Browsing is deterministic and offline; only selected ROMs and optional art use the network. */
public class GbaCatalogClient {
    private final GbaCatalog catalog;

    public GbaCatalogClient() { catalog = null; }
    GbaCatalogClient(GbaCatalog catalog) { this.catalog = catalog; }

    public CatalogPage fetch(String url, String userAgent) throws IOException {
        if (!GbaCatalog.isPageUrl(url)) throw new IOException("Unsupported GBA catalog address.");
        if (userAgent != null && (userAgent.length() > 16384 || userAgent.indexOf('\r') >= 0
                || userAgent.indexOf('\n') >= 0 || userAgent.indexOf('\0') >= 0)) {
            throw new IOException("Invalid request metadata.");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("GBA catalog loading cancelled.");
        try { return (catalog == null ? GbaCatalog.bundled() : catalog).page(url); }
        catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
    }
}
