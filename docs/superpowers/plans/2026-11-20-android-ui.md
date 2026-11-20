# Android UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the debug screen with the console-style Compose app: This device, Activity, Conflicts and Settings tabs, plus first-run setup. It is driven by gamepad or touch on the Thor and Odin 3.

**Architecture:**
- **One Activity.** `MainActivity` (ComponentActivity) hosts a Compose UI with a hand-rolled destination stack. Navigation-compose is not needed for five destinations.
- **A reactive layer** (`ui/state`) turns the sync core's current pull-style state (`AppGraph.snapshot`, `StateStore`, `LogBuffer`) into `StateFlow`s.
- **The session publishes** its waiting deliveries and running emulators.
- **Server data** comes through new `ApiClient` methods, using the device's Bearer key, which the server accepts on `/v1/web/*`.
- **A shared console kit** (`ui/kit`) implements spec §5–7: tokens, type, the cursor row, bumper tabs and the hint bar. Gamepad buttons arrive as Android key events.

**Tech Stack:**
- Kotlin, using AGP 9's built-in Kotlin.
- Jetpack Compose (BOM), activity-compose, lifecycle-viewmodel-compose.
- kotlinx-coroutines and kotlinx-serialization, as before.
- Tests: JUnit 4, Robolectric with compose-ui-test-junit4 (JVM UI tests), MockWebServer.

**Spec:**
- docs/superpowers/specs/2026-11-20-ui-design.md (authority).
- Product spec §9 and §13 (setup steps, first-sync review, one active variant per emulator).
- Mockups: design/mockups/app-main.html and design/mockups/app-setup-conflicts.html.

**Branch:** `feat/android-ui`, created from `feat/android` (worktree `../EmuSync-android`). Copy this plan and the UI spec into the worktree's `docs/superpowers/` at Task 1.

**Depends on:** web UI plan Task 5. Its server additions (`GET /v1/web/conflicts`, `GameRow.Devices`, `DeviceStatusRequest.RunningEmulators`, `VersionSummary.ManifestHash`) must be merged into the server the app is tested against, before Task 3 here.

## Global Constraints

**Visual system**
- Colours come only from `EmuColors` (spec §3 tokens), in dark and light. The theme follows the system unless Settings overrides it (stored in `StateStore.settings.theme` as `"system"|"light"|"dark"`).
- No Material styling shows: no ripples tinted with a brand colour, no elevation, no rounded cards, no FAB, no bottom navigation. Material 3 may be used only as plumbing (`MaterialTheme` wrapping text selection and colours from `EmuColors`). Its components are restyled through the `ui/kit` wrappers, never used raw on screens.
- The spec §2 banned list applies. The only radius is 3 dp.
- Fonts are bundled in `res/font`: Barlow 400/500/600/700, Barlow Semi Condensed 600/700, JetBrains Mono 400 (all OFL). The licence texts go in `assets/licenses/`.
- Layout targets 960×540 dp landscape:
  - side padding 28 dp;
  - top bar 52 dp, hint bar 44 dp;
  - rows 40 dp.
- Portrait (for example the Thor's bottom screen, or a phone) stacks the two panes vertically; it must not clip.

**Input**
- Everything works by gamepad and by touch.
- Key mapping:

  | Key event | Action |
  |---|---|
  | `KEYCODE_DPAD_*` | move the cursor |
  | `KEYCODE_BUTTON_A` / `DPAD_CENTER` / `ENTER` | a |
  | `KEYCODE_BUTTON_B` / `BACK` / `ESCAPE` | b |
  | `KEYCODE_BUTTON_X`, `KEYCODE_BUTTON_Y` | x, y |
  | `KEYCODE_BUTTON_L1/R1` | page tabs |
  | `KEYCODE_BUTTON_L2/R2` | sub-tabs |

- The hint bar always shows glyphs.
- The cursor moves instantly; there is no animation on focus moves.

**Behaviour and copy**
- The UI never writes emulator files itself. Every apply goes through the existing `SyncService` actions (`ACTION_APPLY`, `ACTION_SCAN`, `ACTION_RELOAD`), so the never-write-while-running rule stays in one place.
- Copy follows the web plan's shared vocabulary, for example "waiting for Eden to close" and "this is the newest". A waiting delivery always gets the one-line instruction "Close {Emulator} to get the newer {Game} save from {Device}."
- No secret is shown: the device key, the password, notification tokens.
- Accessibility: contrast reaches WCAG AA in both themes. Every clickable element has a `contentDescription` or text, and a 48 dp minimum touch target.

## Review Focus

1. **The service isn't running, Shizuku is off, or the device is signed out**: every tab still renders, and the top bar states the problem in `--fault` with the one action that fixes it. Test in Task 6.
2. **The server is unreachable**: tabs show the last loaded server data with "Offline · last updated {time}". Nothing spins forever. Test in Task 4.
3. **Touch only, no gamepad**: every action reachable by a gamepad is also a tappable control. Test in Task 5.
4. **An emulator closes while the user is on the Conflicts or This device tab**: the waiting line disappears within one refresh, with no stale "waiting". Test in Task 4.
5. **Rotation or process death mid-setup**: setup resumes at the same step with its choices kept. Test in Task 10.

## File Structure (paths under `android/app/src/main/kotlin/dev/emusync/app/`)

```
MainActivity.kt                                  (T1)
ui/theme/{EmuColors.kt,EmuType.kt,EmuTheme.kt}   (T1)
service/{AppGraph.kt,SyncService.kt,Notifier.kt}  (T2 publish waiting/running; T1 openApp target)
sync/StateStore.kt                                (T2 change flow; T7 theme/variant settings)
api/{Api.kt,ApiClient.kt}, model/Web.kt           (T3)
ui/state/{AppState.kt,ServerRepo.kt}              (T4)
ui/input/{Actions.kt,KeyMap.kt}                   (T5)
ui/kit/{TopBar,BumperTabs,CursorList,CursorRow,EmuButton,HintBar,Facts,EmptyLine,ErrorLine,ConsoleScaffold}.kt (T5)
ui/screens/{DeviceScreen,ConflictsScreen,ActivityScreen,SettingsScreen}.kt + ViewModels (T6–T9)
ui/setup/{SetupFlow.kt,SetupViewModel.kt,steps/*.kt}  (T10)
res/font/*.ttf, assets/licenses/*.txt             (T1)
```

---

### Task 1: Compose, theme, fonts and MainActivity

**Files:**
- Modify: `android/app/build.gradle.kts`, `android/build.gradle.kts`, `AndroidManifest.xml`, `service/Notifier.kt` (`openApp()` → `MainActivity`)
- Create: `MainActivity.kt`, `ui/theme/EmuColors.kt`, `EmuType.kt`, `EmuTheme.kt`, `res/font/*`, `assets/licenses/*`, `res/values/themes.xml` (a plain `Theme.EmuSync` for window background only)
- Test: `android/app/src/test/kotlin/dev/emusync/app/ui/theme/ContrastTest.kt`, `MainActivityLaunchTest.kt` (Robolectric)

**Interfaces:**
- Produces:
  - `data class EmuColors(bg, ink, ink2, ink3, rule, rule2, focusBg, focusInk, wait, fault, glyph: Color)` with `EmuColors.Dark` and `EmuColors.Light`, holding exactly the spec §3 values.
  - `val LocalEmu = staticCompositionLocalOf { EmuColors.Dark }`
  - `EmuType` text styles: `title` (Barlow Semi Condensed 600, 26 sp), `section` (15 sp 600), `tab` (15 sp 600), `rowPrimary` (Barlow 600, 15 sp), `body` (14 sp), `meta` (13 sp), `header` (11 sp 600, uppercase), `mono` (JetBrains Mono 12 sp, tabular).
  - `@Composable fun EmuTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit)`
  - `enum class ThemeMode { System, Light, Dark }`
  - `MainActivity` becomes the launcher. `DebugActivity` stays declared but not exported; it is reached from Settings → Diagnostics in Task 9.

- [ ] **Step 1: Write failing tests:**
  - `ContrastTest`: ink, ink2, ink3, wait and fault on bg, and focusInk on focusBg, each ≥ 4.5:1 in Dark and Light. If light `ink3 #7C7C76` fails, raise it, and the web plan's tokens and the spec §3 table must match (note it in the commit);
  - `MainActivityLaunchTest`: the activity launches under Robolectric and the root composable shows the wordmark text "EmuSync".
- [ ] **Step 2: Run** `cd android && ./gradlew :app:testDebugUnitTest --tests '*ContrastTest' --tests '*MainActivityLaunchTest'` → FAIL.
- [ ] **Step 3: Implement.**
  - Add the Compose compiler plugin matching the Kotlin version, the compose BOM (latest stable), `ui`, `foundation`, `material3` (plumbing only), `activity-compose`, `lifecycle-viewmodel-compose` and `lifecycle-runtime-compose`.
  - For tests: `robolectric`, `ui-test-junit4`, `ui-test-manifest`.
  - Record the chosen versions in `docs/superpowers/research/2026-11-19-android-toolchain.md`.
  - Download the fonts from the google/fonts GitHub repository (OFL).
- [ ] **Step 4: Run** the whole unit-test suite plus `assembleDebug` → PASS (the 274 existing tests still pass).
- [ ] **Step 5: Commit** `feat(app): Compose, console theme tokens, bundled fonts and MainActivity`

### Task 2: Make sync-core state observable (settings changes, waiting deliveries, running emulators)

**Files:**
- Modify: `sync/StateStore.kt`, `service/AppGraph.kt`, `service/SyncService.kt`, `sync/DeliveryApplier.kt`, `model/Protocol.kt` (`DeviceStatusRequest.runningEmulators`), and the code that reports status (`PUT /v1/devices/me/status`)
- Test: `sync/StateStoreFlowTest.kt`, `service/WaitingDeliveriesTest.kt`, `sync/StatusReportTest.kt`

**Interfaces:**
- Produces:
  - `StateStore.changes: StateFlow<Long>`. It increments after every successful write.
  - `data class WaitingDelivery(deliveryId: String, emulatorId: String, emulatorName: String, gameKey: String?, displayName: String?, fromDeviceName: String?)`
  - `AppGraph.waiting: StateFlow<List<WaitingDelivery>>`. It is set by `DeliveryApplier` after each apply pass: every delivery skipped because its emulator is running. It is cleared for an emulator when `onEmulatorExited` applies them.
  - `AppGraph.running: StateFlow<Set<String>>`: the emulator ids running, refreshed on the monitor tick.
  - `DeviceStatusRequest(…, runningEmulators: List<String>? = null)`. It is sent on each status report.
  - `LogBuffer.lines: StateFlow<List<String>>`: the last 200 lines.

- [ ] **Step 1: Write failing tests:**
  - `changes` increments on `settings =` and on `put`;
  - an apply pass with a running Eden publishes one `WaitingDelivery` with `displayName` from `gameTitle`;
  - after `onEmulatorExited("eden")` applies it, `waiting` is empty;
  - the status report JSON contains `"runningEmulators":["eden"]` (MockWebServer).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.** Add a `fromDeviceName` lookup from `DeliveryDto` if present, otherwise null; the UI then says "from another device".
- [ ] **Step 4: Run** the whole suite → PASS.
- [ ] **Step 5: Commit** `feat(app): observable settings, waiting deliveries and running emulators; report running emulators to the server`

### Task 3: API client for the UI

**Files:**
- Modify: `api/Api.kt`, `api/ApiClient.kt`
- Create: `model/Web.kt`
- Test: `api/WebApiTest.kt` (MockWebServer)

**Interfaces:**
- Produces, as Kotlin mirrors of the server DTOs (field names identical, `ProtocolJson`):
  - `GameRow` with `devices: List<GameDeviceState>`, and `DeviceGameState`;
  - `GameDetail`, `SaveItemDetail`, `VersionSummary` (with `manifestHash`, `fileCount`);
  - `ConflictRow`, `ConflictSide`;
  - `ResolveConflictRequest(choice, keepVersionId, variantName)`, `BulkFirstSyncRequest`;
  - `ConfigOverview`, `GroupRow`, `SoloRow`;
  - `ApplyConfigRequest(targetDeviceId, emulatorId, sourceVersionId, mode)`, `JoinGroupRequest`;
  - `SetupChecklist`, `SetupStep`;
  - `ActivityPage`, `ActivityItem`;
  - `DeviceSummary` (with `runningEmulators`).
- New `Api` methods:
  - `games(emulator: String?): List<GameRow>`
  - `game(emulatorId, gameKey): GameDetail`
  - `conflicts(): List<ConflictRow>`
  - `resolveConflict(itemId, req)`
  - `firstSync(req)`
  - `configs(): ConfigOverview`
  - `joinGroup(groupId, deviceId)`
  - `applyConfig(req): ApplyConfigResponse`
  - `keepOwn(deviceId, emulatorId)`
  - `setup(deviceId): SetupChecklist`
  - `activity(device: String?, before: Long?, limit: Int = 50): ActivityPage`
  - `devices(): List<DeviceSummary>`
  - `me(): DeviceSummary`
- `gameKey` path segments are percent-encoded, keeping `/`.

- [ ] **Step 1: Write failing tests:** one per method, asserting the method, path and query, the Bearer and protocol headers, and the parsed fields. Plus:
  - `game("melonds","nds/Pokemon - Platinum Version (USA)")` hits `/v1/web/games/melonds/nds/Pokemon%20-%20Platinum%20Version%20(USA)`;
  - a 409 `conflict_open` maps to `ApiException(code="conflict_open")`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): API client methods and DTOs for the app UI`

### Task 4: App state and server repository

**Files:**
- Create: `ui/state/AppState.kt`, `ui/state/ServerRepo.kt`
- Test: `ui/state/AppStateTest.kt`, `ui/state/ServerRepoTest.kt`

**Interfaces:**
- Produces:
  - `data class TopStatus(deviceName: String?, server: ServerConn, shizuku: ShizukuState, signedIn: Boolean)`
  - `enum class ServerConn { Connected, Offline, SignedOut }`
  - `class AppState(graph: AppGraph, store: StateStore, gate: ShizukuGate, scope)` exposing:
    - `top: StateFlow<TopStatus>`
    - `emulators: StateFlow<List<EmulatorRowUi>>`
    - `waiting: StateFlow<List<WaitingDelivery>>`
    - `running: StateFlow<Set<String>>`
  - `data class EmulatorRowUi(id, name, state: EmuRowState, count: Int)`
  - `sealed EmuRowState { Open(waiting: Int); Saves(n); ConfigGroup(name); NotSetUp; Ok }`
  - `class ServerRepo(api: () -> Api?, scope)`. It exposes `games: StateFlow<Loaded<List<GameRow>>>` and `conflicts: StateFlow<Loaded<List<ConflictRow>>>`, keyed and refreshed on:
    - start;
    - each SSE `deliveries` event;
    - each change in `AppGraph.running`;
    - a 60-second timer while the UI is visible.
  - `data class Loaded<T>(value: T?, updatedAt: Instant?, error: ApiException?, offline: Boolean)`

- [ ] **Step 1: Write failing tests:**
  - with the server down, `games` keeps the last value with `offline=true` and `updatedAt` unchanged;
  - an emulator leaving `running` triggers one refresh, after which the waiting row is gone;
  - `top` reports `SignedOut` when `deviceKey` is null;
  - Shizuku `NotRunning` is reflected in `top`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): reactive app state and cached server repository`

### Task 5: Console kit and gamepad input

**Files:**
- Create: `ui/input/Actions.kt`, `ui/input/KeyMap.kt`, `ui/kit/*.kt` (see File Structure)
- Test: `ui/input/KeyMapTest.kt`, `ui/kit/CursorListTest.kt`, `ui/kit/BumperTabsTest.kt`, `ui/kit/TouchParityTest.kt`

**Interfaces:**
- Produces:
  - `enum class Action { Up, Down, Left, Right, A, B, X, Y, L1, R1, L2, R2 }`
  - `fun keyToAction(keyCode: Int): Action?`, per the Global Constraints mapping.
  - `@Composable fun ConsoleScaffold(top, tabs, hints: List<Hint>, status: String?, onAction: (Action) -> Boolean, content)`. It installs a root `onPreviewKeyEvent` that routes actions to the innermost handler first.
  - `@Composable fun <T> CursorList(items: List<T>, key: (T) -> Any, selected: Int, onSelect: (Int) -> Unit, onOpen: (T) -> Unit, row: @Composable RowScope.(T, focused: Boolean) -> Unit)`
    - The focused row is drawn inverted (`focusBg`/`focusInk`, 3 dp radius).
    - Tapping a row selects it; tapping the selected row opens it.
  - `@Composable fun BumperTabs(items: List<String>, active: Int, onChange: (Int) -> Unit, level: Int = 1, badge: (Int) -> String? = { null })`. Badges render in `fault`, for example "Conflicts 1".
  - `@Composable fun HintBar(status: String?, hints: List<Hint>)`, with `data class Hint(action: Action, label: String, onClick: () -> Unit)`. Each hint is also a tappable button: this gives touch parity.
  - `@Composable fun EmuButton(text, primary: Boolean = false, glyph: Action? = null, onClick)`
  - `Facts`, `EmptyLine`, `ErrorLine` (with the same error-code copy as the web plan Task 10).

- [ ] **Step 1: Write failing tests:**
  - the key mapping table;
  - `CursorList`: DPAD_DOWN moves the selection, BUTTON_A opens;
  - `BumperTabs`: BUTTON_R1 advances, and the badge shows only when non-null;
  - `TouchParityTest`: every `Hint` renders as a clickable node that invokes `onClick`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**, matching the sizes in design/mockups/app-main.html.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): console kit — cursor list, bumper tabs, hint bar, buttons, gamepad mapping`

### Task 6: "This device" tab and the top bar

**Files:**
- Create: `ui/screens/DeviceScreen.kt`, `ui/screens/DeviceViewModel.kt`, `ui/kit/TopBar.kt`
- Test: `ui/screens/DeviceScreenTest.kt`

**Interfaces:**
- Consumes: `AppState`, `ServerRepo.games`.
- Produces, as in mockup app-main:
  - **Left pane:** "Emulators", with one row per module in registry order: name and state.

    | State | Copy |
    |---|---|
    | Open with waiting deliveries | "Open · N waiting" (`wait`) |
    | Has saves | "N saves" |
    | In a config group | "config group" |
    | Not detected | "not set up" (`ink3`) |

  - **Right pane:** "{Emulator} · N games", with columns GAME · NEWEST ON · HERE. HERE comes from this device's `GameDeviceState`:

    | State | HERE copy |
    |---|---|
    | Newest | "this is the newest" |
    | HasIt | "up to date" |
    | Waiting | "waiting for {Emulator} to close" (`wait`) |
    | Pending | "on its way" |
    | Behind | "older save" |
    | Differs | "two saves · choose in Conflicts" (`fault`) |

  - When `waiting` is non-empty for the focused emulator, a line below the list reads "Close {Emulator} to get the newer {Game} save from {Device}. Nothing is written while {Emulator} is open."
  - **Top bar:** the device name; "Server connected" / "Offline · last updated {hh:mm}" (`wait`) / "Signed out" (`fault`); "Shizuku on" / "Shizuku is off: tap to fix" (`fault`, opens the Shizuku help from Task 10).
  - **Hints:** A Open (game detail is a later plan; for now it opens the web page through an `ACTION_VIEW` intent at `{serverUrl}/games/{emu}/{key}`) · X Sync now (`ACTION_SCAN` then `ACTION_APPLY`) · Y Emulator settings (Settings, scrolled to that emulator) · B Back.

- [ ] **Step 1: Write failing tests:**
  - Eden open with one waiting shows "Open · 1 waiting" and the close line;
  - a Differs game shows the fault copy;
  - Shizuku NotRunning shows "Shizuku is off: tap to fix";
  - signed out shows "Signed out" and the right pane says "Sign in to see your games." with an A action to setup;
  - X sends `ACTION_SCAN` and `ACTION_APPLY` intents (Robolectric shadow).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): This device tab with emulator states, games and waiting line`

### Task 7: Conflicts tab (including first-sync review)

**Files:**
- Create: `ui/screens/ConflictsScreen.kt`, `ConflictsViewModel.kt`
- Test: `ui/screens/ConflictsScreenTest.kt`

**Interfaces:**
- Consumes: `ServerRepo.conflicts`, `Api.resolveConflict`, `Api.firstSync`, `store.deviceId`.
- Produces, as in mockup app-setup-conflicts:
  - **Left pane:** the conflicts that involve this device, meaning one side's `deviceId == store.deviceId`.
  - **Right pane:**
    - Red "Two different saves".
    - The subtitle: FirstSync → "This {Device} and the {Other} each had a save before they first synced. Both are safe on the server."; Concurrent → "Both devices changed this save before syncing."
    - Two columns, "This {Device}" and "{Other}", each showing Received time and size with file count.
    - The line "Whatever you choose, the other copy stays in history."
  - **Hints:**

    | Button | Action |
    |---|---|
    | A | Keep this {Device}'s |
    | X | Keep {Other}'s |
    | Y | Keep both (variant name "{Other} run") |
    | B | Back |

  - The status line is "{Emulator} is open: your choice is applied when it closes" when the emulator is running, otherwise "Applied right away".
  - With two or more FirstSync conflicts, L2/R2 adds the bulk actions "Use this device's for all" and "Use {Other}'s for all", via `firstSync` with `UseDevice` / `UseServer`, after a confirm sheet.
  - After a resolve: send `ACTION_APPLY` and refresh conflicts.
  - Empty: "Nothing to decide."

- [ ] **Step 1: Write failing tests:**
  - A calls `resolveConflict(item, KeepVersion, thisSide)`;
  - Y sends `variantName="Odin 3 run"`;
  - the running-emulator status copy;
  - the bulk confirm, then `firstSync(UseDevice)`;
  - only conflicts involving this device are listed;
  - empty-state copy.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): Conflicts tab with face-button choices and first-sync bulk review`

### Task 8: Activity tab

**Files:**
- Create: `ui/screens/ActivityScreen.kt`, `ActivityViewModel.kt`
- Test: `ui/screens/ActivityScreenTest.kt`

**Interfaces:**
- Consumes: `Api.activity(device = store.deviceId)`, `LogBuffer.lines`.
- Produces:
  - **Sub-tabs (L2/R2):** "This device" (server activity for this device, paged by `nextBefore` with "Load older" on A at the end of the list) and "Log" (`LogBuffer`, mono, newest first, for diagnostics).
  - **Rows:** relative time · message.
  - Offline: the cached page plus "Offline · last updated {time}".

- [ ] **Step 1: Write failing tests:**
  - the server query passes `device`;
  - "Load older" passes `before`;
  - the Log sub-tab renders the newest line first;
  - offline copy.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): Activity tab (server activity and local log)`

### Task 9: Settings tab and active variant per emulator

**Files:**
- Create: `ui/screens/SettingsScreen.kt`, `SettingsViewModel.kt`
- Modify:
  - `sync/StateStore.kt`: `DeviceSettingsData` gains `theme: String = "system"` and `variants: Map<String,String> = emptyMap()`. Migration: copy the `eden.package` override into `variants["eden"]` on first read.
  - each module's `detect`: prefer `settings.variants[emulatorId]` when that package is installed, otherwise the first installed package in preference order.
- Test: `ui/screens/SettingsScreenTest.kt`, `modules/ActiveVariantTest.kt`

**Interfaces:**
- Produces sections as sub-tabs (L2/R2):

  | Section | Contents |
  |---|---|
  | Sync | Auto-apply toggle with the copy "Apply newer saves automatically when the emulator is closed"; ROM folders list (add or remove); per-emulator overrides (Azahar folder, PPSSPP memstick) |
  | Emulators | Per module: the installed variants as cursor rows, with the active one marked "active". Choosing one saves `variants` and sends `ACTION_RELOAD`. |
  | Appearance | System / Light / Dark |
  | Account | Server URL, this device's name, "Sign out" (confirm: "This device stops syncing until you sign in again. Nothing on it is deleted.") |
  | Diagnostics | Opens `DebugActivity` |

  Sign out clears `deviceKey` and `deviceId`, keeps `serverUrl`, and sends `ACTION_RELOAD`.

- [ ] **Step 1: Write failing tests:**
  - choosing `dev.igawa6.edenduo` makes Eden `detect` return that package when installed;
  - the stored variant is ignored if it isn't installed;
  - the `eden.package` migration;
  - the theme choice recomposes with the Light colours;
  - sign out clears the key and shows "Signed out" in the top bar.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): Settings tab and per-emulator active variant`

### Task 10: First-run setup flow

**Files:**
- Create: `ui/setup/SetupFlow.kt`, `SetupViewModel.kt`, and `ui/setup/steps/{ServerStep,ShizukuStep,EmulatorsStep,SyncChoiceStep,FirstSyncStep}.kt`
- Test: `ui/setup/SetupFlowTest.kt`

**Interfaces:**
- Consumes: `Api.register/login/redeemPairing`, `ShizukuGate`, `AppGraph.snapshot`, `Api.configs`, `Api.joinGroup`, `Api.applyConfig`, `Api.keepOwn`, `Api.conflicts`, `Api.firstSync`.
- Produces: the setup flow, as in mockup app-setup-conflicts.
  - **Layout:** steps listed on the left; one question per screen; A Choose, B Back.
  - **Entry:** shown when `deviceKey == null` at launch, and from Settings → "Set up again". Progress is saved in `SavedStateHandle` plus `StateStore.signInForm` so rotation and process death keep the step.
  - **The steps:**
    1. **Server and sign-in.** Server URL field, which checks `GET /v1/server-info` and shows its name. Then either "Pairing code" (8 characters, the default) or "Email and password". Registration is offered only when `registrationOpen`.
    2. **Shizuku.** It shows the state, plus numbered instructions per state:
       - NotRunning: "Open Shizuku and start it (Wireless debugging or root)."
       - NoPermission: "Allow EmuSync when Shizuku asks." with A calling `requestPermission`.
       - It continues automatically when Ready.
    3. **Emulators found.** The detected emulators. For any module with more than one installed variant, a cursor row choice of the active one, which writes `variants`. Azahar or ROM folders get asked for here when a module reports a missing root.
    4. **How each one syncs.** For each detected emulator, with copy exactly as in the mockup:
       - Join "{group}" with {members}: `joinGroup` (offered only when a group exists for that emulator);
       - Copy {Device}'s settings once: `applyConfig(Copy)`;
       - Only back up this device's settings: `keepOwn`.
    5. **First sync.** Start the service, run `ACTION_SCAN`, wait for the scan to finish (snapshot status), then fetch `conflicts()` filtered to FirstSync for this device.
       - With none: "All set." and A goes to the This device tab.
       - Otherwise, the review: each game as a row with "Use this device's" / "Use {Other}'s" (A / X), plus the L2/R2 bulk actions as in Task 7.
- The QR scan of a pairing code is out of scope (code entry only). That is a ruling: it adds a camera permission and dependency for a one-time step.

- [ ] **Step 1: Write failing tests:**
  - step 1 shows `invalid_code` in place;
  - Shizuku NoPermission → A calls `requestPermission`;
  - Ready auto-advances;
  - the variant choice writes settings;
  - the step 4 options match the server groups and call the right API;
  - recreating the activity on step 4 restores step 4 with the earlier choices;
  - step 5 with no FirstSync conflicts shows "All set."
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): first-run setup flow (server, Shizuku, emulators, sync choice, first sync)`

### Task 11: On-device verification and design review

**Files:**
- Create: `docs/superpowers/research/2026-11-2x-app-ui-e2e.md` (results), and screenshots in `design/screens/app/`.

- [ ] **Step 1:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 2:** Install on the Thor and the Odin 3 over adb. Keep auto-apply off unless the owner says otherwise. Confirm the old state (key, settings) carried over, so no new sign-in is needed.
- [ ] **Step 3:** Drive every tab with a gamepad (`adb shell input keyevent KEYCODE_BUTTON_*`) and by touch. Capture each screen in dark and light with `adb exec-out screencap -p`. Check them against the mockups, fixing clipping, focus visibility and copy.
- [ ] **Step 4:** Verify the waiting line on the Thor: with Eden open and a delivery pending, it shows the close instruction; it clears within one refresh after Eden closes. Don't force-close Eden; ask the owner.
- [ ] **Step 5:** Run `/interface-design:design-review` with the screenshots and apply the blocker fixes.
- [ ] **Step 6: Commit** `chore(app): on-device UI verification notes and screenshots`
