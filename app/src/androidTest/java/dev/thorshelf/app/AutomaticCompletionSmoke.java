package dev.thorshelf.app;

import android.app.DownloadManager;
import android.app.Instrumentation;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Opt-in device check. Only the fresh generated fixture may be processed or deleted. */
final class AutomaticCompletionSmoke {
    private AutomaticCompletionSmoke() { }

    static long run(Context context, Instrumentation instrumentation) throws Exception {
        require(!PreparationService.isRunning(context), "Wait for the current save to finish before this check.");
        Uri folder = PreparationFolder.saved(context.getSharedPreferences("settings", Context.MODE_PRIVATE));
        require(PreparationFolder.canWrite(context, folder), "Select a writable games folder in Settings first.");
        SharedPreferences preferences = context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE);
        JSONArray originalRecords = new JSONArray(preferences.getString("records", "[]"));
        DownloadStore store = new DownloadStore(context);
        List<DownloadStore.Entry> existing = store.list();
        Set<Long> guarded = new HashSet<>();
        long id = -1;
        try {
            for (DownloadStore.Entry entry : existing) {
                if (entry.isComplete() && entry.name.toLowerCase(Locale.ROOT).endsWith(".cia")) {
                    guarded.add(entry.id);
                    require(store.setCompletionStatus(entry.id, true, entry.completionStatus), "Could not protect existing downloads.");
                }
            }
            Set<String> before = children(context, folder);
            id = SyntheticDemoSeeder.seedFresh(context);
            File original = originalFile(context, id);
            require(original.isFile(), "The generated original is missing.");
            DownloadCompletion.processPending(context);
            awaitFinished(context, id);
            awaitCoordinatorIdle(context, instrumentation);
            DownloadStore.Entry completed = entry(new DownloadStore(context), id);
            Uri saved = new DownloadStore(context).preparedUri(id);
            require(completed.isComplete() && completed.originalDeleted && saved != null,
                    "Saved history or readable destination was not retained.");
            require(!completed.requiresInstall, "Synthetic base game was not converted to a cartridge.");
            require(!original.exists() && !hasDownload(context, id), "The original download was not removed.");
            verifyCartridge(context, saved);
            Set<String> after = children(context, folder);
            require(after.containsAll(before), "A pre-existing destination document disappeared.");
            Set<String> added = new HashSet<>(after);
            added.removeAll(before);
            require(added.size() == 1 && added.contains(DocumentsContract.getDocumentId(saved)),
                    "Automatic completion did not create exactly one final file.");
            DownloadCompletion.processPending(context);
            require(!PreparationService.isRunning(context) && children(context, folder).equals(after),
                    "A repeat completion trigger created another output.");
            return id;
        } finally {
            if (id > 0 && PreparationService.isRunning(context) && PreparationService.downloadId(context) == id) {
                PreparationService.cancel(context);
            }
            long deadline = SystemClock.elapsedRealtime() + 30_000;
            while (PreparationService.isRunning(context) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            require(!PreparationService.isRunning(context), "Synthetic save did not stop; existing downloads remain guarded.");
            awaitCoordinatorIdle(context, instrumentation);
            restoreCompletionFields(preferences, originalRecords, guarded);
        }
    }

    @SuppressWarnings("deprecation")
    private static void awaitCoordinatorIdle(Context context, Instrumentation instrumentation) throws InterruptedException {
        // Service cleanup starts the follow-up coordinator on the main looper. Drain that
        // callback, then join its worker before restoring any earlier eligible downloads.
        long deadline = SystemClock.elapsedRealtime() + 30_000;
        boolean serviceRunning;
        do {
            serviceRunning = false;
            for (ActivityManager.RunningServiceInfo service : context.getSystemService(ActivityManager.class).getRunningServices(100)) {
                if (PreparationService.class.getName().equals(service.service.getClassName())) serviceRunning = true;
            }
            if (serviceRunning) Thread.sleep(100);
        } while (serviceRunning && SystemClock.elapsedRealtime() < deadline);
        require(!serviceRunning, "Preparation service did not stop; existing downloads remain guarded.");
        instrumentation.waitForIdleSync();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("thorshelf-next-download".equals(thread.getName())
                    || "thorshelf-preparation-recovery".equals(thread.getName())) {
                thread.join(30_000);
                require(!thread.isAlive(), "Completion coordinator did not stop; existing downloads remain guarded.");
            }
        }
    }

    private static void awaitFinished(Context context, long id) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            DownloadStore.Entry current = entry(new DownloadStore(context), id);
            if (!PreparationService.isRunning(context)) {
                if (current.originalDeleted) return;
                if (current.completionAttempted) throw new AssertionError("Synthetic completion failed: " + current.completionStatus);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Synthetic completion timed out: " + PreparationService.status(context));
    }

    private static void verifyCartridge(Context context, Uri saved) throws Exception {
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(saved);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            require(input != null, "Cannot read saved cartridge.");
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            bytes = output.toByteArray();
        }
        require(bytes.length == 0x4000 + 2 * SyntheticCia.CONTENT_LENGTH, "Saved cartridge has the wrong size.");
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        require(header.getInt(0x100) == 0x4453434e && header.getLong(0x108) == SyntheticCia.TITLE_ID,
                "Saved cartridge has the wrong NCSD header or title ID.");
        for (int slot : new int[]{0, 2}) {
            int offset = header.getInt(0x120 + slot * 8) * 512;
            require(offset >= 0x4000 && offset + SyntheticCia.CONTENT_LENGTH <= bytes.length,
                    "Saved cartridge partition is out of bounds.");
            require((bytes[offset + 0x18f] & 4) != 0, "Saved cartridge partition is still encrypted.");
            int payload = offset + SyntheticCia.EXEFS_OFFSET + 0x200;
            require(Arrays.equals(SyntheticCia.PAYLOAD, Arrays.copyOfRange(bytes, payload, payload + SyntheticCia.PAYLOAD.length)),
                    "Saved cartridge does not contain the expected decrypted payload.");
        }
    }

    private static Set<String> children(Context context, Uri folder) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder));
        Set<String> result = new HashSet<>();
        try (Cursor cursor = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            require(cursor != null, "Cannot inspect the selected games folder.");
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }

    private static File originalFile(Context context, long id) {
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            require(cursor != null && cursor.moveToFirst(), "Synthetic download is missing.");
            Uri uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)));
            require("file".equals(uri.getScheme()), "Synthetic original does not have a local file URI.");
            return new File(uri.getPath());
        }
    }

    private static boolean hasDownload(Context context, long id) {
        try (Cursor cursor = context.getSystemService(DownloadManager.class)
                .query(new DownloadManager.Query().setFilterById(id))) {
            return cursor == null || cursor.moveToFirst();
        }
    }

    private static DownloadStore.Entry entry(DownloadStore store, long id) {
        for (DownloadStore.Entry entry : store.list()) if (entry.id == id) return entry;
        throw new AssertionError("Synthetic download history disappeared.");
    }

    private static void restoreCompletionFields(SharedPreferences preferences, JSONArray original, Set<Long> guarded) throws Exception {
        JSONArray current = new JSONArray(preferences.getString("records", "[]"));
        for (int i = 0; i < current.length(); i++) {
            JSONObject record = current.getJSONObject(i);
            long id = record.getLong("id");
            if (!guarded.contains(id)) continue;
            for (int j = 0; j < original.length(); j++) {
                JSONObject prior = original.getJSONObject(j);
                if (prior.getLong("id") != id) continue;
                for (String field : new String[]{"completionAttempted", "completionStatus"}) {
                    if (prior.has(field)) record.put(field, prior.get(field));
                    else record.remove(field);
                }
            }
        }
        require(preferences.edit().putString("records", current.toString()).commit(), "Could not restore existing completion states.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
