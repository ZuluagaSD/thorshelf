package dev.thorshelf.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Explicit emulator-preview setup only; this class never ships in the application APK. */
final class SyntheticDemoSeeder {
    private static final String NAME = "Synthetic cartridge demo (not a game).cia";

    private SyntheticDemoSeeder() { }

    @SuppressWarnings("deprecation")
    static long seed(Context context) throws Exception {
        DownloadStore store = new DownloadStore(context);
        for (DownloadStore.Entry entry : store.list()) {
            if (NAME.equals(entry.name) && entry.isComplete() && store.completedUri(entry.id) != null) {
                return entry.id;
            }
        }
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Cannot create the synthetic demo folder.");
        }
        File file = new File(directory, "synthetic-preparation-demo-" + System.currentTimeMillis() + ".cia");
        byte[] fixture = SyntheticCia.createCartridge(true);
        Files.write(file.toPath(), fixture);
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long id = -1;
        try {
            id = manager.addCompletedDownload(NAME, "Locally generated encryption fixture; not a playable game",
                    false, "application/octet-stream", file.getAbsolutePath(), fixture.length, true);
            if (id <= 0) throw new IOException("Android could not register the synthetic demo.");
            SharedPreferences preferences = context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE);
            JSONArray records = new JSONArray(preferences.getString("records", "[]"));
            records.put(new JSONObject().put("id", id).put("name", NAME)
                    .put("sourcePage", "https://hshop.erista.me/"));
            if (!preferences.edit().putString("records", records.toString()).commit()) {
                throw new IOException("Could not save the synthetic demo record.");
            }
            if (new DownloadStore(context).completedUri(id) == null) {
                // Use normal store cleanup after registering so no stale entry remains.
                new DownloadStore(context).cancel(id);
                throw new IOException("Android registered the fixture but did not provide a readable URI.");
            }
            return id;
        } catch (Exception failure) {
            if (id > 0) manager.remove(id);
            file.delete();
            throw failure;
        }
    }
}
