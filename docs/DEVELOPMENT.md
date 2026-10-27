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
