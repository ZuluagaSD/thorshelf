package dev.thorshelf.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowPackageManager;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class MelonDsLauncherTest {
    private static final String STABLE = "me.magnum.melonds";
    private static final String NIGHTLY = "me.magnum.melonds.nightly";
    private static final String LIBRARY = "me.magnum.melonds.ui.romlist.RomListActivity";
    private static final String EMULATOR = "me.magnum.melonds.ui.emulator.EmulatorActivity";
    private static final Uri ROM = Uri.parse("content://com.android.externalstorage.documents/document/"
            + "primary%3AROMs%2Fnds%2FTest%20game.nds");

    private ActivityController<Activity> controller;
    private Activity activity;

    @Before public void launch() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
    }

    @After public void close() {
        controller.pause().stop().destroy();
    }

    @Test public void opensInstalledStableLibraryWhenBothBuildsAreAvailable() {
        install(NIGHTLY);
        install(STABLE);

        assertTrue(MelonDsLauncher.open(activity, null));

        assertLibraryLaunch(STABLE);
    }

    @Test public void opensSavedGameInStableWithUriAndTemporaryReadAccess() {
        install(STABLE);
        install(NIGHTLY);

        assertTrue(MelonDsLauncher.open(activity, ROM));

        assertGameLaunch(STABLE);
    }

    @Test public void opensNightlyLibraryWhenStableIsNotInstalled() {
        install(NIGHTLY);

        assertTrue(MelonDsLauncher.open(activity, null));

        assertLibraryLaunch(NIGHTLY);
    }

    @Test public void opensGameWithNightlyPackageAndSharedEmulatorClass() {
        install(NIGHTLY);

        assertTrue(MelonDsLauncher.open(activity, ROM));

        assertGameLaunch(NIGHTLY);
    }

    @Test public void returnsFalseWithoutLaunchingWhenNoSupportedBuildIsInstalled() {
        assertFalse(MelonDsLauncher.open(activity, null));
        assertFalse(MelonDsLauncher.open(activity, ROM));
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    private void install(String packageName) {
        ShadowPackageManager packages = shadowOf(activity.getPackageManager());
        ComponentName library = new ComponentName(packageName, LIBRARY);
        ActivityInfo libraryInfo = packages.addActivityIfNotPresent(library);
        libraryInfo.exported = true;
        libraryInfo.enabled = true;
        libraryInfo.applicationInfo.enabled = true;
        IntentFilter libraryFilter = new IntentFilter(Intent.ACTION_MAIN);
        libraryFilter.addCategory(Intent.CATEGORY_LAUNCHER);
        packages.addIntentFilterForActivity(library, libraryFilter);

        ComponentName emulator = new ComponentName(packageName, EMULATOR);
        ActivityInfo emulatorInfo = packages.addActivityIfNotPresent(emulator);
        emulatorInfo.exported = true;
        emulatorInfo.enabled = true;
        packages.addIntentFilterForActivity(emulator, upstreamGameFilter(packageName));
    }

    private void assertLibraryLaunch(String packageName) {
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(new ComponentName(packageName, LIBRARY), intent.getComponent());
        assertEquals(Intent.ACTION_MAIN, intent.getAction());
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertNull(intent.getData());
        assertNull(intent.getClipData());
        assertFalse(intent.hasExtra("uri"));
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    private void assertGameLaunch(String packageName) {
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(new ComponentName(packageName, EMULATOR), intent.getComponent());
        assertEquals(packageName + ".LAUNCH_ROM", intent.getAction());
        assertNull(intent.getData());
        assertNull(intent.getType());
        // Android 13+ applies full filter matching even to external explicit intents.
        assertTrue("Game launch must match melonDS's action-only exported intent filter",
                upstreamGameFilter(packageName).match(activity.getContentResolver(), intent,
                        true, "MelonDsLauncherTest") >= 0);
        // Older melonDS versions cast this extra to String rather than accepting a Uri.
        assertEquals(ROM.toString(), intent.getExtras().get("uri"));
        assertNotNull(intent.getClipData());
        assertEquals(1, intent.getClipData().getItemCount());
        assertEquals(ROM, intent.getClipData().getItemAt(0).getUri());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.getFlags());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    private static IntentFilter upstreamGameFilter(String packageName) {
        IntentFilter filter = new IntentFilter(packageName + ".LAUNCH_ROM");
        filter.addAction(packageName + ".LAUNCH_FIRMWARE");
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        return filter;
    }
}
