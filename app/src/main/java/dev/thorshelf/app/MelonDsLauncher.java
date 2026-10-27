package dev.thorshelf.app;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Uses melonDS Android's exported LAUNCH_ROM contract, including older string-URI builds. */
final class MelonDsLauncher {
    private static final String[] PACKAGES = {"me.magnum.melonds", "me.magnum.melonds.nightly"};
    private static final String EMULATOR_ACTIVITY = "me.magnum.melonds.ui.emulator.EmulatorActivity";
    static final String WEBSITE = "https://github.com/rafaelvcaetano/melonDS-android/releases";

    private MelonDsLauncher() {}

    /** A null ROM opens the library. Returns false when no supported installed build can open. */
    static boolean open(Context context, Uri rom) {
        for (String pkg : PACKAGES) {
            Intent library = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (library == null) continue;
            Intent intent = rom == null ? library : gameIntent(pkg, rom);
            try {
                context.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                // The app may have been disabled, removed, or restricted since the lookup.
            }
        }
        return false;
    }

    static Intent gameIntent(String pkg, Uri rom) {
        // melonDS's action-only filter must also match on Android 13+. Carry the URI in
        // its supported string extra and ClipData, leaving Intent.data and MIME type unset.
        Intent intent = new Intent(pkg + ".LAUNCH_ROM")
                .setClassName(pkg, EMULATOR_ACTIVITY)
                .putExtra("uri", rom.toString())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Nintendo DS game", rom));
        return intent;
    }
}
