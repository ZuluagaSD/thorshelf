# ThorShelf

An unofficial Android companion for browsing hShop, managing downloads, and preparing CIA files locally for Azahar, with controller shortcuts and an optional second-screen status view for the AYN Thor.

ThorShelf uses hShop's public website inside Android WebView. Search and categories are real hShop pages; the website's normal verification challenge remains interactive. Background downloads, preparation, and file exports use native Android services. No 3hs API credentials or 3hs source code are included.

The interface takes its visual cues from the original 3DS eShop: warm ivory surfaces, orange controls, illustrated game shelves, visible controller focus, and an animated download parcel. Browse opens a native shelf with bundled editorial illustrations; its featured titles open real hShop searches so you can choose the appropriate version and region. Search and category buttons open the live catalog, and **Shelf** returns to the illustrated home without losing the browser session. The shelf adapts to landscape and portrait, and the current page is restored after rotation.

Downloads show real transfer and preparation progress with actions for completed files. The companion display follows the current screen and queue, including paused, completed, and preparation states. Parcel animation respects Android's animation setting and stops when its screen is hidden. Design mockups and artwork prompts are under `design/mockups/eshop-modern/`; shelf illustrations are decorative artwork, not live catalog covers or gameplay screenshots.

## Install

Install `app/build/outputs/apk/debug/app-debug.apk` on an Android 10 or newer ARM64 device (including the Thor) or an x86_64 emulator. Android may ask you to allow installation from the app opening the APK. This is a development build, signed with a local debug key.

1. Open **Browse**, search for a title, and open its hShop page.
2. Complete the site's verification if prompted, tap its download link, and confirm.
3. Follow progress in **Downloads**. Files are stored in `Download/ThorShelf` in shared internal storage.
4. On a completed CIA, choose **Prepare for Azahar**, then select your games folder, such as `ROMs/n3ds`. Base games are saved there with clean names such as `Pokemon Omega Ruby (World).3ds`. Processing runs on Android and keeps the original download intact. Follow progress in Downloads or the notification.
5. Set that folder as Azahar's **Games Folder** once (this is separate from its **User Folder**). When preparation finishes, choose **Open Azahar** and refresh the game list. Base games do not need CIA installation. Updates, DLC, and other title types remain prepared `.cia` files and require **Install CIA** inside Azahar.
6. **Save a copy** remains available to copy the original file unchanged to another folder or SD card.

**Compatibility:** ThorShelf decrypts the CIA container and NCCH contents locally, rebuilds the package, and checks its structure and hashes. For base games, it builds a decrypted `.3ds` cartridge image that Azahar can discover and run, including the game's manual and Download Play partitions when present in the CIA. The original CIA is retained. ThorShelf does not install or emulate games. Updates and DLC need their matching game, and preparation does not make DSiWare, themes, or other unsupported content playable. Unsupported or corrupt inputs and unavailable seeds produce an error instead of a ready file. Existing `.cxi` files prepared by ThorShelf 0.3.0 remain usable and are not changed.

## Local CIA preparation

- No computer or modded 3DS is involved in the preparation step. The native engine is built from the pinned source of [cia3ds-android](https://github.com/Hinoaaaaaf212/cia3ds-android), which wraps Project_CTR's ctrtool and makerom. Its bundled seed database is used locally; ThorShelf does not fetch seeds or keys at runtime.
- One preparation runs at a time in a foreground service. The app stages the source privately, prepares and validates a separate file, then creates a new file inside the folder chosen in Android's picker. Folder access is remembered for later preparations. Existing files and the original download are never overwritten.
- Keep about **five times the input size plus 64 MiB free in internal storage**, plus room for the final saved copy. The conservative check accounts for staging, extracted contents, and rebuild files. Large files can take several minutes and use substantial CPU and storage.
- Validation checks CIA bounds, included content records and original SHA-256 hashes before rebuilding, NCCH encryption flags and protected-region hashes, individual ExeFS file hashes, output content hashes, and preserved title metadata. These checks detect corrupt or incompletely decrypted output; they are not a guarantee that a particular game runs in Azahar and do not verify Nintendo signatures.
- Cancellation is cooperative between native stages. A large extraction or rebuild may take time to stop. Cancellation or failure removes the temporary workspace and attempts to delete the new partial document. If Android kills the process, reopening ThorShelf cleans up the interrupted job; preparation must be started again.
- Base-game export builds a decrypted NCSD cartridge image with the `.3ds` extension. The executable and any included manual or Download Play contents retain their partition indexes from the CIA. Updates, DLC, demos, and other title categories retain CIA output and require manual installation inside Azahar. Prepared outputs are saved in the folder chosen by the user, separate from the download queue.
- License texts are available under **Setup → Open-source licenses** and in `THIRD_PARTY_NOTICES.txt`; source provenance and local patches are documented in `native/README.md`.

## AYN Thor and controllers

- D-pad moves focus; A activates the focused control; B navigates back.
- L/R switches between Browse, Downloads, and Setup; Y focuses search.
- Use touch for hShop's browser verification challenge.
- Landscape mode reduces browser controls to leave more room for the website.
- **Setup → Companion display** shows the current page and queue summary on a secondary display exposed through Android's Presentation API. The app uses the display Android makes available; it does not assume screen IDs or force a top/bottom arrangement. If no compatible second display is available, the main screen works independently.
- Physical button labels depend on the Thor's Android controller mapping.

## Download behavior

- Wi-Fi only is enabled initially and applies to newly queued downloads.
- Android Download Manager owns transfers, notifications, and automatic retries. Downloads can continue after ThorShelf closes. There is no manual pause/resume control.
- New downloads and prepared games use readable filenames: `Pokemon Omega Ruby (World).cia` and `Pokemon Omega Ruby (World).3ds`. Known title IDs, product codes, package tags, base-game package versions, and old random/Azahar suffixes are removed. Region, edition names, and update/DLC labels and versions are retained.
- Repeated downloads receive simple numbered suffixes such as ` (2)`. Names are reserved against the saved queue and visible files; Android Download Manager also refuses to overwrite a destination if another process created it or scoped storage hid it from the app.
- Older download records show clean names in ThorShelf, and **Save a copy** and **Prepare for Azahar** use clean output names. Existing files are not renamed automatically, so download references and existing library entries remain intact. Android’s document provider chooses a unique name when an exported filename already exists, and preparation reports that actual name.
- hShop's standard and UTF-8 encoded download filenames are parsed consistently on every supported Android version, including responses that provide both `filename` and `filename*`. Opaque download addresses are accepted when the response identifies a supported file; HTML/error responses are rejected.
- If a signed link expires or a transfer fails, use **Catalog page** to obtain a fresh download link.
- Cancel removes the transfer and its partial file. Completed files can be managed with Android's file manager.
- File exports use a foreground service and a new document from the system picker. Progress appears in Downloads and, when permitted, a notification. An interrupted export keeps the source download and attempts to remove the partial copy.
- Files and hShop's download servers may require substantial free space. Saving a copy temporarily needs space for both copies.

## Build

To reopen the local Android 13 emulator with ThorShelf installed, run `./scripts/emulator.sh`. The launcher reuses its virtual device and opens a visible window. On this Linux desktop it runs as a temporary user service so closing the launching terminal does not close Android. Close the emulator window to stop it. The emulator and virtual device currently live under `/tmp/thorshelf-toolchain`, which is temporary storage and may be cleared on reboot.

Prerequisites: JDK 17, Android SDK Platform 35, Android SDK Build Tools 35.0.0, NDK 28.2.13676358, and CMake 3.22.1. Set `JAVA_HOME` and `ANDROID_HOME`, or configure `sdk.dir` in an untracked `local.properties` file. Install the native tools with `sdkmanager 'ndk;28.2.13676358' 'cmake;3.22.1'`.

```bash
./scripts/build.sh
```

The script runs unit tests, Android lint, and the debug APK build. It also recognizes the temporary toolchain under `/tmp/thorshelf-toolchain` used during initial development. A standard Android Studio import works, or run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The Gradle wrapper uses Gradle 8.9 with a pinned distribution checksum and Android Gradle Plugin 8.7.3. The app uses Java and Android framework widgets, plus a source-built native library for CIA preparation. JUnit and Robolectric are test-only dependencies. Native source and dependencies are vendored under `native/`; no prebuilt native binaries are downloaded at runtime.

Java source is under `app/src/main/java/dev/thorshelf/app`. URL/filename policy, download persistence, browser UI, companion display, file export, preparation service, native bridge, and CIA validation are separate classes.

## Verification and remaining device checks

Automated checks cover hostile URLs, filename boundaries, search encoding, and the Android behaviors exercised by the tests in `app/src/test`. Lint checks the supported Android API levels. Robolectric is a simulated framework environment; it does not execute a real browser or replace hardware testing.

The eShop-inspired UI was also checked in the Android 13 emulator at 1920 × 1080 landscape and 1080 × 1920 portrait, including 1.3× text size, shoulder-button tab navigation, the live hShop Games page, and the companion on a simulated secondary display. Actual app screenshots are in `design/screenshots/eshop-modern/`. Physical Thor display and controller validation remains a separate device check.

ThorShelf **0.4.1** passes **155 unit/framework tests** and Android lint with **zero errors** (28 advisory warnings). Filename tests cover readable catalog names, old truncated names, update/DLC metadata, Unicode bounds, duplicate queue destinations, and existing file preservation. Folder preparation tests cover `.3ds`/CIA routing, preserved originals, collision protection, interrupted jobs, partial-file cleanup, and folder selection across activity recreation. The shelf regression tests cover live catalog routes, preserved browser sessions, rotation, selected tabs, and controller navigation on Android 10 and 15. Preparation-summary tests preserve error messages and clean legacy successful filenames. Twelve cartridge-validator tests check NCSD layout, partition indices and bounds, exact payload hashes, corruption, and cancellation. The existing CIA/CXI validators and download regressions remain covered, including hShop's combined `filename`/`filename*` header on Android API 29 and 35.

All **ten native scenarios pass on the physical Android 13 ARM64 Thor** using locally generated synthetic files. They cover NCCH encryption, CIA plus NCCH encryption, sparse mixed content, plaintext and encrypted-to-cartridge conversion, rejected title types and existing outputs, corruption, malformed input, and cancellation. Cartridge cases verify real NCSD headers, sparse supplemental partitions, exact payload bytes and IDs, trimmed file size, source preservation, and scratch cleanup. No game files are included in these automated tests. The original six CIA scenarios previously passed on the Android 13 x86_64 emulator; the four new cartridge scenarios have been run on ARM64.

To run the native scenarios on a connected emulator or device:

```bash
./scripts/build.sh assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r dev.thorshelf.app.test/dev.thorshelf.app.NativePreparationInstrumentation
```

The runner reports `passed=10` and `INSTRUMENTATION_CODE: -1` on success; any failed assertion produces a failure result. Both native ABIs compile; the complete current synthetic suite has run on ARM64.

The earlier CIA-only UI flow also passed in the Android 13 emulator: **Prepare for Azahar → notification permission → Android's document picker → foreground preparation → saved CIA**. The exported document's actual plaintext bytes and hashes were checked, along with unchanged original bytes. A small **Synthetic preparation demo (not a game).cia** remains in that emulator's Downloads tab for preview. It is deliberately not playable. To add that fixture to another development emulator, use `adb shell am instrument -w -e seed_demo true dev.thorshelf.app.test/dev.thorshelf.app.NativePreparationInstrumentation`. This opt-in helper exists only in the separate test APK and preserves existing queue entries.

A manual check on 2026-10-26 used the user's downloaded **Professor Layton and the Miracle Mask** (North America, title `00040000000A8500`). ThorShelf prepared the CIA in the Android emulator, and official Azahar 2126.2 installed it successfully. The same prepared file installed in desktop Azahar 2126.2 and reached the title screen and **New Game** menu at approximately 60 FPS. This verifies boot, not a full playthrough. The SDK emulator could not render the game: it aborted on a Vulkan feature query, and its OpenGL ES 3.1 translator lacks the ES 3.2 features Azahar requires. These virtual-device failures do not establish compatibility or performance on physical hardware.

A physical **AYN Thor (Android 13, ARM64)** check on the same date confirmed ThorShelf 0.2.1 prepared the user's downloaded **Pokémon Omega Ruby** (`000400000011C400`). Azahar 2126.2 reported that the prepared title had no encrypted content, installed it successfully, and rendered the game's opening sequence on the Thor. This is a boot check, not a full playthrough or performance benchmark.

The **0.3.0 folder workflow** passed on the same physical Thor: **Prepare for Azahar → choose `ROMs/n3ds` → grant folder access → save CXI → Open Azahar → refresh Applications**. Azahar discovered the new standalone game, displayed its `.cxi` filename and NCCH type in its details, and launched it to the language-selection screen without CIA installation. The exported file was 1,890,299,904 bytes; the original CIA SHA-256 was unchanged. The previously installed copy was preserved, so Azahar lists both copies. This verifies folder discovery and boot, not a full playthrough.

The **0.4.0 `.3ds` workflow** also passed on the same Thor on 2026-10-26. Preparing the original Omega Ruby CIA into `ROMs/n3ds` produced a 1,898,569,728-byte cartridge. After refreshing Applications, Azahar 2126.2 displayed the `.3ds` filename and **NCSD** type, then launched it to the language-selection screen without installation. Every exported partition passed comparison against the verified CIA, the downloaded original retained its SHA-256, and preparation removed its private workspace. The previous CIA installation and CXI were retained. This is a discovery and boot check, not a full playthrough.

**Azahar 2126.2 file-picker workaround:** selecting a Downloads-provider entry can fail with `File ! does not exist!` before the CIA is read. In **Install CIA file**, open the picker menu, choose **AYN Thor / internal storage**, and browse to the prepared `.cia` in the destination folder (for this check, `ROMs/n3ds`). This path worked on the Thor. Refresh **Applications** after installation. The original download shown in Recents is a separate file from the prepared copy.

Before calling this a stable release, verify on the Thor:

1. Complete a real hShop browser challenge, then start a download and confirm Android receives the expected file.
2. Leave the app, interrupt Wi-Fi, and confirm progress/retry/error behavior after reopening it.
3. Test folder preparation with cancellation, low storage, and saving to an SD card. Confirm refresh discovers a `.3ds` file from the SD card and that original files remain unchanged.
4. Confirm A/B and shoulder button mappings, rotation, and both display configurations.
5. Open your installed Azahar build and install a known compatible decrypted CIA using its file picker.

ThorShelf has been launched in a local Android 13 emulator, and the live hShop home page loads in its WebView. The user completed a live download and preparation on the physical Thor, followed by the Azahar installation and boot check above. Browser challenge handling, interrupted-download recovery, SD-card export, and ThorShelf's companion display still require dedicated hardware checks.

## Service constraints

The browser allows hShop pages and numbered `downloadN.erista.me` HTTPS hosts. External HTTPS links require a browser confirmation; other schemes cannot launch apps from web content. File/content access and mixed HTTP content are disabled in WebView. JavaScript, DOM storage, and cookies are enabled for hShop's normal challenge flow. No JavaScript-to-native bridge is exposed.

Catalog availability, challenge behavior, expiring links, and download host changes are controlled by hShop. If verification fails, update Android System WebView or use **Setup → Open catalog in browser**. Downloads initiated in an external browser are managed by that browser and are not added to ThorShelf's queue.

References: [hShop usage](https://hshop.erista.me/wiki/using-hshop), [3hs](https://hshop.erista.me/3hs), [hShop download errors](https://hshop.erista.me/wiki/common-error-codes), [Cloudflare WebView guidance](https://developers.cloudflare.com/turnstile/get-started/mobile-implementation/), [Azahar source and releases](https://github.com/azahar-emu/azahar), [AYN Thor](https://www.ayntec.com/products/ayn-thor).
