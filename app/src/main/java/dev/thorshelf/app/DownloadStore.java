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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        if (url == null || !DownloadPolicy.isAllowedDownload(url)) {
            throw new IllegalArgumentException("This download address is not an approved HTTPS source.");
        }
        String name = uniqueFilename(DownloadPolicy.safeFilename(fileName));
        String page = catalogPage(sourcePage);
        final DownloadManager.Request request;
        try {
            request = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(name)
                    .setDescription("ThorShelf · hShop")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ThorShelf/" + name)
                    .setAllowedOverRoaming(false);
            if (wifiOnly) {
                request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
            }
            addHeader(request, "User-Agent", userAgent);
            addHeader(request, "Cookie", cookie);
            if (sourcePage != null && !sourcePage.trim().isEmpty()) {
                addHeader(request, "Referer", page);
            }
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException("Could not prepare this download. Reopen its hShop page and try again.");
        }

        synchronized (LOCK) {
            final long id;
            try {
                id = manager.enqueue(request);
            } catch (RuntimeException ignored) {
                throw new IllegalStateException("Android could not start the download. Check available storage and the download service.");
            }
            List<TrackedDownload> records = readRecords();
            records.add(new TrackedDownload(id, name, page));
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
                if (entry == null) {
                    entry = queried
                            ? new Entry(record, 0, -1, DownloadManager.STATUS_FAILED, DownloadManager.ERROR_UNKNOWN,
                            "Removed from Android downloads. Reopen its hShop page for a fresh link.")
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
                records.add(new TrackedDownload(record.optLong("id"),
                        DownloadPolicy.safeFilename(record.optString("name", "download")),
                        catalogPage(record.optString("sourcePage", CATALOG_HOME))));
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
                array.put(object);
            }
            return preferences.edit().putString(RECORDS_KEY, array.toString()).commit();
        } catch (JSONException | RuntimeException ignored) {
            return false;
        }
    }

    private static String uniqueFilename(String safeName) {
        String suffix = "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        int dot = safeName.lastIndexOf('.');
        if (dot > 0 && safeName.length() - dot > 10) {
            dot = -1;
        }
        String extension = dot > 0 ? safeName.substring(dot) : "";
        String stem = dot > 0 ? safeName.substring(0, dot) : safeName;
        while (!stem.isEmpty() && (stem.length() + suffix.length() + extension.length() > 100
                || (stem + suffix + extension).getBytes(StandardCharsets.UTF_8).length > 180)) {
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
        }
        if (dot > 0) {
            return stem + suffix + extension;
        }
        return stem + suffix;
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
                        return "Not enough storage. Free some space, then reopen its hShop page for a fresh link.";
                    case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                        return "Download storage is unavailable. Reopen its hShop page when storage is available.";
                    case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                        return "A file already exists at this destination. Reopen its hShop page for a fresh link.";
                    case DownloadManager.ERROR_FILE_ERROR:
                        return "Could not save the file. Check storage, then reopen its hShop page for a fresh link.";
                    case DownloadManager.ERROR_CANNOT_RESUME:
                        return "This transfer could not resume. Reopen its hShop page for a fresh link.";
                    case DownloadManager.ERROR_HTTP_DATA_ERROR:
                        return "The connection failed. Reopen its hShop page for a fresh link.";
                    default:
                        return "Download failed or its link expired. Reopen its hShop page for a fresh link.";
                }
            default:
                return "Android download status is unavailable";
        }
    }

    private static final class TrackedDownload {
        final long id;
        final String name;
        final String sourcePage;

        TrackedDownload(long id, String name, String sourcePage) {
            this.id = id;
            this.name = name;
            this.sourcePage = sourcePage;
        }
    }

    public static final class Entry {
        public final long id;
        public final long downloaded;
        public final long total;
        public final String name;
        public final String sourcePage;
        public final String statusLabel;
        public final int status;
        public final int reason;

        private Entry(TrackedDownload record, long downloaded, long total, int status, int reason,
                      String statusLabel) {
            this.id = record.id;
            this.name = record.name;
            this.sourcePage = record.sourcePage;
            this.downloaded = downloaded;
            this.total = total;
            this.status = status;
            this.reason = reason;
            this.statusLabel = statusLabel;
        }

        public boolean isComplete() {
            return status == DownloadManager.STATUS_SUCCESSFUL;
        }

        public boolean isActive() {
            return status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED;
        }
    }
}
