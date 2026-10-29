# Development

## Toolchain

- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- Android NDK 28.2.13676358 and CMake 3.22.1
- Android Platform Tools (`adb`) for device tests

Set `JAVA_HOME` and `ANDROID_HOME`, or configure `sdk.dir` in an untracked
`local.properties`. Install the required SDK packages with:

```bash
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools' \
  'ndk;28.2.13676358' 'cmake;3.22.1'
```

The wrapper pins Gradle 8.9 and its checksum; Android Gradle Plugin is 8.7.3.
Native provenance is in [native/README.md](../native/README.md).

## Build and test

```bash
./scripts/build.sh
```

Run this from the repository root for `testDebugUnitTest lintDebug assembleDebug`.
The APK is `app/build/outputs/apk/debug/app-debug.apk`. Tests use JUnit and Robolectric;
real native execution requires the device tests below.

The script accepts Gradle tasks as arguments and recognizes the development
toolchain in `/tmp/thorshelf-toolchain` (override with `THORSHELF_TOOLCHAIN_DIR`).
That temporary directory may be cleared on reboot. With your environment already
configured, the equivalent command is:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For an unsigned release APK:

```bash
./scripts/build.sh lintRelease assembleRelease
```

Output: `app/build/outputs/apk/release/app-release-unsigned.apk`. Published APKs are
signed separately with Android Build Tools `apksigner`; keep the release keystore
and credentials outside the repository. A debug APK cannot update an installation
signed with the release key, or vice versa.

## Optional emulator

Install SDK packages `emulator` and `system-images;android-33;google_apis;x86_64`,
then run `./scripts/emulator.sh` after building. The script uses `avdmanager` and
Python 3 to create an Android 13 device, opens it in landscape, and installs the
debug APK. It reuses existing devices without wiping or resizing them.

New virtual devices use `dist/android-emulator/avd/` with a 16 GiB data disk;
`ANDROID_AVD_HOME` overrides the location. Older devices under the toolchain
directory are also reused. On Linux with user systemd, the emulator runs as a
temporary user service. Close its window to stop it.

## GBA catalog and verification

The GBA catalog is bundled metadata generated from Central Arquivista's file
manifest and No-Intro identities. [GBA_CATALOG.md](GBA_CATALOG.md) documents the
inputs, attribution, exclusions, and regeneration command. Generator checks run
with `python3 -m unittest discover -s scripts -p 'test_generate_gba_catalog.py'`.

GBA downloads retain their expected raw-file SHA-1 and byte size in download
history. Saving streams through `GbaRomExporter`; `ExportService` records the
saved destination only after verification and successful stream closure. Failed
exports remove their owned partial output while retaining the original download.
DS and GBA have independent folder preferences and share destination ownership
checks. Existing DS/3DS history migrates without changing its saved destinations.

Implementation validation on 2026-10-29: **771 unit/framework tests** and **four
generator tests** passed. Debug lint completed with no errors and 45 advisory
warnings; the debug APK built successfully. GBA browsing, filtered search, artwork,
and portrait/landscape layouts were also checked on the Android 13 x86_64 emulator.
Tests use bundled metadata and
synthetic files/providers, including wrong size/hash, cancellation, write/close
failures, retries, saved-state restoration, and existing DS/3DS behavior. A live
Central Arquivista game transfer, physical-device saving, and GBA playback remain
outside these automated checks.

## Appearance

`AppTheme` resolves the saved `settings/theme` choice: System (the default), Light,
Dark, or OLED black. `Ui.colors(context)` returns an immutable palette for that
context. Native activity/dialog styles mirror those colors; keep the palettes
and `res/values/styles.xml` aligned when changing them. The companion presentation
uses the same preference. Theme selection recreates the activity using its normal
saved-state path, preserving the catalog, filters, download history, and pending
save picker.

OLED uses black root and card backgrounds, removes decorative dots and card
elevation, and keeps artwork unchanged. The launch-only theme is black before
preferences load, including Android 12's native splash screen. Runtime System
mode still follows Android's current night setting. Android 15 can enforce
transparent system bars, so the content beneath them also paints the theme
background. Bar contrast overlays are disabled and icon appearance follows the
palette.

`AppThemeTest` and `ThemeUiTest` cover contrast, actual background/focus pixels,
native dialogs, system bars, startup backgrounds, preference/configuration
changes, restored GBA browsing/download state, and companion recreation on SDK
29 and 35.

Appearance validation on 2026-10-29: all **793 unit/framework tests** passed,
including 22 new theme tests. Debug lint had no errors (45 advisory warnings),
and the debug APK built successfully. Dark/OLED settings, dialogs, catalogs,
downloads, System night-mode switching, and portrait/landscape layouts were
checked on the Android 13 x86_64 emulator. Physical OLED hardware has not been
checked in this pass.

## Native device tests

Connect one emulator or Android device with debugging enabled:

```bash
./scripts/build.sh assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r dev.thorshelf.app.test/dev.thorshelf.app.NativePreparationInstrumentation
```

Success reports `passed=10` and `INSTRUMENTATION_CODE: -1`. The scenarios generate
synthetic files locally and cover encryption, cartridge conversion, sparse
partitions, validation, cancellation, and cleanup. No game files are included.

To check automatic saving, first choose a writable 3DS games folder in Settings:

```bash
adb shell am instrument -w -e test_completion true dev.thorshelf.app.test/dev.thorshelf.app.NativePreparationInstrumentation
```

This verifies the saved synthetic cartridge, original-file removal, retained
history, and duplicate prevention. Use `-e seed_demo true` instead to add a
non-playable synthetic fixture for manual UI checks. Both helpers require the
test APK and preserve existing queue entries.

## Recorded validation — v0.7.0, 2026-10-29

The release passed 793 unit/framework tests and four GBA catalog-generator tests.
Release lint reported zero errors and 45 advisory warnings. The Android 10+
release APK contains ARM64 and x86_64 libraries; its signature, certificate match
against the published v0.6.0 APK, and 16 KiB page/ZIP alignment were verified.
Version code 9 updates the public v0.6.0 installation in place using the existing
release signing key. Native engine sources are unchanged from v0.6.0.

GBA and theme emulator coverage and outstanding physical-device checks are
documented above. A live GBA game transfer/playback and physical OLED checks have
not been performed in this release pass.

## Recorded validation — v0.6.0, 2026-10-27

The release passed 698 unit/framework tests, release lint with zero errors
(43 advisory warnings), and the ARM64/x86_64 release build. The published APK's
signature and ZIP alignment were verified. All ten native scenarios passed on
the Android 13 ARM64 Thor; automatic-save smoke testing passed on the Android 13
x86_64 emulator.

Remaining dedicated Thor checks: live hShop verification; interrupted downloads;
cancellation, low storage, and SD-card saving; controller/rotation/dual-display
coverage; and a full DS download → save → melonDS launch. Emulator and synthetic
checks do not establish game compatibility or full playthrough performance.
