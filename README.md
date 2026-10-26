# ThorShelf

An unofficial Android companion for browsing hShop and Nintendo DS games, managing downloads, and preparing CIA files locally for Azahar, with controller shortcuts and an optional second-screen status view for the AYN Thor.

ThorShelf reads hShop's public catalog pages and presents search results, categories, regions, and title details in native Android cards. Each title has a **Download** button that resolves a fresh signed link, checks its response headers, and adds the file to the native queue. If hShop requires verification, a separate WebView dialog shows the normal interactive check and returns to the native flow when it completes. No 3hs API credentials or 3hs source code are included.

The interface takes its visual cues from the original 3DS eShop: warm ivory surfaces, orange controls, illustrated game shelves, visible controller focus, and an animated download parcel. Browse opens a native shelf with category shortcuts and bundled editorial illustrations; its title cards open real hShop searches so you can choose the appropriate version and region. Search and category buttons open native catalog cards with real metadata and pagination; **Details** shows the selected title in the same design. Tapping the active **Browse** tab returns to the illustrated home. Hardware/controller Back follows catalog navigation, and the current page, query, and selected tab are restored after rotation. Cards adapt to landscape and portrait. Both DS and 3DS use the same full-width search bar above the platform, language, and region filters, including on the home shelf. The featured Pokémon banner and the separate Back/Shelf navigation row have been removed to give games more space.

3DS result cards use [GameTDB](https://www.gametdb.com/) covers matched to the catalog's exact product code and region. Artwork loads asynchronously and carries over into Downloads; older queue entries can recover missing artwork from their saved catalog page. Missing covers retain bundled illustrations or initials. GameTDB is credited and linked in Settings.

Downloads use the full width for the transfer list and actions for completed files. Preparation progress, cancellation, and errors appear on the relevant download's card; progress and cancellation are also available in the Android notification. The companion display prominently follows the current platform, submitted search, and filters while retaining active transfer/preparation progress. Previous preparation results appear only on Downloads and are labeled as history. Parcel animation respects Android's animation setting and stops when its screen is hidden. Design mockups and artwork prompts are under `design/mockups/eshop-modern/`; shelf illustrations are decorative artwork, not live catalog covers or gameplay screenshots.

## Install

Install `app/build/outputs/apk/debug/app-debug.apk` on an Android 10 or newer ARM64 device (including the Thor) or an x86_64 emulator. Android may ask you to allow installation from the app opening the APK. This is a development build, signed with a local debug key.

1. Open **Browse**, search for a title, and choose its matching region and content category.
2. Tap **Download** on its card. Complete hShop's verification if prompted; the file then joins your queue automatically. A **Queued ✓** button opens Downloads.
3. Follow progress in **Downloads**. Files are stored in `Download/ThorShelf` in shared internal storage.
4. On a completed CIA, choose **Prepare for Azahar**, then select your games folder once, such as `ROMs/n3ds`. Later preparations reuse it automatically. Use **Settings → Change games folder** to choose a different destination for future preparations. Base games are saved there with clean names such as `Pokemon Omega Ruby (World).3ds`. Processing runs on Android and keeps the original download intact. Follow preparation progress on the download's card or in the notification.
5. Set that folder as Azahar's **Games Folder** once (this is separate from its **User Folder**). When preparation finishes, choose **Open Azahar** and refresh the game list. Base games do not need CIA installation. Updates, DLC, and other title types remain prepared `.cia` files and require **Install CIA** inside Azahar.
6. After that download is successfully prepared, **Delete original** appears beneath **Prepare for Azahar**. Confirming deletes the original download and removes its card; the prepared file in your games folder stays available. The app checks that the prepared copy is still readable before removing the original.

**Compatibility:** ThorShelf decrypts the CIA container and NCCH contents locally, rebuilds the package, and checks its structure and hashes. For base games, it builds a decrypted `.3ds` cartridge image that Azahar can discover and run, including the game's manual and Download Play partitions when present in the CIA. The original CIA is retained. ThorShelf does not install or emulate games. Updates and DLC need their matching game, and preparation does not make DSiWare, themes, or other unsupported content playable. Unsupported or corrupt inputs and unavailable seeds produce an error instead of a ready file. Existing `.cxi` files prepared by ThorShelf 0.3.0 remain usable and are not changed.

## Nintendo DS support

The **Search in: 3DS / DS** filter sits directly below the search bar. Switching platforms keeps your search text and searches the selected catalog automatically. **Browse → Search in → DS** opens the [DSiDrop catalog](https://github.com/0x1a4-dev/DSiDrop). ThorShelf reads the public JSON index directly; no DSiDrop server or companion application is needed. The inspected index contains 7,332 release records, including regional versions and revisions, with 6,967 artwork references.

Normal browsing excludes demos, prototypes, betas, kiosks, known bad dumps, and non-game utilities. The inspected index produces **6,897 editions grouped into 4,911 game cards**. Releases with the same title share a card: **Choose edition** opens their individual versions. Language and region filtering happens before grouping and pagination, so the picker contains only matching editions. A sole matching edition can be downloaded directly. The selected edition's full name and artwork are retained in Downloads.

The **Language** selector below search applies to both platforms and remembers your choice across platform switches, rotation, and restarts. **All languages** includes every title. Choosing a language includes multilingual games that list it and excludes titles with unknown language metadata. Changing language preserves the query and restarts pagination. Language names appear on cards and in details when available.

The **Region** selector sits beside Language and works together with it on both platforms. **All regions** is the default; choices include North America, Europe, Japan, Korea, China, Taiwan, Australia, and World. Europe includes country-specific editions such as France and Germany; North America includes USA and Canada. World/region-free titles are a separate region. Unknown regions remain available under All regions. Region selection persists across platform switches, rotation, and restarts; changing either filter preserves the other selection and search query while resetting pagination. Switching regions within a 3DS regional category returns to its category list so the previous region cannot hide matching editions.

DS languages come first from explicit catalog/filename language lists, then conservative single-country edition tags. An offline supplement adds language lists for 1,354 otherwise unknown editions by matching exact No-Intro edition names through the [libretro database](https://github.com/libretro/libretro-database) to GameTDB; it preserves region and revision tags and never uses fuzzy title matching. USA, Europe, and World alone do not establish a language. The supplement and provenance can be regenerated with [generate_nds_languages.py](scripts/generate_nds_languages.py).

For 3DS, hShop does not supply languages, so filtered results use the public [3dsdb language metadata](https://3dsdb.com/xml.php), matched by exact title ID for regular Games entries and cached in memory for 24 hours. Fan translations and ROM hacks do not inherit the original game's languages. Missing metadata remains available under All languages. Language metadata is fetched separately without hShop cookies, and failures offer a retry rather than displaying false empty results. Filtering skips up to eight empty source pages per request; Next/Previous continue browsing when more pages remain.

The default source is `https://raw.githubusercontent.com/0x1a4-dev/DSiDrop/main/public/data/nds.json`. A complete validated index is cached in memory for 15 minutes. Search, pagination, edition selection, Back, and rotation use native controls. Old saved NDS-Shop detail addresses can still resolve through its paginated API; that compatibility lookup does not add its entries to the new browsing catalog.

Search is accent-insensitive and tolerates punctuation and the word "Version" in Pokémon titles. **Pokémon Black**, **White**, **Black 2**, and **White 2** now find their USA/Europe editions, whose English language metadata is verified through GameTDB. These editions match both North America and Europe filters. Other language editions retain their original names and metadata.

DS cards use the catalog's [libretro box-art references](https://github.com/libretro-thumbnails/Nintendo_-_Nintendo_DS). Images load in the background and share an in-memory cache; DS downloads retain their artwork metadata for Downloads. Missing or unavailable images fall back to a bundled illustration or initials. The illustrated 3DS shelf continues using its existing artwork.

1. Choose **DS**, search or browse, choose an edition if needed, check its language and region, and tap **Download**. ZIP files from the catalog's pinned Internet Archive collection use the Android-managed queue and Wi-Fi setting.
2. When complete, choose **Save DS game**. Android's picker creates a new `.nds` document in your chosen DS games folder, such as `ROMs/nds`. ZIP downloads must contain exactly one `.nds` file; compatible older direct ROM downloads are copied. The original download remains intact.
3. Open the saved file in your DS emulator or add its folder to that emulator's library. DS games do not use **Prepare for Azahar**. ThorShelf does not emulate games or install an emulator.

Existing NDS-Shop and GitHub / Universal-DB queue entries and their ZIP exports remain supported. The integration excludes 7z archives, `.dsi` files, DSiWare, custom database management, and external download hosts outside the supported set. Provider installation scripts and destination paths are never executed or applied. ZIP export checks CRCs and directory completeness, rejects multiple ROMs and unsafe paths, and limits the ROM to 512 MiB, total expanded data to 576 MiB, and archives to 128 entries. Failed exports clean up the newly created destination. The selected provider must report that the destination is empty before export starts.

DS catalog and download metadata requests validate HTTPS hosts and paths on every redirect, reject HTML/error responses and unsupported formats, and never receive hShop cookies or referers. Archive URLs are restricted to the catalog's single collection, and approved mirror redirects must preserve the selected ZIP filename. JSON responses, pagination, records, and fields are bounded. Artwork loads separately with bounded responses and bitmap decoding. Metadata checks happen before Android Download Manager takes ownership of the transfer; subsequent transfer redirects are handled by Android. Archive MD5/SHA1 fields are metadata only; extraction validates ZIP CRCs, not those source hashes.

The DSiDrop update (0.5.0) passed the complete **620-test unit/framework suite**, Android lint with **zero errors** (35 advisory warnings), and the ARM64/x86_64 debug build. A full-index parser check verified grouping and all four English Black/White editions. Live emulator checks confirmed White/White 2 under North America and Europe, English Mario Kart edition selection, and preserved search/filters after Back. Version 0.5.0 is installed on the physical Thor; Black/Black 2 and White/White 2 covers and English labels were verified there, and the companion display followed both searches. Screenshots are under `design/screenshots/curated-ds/`. These live checks fetched metadata and artwork, not game payloads; full DS transfer and physical-device export/playability were not exercised.

The preceding region-filter update passed the complete **554-test unit/framework suite**, followed by **27 focused client/filter checks** after a category-count adjustment, and Android lint with **zero errors** (37 advisory warnings). Region tests cover combined language/region matching, country aliases, unknown and World editions, category navigation, pagination, persisted selections, stale responses, and verification. Live emulator checks confirmed Spanish + Europe results on DS and 3DS, switching 3DS results to North America while preserving Spanish, and adjacent filter controls in landscape and portrait. Screenshots are under `design/screenshots/region-filter/`.

The preceding language-filter update passed the complete **517-test unit/framework suite**, followed by **115 DS and filter-UI checks** after a result-label correction. Android lint reported **zero errors** (35 advisory warnings), and the debug APK built for ARM64 and x86_64. Live Android 13 emulator checks confirmed French Pokémon Black/Black 2 results, their exclusion under English, multilingual Mario Kart DS under English, and a switch to English 3DS Mario Kart results with language metadata. Screenshots are under `design/screenshots/language-filter/`. No game payload was downloaded for these checks.

The preceding NDS-Shop integration and artwork loading passed **461 unit/framework tests**, Android lint with **zero errors** (33 advisory warnings), and the debug APK build for ARM64 and x86_64. The current NDS-Shop API returned **62 DS games** on 2026-10-26; Pokémon Black and White's French `.nds` and box-art response headers returned HTTP 200. English Black/White searches, French-edition labels, cover art, and title details were verified in the Android 13 emulator. No game payload was downloaded for these checks. Full DS transfers, emulator launch/playability, and DS export on the physical Thor remain device checks. Previews are under `design/screenshots/nintendo-ds/`.

## Local CIA preparation

- No computer or modded 3DS is involved in the preparation step. The native engine is built from the pinned source of [cia3ds-android](https://github.com/Hinoaaaaaf212/cia3ds-android), which wraps Project_CTR's ctrtool and makerom. Its bundled seed database is used locally; ThorShelf does not fetch seeds or keys at runtime.
- One preparation runs at a time in a foreground service. The app stages the source privately, prepares and validates a separate file, then creates a new file inside the folder chosen in Android's picker. Folder access is remembered for later preparations, including after restarting the app. If access is revoked or the folder is unavailable, the app asks for a folder again. Existing files and the original download are never overwritten.
- Keep about **five times the input size plus 64 MiB free in internal storage**, plus room for the final saved copy. The conservative check accounts for staging, extracted contents, and rebuild files. Large files can take several minutes and use substantial CPU and storage.
- Validation checks CIA bounds, included content records and original SHA-256 hashes before rebuilding, NCCH encryption flags and protected-region hashes, individual ExeFS file hashes, output content hashes, and preserved title metadata. These checks detect corrupt or incompletely decrypted output; they are not a guarantee that a particular game runs in Azahar and do not verify Nintendo signatures.
- Cancellation is cooperative between native stages. A large extraction or rebuild may take time to stop. Cancellation or failure removes the temporary workspace and attempts to delete the new partial document. If Android kills the process, reopening ThorShelf cleans up the interrupted job; preparation must be started again.
- Base-game export builds a decrypted NCSD cartridge image with the `.3ds` extension. The executable and any included manual or Download Play contents retain their partition indexes from the CIA. Updates, DLC, demos, and other title categories retain CIA output and require manual installation inside Azahar. Prepared outputs are saved in the folder chosen by the user, separate from the download queue.
- License texts are available under **Settings → Open-source licenses** and in `THIRD_PARTY_NOTICES.txt`; source provenance and local patches are documented in `native/README.md`.

## AYN Thor and controllers

- D-pad moves focus; A activates the focused control; B navigates back.
- L/R switches between Browse, Downloads, and Settings; Y focuses search.
- Use touch for hShop's browser verification challenge.
- Landscape uses two columns of catalog cards; portrait keeps a single column.
- Downloads scroll separately from the preparation panel. The panel stays within the available screen, reduces decorative artwork on shorter layouts, and keeps its action buttons visible while long status text scrolls inside it.
- **Settings → Companion display** shows the current page and queue summary on a secondary display exposed through Android's Presentation API. The app uses the display Android makes available; it does not assume screen IDs or force a top/bottom arrangement. If no compatible second display is available, the main screen works independently.
- Physical button labels depend on the Thor's Android controller mapping.

## Download behavior

- Wi-Fi only is enabled initially and applies to newly queued downloads.
- Android Download Manager owns transfers, notifications, and automatic retries. Downloads can continue after ThorShelf closes. There is no manual pause/resume control.
- New downloads and prepared games use readable filenames: `Pokemon Omega Ruby (World).cia` and `Pokemon Omega Ruby (World).3ds`. Known title IDs, product codes, package tags, base-game package versions, and old random/Azahar suffixes are removed. Region, edition names, and update/DLC labels and versions are retained.
- Repeated downloads receive simple numbered suffixes such as ` (2)`. Names are reserved against the saved queue and visible files; Android Download Manager also refuses to overwrite a destination if another process created it or scoped storage hid it from the app.
- Older download records show clean names in ThorShelf, and **Prepare for Azahar** uses clean output names. Existing files are not renamed automatically, so download references and existing library entries remain intact. Android’s document provider chooses a unique name when a prepared filename already exists, and preparation reports that actual name.
- hShop's standard and UTF-8 encoded download filenames are parsed consistently on every supported Android version, including responses that provide both `filename` and `filename*`. Opaque download addresses are accepted when the response identifies a supported file; HTML/error responses are rejected.
- If a signed link expires or a transfer fails, remove the failed entry and find the title in **Browse** to request a fresh download link.
- Cancel removes the transfer and its partial file. Completed CIA cards offer **Prepare for Azahar** and, after successful preparation, **Delete original**. Preparation status is remembered separately for each download across app restarts. Originals cannot be deleted through this action during preparation or when the prepared copy is unavailable. Files prepared by older builds without this per-download record need to be prepared again to enable the button; they can also be managed with Android's file manager.
- Files and hShop's download servers may require substantial free space. Preparation needs space for both the original and prepared copy, plus temporary working files.

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

The Gradle wrapper uses Gradle 8.9 with a pinned distribution checksum and Android Gradle Plugin 8.7.3. The app uses Java and Android framework widgets, jsoup for public catalog HTML parsing, and a source-built native library for CIA preparation. jsoup and Android compatibility-library notices are included in the app. JUnit and Robolectric are test-only dependencies. Native source and dependencies are vendored under `native/`; no prebuilt native binaries are downloaded at runtime.

Java source is under `app/src/main/java/dev/thorshelf/app`. URL/filename policy, download persistence, native catalog UI and verification, companion display, file export, preparation service, native bridge, and CIA validation are separate classes.

## Verification and remaining device checks

Automated checks cover hostile URLs, filename boundaries, search encoding, and the Android behaviors exercised by the tests in `app/src/test`. Lint checks the supported Android API levels. Robolectric is a simulated framework environment; it does not execute a real browser or replace hardware testing.

The eShop-inspired UI was also checked in the Android 13 emulator at 1920 × 1080 landscape and 1080 × 1920 portrait, including 1.3× text size, shoulder-button tab navigation, the live hShop Games page, and the companion on a simulated secondary display. Actual app screenshots are in `design/screenshots/eshop-modern/`. Physical Thor display and controller validation remains a separate device check.

The native catalog and saved-folder workflows pass **251 unit/framework tests** and Android lint with **zero errors** (31 advisory warnings). Filename tests cover readable catalog names, old truncated names, update/DLC metadata, Unicode bounds, duplicate queue destinations, and existing file preservation. Folder preparation tests cover `.3ds`/CIA routing, preserved originals, collision protection, interrupted jobs, partial-file cleanup, and folder selection across activity recreation. Saved-folder UI tests cover reuse after recreation without another prompt, Settings-only selection and replacement, cancellation, revoked or read-only access, unavailable storage, and notification permission continuation. Native catalog regression tests cover real HTML schemas, hostile links, response limits, pagination, native navigation, rotation, selected tabs, controller navigation, duplicate-request protection, stale responses, and verification completion/cancellation on Android 10 and 15. HTTP metadata tests cover redirects, cookies, error responses, and the minimal ranged-request fallback for servers that reject HEAD. Preparation-summary tests preserve error messages and clean legacy successful filenames. Twelve cartridge-validator tests check NCSD layout, partition indices and bounds, exact payload hashes, corruption, and cancellation. The existing CIA/CXI validators and download regressions remain covered, including hShop's combined `filename`/`filename*` header on Android API 29 and 35.

All **ten native scenarios pass on the physical Android 13 ARM64 Thor** using locally generated synthetic files. They cover NCCH encryption, CIA plus NCCH encryption, sparse mixed content, plaintext and encrypted-to-cartridge conversion, rejected title types and existing outputs, corruption, malformed input, and cancellation. Cartridge cases verify real NCSD headers, sparse supplemental partitions, exact payload bytes and IDs, trimmed file size, source preservation, and scratch cleanup. No game files are included in these automated tests. The original six CIA scenarios previously passed on the Android 13 x86_64 emulator; the four new cartridge scenarios have been run on ARM64.

To run the native scenarios on a connected emulator or device:

```bash
./scripts/build.sh assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r dev.thorshelf.app.test/dev.thorshelf.app.NativePreparationInstrumentation
```

The runner reports `passed=10` and `INSTRUMENTATION_CODE: -1` on success; any failed assertion produces a failure result. Both native ABIs compile; the complete current synthetic suite has run on ARM64.

The earlier CIA-only UI flow also passed in the Android 13 emulator: **Prepare for Azahar → notification permission → Android's document picker → foreground preparation → saved CIA**. The exported document's actual plaintext bytes and hashes were checked, along with unchanged original bytes. The preview helper now creates **Synthetic cartridge demo (not a game).cia**, with the executable and supplemental partitions required by the current cartridge preparation pipeline. It is deliberately not playable. To add this fixture to a development emulator, use `adb shell am instrument -w -e seed_demo true dev.thorshelf.app.test/dev.thorshelf.app.NativePreparationInstrumentation`. This opt-in helper exists only in the separate test APK and preserves existing queue entries.

The completed-download actions passed a 297-test full unit/framework run, followed by 65 preparation service/UI checks after the final concurrency changes, plus Android lint with zero errors. These tests cover per-download preparation records across restarts, failure/cancellation, missing or empty prepared copies, preserved prepared files and unrelated downloads during deletion, and keeping status reads responsive during slow provider access. The simplified cards were inspected in the emulator; the final manual preparation/deletion check was interrupted when that emulator disconnected.

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

The native catalog update has been checked in the Android 13 emulator with live search results and title details in portrait and landscape. Public search/title requests and fresh download response headers were verified against hShop without saving a content file. Native catalog screenshots are in `design/screenshots/native-catalog/`. Full file transfers use the existing Android Download Manager queue; the new verification dialog is covered by simulated framework tests but still needs a live challenge check. The user completed a live download and preparation on the physical Thor, followed by the Azahar installation and boot check above. Browser challenge handling, interrupted-download recovery, SD-card export, and ThorShelf's companion display still require dedicated hardware checks.

## Service constraints

Catalog requests are limited to hShop's HTTPS search, category, and numeric title paths. Public HTML is parsed as data without executing scripts; responses, row counts, redirects, and field lengths are bounded. Download links must belong to the requested title and an allowed hShop/download host; metadata requests validate every redirect and reject HTML or unsupported files before enqueueing. Servers that reject HEAD receive a minimal `Range: bytes=0-0` request; the response body is not read or saved by the metadata client. Cookies are not forwarded across hosts.

WebView is created only for an explicit hShop verification requirement. It stays on the requested catalog page, preserves the normal challenge and cookies, and returns public page HTML through an evaluated-script callback. File/content access, mixed HTTP content, and popup windows are disabled; no JavaScript-to-native interface is exposed. Changing search or leaving the catalog ignores stale responses; signed download links are fetched again for each new request.

Catalog availability, HTML structure, challenge behavior, expiring links, and download host changes are controlled by hShop. Unrecognized page layouts show a retry state instead of invented results. Search and category data are cached only in memory; downloads always request a fresh link. If verification fails, update Android System WebView or use **Settings → Open catalog in browser**. Downloads initiated in an external browser are managed by that browser and are not added to ThorShelf's queue.

References: [hShop usage](https://hshop.erista.me/wiki/using-hshop), [3hs](https://hshop.erista.me/3hs), [hShop download errors](https://hshop.erista.me/wiki/common-error-codes), [Cloudflare WebView guidance](https://developers.cloudflare.com/turnstile/get-started/mobile-implementation/), [Azahar source and releases](https://github.com/azahar-emu/azahar), [AYN Thor](https://www.ayntec.com/products/ayn-thor).
