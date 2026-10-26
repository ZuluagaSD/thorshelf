package dev.thorshelf.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Copies a completed download to the new document returned by ACTION_CREATE_DOCUMENT. */
public final class ExportService extends Service {
    private static final Object STATE_LOCK = new Object();
    private static final String CHANNEL = "export";
    private static final int NOTIFICATION_ID = 102;
    private static volatile boolean active;
    private static volatile boolean recoveryRunning;
    private static String activeToken;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile CopyJob current;

    /** The destination must be a newly created SAF document, never an existing library file. */
    public static void start(Context context, long downloadId, Uri destination, String name) {
        start(context, downloadId, destination, name, false);
    }

    /** Exports an original .nds file, extracting exactly one ROM when its download is a ZIP. */
    public static void startNds(Context context, long downloadId, Uri destination, String name) {
        start(context, downloadId, destination, name, true);
    }

    private static void start(Context context, long downloadId, Uri destination, String name, boolean nds) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        if (downloadId <= 0 || destination == null || !"content".equals(destination.getScheme())) {
            throw new IllegalArgumentException("Choose a new document as the export destination.");
        }
        status(app); // Recover a destination left behind by a previous process before starting again.
        String token = UUID.randomUUID().toString();
        String safeName = DownloadPolicy.safeFilename(name);
        synchronized (STATE_LOCK) {
            if (active || recoveryRunning) {
                deleteUnusedDocument(app, destination);
                throw new IllegalStateException("An export is already running or being cleaned up. Wait for it to finish.");
            }
            active = true;
            activeToken = token;
            boolean saved = preferences(app).edit()
                    .putString("status", "Export queued: " + safeName)
                    .putBoolean("in_progress", true)
                    .putString("destination", destination.toString())
                    .putBoolean("destination_owned", false)
                    .putString("token", token).commit();
            if (!saved) {
                active = false;
                activeToken = null;
                deleteUnusedDocument(app, destination);
                throw new IllegalStateException("Could not save the export request. Check available device storage.");
            }
        }
        Intent intent = new Intent(app, ExportService.class)
                .putExtra("download_id", downloadId)
                .putExtra("destination", destination.toString())
                .putExtra("name", safeName)
                .putExtra("nds", nds)
                .putExtra("token", token);
        try {
            app.startForegroundService(intent);
        } catch (RuntimeException ignored) {
            synchronized (STATE_LOCK) {
                active = false;
                activeToken = null;
                preferences(app).edit().putString("status",
                        "Export could not start. Keep ThorShelf visible and try again.").commit();
            }
            status(app); // The saved destination is cleaned up asynchronously.
            throw new IllegalStateException("Export could not start. Keep ThorShelf visible and try again.");
        }
    }

    /** Last progress or result; detects a process interruption and cleans up its partial document. */
    public static String status(Context context) {
        Context application = context.getApplicationContext();
        final Context app = application == null ? context : application;
        synchronized (STATE_LOCK) {
            SharedPreferences prefs = preferences(app);
            if (prefs.getBoolean("in_progress", false) && !active && !recoveryRunning) {
                recoveryRunning = true;
                String savedDestination = prefs.getString("destination", "");
                boolean owned = prefs.getBoolean("destination_owned", true);
                prefs.edit().putString("status", "Export was interrupted. Cleaning up its partial file…").commit();
                new Thread(() -> {
                    Uri destination = parseDestination(savedDestination);
                    boolean deleted = owned ? deleteDocument(app, destination) : deleteEmptyDocument(app, destination);
                    releaseDocumentGrant(app, destination);
                    synchronized (STATE_LOCK) {
                        prefs.edit().putBoolean("in_progress", false)
                                .remove("destination").remove("destination_owned").remove("token")
                                .putString("status", deleted
                                        ? "Export was interrupted. The original download is safe; export it again."
                                        : "Export was interrupted. The original is safe; remove the partial destination file before trying again.")
                                .commit();
                        recoveryRunning = false;
                    }
                }, "thorshelf-export-recovery").start();
            }
            return prefs.getString("status", "");
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "File exports", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Progress while copying downloads to a folder you choose");
            notifications.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Uri destination = intent == null ? null : parseDestination(intent.getStringExtra("destination"));
        String token = intent == null ? null : intent.getStringExtra("token");
        String name = intent == null ? "download" : DownloadPolicy.safeFilename(intent.getStringExtra("name"));
        long downloadId = intent == null ? -1 : intent.getLongExtra("download_id", -1);
        if (destination == null || token == null || downloadId <= 0) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (current != null && !current.finished.get()) {
            // A duplicate delivery of the same request must never delete the active destination.
            if (!current.token.equals(token)) deleteUnusedDocument(this, destination);
            return START_NOT_STICKY;
        }
        CopyJob job = new CopyJob(startId, downloadId, destination, name, token,
                intent.getBooleanExtra("nds", false));
        current = job;
        synchronized (STATE_LOCK) {
            active = true;
            activeToken = token;
        }
        try {
            startForeground(NOTIFICATION_ID, notification("Preparing " + name, 0, -1),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            job.future = worker.submit(() -> copy(job));
        } catch (RuntimeException ignored) {
            cancel(job, "Export could not start. Keep ThorShelf visible and check Android's app permissions.");
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void copy(CopyJob job) {
        job.started.set(true);
        boolean success = false;
        String message = "Export failed. Check the destination's available space and access, then try again.";
        try {
            if (job.cancelled) return;
            ContentResolver resolver = getContentResolver();
            // ACTION_CREATE_DOCUMENT should produce an empty document; never replace existing content.
            long destinationSize = sizeOf(resolver, job.destination);
            job.destinationVerified = true;
            if (destinationSize > 0 || job.nds && destinationSize < 0) {
                job.mayDeleteDestination = false;
                message = destinationSize > 0
                        ? "The chosen destination already contains data. Choose a new filename for the export."
                        : "Could not confirm that the destination is empty. Choose a new file with another document provider.";
                return;
            }
            job.mayDeleteDestination = true;
            DownloadStore store = new DownloadStore(this);
            Uri source = store.completedUri(job.downloadId);
            if (source == null) {
                message = "The source download is missing or incomplete. Download it again before exporting.";
                return;
            }
            if (source.equals(job.destination)) {
                job.mayDeleteDestination = false;
                message = "The export destination must differ from the original download.";
                return;
            }
            String sourceName = null;
            if (job.nds) {
                for (DownloadStore.Entry entry : store.list()) {
                    if (entry.id == job.downloadId && entry.isComplete()) sourceName = entry.name;
                }
                // Use the saved source name, never the display name supplied by the picker caller.
                if (sourceName == null || !(sourceName.toLowerCase(java.util.Locale.ROOT).endsWith(".nds")
                        || sourceName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip"))) {
                    message = "Choose a completed .nds download or a ZIP containing one DS ROM.";
                    return;
                }
            }
            long total = sizeOf(resolver, source);
            if (job.cancelled) return;
            job.input = resolver.openInputStream(source);
            if (job.input == null) throw new IOException();
            if (job.cancelled) return;
            // Persist ownership only after confirming an empty destination distinct from the
            // tracked source. Recovery can now remove our partial output if this process dies.
            if (!preferences(this).edit().putBoolean("destination_owned", true).commit()) {
                throw new IOException("Could not save export cleanup state.");
            }
            job.output = resolver.openOutputStream(job.destination, "wt");
            if (job.output == null) throw new IOException();
            if (job.nds) {
                NdsRomExporter.export(job.input, job.output, sourceName, total, new NdsRomExporter.Progress() {
                    long lastUpdate;
                    @Override public boolean isCancelled() { return job.cancelled; }
                    @Override public void onProgress(long copied, long expected) {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastUpdate >= 500) {
                            progress(job, copied, expected);
                            lastUpdate = now;
                        }
                    }
                });
            } else {
                byte[] buffer = new byte[128 * 1024];
                long copied = 0;
                long lastUpdate = 0;
                int count;
                while (!job.cancelled && !Thread.currentThread().isInterrupted()
                        && (count = job.input.read(buffer)) != -1) {
                    job.output.write(buffer, 0, count);
                    copied += count;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastUpdate >= 500) {
                        progress(job, copied, total);
                        lastUpdate = now;
                    }
                }
                if (total >= 0 && copied != total && !job.cancelled) {
                    message = "The copy ended before the whole file was transferred. Check storage and export it again.";
                    return;
                }
            }
            if (job.cancelled || Thread.currentThread().isInterrupted()) return;
            job.output.flush();
            job.output.close();
            job.output = null;
            job.input.close();
            job.input = null;
            success = true;
            message = "Export complete: " + job.name;
        } catch (SecurityException ignored) {
            message = "Access to the export destination was lost. Choose the destination again.";
        } catch (NdsRomExporter.InvalidRomException invalid) {
            message = invalid.getMessage();
        } catch (IOException | RuntimeException ignored) {
            // Provider exceptions can contain private paths; use the actionable message above.
        } finally {
            closeQuietly(job.output);
            closeQuietly(job.input);
            finish(job, success, job.cancelled ? job.cancelMessage : message);
        }
    }

    private void progress(CopyJob job, long copied, long total) {
        String message = (job.nds ? "Exporting DS ROM " : "Copying ") + job.name + " · " + bytes(copied)
                + (total > 0 ? " / " + bytes(total) : "");
        synchronized (STATE_LOCK) {
            if (job.finished.get() || !job.token.equals(activeToken)) return;
            preferences(this).edit().putString("status", message).apply();
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications != null) {
            try {
                notifications.notify(NOTIFICATION_ID, notification(message, copied, total));
            } catch (RuntimeException ignored) {
                // Notification permission can change while the foreground service keeps copying.
            }
        }
    }

    private void finish(CopyJob job, boolean success, String message) {
        if (!job.finished.compareAndSet(false, true)) return;
        success = success && !job.cancelled;
        boolean cleaned = success || (job.destinationVerified
                ? !job.mayDeleteDestination || deleteDocument(this, job.destination)
                : deleteEmptyDocument(this, job.destination));
        if (!cleaned) {
            message += " Remove the partial destination file manually. The original download is safe.";
        }
        releaseDocumentGrant(this, job.destination);
        synchronized (STATE_LOCK) {
            if (job.token.equals(activeToken)) {
                preferences(this).edit().putString("status", message).putBoolean("in_progress", false)
                        .remove("destination").remove("destination_owned").remove("token").commit();
                active = false;
                activeToken = null;
            }
        }
        main.post(() -> {
            if (current == job) {
                current = null;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(job.startId);
            }
        });
    }

    private void cancel(CopyJob job, String message) {
        if (job == null || job.finished.get()) return;
        job.cancelMessage = message;
        job.cancelled = true;
        synchronized (STATE_LOCK) {
            if (job.token.equals(activeToken)) {
                preferences(this).edit().putString("status", message).apply();
            }
        }
        Future<?> future = job.future;
        if (future != null) future.cancel(true);
        // Closing a remote document can block, so cancellation never waits on the main thread.
        new Thread(() -> {
            closeQuietly(job.output);
            closeQuietly(job.input);
            if (!job.started.get()) finish(job, false, message);
        }, "thorshelf-export-cancel").start();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        cancel(current, "Android stopped the long-running export. The original is safe; export it again.");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        cancel(current, "Export was interrupted. The original download is safe; export it again.");
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification(String message, long copied, long total) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_shelf).setContentTitle("ThorShelf export")
                .setContentText(message).setContentIntent(content).setOnlyAlertOnce(true)
                .setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS);
        int percent = total > 0 ? (int) Math.min(100, copied * 100.0 / total) : 0;
        return builder.setProgress(100, percent, total <= 0).build();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("export_status", Context.MODE_PRIVATE);
    }

    private static Uri parseDestination(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(value);
            return "content".equals(uri.getScheme()) ? uri : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long sizeOf(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (RuntimeException ignored) {
            // Some document providers cannot report a size until a stream is opened.
        }
        return -1;
    }

    private static boolean deleteDocument(Context context, Uri uri) {
        if (uri == null) return true;
        try {
            return DocumentsContract.deleteDocument(context.getContentResolver(), uri);
        } catch (java.io.FileNotFoundException ignored) {
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean deleteEmptyDocument(Context context, Uri uri) {
        // A request may fail before the worker validates its document. Preserve anything
        // that already contains data (or whose size cannot be established).
        return uri == null || sizeOf(context.getContentResolver(), uri) != 0 || deleteDocument(context, uri);
    }

    private static void deleteUnusedDocument(Context context, Uri uri) {
        Context application = context.getApplicationContext();
        Context app = application == null ? context : application;
        synchronized (STATE_LOCK) {
            // Repeated delivery of one picker result must not delete a document already being copied.
            if ((active || recoveryRunning)
                    && uri.toString().equals(preferences(app).getString("destination", ""))) return;
        }
        new Thread(() -> {
            deleteEmptyDocument(app, uri);
            releaseDocumentGrant(app, uri);
        }, "thorshelf-export-unused").start();
    }

    private static void releaseDocumentGrant(Context context, Uri uri) {
        if (uri == null) return;
        try {
            ContentResolver resolver = context.getContentResolver();
            for (UriPermission permission : resolver.getPersistedUriPermissions()) {
                if (!uri.equals(permission.getUri())) continue;
                int flags = (permission.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                        | (permission.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
                if (flags != 0) resolver.releasePersistableUriPermission(uri, flags);
                break;
            }
        } catch (RuntimeException ignored) {
            // A provider may already have revoked the temporary document's persisted grant.
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException | RuntimeException ignored) {
            // Cancellation may close the same provider stream while the copy unwinds.
        }
    }

    private static String bytes(long count) {
        if (count >= 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f GiB", count / (1024.0 * 1024 * 1024));
        }
        if (count >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f MiB", count / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.US, "%.0f KiB", count / 1024.0);
    }

    private static final class CopyJob {
        final int startId;
        final long downloadId;
        final Uri destination;
        final String name;
        final String token;
        final boolean nds;
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean started = new AtomicBoolean();
        volatile Future<?> future;
        volatile InputStream input;
        volatile OutputStream output;
        volatile boolean cancelled;
        volatile boolean destinationVerified;
        volatile boolean mayDeleteDestination;
        volatile String cancelMessage = "Export was interrupted. The original download is safe; export it again.";

        CopyJob(int startId, long downloadId, Uri destination, String name, String token, boolean nds) {
            this.startId = startId;
            this.downloadId = downloadId;
            this.destination = destination;
            this.name = name;
            this.token = token;
            this.nds = nds;
        }
    }
}
