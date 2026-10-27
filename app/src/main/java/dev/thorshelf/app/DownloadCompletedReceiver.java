package dev.thorshelf.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Best-effort background start; foreground refresh also discovers any deferred completions. */
public final class DownloadCompletedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        PendingResult pending = goAsync();
        Context application = context.getApplicationContext();
        new Thread(() -> {
            try { DownloadCompletion.processPending(application == null ? context : application); }
            catch (RuntimeException ignored) { /* A later visible refresh retries deferred requests. */ }
            finally { if (pending != null) pending.finish(); }
        }, "thorshelf-download-completion").start();
    }
}
