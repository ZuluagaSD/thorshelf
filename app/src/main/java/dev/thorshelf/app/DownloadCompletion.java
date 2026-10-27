package dev.thorshelf.app;

import android.content.Context;
import android.net.Uri;

import java.util.List;
import java.util.Locale;

/** Serializes automatic saving, including completed downloads discovered after reopening the app. */
final class DownloadCompletion {
    private static final Object LOCK = new Object();
    static final String FOLDER_UNAVAILABLE = "The games folder is unavailable. Choose an accessible folder in Settings, then retry saving.";

    private DownloadCompletion() { }

    /** Call on a worker: checking the selected folder and saved files may involve provider I/O. */
    static void processPending(Context context) {
        synchronized (LOCK) {
            if (PreparationService.isRunning(context)) return;
            DownloadStore store = new DownloadStore(context);
            List<DownloadStore.Entry> entries = store.list();
            Uri folder = PreparationFolder.saved(context.getSharedPreferences("settings", Context.MODE_PRIVATE));
            // Finish in enqueue order, one game at a time, to bound temporary storage.
            for (int i = entries.size() - 1; i >= 0; i--) {
                DownloadStore.Entry entry = entries.get(i);
                if (!entry.isComplete() || !entry.name.toLowerCase(Locale.ROOT).endsWith(".cia")
                        || entry.originalDeleted || entry.completionAttempted) continue;
                if (entry.isPrepared() && entry.hasVerifiedCopy) {
                    try { PreparationService.startCleanup(context, entry.id, entry.displayName); }
                    catch (RuntimeException ignored) { /* A visible refresh resumes a deferred start. */ }
                    return;
                }
                if (entry.isPrepared() && !store.resetUnverifiedPreparedCopy(entry.id)) {
                    store.setCompletionStatus(entry.id, true,
                            "The saved game cannot be verified and its original is unavailable. Download it again to save a verified copy.");
                    continue;
                }
                if (folder == null || !PreparationFolder.hasPersistedAccess(context, folder)) return;
                if (!PreparationFolder.canWrite(context, folder)) {
                    store.setCompletionStatus(entry.id, true,
                            FOLDER_UNAVAILABLE);
                    continue;
                }
                try { PreparationService.start(context, entry.id, folder, entry.displayName); }
                catch (RuntimeException ignored) {
                    // Android can disallow foreground services from a download-completion broadcast.
                    // Keep it queued so the next visible refresh can safely start the same request.
                }
                return;
            }
        }
    }

    static boolean needsFolder(DownloadStore.Entry entry) {
        return FOLDER_UNAVAILABLE.equals(entry.completionStatus);
    }

    static void folderChanged(Context context) {
        DownloadStore store = new DownloadStore(context);
        for (DownloadStore.Entry entry : store.list()) {
            if (needsFolder(entry)) store.setCompletionStatus(entry.id, false, "Waiting to save to games folder…");
        }
        processPending(context);
    }

    static void retry(Context context, long downloadId) {
        synchronized (LOCK) {
            if (PreparationService.isRunning(context) && PreparationService.downloadId(context) == downloadId) return;
            DownloadStore store = new DownloadStore(context);
            if (!store.resetInvalidPreparedCopy(downloadId)) {
                throw new IllegalStateException("The saved game is unavailable and its original could not be recovered. Download it again.");
            }
            if (!store.setCompletionStatus(downloadId, false, "Waiting to save to games folder…")) {
                throw new IllegalStateException("Could not save the retry request. Check free device storage.");
            }
        }
        processPending(context);
    }
}
