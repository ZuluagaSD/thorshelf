package dev.thorshelf.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.IOException;

/** The reusable GBA destination, independent of the other games folders. */
final class GbaGamesFolder {
    static final String PREFERENCE = "gbaGamesFolder";
    private GbaGamesFolder() { }

    static Uri saved(SharedPreferences preferences) {
        return RomGamesFolder.saved(preferences, PREFERENCE);
    }

    /** Call on a worker: document providers may perform remote I/O. */
    static void startExport(Context context, long downloadId, Uri folder, String name) throws IOException {
        RomGamesFolder.startExport(context, downloadId, folder, name, GamePlatform.GBA);
    }
}
