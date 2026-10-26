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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prepares a private copy, validates it, then saves a new SAF document for Azahar. */
public final class PreparationService extends Service {
    private static final Object STATE_LOCK = new Object();
    private static final String CHANNEL = "preparation";
    private static final String ACTION_CANCEL = "dev.thorshelf.app.CANCEL_PREPARATION";
    private static final int NOTIFICATION_ID = 103;
    private static final long STORAGE_RESERVE = 64L * 1024 * 1024;
    private static volatile boolean active;
    private static volatile boolean recoveryRunning;
    private static String activeToken;
    private static String cancelledToken;

    interface Progress {
        void update(int percent, String message);
        boolean isCancelled();
    }

    /** Package-private seam keeps service lifecycle tests independent of JNI and real games. */
    interface Pipeline {
        PreparedOutput prepare(Context context, File input, File output, File scratch, Progress progress) throws IOException;
        default void cancel() { }
    }

    static final class PreparedOutput {
        final File file;
        final boolean requiresInstall;

        PreparedOutput(File file, boolean requiresInstall) {
            this.file = file;
            this.requiresInstall = requiresInstall;
        }
    }

    static volatile Pipeline pipeline = new LocalPipeline();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile PreparationJob current;

    /** Folder must be returned by ACTION_OPEN_DOCUMENT_TREE; output is created after validation. */
    public static void start(Context context, long downloadId, Uri folder, String name) {
        Context app = application(context);
        if (downloadId <= 0 || folder == null || !"content".equals(folder.getScheme())
                || !DocumentsContract.isTreeUri(folder)) {
            throw new IllegalArgumentException("Choose a games folder for the prepared file.");
        }
        status(app);
        String token = UUID.randomUUID().toString();
        String safeName = DownloadPolicy.safeFilename(name);
        synchronized (STATE_LOCK) {
            if (active || recoveryRunning) {
                throw new IllegalStateException("A CIA is already being prepared or cleaned up. Wait for it to finish.");
            }
            active = true;
            activeToken = token;
            cancelledToken = null;
            if (!preferences(app).edit().putString("status", "Preparation queued: " + safeName)
                    .putBoolean("in_progress", true).putBoolean("ready", false).putInt("progress", 0)
                    .putBoolean("destination_owned", false)
                    .remove("destination").putString("folder", folder.toString()).putString("token", token).commit()) {
                active = false;
                activeToken = null;
                throw new IllegalStateException("Could not save the preparation request. Check free device storage.");
            }
        }
        Intent intent = new Intent(app, PreparationService.class)
                .putExtra("download_id", downloadId).putExtra("folder", folder.toString())
                .putExtra("name", safeName).putExtra("token", token);
        try {
            app.startForegroundService(intent);
        } catch (RuntimeException ignored) {
            synchronized (STATE_LOCK) {
                active = false;
                activeToken = null;
            }
            status(app);
            throw new IllegalStateException("Preparation could not start. Keep ThorShelf visible and try again.");
        }
    }

    public static String status(Context context) {
        Context app = application(context);
        synchronized (STATE_LOCK) {
            SharedPreferences prefs = preferences(app);
            if (prefs.getBoolean("in_progress", false) && !active && !recoveryRunning) {
                recoveryRunning = true;
                String savedDestination = prefs.getString("destination", "");
                boolean ownedDestination = prefs.getBoolean("destination_owned", false);
                prefs.edit().putString("status", "Preparation was interrupted. Cleaning up temporary files…")
                        .putBoolean("ready", false).commit();
                new Thread(() -> {
                    Uri destination = parseDestination(savedDestination);
                    boolean deleted = destination == null || !ownedDestination || isFolderRoot(destination)
                            || deleteDocument(app, destination);
                    releaseDocumentGrant(app, destination);
                    boolean cleaned = deleteTree(workspaceRoot(app));
                    synchronized (STATE_LOCK) {
                        prefs.edit().putBoolean("in_progress", false).putBoolean("ready", false)
                                .remove("destination").remove("destination_owned").remove("folder").remove("token").putInt("progress", 0)
                                .putString("status", "Preparation was interrupted. The original download is safe; try again."
                                        + (!deleted ? " Remove the partial destination file manually." : "")
                                        + (!cleaned ? " Some temporary files could not be removed." : "")).commit();
                        recoveryRunning = false;
                    }
                }, "thorshelf-preparation-recovery").start();
            }
            return prefs.getString("status", "");
        }
    }

    public static boolean isRunning(Context context) {
        status(context);
        synchronized (STATE_LOCK) { return active || recoveryRunning; }
    }

    public static int progress(Context context) {
        return preferences(context).getInt("progress", 0);
    }

    public static boolean isReady(Context context) {
        status(context);
        return preferences(context).getBoolean("ready", false);
    }

    public static boolean requiresInstall(Context context) {
        // Prepared files from earlier versions were CIAs.
        return preferences(context).getBoolean("requires_install", true);
    }

    /** Native extraction and rebuilding stop at their next safe cancellation point. */
    public static void cancel(Context context) {
        Context app = application(context);
        String token;
        synchronized (STATE_LOCK) {
            if (!active) return;
            token = activeToken;
            cancelledToken = token;
            preferences(app).edit().putString("status", "Stopping preparation at the next safe point…").apply();
        }
        try {
            app.startService(new Intent(app, PreparationService.class).setAction(ACTION_CANCEL).putExtra("token", token));
        } catch (RuntimeException ignored) {
            // The worker also observes cancelledToken, including when Android refuses a new start.
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "CIA preparation", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Progress preparing a local CIA copy for Azahar");
            manager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            PreparationJob job = current;
            if (job != null && job.token.equals(intent.getStringExtra("token"))) {
                cancelJob(job, "Preparation cancelled. The original download is safe.");
            } else if (job == null) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        Uri folder = intent == null ? null : parseDestination(intent.getStringExtra("folder"));
        String token = intent == null ? null : intent.getStringExtra("token");
        long downloadId = intent == null ? -1 : intent.getLongExtra("download_id", -1);
        if (folder == null || !DocumentsContract.isTreeUri(folder) || token == null || downloadId <= 0) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        PreparationJob previous = current;
        if (previous != null && !previous.finished.get()) {
            return START_NOT_STICKY;
        }
        synchronized (STATE_LOCK) {
            if (!token.equals(preferences(this).getString("token", ""))) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            active = true;
            activeToken = token;
        }
        PreparationJob job = new PreparationJob(startId, downloadId, folder,
                DownloadPolicy.safeFilename(intent.getStringExtra("name")), token, pipeline,
                new File(workspaceRoot(this), token));
        current = job;
        try {
            startForeground(NOTIFICATION_ID, notification(job, "Preparing " + job.name, 0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            job.future = worker.submit(() -> prepare(job));
        } catch (RuntimeException ignored) {
            cancelJob(job, "Preparation could not start. Keep ThorShelf visible and check Android's app permissions.");
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void prepare(PreparationJob job) {
        job.started.set(true);
        boolean success = false;
        String message = "Preparation failed. Check free storage and try again.";
        try {
            checkCancelled(job);
            Uri source = new DownloadStore(this).completedUri(job.downloadId);
            if (source == null) {
                message = "The source download is missing or incomplete. Download it again before preparing.";
                return;
            }
            ContentResolver resolver = getContentResolver();
            if (!job.directory.isDirectory() && !job.directory.mkdirs()) {
                throw new IOException("Cannot create workspace");
            }
            long sourceSize = sizeOf(resolver, source);
            if (sourceSize > 0) requireSpace(job.directory, sourceSize, 1);
            File input = new File(job.directory, "input.cia");
            File output = new File(job.directory, "prepared.cia");
            message = "Could not copy the source into temporary storage. Check free space and download it again if needed.";
            job.input = resolver.openInputStream(source);
            if (job.input == null) throw new IOException();
            job.output = new FileOutputStream(input);
            long staged = copy(job, sourceSize, 0, 15, "Reading original CIA");
            closeStreams(job);
            if (staged == 0) {
                message = "The downloaded CIA is empty. Download it again before preparing.";
                return;
            }
            checkCancelled(job);
            message = "This CIA could not be prepared. It may be incomplete or need an unavailable key or seed.";
            PreparedOutput prepared = job.pipeline.prepare(this, input, output, new File(job.directory, "scratch"), new Progress() {
                @Override public void update(int percent, String detail) {
                    publish(job, Math.max(15, Math.min(90, percent)), detail);
                }
                @Override public boolean isCancelled() { return cancelled(job); }
            });
            checkCancelled(job);
            if (prepared == null || prepared.file == null || !prepared.file.isFile() || prepared.file.length() == 0) {
                message = "Preparation did not produce a valid output file. The original download is safe.";
                return;
            }
            job.requiresInstall = prepared.requiresInstall;
            String outputName = preparedFilename(job.name, prepared.requiresInstall);
            message = "Could not save the prepared game. Check the folder's free space and access, then try again.";
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(job.folder, DocumentsContract.getTreeDocumentId(job.folder));
            job.destination = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", outputName);
            if (job.destination == null || isFolderRoot(job.destination)
                    || DocumentsContract.getDocumentId(parent).equals(DocumentsContract.getDocumentId(job.destination))) {
                job.destination = null;
                throw new IOException("Provider did not create a child file");
            }
            if (source.equals(job.destination) || !isEmptyDocument(resolver, job.destination)) {
                job.destination = null;
                message = "The folder returned an existing file. Nothing was overwritten; choose another folder and try again.";
                return;
            }
            job.mayDeleteDestination = true;
            synchronized (STATE_LOCK) {
                if (!job.token.equals(activeToken) || !preferences(this).edit()
                        .putString("destination", job.destination.toString()).putBoolean("destination_owned", true).commit()) {
                    throw new IOException("Cannot record destination ownership");
                }
            }
            checkCancelled(job);
            job.input = new FileInputStream(prepared.file);
            job.output = resolver.openOutputStream(job.destination, "wt");
            if (job.output == null) throw new IOException();
            copy(job, prepared.file.length(), 90, 100, "Saving " + (prepared.requiresInstall ? "prepared CIA" : "game to folder"));
            closeStreams(job);
            checkCancelled(job);
            success = true;
            message = "Saved " + outputName + ". " + (prepared.requiresInstall
                    ? "Use Install CIA in Azahar to add this prepared package."
                    : "Refresh Azahar's game list to see it. Add this folder to Azahar's Games Folder once if needed.");
        } catch (PreparationException error) {
            message = error.getMessage();
        } catch (SecurityException ignored) {
            message = "File access was lost. Choose the destination again and retry preparation.";
        } catch (IOException | RuntimeException ignored) {
            // Provider errors may include private paths; keep the actionable message for this stage.
        } finally {
            closeStreams(job);
            if (cancelled(job)) message = job.cancelMessage;
            finish(job, success, message);
        }
    }

    private long copy(PreparationJob job, long total, int from, int to, String detail) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long copied = 0;
        int count;
        publish(job, from, detail);
        while ((count = job.input.read(buffer)) != -1) {
            checkCancelled(job);
            job.output.write(buffer, 0, count);
            copied += count;
            int percent = total > 0 ? from + (int) Math.min(to - from, copied * (double) (to - from) / total) : from;
            publish(job, percent, detail);
        }
        checkCancelled(job);
        if (total >= 0 && copied != total) throw new IOException("The stream ended at an unexpected size");
        job.output.flush();
        // Stream close errors, including remote provider failures, must prevent a success result.
        job.output.close();
        job.output = null;
        job.input.close();
        job.input = null;
        return copied;
    }

    private void publish(PreparationJob job, int percent, String detail) {
        if (cancelled(job) || job.finished.get()) return;
        long now = SystemClock.elapsedRealtime();
        if (percent == job.lastPercent && now - job.lastUpdate < 500) return;
        if (now - job.lastUpdate < 300 && percent != 100) return;
        job.lastPercent = percent;
        job.lastUpdate = now;
        String message = detail + " · " + percent + "%";
        synchronized (STATE_LOCK) {
            if (!job.token.equals(activeToken)) return;
            preferences(this).edit().putString("status", message).putInt("progress", percent).apply();
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            try { manager.notify(NOTIFICATION_ID, notification(job, message, percent)); }
            catch (RuntimeException ignored) { }
        }
    }

    private void finish(PreparationJob job, boolean success, String message) {
        if (!job.finished.compareAndSet(false, true)) return;
        success = success && !cancelled(job);
        if (!success && job.mayDeleteDestination
                && !deleteDocument(this, job.destination)) {
            message += " Remove the partial destination file manually. The original download is safe.";
        }
        if (!deleteTree(job.directory)) message += " Some temporary files could not be removed.";
        synchronized (STATE_LOCK) {
            if (job.token.equals(activeToken)) {
                preferences(this).edit().putString("status", message).putBoolean("in_progress", false)
                        .putBoolean("ready", success).putInt("progress", success ? 100 : 0)
                        .putBoolean("requires_install", job.requiresInstall)
                        .remove("destination").remove("destination_owned").remove("folder").remove("token").commit();
                active = false;
                activeToken = null;
                cancelledToken = null;
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

    private void cancelJob(PreparationJob job, String message) {
        if (job == null || job.finished.get() || job.cancelled) return;
        job.cancelMessage = message;
        job.cancelled = true;
        synchronized (STATE_LOCK) {
            if (job.token.equals(activeToken)) {
                cancelledToken = job.token;
                preferences(this).edit().putString("status", "Stopping preparation at the next safe point…").apply();
            }
        }
        job.pipeline.cancel();
        Future<?> future = job.future;
        if (future != null) future.cancel(true);
        new Thread(() -> {
            closeStreams(job);
            if (!job.started.get()) finish(job, false, message);
        }, "thorshelf-preparation-cancel").start();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        cancelJob(current, "Android stopped the long-running preparation. The original is safe; try again.");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        cancelJob(current, "Preparation was interrupted. The original download is safe; try again.");
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification notification(PreparationJob job, String message, int percent) {
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION_ID,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, NOTIFICATION_ID,
                new Intent(this, PreparationService.class).setAction(ACTION_CANCEL).putExtra("token", job.token),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_shelf)
                .setContentTitle("Preparing for Azahar").setContentText(message).setContentIntent(open)
                .setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(100, percent, false)
                .addAction(new Notification.Action.Builder(null, "Cancel", cancel).build()).build();
    }

    private static boolean cancelled(PreparationJob job) {
        synchronized (STATE_LOCK) {
            return job.cancelled || job.token.equals(cancelledToken) || Thread.currentThread().isInterrupted();
        }
    }

    private static void checkCancelled(PreparationJob job) throws InterruptedIOException {
        if (cancelled(job)) throw new InterruptedIOException("Preparation cancelled");
    }

    private static void requireSpace(File directory, long length, int copies) throws PreparationException {
        long required = length > (Long.MAX_VALUE - STORAGE_RESERVE) / copies
                ? Long.MAX_VALUE : length * copies + STORAGE_RESERVE;
        if (directory.getUsableSpace() < required) {
            long gibibytes = Math.max(1, (long) Math.ceil(required / (1024.0 * 1024 * 1024)));
            throw new PreparationException("More temporary storage is needed: free at least " + gibibytes
                    + " GiB on the device and retry. The original download is safe.");
        }
    }

    private static Context application(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("preparation_status", Context.MODE_PRIVATE);
    }

    private static File workspaceRoot(Context context) { return new File(context.getFilesDir(), "preparation"); }

    private static Uri parseDestination(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(value);
            return "content".equals(uri.getScheme()) ? uri : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private static long sizeOf(ContentResolver resolver, Uri uri) {
        if (uri == null) return -1;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (RuntimeException ignored) { }
        return -1;
    }

    private static boolean isEmptyDocument(ContentResolver resolver, Uri uri) throws IOException {
        long size = sizeOf(resolver, uri);
        if (size >= 0) return size == 0;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) throw new IOException("Cannot verify the new destination");
            return stream.read() == -1;
        }
    }

    private static boolean deleteDocument(Context context, Uri uri) {
        if (uri == null) return true;
        if (isFolderRoot(uri)) return false;
        try { return DocumentsContract.deleteDocument(context.getContentResolver(), uri); }
        catch (java.io.FileNotFoundException ignored) { return true; }
        catch (RuntimeException ignored) { return false; }
    }

    private static boolean isFolderRoot(Uri uri) {
        if (uri == null || !DocumentsContract.isTreeUri(uri)) return false;
        try {
            return uri.getPathSegments().size() == 2
                    || DocumentsContract.getTreeDocumentId(uri).equals(DocumentsContract.getDocumentId(uri));
        } catch (IllegalArgumentException ignored) { return true; }
    }

    private static String preparedFilename(String original, boolean requiresInstall) {
        String stem = original == null ? "game" : original.replaceFirst("(?i)\\.cia$", "");
        return DownloadPolicy.safeFilename(stem + " - Azahar." + (requiresInstall ? "cia" : "3ds"));
    }

    private static void releaseDocumentGrant(Context context, Uri uri) {
        if (uri == null || DocumentsContract.isTreeUri(uri)) return;
        try {
            ContentResolver resolver = context.getContentResolver();
            for (UriPermission permission : resolver.getPersistedUriPermissions()) {
                if (!uri.equals(permission.getUri())) continue;
                int flags = (permission.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                        | (permission.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
                if (flags != 0) resolver.releasePersistableUriPermission(uri, flags);
                break;
            }
        } catch (RuntimeException ignored) { }
    }

    private static void closeStreams(PreparationJob job) {
        closeQuietly(job.output);
        closeQuietly(job.input);
        job.output = null;
        job.input = null;
    }

    private static void closeQuietly(Closeable stream) {
        if (stream == null) return;
        try { stream.close(); }
        catch (IOException | RuntimeException ignored) { }
    }

    private static boolean deleteTree(File file) {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return true;
        boolean success = true;
        // Never traverse symlinks even if a native tool left an unexpected workspace entry.
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) success = deleteTree(child) && success;
        }
        return file.delete() && success;
    }

    private static final class LocalPipeline implements Pipeline {
        @Override public PreparedOutput prepare(Context context, File input, File output, File scratch, Progress progress) throws IOException {
            CiaValidator.Result original;
            try { original = CiaValidator.inspect(input); }
            catch (IOException error) { throw new PreparationException("This download is not a complete, supported CIA: " + error.getMessage()); }
            if (!original.encryptedContent) {
                try {
                    CiaValidator.validatePrepared(input, new CiaValidator.Progress() {
                        @Override public void onProgress(int percent, String message) { progress.update(15 + percent * 53 / 100, "Checking CIA contents"); }
                        @Override public boolean isCancelled() { return progress.isCancelled(); }
                    });
                    if (!input.renameTo(output)) throw new IOException("Cannot keep the verified local copy");
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (IOException error) { throw new PreparationException("CIA validation failed: " + error.getMessage()); }
                return exportGame(context, output, original, progress);
            }
            requireSpace(input.getParentFile(), input.length(), 4);
            try {
                NativePreparation.prepare(input, output, scratch, NativePreparation.bundledSeedDatabase(context),
                        new NativePreparation.Progress() {
                            @Override public void onProgress(int percent, String message) {
                                progress.update(15 + percent * 45 / 100,
                                        message == null || message.trim().isEmpty() ? "Preparing CIA" : message);
                            }
                            @Override public boolean isCancelled() { return progress.isCancelled(); }
                        });
            } catch (InterruptedIOException cancelled) { throw cancelled; }
            catch (IOException error) { throw new PreparationException(error.getMessage()); }
            CiaValidator.Result prepared;
            try {
                prepared = CiaValidator.validatePrepared(output, new CiaValidator.Progress() {
                    @Override public void onProgress(int percent, String message) { progress.update(60 + percent * 8 / 100, "Validating prepared CIA"); }
                    @Override public boolean isCancelled() { return progress.isCancelled(); }
                });
            } catch (InterruptedIOException cancelled) { throw cancelled; }
            catch (IOException error) { throw new PreparationException("The prepared CIA did not pass validation: " + error.getMessage()); }
            if (!original.titleId.equals(prepared.titleId) || original.titleVersion != prepared.titleVersion
                    || !original.contentIdentities.equals(prepared.contentIdentities)) {
                throw new PreparationException("The prepared CIA changed its title or content list. It was not saved; the original is safe.");
            }
            // Free the private staging copy before building a cartridge. The downloaded source
            // is separate and never touched; this keeps the conservative storage budget bounded.
            if (!input.delete()) throw new PreparationException("Could not clear temporary storage. Retry preparation.");
            return exportGame(context, output, prepared, progress);
        }

        private PreparedOutput exportGame(Context context, File cia, CiaValidator.Result validated, Progress progress) throws IOException {
            if (!CxiExporter.isBaseApplication(validated)) return new PreparedOutput(cia, true);
            requireSpace(cia.getParentFile(), cia.length(), 4);
            File cartridge = new File(cia.getParentFile(), "prepared.3ds");
            try {
                NativePreparation.prepareCartridge(cia, cartridge, new File(cia.getParentFile(), "cartridge-scratch"),
                        NativePreparation.bundledSeedDatabase(context), new NativePreparation.Progress() {
                    @Override public void onProgress(int percent, String message) {
                        progress.update(68 + percent * 16 / 100, "Creating .3ds game for Azahar");
                    }
                    @Override public boolean isCancelled() { return progress.isCancelled(); }
                });
                CartridgeValidator.validate(cia, cartridge, new CiaValidator.Progress() {
                    @Override public void onProgress(int percent, String message) {
                        progress.update(84 + percent * 6 / 100, "Checking .3ds game data");
                    }
                    @Override public boolean isCancelled() { return progress.isCancelled(); }
                });
            } catch (InterruptedIOException cancelled) { throw cancelled; }
            catch (IOException error) {
                throw new PreparationException("Could not create a playable game file: " + error.getMessage());
            }
            return new PreparedOutput(cartridge, false);
        }

        @Override public void cancel() { NativePreparation.cancel(); }
    }

    private static final class PreparationException extends IOException {
        PreparationException(String message) { super(message); }
    }

    private static final class PreparationJob {
        final int startId;
        final long downloadId;
        final Uri folder;
        final String name;
        final String token;
        final Pipeline pipeline;
        final File directory;
        final AtomicBoolean started = new AtomicBoolean();
        final AtomicBoolean finished = new AtomicBoolean();
        volatile boolean cancelled;
        volatile Uri destination;
        volatile boolean requiresInstall = true;
        volatile boolean mayDeleteDestination;
        volatile String cancelMessage = "Preparation cancelled. The original download is safe.";
        volatile Future<?> future;
        volatile InputStream input;
        volatile OutputStream output;
        int lastPercent = -1;
        long lastUpdate;

        PreparationJob(int startId, long downloadId, Uri folder, String name, String token, Pipeline pipeline, File directory) {
            this.startId = startId;
            this.downloadId = downloadId;
            this.folder = folder;
            this.name = name;
            this.token = token;
            this.pipeline = pipeline;
            this.directory = directory;
        }
    }
}
