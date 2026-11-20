# EmuSync Android Sync Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android background sync agent. It finds the owner's emulators through Shizuku, uploads saves,
states and config bundles to the EmuSync server as soon as they change, and applies deliveries safely. A
temporary debug screen handles setup and testing.

**Architecture:**
- **Two processes:**
  - **Shizuku UserService:** a privileged helper running as the shell uid. It does only file I/O, inotify
    watching and process probing, exposed through one AIDL interface.
  - **App process:** everything else, in a `specialUse` foreground service. That means the per-emulator modules
    (where the files are, how games are identified), the sync engine (manifests, upload rules, delivery rules),
    and the HTTP/SSE client.
- **Testability:** all logic above the AIDL boundary is written against an `Fs` interface. JVM unit tests run it
  on temp directories with fixtures copied from the owner's real device layout.

**Tech Stack:** Kotlin, AGP 9.5.1, Gradle 9.9.2, JDK 21 (`/usr/lib/jvm/java-21-openjdk`), compileSdk 36,
targetSdk 35, minSdk 33, Shizuku api/provider 13.1.5, OkHttp 5.5.1 + okhttp-sse 5.5.1 (+ mockwebserver3 5.5.1
for tests), kotlinx-serialization-json 1.13.0, JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-11-19-emulator-save-config-sync-design.md` (§6, §7, §9, §11 test results).
**Research:** `docs/superpowers/research/2026-11-19-android-toolchain.md`.
**Server API:** `docs/superpowers/plans/2026-11-19-server.md`, Tasks 3–11. Copy the request/response shapes from
there. On the wire, JSON is camelCase and **enums are strings** with their declared names (`"Save"`,
`"Conflict"`).

## Global Constraints

- **Location:** the project lives in `android/` inside the EmuSync repo, worked on branch `feat/android` in the
  worktree `~/Development/EmuSync-android`.
- **App ID:** application ID and package `dev.emusync.app`.
- **UI:** no product UI. The only UI is `DebugActivity` (Task 13). The real screens wait for owner-reviewed
  mockups (spec §14).
- **No root.** All access to other apps' files goes through Shizuku's UserService. The app never asks for
  `MANAGE_EXTERNAL_STORAGE`.
- **Never write while running:** never write into an emulator's files while that emulator's process is alive
  (`pidof <package>` non-empty). Eden keeps its process alive after leaving a game (spec §11), so "alive" means
  "do not write".
- **Never overwrite unsynced data:**
  - Never overwrite or delete a local file unless the local item's current manifest hash equals the manifest
    hash of the version the device last synced. If not, upload first and ack `SkippedLocalChanged`.
  - Before every overwrite, copy the current files to
    `/sdcard/Android/data/dev.emusync.app/files/backups/<emulatorId>/<itemKey-safe>/<serverTimeMillis>/`.
    Keep the newest 5 per item.
- **Uploads:**
  - Debounce: an item uploads after its files have been unchanged for 3 s (`SyncConfig.quietMillis = 3000`).
  - Multi-file items upload as one manifest.
  - Config bundles upload when the emulator's process exits.
- **Manifests** match the server:
  - entries `(path, hash, size)`
  - `path` relative, `/`-separated, no `..`
  - `hash` lowercase hex SHA-256
  - sorted by path (ordinal)
  - manifest hash = SHA-256 of the concatenated `path\0hash\0size\n` lines
- **Writes are atomic per file:** write `<name>.emusync-tmp`, fsync, rename over the target. Files present
  locally but absent from the incoming manifest are deleted after the backup.
- **Device roots** reported to the server:
  - `{INTERNAL}` = `/storage/emulated/0`
  - `{SD}` = the first removable volume under `/storage/XXXX-XXXX`, if any
  - `{EMU:<id>}` = that emulator's data root
- **Protocol header** `EmuSync-Protocol: 1` on every request. Auth is `Authorization: Bearer <deviceKey>`.
- **Upload size limit:** check blob size before PUT. Over the server limit, skip the item and log it. A chunked
  over-limit upload gets a connection reset, not a JSON error (server Task 5).

## Review Focus

1. **The emulator is mid-write when the debounce fires** (a 2 MB Eden save takes ~3 s of modify events) → the
   upload only happens after 3 s of quiet, and the manifest is re-checked right before upload. If any file
   changed in between, wait again. (Task 10)
2. **A delivery arrives while the emulator process is alive**, including Eden idling in the background → nothing
   is written, a notification says "close <emulator> to receive the newer save", and the write happens when the
   process exits. (Task 11)
3. **A delivery for an item whose local files changed since the last sync** → no overwrite: upload first, ack
   `SkippedLocalChanged`. (Task 11)
4. **Shizuku dies mid-sync** (binder dead, e.g. after a reboot) → every `Fs` call fails with
   `FsUnavailableException`, the engine stops cleanly without partial writes being acked, the status reports
   `shizukuOk=false`, and work resumes on rebind. (Tasks 3, 12)
5. **Different profile UUIDs on two devices** (Eden: Thor `23E468EE…`, Odin `81C6300A…`) → saves are keyed by
   title ID only and written into **this** device's profile folder. (Task 5)

---

## File Structure

```
android/
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/wrapper/*  gradlew
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/aidl/dev/emusync/app/shell/IShellService.aidl  IWatchListener.aidl
  app/src/main/java/dev/emusync/app/
    shell/      ShellService.kt (UserService impl)  ShellFsCore.kt (pure java.io logic)  Watcher.kt  ProcessProbe.kt
    fs/         Fs.kt (interface + FileEntry)  RemoteFs.kt (AIDL client)  LocalFs.kt (java.io, used in tests)  ShizukuGate.kt
    model/      Manifest.kt  Protocol.kt (DTOs)  ItemKey.kt
    api/        ApiClient.kt  EventStream.kt
    modules/    EmulatorModule.kt  ModuleRegistry.kt  ContentUris.kt
                eden/EdenModule.kt  dolphin/DolphinModule.kt dolphin/DiscId.kt  retroarch/RetroArchModule.kt retroarch/RaCfg.kt
                melonds/MelonDsModule.kt  azahar/AzaharModule.kt  ppsspp/PpssppModule.kt  vita3k/Vita3kModule.kt
    sync/       StateStore.kt  SyncConfig.kt  SyncEngine.kt  DeliveryApplier.kt  LocalBackups.kt
    service/    SyncService.kt  BootReceiver.kt  Notifier.kt
    debug/      DebugActivity.kt
  app/src/test/java/dev/emusync/app/...   (JVM tests; fixtures in app/src/test/resources/fixtures/)
```

## Shared interfaces (every task uses these exact names)

```kotlin
// fs/Fs.kt
data class FileEntry(val path: String, val isDir: Boolean, val size: Long, val mtimeMillis: Long)
class FsUnavailableException(msg: String) : Exception(msg)
interface Fs {
  fun list(dir: String): List<FileEntry>              // non-recursive; empty if missing
  fun walk(dir: String): List<FileEntry>              // recursive files only
  fun stat(path: String): FileEntry?
  fun exists(path: String): Boolean
  fun openRead(path: String): java.io.InputStream
  fun writeAtomic(path: String, data: java.io.InputStream)   // tmp + fsync + rename, mkdirs parents
  fun delete(path: String): Boolean
  fun copy(src: String, dst: String)
  fun isProcessAlive(pkg: String): Boolean
  fun installedPackages(): Set<String>
}
// model/Manifest.kt
data class ManifestEntry(val path: String, val hash: String, val size: Long)
object Manifests { fun build(fs: Fs, baseDir: String, files: List<String>): List<ManifestEntry>; fun hash(m: List<ManifestEntry>): String }
// modules/EmulatorModule.kt
data class Detected(val emulatorId: String, val pkg: String, val version: String, val dataRoot: String, val extraRoots: Map<String,String> = emptyMap())
data class LocalItem(val kind: ItemKind, val gameKey: String, val slot: String, val core: String,
                     val baseDir: String, val files: List<String>, val displayName: String? = null)  // files relative to baseDir
data class ConfigBundleFiles(val files: Map<String, String>)   // bundle path ("data/retroarch.cfg") -> absolute path
interface EmulatorModule {
  val emulatorId: String
  val packages: List<String>                                     // preferred order
  fun detect(fs: Fs, settings: DeviceSettings): Detected?
  fun saveItems(fs: Fs, d: Detected): List<LocalItem>
  fun watchDirs(fs: Fs, d: Detected): List<String>
  fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings): List<InstalledGame>
  fun configBundle(fs: Fs, d: Detected): ConfigBundleFiles
  fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String): String  // where a delivered item is written
  fun cores(fs: Fs, d: Detected): List<String>? = null
}
data class DeviceSettings(val romDirs: List<String>, val overrides: Map<String,String>)  // overrides e.g. "azahar.root", "eden.package"
```

`ItemKind`, `InstalledGame`, `ManifestEntry`, `SaveUploadStatus`, `DeliveryKind`, `DeliveryResult`, and the
request/response DTOs mirror the server (server plan Tasks 6–8, 11) in `model/Protocol.kt`.

---

### Task 1: Project scaffold and build

**Files:** Create `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`
(`org.gradle.java.home=/usr/lib/jvm/java-21-openjdk`), the Gradle wrapper (9.9.2), `android/app/build.gradle.kts`,
`AndroidManifest.xml`, a placeholder `debug/DebugActivity.kt`, `app/src/test/.../SmokeTest.kt`, and
`android/.gitignore`.

- **Manifest:**
  - `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`,
    `RECEIVE_BOOT_COMPLETED`
  - the ShizukuProvider exactly as in the research doc
  - `SyncService` declared with `foregroundServiceType="specialUse"` and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
    property "Emulator save file sync"
  - `BootReceiver`
  - `<queries>` for the emulator packages in Task 5–9 module lists
- **Build:** `buildFeatures { aidl = true; buildConfig = true }`, and the `kotlin("plugin.serialization")` plugin.

- [ ] **Step 1:** Create the worktree:
  `git -C ~/Development/EmuSync worktree add ../EmuSync-android -b feat/android main`. Then
  cherry-pick or merge nothing else; this plan needs only docs.
- [ ] **Step 2:** Write `SmokeTest.kt`: `assertEquals(4, 2 + 2)`. This proves the JVM test task runs.
- [ ] **Step 3:** Run `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, with
  the APK at `app/build/outputs/apk/debug/app-debug.apk`. If a version doesn't resolve, use the nearest
  available and record it.
- [ ] **Step 4:** Commit `feat(android): project scaffold`.

### Task 2: Protocol DTOs and API client

**Files:** Create `model/Protocol.kt`, `api/ApiClient.kt`, `api/EventStream.kt`. Test `api/ApiClientTest.kt`
(mockwebserver3).

**Interfaces:**
- `class ApiClient(baseUrl: String, deviceKey: String?, http: OkHttpClient = default)` with suspend functions:
  - `register(email, password, deviceName)`, `login(...)`, `redeemPairing(code, deviceName)`: each returns
    `DeviceKeyResponse`, with platform `"android"`
  - `putStatus(DeviceStatusRequest)`, `putInstalls(emulatorId, List<InstalledGame>)`
  - `missingBlobs(hashes)`, `putBlob(hash, size, source: () -> InputStream)` (streamed request body),
    `getBlob(hash): InputStream`
  - `uploadSave(SaveUploadRequest): SaveUploadResponse`, `uploadConfig(ConfigUploadRequest): ConfigUploadResponse`
  - `deliveries(): List<DeliveryDto>`, `ack(id, AckRequest)`
  - Non-2xx responses throw `ApiException(status, code, message)`, parsed from `{"code", "message"}`.
- `class EventStream(client, baseUrl, deviceKey, onDeliveries: () -> Unit, onState: (Boolean) -> Unit)`: SSE on
  `/v1/events`, `readTimeout(0)`, reconnects with backoff of 1, 2, 5, 10, 30 s.
- JSON: `Json { ignoreUnknownKeys = true; explicitNulls = false }`. Enums are serialized by name.

- [ ] **Step 1: Write failing tests:**
  - `sends_protocol_header_and_bearer`
  - `parses_error_body_into_ApiException`
  - `uploadSave_serializes_enum_as_string` (the request body contains `"kind":"Save"`)
  - `putBlob_streams_body_with_octet_stream`
  - `event_stream_calls_onDeliveries_for_deliveries_event`
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(android): protocol DTOs and API client`.

### Task 3: Shizuku shell service and RemoteFs

**Files:** Create the AIDL files (`IShellService`, `IWatchListener`), `shell/ShellFsCore.kt`, `shell/ShellService.kt`,
`shell/Watcher.kt`, `shell/ProcessProbe.kt`, `fs/Fs.kt`, `fs/LocalFs.kt`, `fs/RemoteFs.kt`, `fs/ShizukuGate.kt`.
Test `shell/ShellFsCoreTest.kt`, `fs/LocalFsTest.kt`.

**Interfaces:**
- `IShellService.aidl`:
  - `List<String> listJson(String dir)`, with JSON-encoded `FileEntry` lists (chunk ≤ 2000 entries per call;
    `walk` pages via an offset arg)
  - `String statJson(String path)`
  - `ParcelFileDescriptor openRead(String path)`
  - `void writeAtomic(String path, in ParcelFileDescriptor src)`
  - `boolean delete(String path)`
  - `void copy(String src, String dst)`
  - `boolean isProcessAlive(String pkg)`
  - `String installedPackagesCsv()`
  - `void watch(in String[] dirs, IWatchListener l)`, `void unwatchAll()`
  - `void destroy() = 16777114;`
- `IWatchListener.aidl`: `oneway void onChanged(String dir, String name)`.
- **`ShellFsCore`** holds all java.io logic, so it's testable on the JVM. `writeAtomic` writes
  `<path>.emusync-tmp`, calls `FileOutputStream.fd.sync()`, then `renameTo`. If `renameTo` fails, delete the
  target first and retry once.
- **`Watcher`:**
  - Recursive: one `FileObserver(File, MODIFY|CLOSE_WRITE|CREATE|MOVED_TO|DELETE)` per directory, plus a new
    observer for each created subdirectory.
  - Holds strong references to its observers.
  - Forwards events to the listener without debouncing; the app debounces.
- **`ProcessProbe`:**
  - `isProcessAlive` runs `pidof <pkg>` via `ProcessBuilder` and returns true if the output is non-empty.
  - `installedPackages` runs `pm list packages`.
- **`ShizukuGate`:** `state: StateFlow<ShizukuState>` (`NotRunning | NoPermission | Ready`),
  `requestPermission()`, `bind(): IShellService` (UserServiceArgs as in the research doc,
  `version = BuildConfig.VERSION_CODE`), and rebinding on binder death.
- **`RemoteFs`** implements `Fs` over `IShellService`. Every `RemoteException` or `DeadObjectException`, and any
  call while not Ready, becomes `FsUnavailableException`.
- **`LocalFs`** implements `Fs` over `ShellFsCore` directly, for tests and the JVM.
  `isProcessAlive` uses a settable fake set.

- [ ] **Step 1: Write failing tests:**
  - `writeAtomic_replaces_file_and_leaves_no_tmp`
  - `writeAtomic_creates_parent_dirs`
  - `walk_returns_files_recursively_with_relative_paths_sorted`
  - `list_missing_dir_is_empty`
  - `delete_and_copy_work`
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS. Also run `assembleDebug`.
- [ ] **Step 5:** Commit `feat(android): shizuku shell service and file access`.

### Task 4: Manifests and local state store

**Files:** Create `model/Manifest.kt`, `model/ItemKey.kt`, `sync/StateStore.kt`, `sync/SyncConfig.kt`.
Test `model/ManifestTest.kt`, `sync/StateStoreTest.kt`.

**Interfaces:**
- `Manifests.build` streams each file through SHA-256 (64 KiB buffer).
- `Manifests.hash` follows the global-constraint formula.
- `ItemKey.of(emulatorId, kind, gameKey, slot, core)` gives the string
  `"$emulatorId|$kind|$gameKey|$slot|$core"`. `ItemKey.safe(key)` maps it to a filesystem-safe name: SHA-1 hex
  of the key, first 16 chars.
- `StateStore(file: java.io.File)`:
  - `get(itemKey): ItemState?` and `put(itemKey, ItemState)` with
    `ItemState(knownVersionId: String?, knownManifestHash: String?, lastUploadedHash: String?)`
  - `configState(emulatorId)` and `putConfigState(...)`, with the same shape plus `bundleId`
  - `deviceKey`, `serverUrl`, `deviceId`
  - Persisted as JSON with an atomic file replace.
- `SyncConfig`: `quietMillis = 3000`, `pollMillis = 60000`, `backupsPerItem = 5`.

- [ ] **Step 1: Write failing tests:**
  - `manifest_hash_matches_server_formula`: a fixed vector. Entries `[("a.sav","<sha of 'x'>",1)]` → the expected
    hex is computed in the test the same way the server does.
  - `manifest_sorted_ordinal`
  - `state_store_roundtrips_and_survives_reload`
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(android): manifests and local sync state`.

### Task 5: Module framework, content URIs, and the Eden module

**Files:** Create `modules/EmulatorModule.kt`, `modules/ModuleRegistry.kt`, `modules/ContentUris.kt`,
`modules/eden/EdenModule.kt`. Test `modules/ContentUrisTest.kt`, `modules/eden/EdenModuleTest.kt`, with fixtures
under `fixtures/eden/`.

**Fixtures:**
- Extract `config.ini` from `~/Development/EmuSync-device-backups/2026-11-19/thor.tar` and `odin3.tar`, and build
  small fake save trees matching their layouts.
- Strip nothing secret; these are config files only. Never copy `keys/`.

**Rules:**
- **`ContentUris.toPath(uri, roots)`:** `content://com.android.externalstorage.documents/tree/primary%3AA%2FB` →
  `/storage/emulated/0/A/B`; `tree/XXXX-XXXX%3AA` → `/storage/XXXX-XXXX/A`; anything else → null. URL-decode the
  value.
- **Eden packages:** `dev.eden.eden_emulator`, `dev.eden.eden_emulator.combo`, `dev.igawa6.edenduo`. The active
  one is `settings.overrides["eden.package"]`, else the first installed in that order.
  - Data root: `/sdcard/Android/data/<pkg>/files`.
- **Save root:**
  - `config.ini [Paths]` `save_directory` if non-empty, else `nand_directory`, else `<root>/nand`, plus
    `/user/save`.
  - Look under `0000000000000000/<profile>/`. The **active profile** is the profile dir with the newest mtime
    of any file inside.
- **Items:** each dir `0000000000000000/<profile>/<16-hex TitleID>/` becomes `LocalItem(Save, gameKey=TitleID
  upper-cased, slot="", core="", baseDir=<that dir>, files=all files under it)`. Only the active profile's.
- **`targetDirFor(Save, titleId)`:** `<saveRoot>/0000000000000000/<activeProfile>/<titleId>`. If no profile dir
  exists yet, use the profile dirs' parent and a profile name from `nand/system/save/8000000000000010`. If none
  can be found, throw `ModuleException("no Eden profile yet; open Eden once")`.
- **Installed games** (union, deduplicated):
  - TitleIDs found as `[0100XXXXXXXXXXXX]` in file names under the `gamedirs` paths (via `ContentUris`) and
    under `settings.romDirs`
  - TitleIDs with a save dir
  - For `.nsp` files without a TitleID in the name: parse the PFS0 header (magic `PFS0`, u32 file count, u32
    string-table size, entries of 0x18 bytes, then the string table). Take the first 16 hex chars of any
    `*.tik` file name, with the last 3 hex digits forced to `000` (base title).
- **`watchDirs`:** the active profile dir and the save root.
- **`configBundle`:** `data/config/config.ini`, `data/config/custom/*.ini`, `data/config/input/*.ini` mapped from
  `<root>/config/...`.

- [ ] **Step 1: Write failing tests** (fixtures via `LocalFs` on a temp copy):
  - `content_uri_primary_and_sd_map_to_paths`
  - `eden_uses_save_directory_when_set` (Thor fixture → `/…/Games/switch-saves/user/save`)
  - `eden_falls_back_to_nand_when_save_directory_empty` (Odin fixture)
  - `eden_items_keyed_by_titleid_only_active_profile`
  - `eden_target_dir_uses_this_devices_profile`: Odin profile `81C6300ADAB1B1F0B230A03591DF9110` with Thor's
    title → path under the Odin profile
  - `eden_installed_from_filenames_and_saves`
  - `eden_nsp_pfs0_ticket_titleid` (a synthetic PFS0 with a `.tik` entry)
  - `eden_config_bundle_lists_ini_files`
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(android): module framework and Eden`.

### Task 6: Dolphin module

**Files:** Create `modules/dolphin/DolphinModule.kt`, `modules/dolphin/DiscId.kt`. Test `DolphinModuleTest.kt`,
`DiscIdTest.kt`.

**Rules:**
- **Package and root:** `org.dolphinemu.dolphinemu`, root `/sdcard/Android/data/<pkg>/files`.
- **GameCube saves:**
  - Each `.gci` under `GC/<USA|EUR|JAP>/Card A/` is one item.
  - gameKey = the first 6 bytes of the GCI file (game code + maker) as ASCII, e.g. `GM8E01`. Slot = the GCI's
    filename field: bytes 0x08..0x28, trimmed of NULs.
  - baseDir = the card folder, files = `[that .gci name]`.
  - Target: same region folder; the name is the incoming file's name.
- **Wii saves:** `Wii/title/00010000/<8-hex>/data/` → gameKey = the 8 hex digits decoded as 4 ASCII characters
  plus `"W"`. This needs owner-device verification; a fixture is enough for now. files = everything under
  `data/`.
- **States:** `StateSaves/<ID>.sNN` → `State`, slot `NN`, core `""`.
- **Installed games:** ROM files under `Config/Dolphin.ini [General] ISOPath0..N` (content URIs via
  `ContentUris`) and `settings.romDirs`, with the game ID read by `DiscId.read(fs, path)`:
  - `.iso`/`.gcm`: bytes 0..6
  - `.ciso`: bytes 0x8000..0x8006
  - `.wbfs`: 0x200..0x206
  - `.rvz`/`.wia`: `null` for now; record a TODO and fall back to a filename heuristic, `\(([A-Z0-9]{6})\)` or
    none
  - Plus every gameKey that has a GCI.
- **Config bundle:** `data/Config/*.ini`, `data/GameSettings/*.ini`.
- **Fixtures:** real GCI headers. Copy the first 0x40 bytes of the Odin `01-GM8E-MetroidPrime A.gci` from the
  backup tar, padded.

- [ ] **Steps:** failing tests `gci_gamekey_and_slot_from_header`, `wii_title_dir_item`, `state_slot_parsed`,
  `disc_id_iso_ciso_wbfs`, `config_bundle_files`; FAIL; implement; PASS. Commit `feat(android): Dolphin module`.

### Task 7: RetroArch module

**Files:** Create `modules/retroarch/RaCfg.kt`, `modules/retroarch/RetroArchModule.kt`. Tests and fixtures:
the Odin `retroarch.cfg` from the backup tar, plus a playlist.

**Rules:**
- **Packages:** `com.retroarch.aarch64`, `com.retroarch`, `com.retroarch.ra32`. The cfg is
  `/sdcard/Android/data/<pkg>/files/retroarch.cfg`. **If it's missing, `detect` returns `Detected` with
  `extraRoots["cfgMissing"]="1"`.** The debug UI shows "RetroArch config not found externally" (spec §11
  open item).
- **`RaCfg`:** parses `key = "value"` lines. `"default"`, an empty value, or a missing key means default.
  `:` prefixed values are relative to the RetroArch root.
- **RetroArch root:** the first existing of `/sdcard/RetroArch`, `/sdcard/Android/media/<pkg>/RetroArch`,
  `/sdcard/Android/data/<pkg>/files/RetroArch`.
- **Default directories:** saves = `<root>/saves`, states = `<root>/states`, config = `<root>/config`,
  playlists = `<root>/playlists`.
- **Save items:**
  - Every file in the save dir (recursive) with extension `.srm`, `.sav`, `.rtc`, `.mcr`, `.eep`, `.fla`,
    `.dsv`, `.bin`.
  - gameKey = `"retroarch/" + basename-without-ext`, core `""`, slot = the extension (so `.srm` and `.rtc` are
    separate items).
  - Target: with `sort_savefiles_enable=true`, the incoming core folder if known, else the save dir root. If
    this device sorts by core, place it into the folder of the core that the playlist maps to that game, else
    the root.
- **State items:**
  - `*.state`, `*.state1..9`, `*.state.auto` in the states dir (recursive).
  - gameKey as for saves, slot = the suffix (`""`, `1`, `auto`, …).
  - core = the parent folder name if `sort_savestates_enable` and the file is in a subfolder, else `""`.
- **Installed games:** basenames of `path` entries in every `<playlists>/*.lpl` (JSON `items[].path`), plus
  ROM-dir files with ROM extensions, as `retroarch/<basename>`.
- **Cores:** distinct basenames of `core_path` in playlists, without `_android.so`.
- **Config bundle:** `data/retroarch.cfg`, plus `root/config/**` (overrides, remaps, core options).

- [ ] **Steps:** failing tests `cfg_default_dirs_resolve_to_root`, `cfg_explicit_dirs_used`, `srm_and_rtc_are_separate_items`,
  `states_core_from_sorted_folder`, `installed_from_playlists`, `cores_from_playlists`, `cfg_missing_flagged`; FAIL;
  implement; PASS. Commit `feat(android): RetroArch module`.

### Task 8: melonDS module

**Files:** Create `modules/melonds/MelonDsModule.kt`. Test it.

**Rules:**
- **Package:** `me.magnum.melonds`.
- **ROM dirs:** `settings.romDirs` entries ending in `nds` (case-insensitive) plus auto-detect
  `/sdcard/Games/nds` and `/storage/*-*/Games/nds`.
- **Saves:**
  - `<rom basename>.sav` next to each ROM → `Save`, gameKey `"nds/<basename>"`.
  - Also the fallback dir `Android/data/me.magnum.melonds/files/saves`.
- **States:** `<basename>.ml0`…`.ml8` next to the ROM or in `Android/data/<pkg>/files/savestates` → `State`,
  slot `0`–`8`.
- **Installed games:** `.nds`, `.dsi`, `.zip`, `.7z` in the ROM dirs (basename).
- **Target:** next to that device's ROM with the same basename. If the ROM isn't present, the item isn't
  installed, so no delivery is ever made for it.
- **Config bundle:** files in the export folder `/sdcard/EmuSync/melonds-export/` (the user exports melonDS
  settings there; spec §7). Bundle paths `export/<name>`. Applying writes into the same folder, and the user
  imports in melonDS. The debug UI says so.

- [ ] **Steps:** failing tests `sav_next_to_rom_is_item`, `states_ml_slots`, `installed_from_rom_dirs_including_sd`,
  `export_folder_is_config_bundle`; FAIL; implement; PASS. Commit `feat(android): melonDS module`.

### Task 9: Azahar, PPSSPP and Vita3K modules

**Files:** Create `modules/azahar/AzaharModule.kt`, `modules/ppsspp/PpssppModule.kt`, `modules/vita3k/Vita3kModule.kt`.
Test each.

**Rules:**
- **Azahar:**
  - Packages `org.azahar_emu.azahar`, `io.github.lime3ds.android`.
  - Root: `settings.overrides["azahar.root"]`, else search `/sdcard` to depth 4 for a dir containing both
    `config/config.ini` and `sdmc/`. The owner's Thor has `/sdcard/Games/3ds/user`.
  - Saves: `sdmc/Nintendo 3DS/<id0>/<id1>/title/00040000/<low8>/data/**` → gameKey `00040000<LOW8>` (upper-cased).
    Use the single id0/id1 pair; if several exist, use the most recently modified.
  - States: `states/*` files named `<titleid>.<slot>.*`, with the slot parsed from the name.
  - Installed: title dirs under `sdmc/.../title/00040000/*` with `content/` present, plus `.3ds`/`.cci`/`.cxi`
    ROMs whose program ID is the u64 LE at 0x118 of the NCCH header. NCSD (`.3ds`/`.cci`) has the first
    partition's NCCH at offset 0x4000 (verify against the azahar source at
    `<scratchpad>/src/azahar`,
    and correct if wrong). `.cia`: leave parsing to a TODO, and count it as installed only if the title dir
    exists.
  - Config bundle: `root/config/config.ini`, `root/config/custom/*.ini`.
- **PPSSPP:**
  - Packages `org.ppsspp.ppsspp`, `org.ppsspp.ppssppgold`.
  - Memstick: `settings.overrides["ppsspp.memstick"]`, else search `/sdcard` and `/storage/*-*` to depth 4 for
    `PSP/SYSTEM/ppsspp.ini`, else `/sdcard/Android/data/<pkg>/files`.
  - Saves: each dir in `PSP/SAVEDATA/` → gameKey = the first 9 chars, slot = the rest of the dir name.
  - States: `PSP/PPSSPP_STATE/<ID>_<ver>_<N>.ppst` → slot `N`, core `""`.
  - Installed: save IDs plus ROM file names matching `[A-Z]{4}\d{5}`.
  - Config bundle: `PSP/SYSTEM/ppsspp.ini`, `controls.ini`, `*_ppsspp.ini`, mapped as `memstick/PSP/SYSTEM/...`.
- **Vita3K:**
  - Package `org.vita3k.emulator`, root `/sdcard/Android/data/<pkg>/files`.
  - The Vita fs is `pref-path` from `config.yml`, else `<root>/vita`.
  - Saves: `ux0/user/00/savedata/<TITLEID>/**` → gameKey TITLEID.
  - Installed: `ux0/app/*` dir names.
  - Config bundle: `data/config.yml`, `data/config/*.xml`.

- [ ] **Steps:** failing tests per module: `azahar_root_detected_by_markers`, `azahar_save_titleid`, `ncsd_program_id`,
  `ppsspp_memstick_search_and_save_slots`, `vita3k_savedata_and_apps`, plus one config-bundle test each; FAIL;
  implement; PASS. Commit `feat(android): Azahar, PPSSPP and Vita3K modules`.

### Task 10: Sync engine (scanning, debounced upload, status and installs)

**Files:** Create `sync/SyncEngine.kt`. Test `sync/SyncEngineTest.kt`, with a fake `Api` interface extracted
from ApiClient (`interface Api` with the same methods; ApiClient implements it) and `LocalFs`.

**Interfaces:**
- `class SyncEngine(fs: Fs, api: Api, store: StateStore, registry: ModuleRegistry, settings: DeviceSettings, clock: () -> Long, log: (String) -> Unit)`:
  - `suspend fun fullScan()`: detect modules, report status, report installs, then upload every changed item.
  - `fun onFsEvent(dir: String)`: marks the items under that dir dirty, with `dirtySince = clock()`.
  - `suspend fun tick()`: uploads dirty items quiet for at least `quietMillis`.
  - `suspend fun onEmulatorExited(emulatorId)`: config bundle upload and a rescan of that module.
- **Status:**
  - `putStatus` with platform `"android"`, the app version, `shizukuOk`, emulators (active package + versionName
    via `pm`, cores for retroarch), and roots.
  - Roots are `{INTERNAL}`, `{SD}` if a `/storage/XXXX-XXXX` exists, and `{EMU:<id>}` = `Detected.dataRoot`.
- **Upload of one item:**
  1. Build the manifest.
  2. Skip if its hash equals `knownManifestHash` (nothing new).
  3. Wait for quiet: re-stat the files and rebuild if any mtime or size changed since the build.
  4. `missingBlobs`, then `putBlob` for each missing one.
  5. `uploadSave` with `baseVersionId = knownVersionId`.
  6. The response:
     - `Accepted`/`Unchanged`: store `knownVersionId = response.versionId` (Accepted) or `headVersionId`
       (Unchanged), and `knownManifestHash` = this hash.
     - `Conflict`: store nothing new, log it, and post a notification through the `onConflict` callback.
- **Config upload:** the same steps through `uploadConfig`, with the bundle's file map, keyed per emulator.
- **Failure handling:** `FsUnavailableException` aborts the current pass with nothing stored. `ApiException`
  for one item is logged, and the loop continues.

- [ ] **Step 1: Write failing tests:**
  - `first_scan_uploads_items_with_null_base`
  - `unchanged_item_not_reuploaded`
  - `event_then_quiet_period_uploads_once`: three events 1 s apart, `tick()` at +2 s does nothing, at +3.1 s after
    the last event uploads once
  - `file_changing_during_upload_defers`
  - `accepted_stores_known_version`
  - `conflict_does_not_store_and_notifies`
  - `only_missing_blobs_are_put`
  - `fs_unavailable_aborts_without_state_change`
  - `status_reports_roots_and_emulators`
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(android): sync engine uploads`.

### Task 11: Delivery applier

**Files:** Create `sync/DeliveryApplier.kt`, `sync/LocalBackups.kt`. Test `sync/DeliveryApplierTest.kt`.

**Interfaces:**
- `class DeliveryApplier(fs, api, store, registry, engine, notifier: (String) -> Unit)`.
- `suspend fun applyAll(): ApplyReport`, which runs for each delivery from `api.deliveries()`, in order:
  1. Resolve the module and `Detected`. If not detected, ack `Failed("emulator not found")`.
  2. **If `fs.isProcessAlive(pkg)`:** don't ack. Notify "Close <emulator> to receive the newer save for
     <displayName/gameKey>" (once per delivery id) and add the delivery to `pendingWhileRunning`.
  3. Find the local item (same ItemKey) and build its current manifest.
     - If it exists and its hash ≠ `knownManifestHash`, run `engine.upload(item)` first and ack
       `SkippedLocalChanged`.
     - Exception: if `knownManifestHash` is null (never synced) and local files exist, upload first as well.
  4. Download every blob of the delivery manifest into the app cache (`context.cacheDir/dl/<hash>`), verifying
     SHA-256.
  5. `LocalBackups.backup(fs, item)` copies the current files to the backup path from the global constraints
     and prunes to the newest 5.
  6. Write each file with `fs.writeAtomic(targetDir + "/" + path, …)`, then delete local files of that item that
     aren't in the manifest.
  7. Store `knownVersionId = delivery.versionId` and `knownManifestHash = Manifests.hash(delivery.manifest)`,
     then ack `Applied`.
  8. Any exception in steps 4–6 acks `Failed(detail)`. Files already written are restored from the backup taken
     in step 5.
- **Config deliveries:** the same flow over the bundle's files, with the bundle-path → absolute-path mapping
  from `module.configBundle()`. For a file new on this device, use the module's bundle-path root mapping
  (`data/…` → `dataRoot/…`, `root/…` → `extraRoots["root"]/…`, `export/…` → the export dir). If
  `missingLocalKeys` is non-empty, notify "Set your ROM folders in <emulator> on this device".
- **Vault deliveries:** write into the module's vault target: Eden `keys/` for category `keys`, Azahar `sysdata/`,
  RetroArch `system/`, Dolphin `Wii/`/`GC/` for `bios`, else `/sdcard/EmuSync/vault/<category>/`.
- `suspend fun onEmulatorExited(emulatorId)` retries the `pendingWhileRunning` items for it.

- [ ] **Step 1: Write failing tests:**
  - `running_emulator_blocks_write_and_notifies_once`
  - `applies_after_emulator_exits`
  - `local_change_uploads_first_and_acks_skipped`
  - `backup_taken_before_overwrite_and_pruned_to_5`
  - `extra_local_files_deleted_after_backup`
  - `hash_mismatch_on_download_acks_failed_and_restores`
  - `applied_updates_known_version`
  - `eden_delivery_written_into_this_devices_profile` (integration with EdenModule on fixtures)
  - `config_delivery_maps_bundle_paths`
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(android): safe delivery applier`.

### Task 12: Foreground service, events, boot and Shizuku lifecycle

**Files:** Create `service/SyncService.kt`, `service/BootReceiver.kt`, `service/Notifier.kt`. Modify `ShizukuGate`
if needed.

**Behaviour:**
- **`SyncService` (specialUse FGS):**
  - Shows a persistent notification with the state ("Watching 6 emulators", "Shizuku is off: open EmuSync",
    "Offline: 3 changes queued").
  - When Shizuku is `Ready`: binds the shell, runs `engine.fullScan()`, then `watch(all module watchDirs)`.
  - On a watch callback: `engine.onFsEvent`.
  - A 1 s ticker runs `engine.tick()`.
  - A 5 s process monitor runs `isProcessAlive` for each detected package. On the alive→dead transition it calls
    `engine.onEmulatorExited` and `applier.onEmulatorExited`.
  - The `EventStream` `deliveries` event and a 60 s poll both run `applier.applyAll()`.
  - On Shizuku death: report `shizukuOk=false` (best effort), stop the watchers, and wait for `Ready`.
- **`BootReceiver`** starts `SyncService` on `BOOT_COMPLETED`. The service waits for Shizuku.
- **`Notifier`:** a channel `sync`, and separate notification ids for the "close emulator" and conflict
  messages.
- **Test:** an instrumentation test isn't required. JVM-test the state-text function
  `statusText(ShizukuState, watching:Int, queued:Int)`. Verify the rest on the device in Task 14.

- [ ] **Steps:** failing `statusText` tests; FAIL; implement; PASS; `assembleDebug`. Commit `feat(android): sync service`.

### Task 13: Temporary debug setup screen

**Files:** Create `debug/DebugActivity.kt`, built in code (no Compose, no XML layouts beyond one ScrollView).

**Shows:**
- Server URL field, defaulting to `http://192.168.1.117:5100`.
- Sign-in: email + password with Register/Login buttons, or a pairing code with a Pair button.
- Shizuku state and a "Request permission" button.
- Detected emulators: package, data root, item count, and flags such as "RetroArch config not found externally".
- Text fields for the ROM folders (comma-separated) and the Azahar root and PPSSPP memstick overrides.
- Buttons: "Start sync service", "Scan now", "Apply deliveries now".
- The last 200 log lines.

All values persist in the StateStore. The title bar says "EmuSync (debug)". No styling work; this screen gets
replaced after the mockups.

- [ ] **Steps:** `assembleDebug` passes. Commit `feat(android): temporary debug setup screen`.

### Task 14: On-device end-to-end check (Thor + Odin 3)

**Preconditions:**
- Server plan complete through Task 11 at least.
- The server running as a Docker container publishing `5100:8080` with
  `PUBLIC_URL=http://192.168.1.117:5100`.
- Device backups taken (they were, 2026-11-19).

- [ ] **Step 1:** Install Shizuku 13.6.0 (from the official GitHub release) and `app-debug.apk` on both devices
  via `adb install`. Start Shizuku over adb with the start command shown by the Shizuku app or its docs. Unlock
  the Odin with its PIN if needed (`adb shell input keyevent 82; adb shell input text <PIN>; adb shell input
  keyevent 66`). Grant Shizuku permission to EmuSync. Granting needs a tap; use `adb shell input tap` coordinates
  from `uiautomator dump`.
- [ ] **Step 2:** Register on the Thor, pair the Odin with a code, and start the service on both.
- [ ] **Step 3:**
  - **Expected:** the server lists both devices with roots and emulators. Every existing save is uploaded.
    Games present on both devices with differing saves show as FirstSync reviews, and nothing is written to
    either device.
  - **Verify:** check with `GET /v1/web/games` and through the Activity log, and confirm with
    `adb shell ls -l` that no save file's mtime changed.
- [ ] **Step 4: Live save test.** If the owner is present, they make an in-game save in Eden on the Thor.
  Otherwise use a **synthetic test item**: create
  `/sdcard/Games/nds/EmuSyncTest.nds` (a dummy file) on both devices and `EmuSyncTest.sav` on the Thor only. That
  exercises melonDS rules with no real game data.
  - **Expected:** the Thor uploads within ~5 s. The Odin, which has the dummy ROM "installed", receives the save
    while melonDS isn't running. The file appears on the Odin with identical SHA-256, and a backup dir is
    created only if a file existed.
- [ ] **Step 5:** Edit `EmuSyncTest.sav` on the Odin. It syncs back to the Thor. Then create a concurrent edit on
  both while one is offline (toggle the server off) → a conflict, and nothing is overwritten.
- [ ] **Step 6:** Record the results in `docs/superpowers/research/2026-11-20-device-e2e.md` and remove the
  synthetic test files from both devices.
