package dev.thorshelf.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Tracks Android-managed downloads without keeping their temporary download URLs. */
public final class DownloadStore {
    private static final Object LOCK = new Object();
    private static final String RECORDS_KEY = "records";
    private static final String CATALOG_HOME = "https://hshop.erista.me/";

    private final Context context;
    private final DownloadManager manager;
    private final SharedPreferences preferences;

    public DownloadStore(Context context) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.manager = (DownloadManager) this.context.getSystemService(Context.DOWNLOAD_SERVICE);
        this.preferences = this.context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE);
        if (manager == null) {
            throw new IllegalStateException("Android's download service is unavailable.");
        }
    }

    /** Returns the Android download ID. Cookies and temporary URLs are never saved by this store. */
    public long enqueue(String url, String fileName, String userAgent, String cookie,
                        String sourcePage, boolean wifiOnly) {
        return enqueue(url, fileName, userAgent, cookie, sourcePage, wifiOnly, GamePlatform.THREE_DS, "", "", -1);
    }

    public long enqueue(String url, String fileName, String userAgent, String cookie,
                        String sourcePage, boolean wifiOnly, String artworkUrl) {
        return enqueue(url, fileName, userAgent, cookie, sourcePage, wifiOnly, GamePlatform.THREE_DS, artworkUrl, "", -1);
    }

    /** Queues DS files without hShop cookies or a browser Referer. */
    public long enqueueNds(String url, String fileName, String userAgent,
                           String sourcePage, boolean wifiOnly) {
        return enqueueNds(url, fileName, userAgent, sourcePage, wifiOnly, "");
    }

    /** Keeps optional public artwork separately from the game transfer and its request headers. */
    public long enqueueNds(String url, String fileName, String userAgent,
                           String sourcePage, boolean wifiOnly, String artworkUrl) {
        return enqueue(url, fileName, userAgent, null, sourcePage, wifiOnly, GamePlatform.DS, artworkUrl, "", -1);
    }

    /** Queues only the exact raw GBA edition and integrity metadata in the bundled catalog. */
    public long enqueueGba(String url, String fileName, String userAgent, String sourcePage,
                           boolean wifiOnly, String artworkUrl, String expectedSha1, long expectedSize) {
        final GbaCatalog.Rom rom;
        try { rom = GbaCatalog.lookup(sourcePage); }
        catch (IOException unavailable) {
            throw new IllegalStateException("The GBA catalog is unavailable. Reopen the catalog and try again.");
        }
        if (rom == null || !GbaRomExporter.hasExpectedIdentity(expectedSha1, expectedSize)
                || !rom.sha1.equalsIgnoreCase(expectedSha1) || rom.sizeBytes != expectedSize
                || !DownloadPolicy.isAllowedGbaRedirect(rom.downloadUrl, url)) {
            throw new IllegalArgumentException("The GBA download does not match its catalog edition.");
        }
        return enqueue(url, fileName, userAgent, null, sourcePage, wifiOnly, GamePlatform.GBA,
                artworkUrl, expectedSha1.toLowerCase(Locale.ROOT), expectedSize);
    }

    private long enqueue(String url, String fileName, String userAgent, String cookie,
                         String sourcePage, boolean wifiOnly, GamePlatform platform, String artworkUrl,
                         String expectedSha1, long expectedSize) {
        boolean nds = platform == GamePlatform.DS;
        boolean gba = platform == GamePlatform.GBA;
        if (!(gba ? DownloadPolicy.isAllowedGbaDownload(url)
                : nds ? DownloadPolicy.isAllowedNdsDownload(url) : DownloadPolicy.isAllowedDownload(url))) {
            throw new IllegalArgumentException("This download address is not an approved HTTPS source.");
        }
        if (nds && (!DownloadPolicy.isNdsContentFile(fileName == null ? null : fileName.trim())
                || !DownloadPolicy.isNdsContentFile(GameFilename.clean(fileName)))) {
            throw new IllegalArgumentException("DS downloads must be NDS files or ZIP bundles.");
        }
        if (gba && (!DownloadPolicy.isGbaContentFile(fileName == null ? null : fileName.trim())
                || !DownloadPolicy.isGbaContentFile(GameFilename.clean(fileName)))) {
            throw new IllegalArgumentException("GBA downloads must be GBA files.");
        }
        String page = gba ? sourcePage
                : nds ? (NdsCatalog.isDetailUrl(sourcePage) ? sourcePage : NdsCatalog.HOME)
                : hshopPage(sourcePage);
        synchronized (LOCK) {
            // Reserve the name while holding the same lock as enqueue and persistence. Pending
            // downloads may not have created a file yet, so filesystem checks alone are not enough.
            List<TrackedDownload> records = readRecords();
            String name = uniqueFilename(fileName, occupiedNames(records));
            final DownloadManager.Request request;
            try {
                request = new DownloadManager.Request(Uri.parse(url))
                        .setTitle(name)
                        .setDescription(gba ? "ThorShelf · Game Boy Advance"
                                : nds ? "ThorShelf · Nintendo DS" : "ThorShelf · hShop")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ThorShelf/" + name)
                        .setAllowedOverRoaming(false);
                if (wifiOnly) {
                    request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
                }
                addHeader(request, "User-Agent", userAgent);
                if (platform == GamePlatform.THREE_DS) addHeader(request, "Cookie", cookie);
                if (platform == GamePlatform.THREE_DS && sourcePage != null && !sourcePage.trim().isEmpty()) {
                    addHeader(request, "Referer", page);
                }
            } catch (RuntimeException ignored) {
                throw new IllegalArgumentException("Could not prepare this download. Reopen its catalog page and try again.");
            }
            final long id;
            try {
                id = manager.enqueue(request);
            } catch (RuntimeException ignored) {
                throw new IllegalStateException("Android could not start the download. Check available storage and the download service.");
            }
            TrackedDownload record = new TrackedDownload(id, name, page, null, null, artworkUrl);
            record.platform = platform;
            record.expectedSha1 = expectedSha1;
            record.expectedSize = expectedSize;
            records.add(record);
            if (!writeRecords(records)) {
                try {
                    manager.remove(id);
                } catch (RuntimeException ignored) {
                    throw new IllegalStateException("The download started, but its details could not be saved. Manage it in Android's Downloads app.");
                }
                throw new IllegalStateException("Could not save the download. Free some device storage and try again.");
            }
            return id;
        }
    }

    /** A snapshot ordered from most recently enqueued to oldest. */
    public List<Entry> list() {
        synchronized (LOCK) {
            List<TrackedDownload> records = readRecords();
            List<Entry> result = new ArrayList<>(records.size());
            if (records.isEmpty()) {
                return result;
            }

            long[] ids = new long[records.size()];
            Map<Long, TrackedDownload> trackedById = new HashMap<>();
            for (int i = 0; i < records.size(); i++) {
                TrackedDownload record = records.get(i);
                ids[i] = record.id;
                trackedById.put(record.id, record);
            }
            Map<Long, Entry> entries = new HashMap<>();
            boolean queried = false;
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(ids))) {
                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
                    int statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
                    int reasonColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON);
                    int downloadedColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int totalColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    while (cursor.moveToNext()) {
                        TrackedDownload record = trackedById.get(cursor.getLong(idColumn));
                        if (record == null) {
                            continue;
                        }
                        int status = cursor.getInt(statusColumn);
                        int reason = cursor.getInt(reasonColumn);
                        long downloaded = Math.max(0, cursor.getLong(downloadedColumn));
                        long total = cursor.getLong(totalColumn);
                        String label = statusLabel(status, reason);
                        entries.put(record.id, new Entry(record, downloaded, total, status, reason, label));
                    }
                    queried = true;
                }
            } catch (RuntimeException ignored) {
                // Never expose DownloadProvider messages: they can contain temporary URLs or local paths.
            }

            for (int i = records.size() - 1; i >= 0; i--) {
                TrackedDownload record = records.get(i);
                Entry entry = entries.get(record.id);
                if (record.originalDeleted || (entry == null && queried && record.preparedDestination != null)) {
                    entry = new Entry(record, record.preparedSize, record.preparedSize,
                            DownloadManager.STATUS_SUCCESSFUL, 0, "Saved to games folder");
                } else if (entry == null) {
                    entry = queried
                            ? new Entry(record, 0, -1, DownloadManager.STATUS_FAILED, DownloadManager.ERROR_UNKNOWN,
                            "Removed from Android downloads. Reopen its catalog page for a fresh link.")
                            : new Entry(record, 0, -1, 0, 0,
                            "Android download status is unavailable. Check again shortly.");
                }
                result.add(entry);
            }
            return result;
        }
    }

    /** Cancels a transfer (or deletes its completed file) and removes it from the app's list. */
    public void cancel(long id) {
        synchronized (LOCK) {
            List<TrackedDownload> records = readRecords();
            boolean found = false;
            for (int i = records.size() - 1; i >= 0; i--) {
                if (records.get(i).id == id) {
                    records.remove(i);
                    found = true;
                }
            }
            if (!found) {
                return;
            }
            try {
                manager.remove(id);
            } catch (RuntimeException ignored) {
                throw new IllegalStateException("Android could not remove this download. Try again from Android's Downloads app.");
            }
            if (!writeRecords(records)) {
                throw new IllegalStateException("The download was removed, but the saved list could not be updated.");
            }
        }
    }

    /** Fills missing public artwork without changing a transfer or replacing newer metadata. */
    public boolean updateArtwork(long id, String artworkUrl) {
        if (!ArtworkPolicy.isAllowed(artworkUrl)) return false;
        synchronized (LOCK) {
            List<TrackedDownload> records = readRecords();
            for (int i = 0; i < records.size(); i++) {
                TrackedDownload record = records.get(i);
                if (record.id != id) continue;
                if (!record.artworkUrl.isEmpty()) return record.artworkUrl.equals(artworkUrl);
                records.set(i, new TrackedDownload(record, record.preparedDestination, record.savedDsDestination, artworkUrl));
                if (writeRecords(records)) return true;
                records.set(i, record);
                writeRecords(records);
                return false;
            }
            return false;
        }
    }

    /** Records a separate copy only after preparation has fully saved and closed it. */
    public boolean markPrepared(long id, Uri destination) {
        return markPrepared(id, destination, true, -1, "");
    }

    boolean markPrepared(long id, Uri destination, boolean requiresInstall, long size, String digest) {
        Uri prepared = preparedUri(destination == null ? null : destination.toString());
        if (prepared == null) return false;
        synchronized (LOCK) {
            List<TrackedDownload> records = readRecords();
            for (int i = 0; i < records.size(); i++) {
                TrackedDownload record = records.get(i);
                if (record.id != id) continue;
                Uri original = successfulDownloadUri(id);
                if (original == null || original.equals(prepared)) return false;
                TrackedDownload updated = new TrackedDownload(record, prepared, record.savedDsDestination, record.artworkUrl);
                updated.requiresInstall = requiresInstall;
                updated.preparedSize = size;
                updated.preparedDigest = digest;
                records.set(i, updated);
                if (writeRecords(records)) return true;
                // commit(false) may still change SharedPreferences' in-memory snapshot. Restore
                // the previous marker so a failed save cannot unlock deletion in this process.
                records.set(i, record);
                writeRecords(records);
                return false;
            }
            return false;
        }
    }

    /** Records a separate DS ROM only after its export has fully saved and closed it. */
    public boolean markDsSaved(long id, Uri destination) {
        Uri saved = preparedUri(destination == null ? null : destination.toString());
        if (saved == null) return false;
        synchronized (LOCK) {
            List<TrackedDownload> records = readRecords();
            for (int i = 0; i < records.size(); i++) {
                TrackedDownload record = records.get(i);
                if (record.id != id) continue;
                if (!isDsSource(record)) return false;
                Uri original = successfulDownloadUri(id);
                if (original == null || original.equals(saved)) return false;
                records.set(i, new TrackedDownload(record, record.preparedDestination, saved, record.artworkUrl));
                if (writeRecords(records)) return true;
                // A failed commit can still update the in-memory preferences snapshot.
                records.set(i, record);
                writeRecords(records);
                return false;
            }
            return false;
        }
    }

    /** Returns the saved DS copy only while it is still readable and nonempty. */
    public Uri savedDsUri(long id) {
        synchronized (LOCK) {
            for (TrackedDownload record : readRecords()) {
                if (record.id != id) continue;
                Uri saved = record.savedDsDestination;
                return isDsSource(record) && saved != null && hasReadableContent(saved) ? saved : null;
            }
            return null;
        }
    }

    private static boolean isDsSource(TrackedDownload record) {
        return record.platform == GamePlatform.DS;
    }

    private static GamePlatform legacyPlatform(String filename, String sourcePage) {
        String name = filename.toLowerCase(Locale.ROOT);
        return name.endsWith(".nds") || name.endsWith(".zip")
                && (NdsCatalog.HOME.equals(sourcePage) || NdsCatalog.isDetailUrl(sourcePage))
                ? GamePlatform.DS : GamePlatform.THREE_DS;
    }

    /** Records GBA output only after its streaming size/hash verification and close succeeded. */
    boolean markGbaSaved(long id, Uri destination, long verifiedSize, String verifiedSha1) {
        Uri saved = preparedUri(destination == null ? null : destination.toString());
        if (saved == null) return false;
        synchronized (LOCK) {
            List<TrackedDownload> records = readRecords();
            for (int i = 0; i < records.size(); i++) {
                TrackedDownload record = records.get(i);
                if (record.id != id) continue;
                if (record.platform != GamePlatform.GBA
                        || !GbaRomExporter.hasExpectedIdentity(record.expectedSha1, record.expectedSize)
                        || record.expectedSize != verifiedSize || !record.expectedSha1.equalsIgnoreCase(verifiedSha1)) return false;
                Uri original = successfulDownloadUri(id);
                if (original == null || original.equals(saved)) return false;
                TrackedDownload updated = new TrackedDownload(record, record.preparedDestination,
                        record.savedDsDestination, record.artworkUrl);
                updated.savedGbaDestination = saved;
                records.set(i, updated);
                if (writeRecords(records)) return true;
                records.set(i, record);
                writeRecords(records);
                return false;
            }
            return false;
        }
    }

    /** Returns a saved GBA copy only while it remains readable and nonempty. */
    public Uri savedGbaUri(long id) {
        synchronized (LOCK) {
            for (TrackedDownload record : readRecords()) {
                if (record.id != id) continue;
                Uri saved = record.savedGbaDestination;
                return record.platform == GamePlatform.GBA && saved != null && hasReadableContent(saved) ? saved : null;
            }
            return null;
        }
    }

    public Uri savedRomUri(long id) {
        Uri gba = savedGbaUri(id);
        return gba != null ? gba : savedDsUri(id);
    }

    /** Persists an attempt or its result so later refreshes never retry a failure in a loop. */
    boolean setCompletionStatus(long id, boolean attempted, String status) {
        synchronized (LOCK) {
            List<TrackedDownload> records = readRecords();
            for (TrackedDownload record : records) {
                if (record.id != id) continue;
                boolean oldAttempted = record.completionAttempted;
                String oldStatus = record.completionStatus;
                record.completionAttempted = attempted;
                record.completionStatus = status == null ? "" : status;
                if (writeRecords(records)) return true;
                record.completionAttempted = oldAttempted;
                record.completionStatus = oldStatus;
                writeRecords(records);
                return false;
            }
            return false;
        }
    }

    /** Returns the saved game only while its document is readable and nonempty. */
    public Uri preparedUri(long id) {
        synchronized (LOCK) {
            for (TrackedDownload record : readRecords()) {
                if (record.id == id) {
                    return record.preparedDestination != null && hasReadableContent(record.preparedDestination)
                            ? record.preparedDestination : null;
                }
            }
            return null;
        }
    }

    /** Earlier versions did not record evidence strong enough to delete a source automatically. */
    boolean resetUnverifiedPreparedCopy(long id) {
        synchronized (LOCK) {
            List<TrackedDownload> records = readRecords();
            for (int i = 0; i < records.size(); i++) {
                TrackedDownload record = records.get(i);
                if (record.id != id || record.preparedDestination == null) continue;
                if (!record.preparedDigest.isEmpty() && record.preparedSize > 0) return true;
                if (successfulDownloadUri(id) == null) return false;
                records.set(i, new TrackedDownload(record, null, record.savedDsDestination, record.artworkUrl));
                if (writeRecords(records)) return true;
                records.set(i, record);
                writeRecords(records);
                return false;
            }
            return true;
        }
    }

    /** Explicit retries rebuild only when the previous saved document cannot be verified. */
    boolean resetInvalidPreparedCopy(long id) {
        synchronized (LOCK) {
            List<TrackedDownload> records = readRecords();
            for (int i = 0; i < records.size(); i++) {
                TrackedDownload record = records.get(i);
                if (record.id != id || record.preparedDestination == null || record.originalDeleted) continue;
                if (hasReadableContent(record.preparedDestination) && matchesSavedCopy(record)) return true;
                if (successfulDownloadUri(id) == null) return false;
                records.set(i, new TrackedDownload(record, null, record.savedDsDestination, record.artworkUrl));
                if (writeRecords(records)) return true;
                records.set(i, record);
                writeRecords(records);
                return false;
            }
            return true;
        }
    }

    /** Removes only a completed source, preserving the saved game's history and destination. */
    public void deletePreparedOriginal(long id) {
        deletePreparedOriginal(id, () -> true);
    }

    void deletePreparedOriginal(long id, BooleanSupplier commitDeletion) {
        synchronized (LOCK) {
            List<TrackedDownload> records = readRecords();
            TrackedDownload record = null;
            for (TrackedDownload candidate : records) {
                if (candidate.id == id) { record = candidate; break; }
            }
            if (record == null || record.preparedDestination == null) {
                throw new IllegalStateException("Save this game to the games folder before deleting its original.");
            }
            if (record.originalDeleted) return;
            Uri original = successfulDownloadUri(id);
            if (original == null && hasDownload(id)) {
                throw new IllegalStateException("Only a completed download can have its original deleted.");
            }
            Uri prepared = record.preparedDestination;
            if (prepared.equals(original) || !hasReadableContent(prepared) || !matchesSavedCopy(record)) {
                throw new IllegalStateException("The saved copy is missing, incomplete, or unavailable. The original download is safe.");
            }
            if (!commitDeletion.getAsBoolean()) {
                throw new IllegalStateException("Saving cancelled. The original download is safe.");
            }
            try {
                // DownloadManager owns the original. Never delete the saved document or its folder.
                if (original != null && manager.remove(id) == 0 && hasDownload(id)) {
                    throw new IllegalStateException("Android could not delete the original download.");
                }
            } catch (RuntimeException ignored) {
                throw new IllegalStateException("The game was saved, but Android could not delete its original. Retry saving to finish cleanup.");
            }
            record.originalDeleted = true;
            record.completionAttempted = true;
            record.completionStatus = "Saved to games folder. Original download removed." + (record.requiresInstall
                    ? " Use Install CIA in Azahar to add this package."
                    : " Refresh Azahar's game list to see it.");
            if (!writeRecords(records)) {
                throw new IllegalStateException("The original was deleted, but its saved status could not be updated. Retry saving to finish cleanup.");
            }
        }
    }

    private boolean hasDownload(long id) {
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            // An unavailable query must not be mistaken for a deleted source.
            return cursor == null || cursor.moveToFirst();
        } catch (RuntimeException ignored) { return true; }
    }

    private boolean matchesSavedCopy(TrackedDownload record) {
        if (record.preparedDigest.isEmpty() || record.preparedSize <= 0) return false;
        try (InputStream stream = context.getContentResolver().openInputStream(record.preparedDestination)) {
            if (stream == null) return false;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            byte[] bytes = new byte[128 * 1024];
            int count;
            while ((count = stream.read(bytes)) != -1) {
                if (Thread.currentThread().isInterrupted()) return false;
                digest.update(bytes, 0, count);
                size += count;
            }
            return size == record.preparedSize && hex(digest.digest()).equals(record.preparedDigest);
        } catch (IOException | RuntimeException | NoSuchAlgorithmException ignored) { return false; }
    }

    static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }

    private Uri successfulDownloadUri(long id) {
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()
                    || cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    != DownloadManager.STATUS_SUCCESSFUL) return null;
            return preparedUri(String.valueOf(manager.getUriForDownloadedFile(id)));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean hasReadableContent(Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            // Providers can report an unknown length. Reading one byte verifies both access and
            // nonempty content without loading a potentially large game into memory.
            return input != null && input.read() != -1;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static Uri preparedUri(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(value);
            return "content".equals(uri.getScheme()) && uri.getAuthority() != null
                    && !uri.getAuthority().isEmpty() ? uri : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Returns a readable content URI only for a tracked, successfully completed download. */
    public Uri completedUri(long id) {
        synchronized (LOCK) {
            boolean tracked = false;
            for (TrackedDownload record : readRecords()) {
                if (record.id == id) {
                    tracked = true;
                    break;
                }
            }
            if (!tracked) {
                return null;
            }
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor == null || !cursor.moveToFirst()
                        || cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        != DownloadManager.STATUS_SUCCESSFUL) {
                    return null;
                }
                return readableUri(id);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private Uri readableUri(long id) {
        try {
            Uri uri = manager.getUriForDownloadedFile(id);
            if (uri == null || !"content".equals(uri.getScheme())) {
                return null;
            }
            try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                return descriptor == null ? null : uri;
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private List<TrackedDownload> readRecords() {
        List<TrackedDownload> records = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(RECORDS_KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject record = array.optJSONObject(i);
                if (record == null || record.optLong("id", -1) <= 0) {
                    continue;
                }
                TrackedDownload tracked = new TrackedDownload(record.optLong("id"),
                        DownloadPolicy.safeFilename(record.optString("name", "download")),
                        catalogPage(record.optString("sourcePage", CATALOG_HOME)),
                        preparedUri(record.optString("preparedDestination", null)),
                        preparedUri(record.optString("savedDsDestination", null)),
                        record.optString("artworkUrl", ""));
                tracked.completionAttempted = record.optBoolean("completionAttempted", false);
                tracked.completionStatus = record.optString("completionStatus", "");
                tracked.originalDeleted = record.optBoolean("originalDeleted", false);
                tracked.requiresInstall = record.optBoolean("requiresInstall", true);
                tracked.preparedSize = record.optLong("preparedSize", -1);
                tracked.preparedDigest = record.optString("preparedDigest", "");
                if (record.has("platform")) tracked.platform = GamePlatform.fromId(record.optString("platform", ""));
                tracked.expectedSha1 = record.optString("expectedSha1", "").toLowerCase(Locale.ROOT);
                tracked.expectedSize = record.optLong("expectedSize", -1);
                tracked.savedGbaDestination = preparedUri(record.optString("savedGbaDestination", null));
                records.add(tracked);
            }
        } catch (JSONException | ClassCastException ignored) {
            // A corrupt local list should not prevent browsing or querying future downloads.
        }
        return records;
    }

    private boolean writeRecords(List<TrackedDownload> records) {
        JSONArray array = new JSONArray();
        try {
            for (TrackedDownload record : records) {
                JSONObject object = new JSONObject();
                object.put("id", record.id);
                object.put("name", record.name);
                object.put("sourcePage", record.sourcePage);
                object.put("platform", record.platform.id);
                if (record.platform == GamePlatform.GBA) {
                    object.put("expectedSha1", record.expectedSha1);
                    object.put("expectedSize", record.expectedSize);
                    if (record.savedGbaDestination != null) {
                        object.put("savedGbaDestination", record.savedGbaDestination.toString());
                    }
                }
                if (!record.artworkUrl.isEmpty()) {
                    object.put("artworkUrl", record.artworkUrl);
                }
                if (record.preparedDestination != null) {
                    object.put("preparedDestination", record.preparedDestination.toString());
                    object.put("requiresInstall", record.requiresInstall);
                    object.put("preparedSize", record.preparedSize);
                    object.put("preparedDigest", record.preparedDigest);
                }
                object.put("completionAttempted", record.completionAttempted);
                object.put("completionStatus", record.completionStatus);
                object.put("originalDeleted", record.originalDeleted);
                if (record.savedDsDestination != null) {
                    object.put("savedDsDestination", record.savedDsDestination.toString());
                }
                array.put(object);
            }
            return preferences.edit().putString(RECORDS_KEY, array.toString()).commit();
        } catch (JSONException | RuntimeException ignored) {
            return false;
        }
    }

    private List<String> occupiedNames(List<TrackedDownload> records) {
        List<String> names = new ArrayList<>();
        for (TrackedDownload record : records) names.add(record.name);
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ThorShelf");
        try {
            String[] existing = directory.list();
            if (existing != null) {
                for (String name : existing) names.add(name);
            }
        } catch (SecurityException ignored) {
            // Scoped storage can hide files owned by other apps. DownloadManager still refuses
            // to overwrite them, including a file created after this best-effort name check.
        }
        // Keep queued names reserved even if our saved list was lost. Android limits this query
        // to this app's downloads; no storage permission or source URL is needed or persisted.
        try (Cursor cursor = manager.query(new DownloadManager.Query())) {
            if (cursor != null) {
                int localColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                while (localColumn >= 0 && cursor.moveToNext()) {
                    try {
                        String value = cursor.getString(localColumn);
                        if (value == null) continue;
                        Uri uri = Uri.parse(value);
                        if (!"file".equals(uri.getScheme()) || uri.getPath() == null) continue;
                        File file = new File(uri.getPath());
                        if (directory.equals(file.getParentFile())) names.add(file.getName());
                    } catch (RuntimeException ignored) {
                        // One inaccessible provider row should not hide other reservations.
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Saved records and visible files still protect the destinations we can identify.
        }
        return names;
    }

    static String uniqueFilename(String proposed, Iterable<String> existingNames) {
        Set<String> occupied = new HashSet<>();
        for (String name : existingNames) {
            if (name != null) occupied.add(name.toLowerCase(Locale.ROOT));
        }
        String base = GameFilename.clean(proposed);
        String candidate = base;
        for (int number = 2; occupied.contains(candidate.toLowerCase(Locale.ROOT)); number++) {
            candidate = GameFilename.numbered(base, number);
        }
        return candidate;
    }

    private static void addHeader(DownloadManager.Request request, String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid download header.");
        }
        request.addRequestHeader(name, value);
    }

    private static String catalogPage(String value) {
        if (GbaCatalog.isDetailUrl(value) || GbaCatalog.HOME.equals(value)) return value;
        if (NdsCatalog.isDetailUrl(value) || NdsCatalog.HOME.equals(value)) return value;
        return hshopPage(value);
    }

    private static String hshopPage(String value) {
        if (value == null || value.trim().isEmpty()) {
            return CATALOG_HOME;
        }
        try {
            Uri uri = Uri.parse(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"hshop.erista.me".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return CATALOG_HOME;
            }
            // Only the catalog location survives. Authentication and expiring link tokens do not.
            return uri.buildUpon().clearQuery().fragment(null).build().toString();
        } catch (RuntimeException ignored) {
            return CATALOG_HOME;
        }
    }

    private static String statusLabel(int status, int reason) {
        switch (status) {
            case DownloadManager.STATUS_PENDING:
                return "Queued";
            case DownloadManager.STATUS_RUNNING:
                return "Downloading";
            case DownloadManager.STATUS_SUCCESSFUL:
                return "Complete";
            case DownloadManager.STATUS_PAUSED:
                switch (reason) {
                    case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                        return "Waiting for a network connection";
                    case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                        return "Waiting for Wi-Fi";
                    case DownloadManager.PAUSED_WAITING_TO_RETRY:
                        return "Connection interrupted; Android will retry";
                    default:
                        return "Paused by Android";
                }
            case DownloadManager.STATUS_FAILED:
                switch (reason) {
                    case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                        return "Not enough storage. Free some space, then reopen its catalog page for a fresh link.";
                    case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                        return "Download storage is unavailable. Reopen its catalog page when storage is available.";
                    case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                        return "A file already exists at this destination. Reopen its catalog page for a fresh link.";
                    case DownloadManager.ERROR_FILE_ERROR:
                        return "Could not save the file. Check storage, then reopen its catalog page for a fresh link.";
                    case DownloadManager.ERROR_CANNOT_RESUME:
                        return "This transfer could not resume. Reopen its catalog page for a fresh link.";
                    case DownloadManager.ERROR_HTTP_DATA_ERROR:
                        return "The connection failed. Reopen its catalog page for a fresh link.";
                    default:
                        return "Download failed or its link expired. Reopen its catalog page for a fresh link.";
                }
            default:
                return "Android download status is unavailable";
        }
    }

    private static final class TrackedDownload {
        final long id;
        final String name;
        final String sourcePage;
        final Uri preparedDestination;
        final Uri savedDsDestination;
        final String artworkUrl;
        GamePlatform platform;
        String expectedSha1 = "";
        long expectedSize = -1;
        Uri savedGbaDestination;
        boolean completionAttempted;
        boolean originalDeleted;
        boolean requiresInstall = true;
        long preparedSize = -1;
        String preparedDigest = "";
        String completionStatus = "";

        TrackedDownload(TrackedDownload other, Uri preparedDestination, Uri savedDsDestination, String artworkUrl) {
            this(other.id, other.name, other.sourcePage, preparedDestination, savedDsDestination, artworkUrl);
            completionAttempted = other.completionAttempted;
            originalDeleted = other.originalDeleted;
            requiresInstall = other.requiresInstall;
            preparedSize = other.preparedSize;
            preparedDigest = other.preparedDigest;
            completionStatus = other.completionStatus;
            platform = other.platform;
            expectedSha1 = other.expectedSha1;
            expectedSize = other.expectedSize;
            savedGbaDestination = other.savedGbaDestination;
        }

        TrackedDownload(long id, String name, String sourcePage, Uri preparedDestination, Uri savedDsDestination,
                        String artworkUrl) {
            this.id = id;
            this.name = name;
            this.sourcePage = sourcePage;
            this.preparedDestination = preparedDestination;
            this.savedDsDestination = savedDsDestination;
            this.artworkUrl = ArtworkPolicy.isAllowed(artworkUrl) ? artworkUrl : "";
            this.platform = legacyPlatform(name, sourcePage);
        }
    }

    public static final class Entry {
        public final long id;
        public final long downloaded;
        public final long total;
        public final String name;
        /** Readable label for older downloads; name still identifies the original saved file. */
        public final String displayName;
        public final String sourcePage;
        public final String artworkUrl;
        public final GamePlatform platform;
        public final String expectedSha1;
        public final long expectedSize;
        public final Uri savedGbaDestination;
        /** The platform's saved raw ROM, while retaining savedDsDestination for older callers. */
        public final Uri savedRomDestination;
        /** Saved export metadata; use savedDsUri before opening to verify the document is available. */
        public final Uri savedDsDestination;
        public final Uri preparedDestination;
        public final boolean originalDeleted;
        public final boolean completionAttempted;
        public final boolean requiresInstall;
        public final boolean hasVerifiedCopy;
        public final String completionStatus;
        public final String statusLabel;
        public final int status;
        public final int reason;
        private final boolean prepared;

        private Entry(TrackedDownload record, long downloaded, long total, int status, int reason,
                      String statusLabel) {
            this.id = record.id;
            this.name = record.name;
            this.displayName = GameFilename.clean(record.name);
            this.sourcePage = record.sourcePage;
            this.artworkUrl = record.artworkUrl;
            this.platform = record.platform;
            this.expectedSha1 = record.expectedSha1;
            this.expectedSize = record.expectedSize;
            this.savedGbaDestination = record.savedGbaDestination;
            this.savedRomDestination = record.platform == GamePlatform.GBA ? record.savedGbaDestination
                    : record.platform == GamePlatform.DS ? record.savedDsDestination : null;
            this.savedDsDestination = record.savedDsDestination;
            this.preparedDestination = record.preparedDestination;
            this.originalDeleted = record.originalDeleted;
            this.completionAttempted = record.completionAttempted;
            this.requiresInstall = record.requiresInstall;
            this.hasVerifiedCopy = record.preparedSize > 0 && !record.preparedDigest.isEmpty();
            this.completionStatus = record.completionStatus;
            this.downloaded = downloaded;
            this.total = total;
            this.status = status;
            this.reason = reason;
            this.statusLabel = statusLabel;
            this.prepared = record.preparedDestination != null;
        }

        public boolean isComplete() {
            return status == DownloadManager.STATUS_SUCCESSFUL;
        }

        public boolean isPrepared() {
            return prepared;
        }

        public boolean isActive() {
            return status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED;
        }
    }
}
