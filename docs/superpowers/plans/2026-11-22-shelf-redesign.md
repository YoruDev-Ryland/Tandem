# Shelf Redesign and App Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the app's main tab and the web Games page with a per-emulator game shelf. Every game's sync state shows on a status rail under the shelf, and the game under the cursor slides out with its cover art. The product also gets its "two marks" icon everywhere.

**Architecture:**
- **Server:** an art service fetches covers on demand, caches them in the data directory and serves them from one endpoint that both device keys and web sessions can call. That matches `/v1/web/games`, which the app already calls with its device key.
- **App and web:** each builds a pure "shelf model" (sorting, rail rows, card copy, notice line) from the existing `GameRow` data and the existing `Presence` mapping, then renders it. No sync, protocol or state-rule changes.

**Tech Stack:**
- Server: ASP.NET Core minimal APIs (.NET 10), xUnit.
- App: Kotlin with Jetpack Compose (Robolectric tests).
- Web: Svelte 5 with Vitest.

**Spec:** `docs/superpowers/specs/2026-11-22-shelf-redesign-design.md`. It is approved; read it in full before any task. Mockups: `.superpowers/brainstorm/959321-1795369930/content/directions.html` (section C) and `shelf-status.html` (option 1). Open them as files in a browser; the fonts load from that folder.

## Global Constraints

**Environment.**
- .NET: prefix commands with `export DOTNET_ROOT=$HOME/.dotnet PATH=$HOME/.dotnet:$PATH`. `TreatWarningsAsErrors` is on.
- Test commands:
  - server: `dotnet test Tandem.slnx`;
  - web: `cd web && npm test && npm run check`;
  - app: `cd android && ./gradlew testDebugUnitTest`.
- Commits: conventional messages, each ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Never push, never merge into main, never touch the running `tandem-e2e` container or the adb devices. Don't dispatch subagents.

**Look (owner's binding rules).** The Console language: Barlow, Barlow Semi Condensed (BSC) and JetBrains Mono, a 3 px radius, and hairlines.
- **Banned:** purple, gradients, glow, blur, springs, boxes in boxes, status pills on healthy rows, an accent colour "for life".
- **Colour meanings:** amber means waiting and red means a problem; no system spine colour may be close to either.
- **Themes:** dark and light are equal citizens. Respect reduced motion (the system "remove animations" setting, and `prefers-reduced-motion` on the web).

**Status.** The rail shows the existing presence states through the existing mapping:
- app: `presenceOf()` in `ui/state/Presence.kt` and `PresenceMark` in `ui/kit/Marks.kt`;
- web: `presence()` in `web/src/lib/deviceStates.ts` and `PresenceMark.svelte`.

Do not invent new states or copy.

**Order on a shelf (app and web):**
1. `openConflict` games first;
2. then by display name, case-insensitive;
3. games with a null display name last, by game key.

**Rail rows.**
- **Label:** take the device name, drop a leading `"AYN "` (case-insensitive), uppercase it and cut it to 5 characters. "AYN Thor" becomes "THOR" and "Odin3" becomes "ODIN3".
- **How many:** at most 3 rows. Further devices are listed only in the card's device lines.
- **Order:** the app puts this device first, then the others by name; the web orders all devices by name.

**The art endpoint**, the contract every task uses: `GET /v1/web/art/{emulatorId}/{*gameKey}`, under the existing `/v1/web` group's default authorization (device key or web session), with the `Tandem-Protocol: 1` header as for all of `/v1`.
- **200** with an `image/png` or `image/jpeg` body, `Cache-Control: private, max-age=604800` and an `ETag`.
- **202** `{"error":"art_pending"}`, with `Retry-After: 3` and `Cache-Control: no-store`.
- **404** `{"error":"no_art"}` with `Cache-Control: private, max-age=86400`, when the sources were tried and have nothing, or when art is turned off.
- **404** `{"error":"not_found"}` when the user has no save item for that game. Art is only fetched for the user's own games, so the server cannot be used to fetch arbitrary URLs.

**Fixed copy.**
- The main tab is "Library", replacing "This device"; the Activity sub-tab "This device" stays.
- Empty emulator: "No games for {Emulator} yet."
- Keyless waiting: "1 save waiting for {Emulator} to close" ("N saves …" for more than one).
- Several notices: "+N more", in ink3.

**Icon geometry** (48-unit grid):
- filled square: x 5, y 14, 18 × 20;
- outlined square: x 26.5, y 15.5, 15 × 17, with a 3-unit stroke.
- The launcher background is `#131312` with the mark in `#ECEBE6`.

## Review Focus

1. **Long and unknown names.** A 60-character title or a raw key such as `0100AC20128AB000` on a 34 dp spine is cut with an ellipsis inside the spine, never spilling over. The card title wraps to 2 lines and then ends with an ellipsis. Tests: Task 4 Step 1 and Task 6 Step 1.
2. **The game list changes under the cursor.** When the focused game disappears (deleted, or the emulator emptied), the cursor moves to the nearest remaining index and never crashes. Switching emulators remembers each emulator's cursor. Tests: Task 3 Step 1 and Task 6 Step 1.
3. **More than 3 devices and duplicate names.** The rail shows 3 rows, and the card lists all devices. Two devices both named "Odin3" get the same disambiguation `Presence.kt` already gives columns. Tests: Task 3 Step 1 and Task 6 Step 1.
4. **Game keys with `/`, spaces or unicode** (RetroArch keys contain `/`). The art URL is encoded exactly as the existing game-detail URL (`encodeSegment` and `encodeKey` in the app, `enc` and `encodeKey` on the web). The server catch-all route receives the same key. A non-image or oversized source response is discarded and recorded as a miss. Tests: Task 2 Steps 1 and 3, and Task 5 Step 1.
5. **Art fails or the server can't be reached.** A network error, 5xx or timeout on art shows the no-art card. There is at most one retry per focus, and the shelf itself keeps working. Tests: Task 5 Step 1 and Task 6 Step 1.

---

### Task 1: The icon on Android (launcher, notification, wordmark)

**Files:**
- Create:
  - `android/app/src/main/res/drawable/ic_tandem_mark.xml` (the mark, `#ECEBE6`, 108 dp adaptive foreground with the mark inside the 66 dp safe zone)
  - `android/app/src/main/res/drawable/ic_tandem_mark_mono.xml` (monochrome layer)
  - `android/app/src/main/res/drawable/ic_stat_tandem.xml` (24 dp, white on transparent)
  - `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`
  - `android/app/src/main/res/values/ic_launcher_background.xml` (`#131312`)
- Modify:
  - `android/app/src/main/AndroidManifest.xml`: `android:icon` and `android:roundIcon` on `<application>`
  - `android/app/src/main/kotlin/dev/tandem/app/service/Notifier.kt:68,105`: replace `android.R.drawable.stat_notify_sync` with `R.drawable.ic_stat_tandem`
  - `android/app/src/main/kotlin/dev/tandem/app/ui/kit/TopBar.kt`: the wordmark gets the mark before the word, at cap height
- Test: `android/app/src/test/kotlin/dev/tandem/app/service/NotifierTest.kt`, `android/app/src/test/kotlin/dev/tandem/app/ui/kit/KitScreenshotTest.kt`

**Interfaces:**
- Produces: `R.drawable.ic_tandem_mark`, `R.drawable.ic_stat_tandem`, and a composable `TandemMark(size: Dp, modifier: Modifier = Modifier)` in `ui/kit/TopBar.kt`. It draws the mark in `LocalEmu.current` ink with Compose `Canvas`, not the drawable, so it follows the theme.

- [ ] **Step 1: Write the failing test.** In `NotifierTest.kt`, add `status notification uses the Tandem mark`, asserting that `notifier.status("x").smallIcon.resId == R.drawable.ic_stat_tandem`, and the same for an alert notification. minSdk is 33, so `Notification.smallIcon` is available.
- [ ] **Step 2: Run** `cd android && ./gradlew testDebugUnitTest --tests '*NotifierTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Add the drawables, mipmaps and manifest attributes, change `Notifier.kt`, and add `TandemMark` to the top bar wordmark. Legacy PNG mipmaps aren't needed, because minSdk is 33 and adaptive icons cover every supported version.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest assembleDebug`. Expected: all tests pass, apart from screenshot goldens that changed only because of the wordmark. Re-record those in the same way the existing screenshot tests are recorded (see `KitScreenshotTest.kt`), then check the new PNGs by eye.
- [ ] **Step 5: Commit** `feat(app): Tandem two-marks icon for launcher, notifications and wordmark`.

### Task 2: Server cover-art service and endpoint

**Files:**
- Create:
  - `src/Tandem.Server/Art/ArtStore.cs`
  - `src/Tandem.Server/Art/ArtFetcher.cs`
  - `src/Tandem.Server/Art/IArtSource.cs`
  - `src/Tandem.Server/Art/LibretroThumbnailSource.cs`
  - `src/Tandem.Server/Art/GameTdbCoverSource.cs`
  - `src/Tandem.Server/Art/ArtEndpoints.cs`
  - `tests/Tandem.Server.Tests/Art/ArtEndpointTests.cs`
  - `tests/Tandem.Server.Tests/Art/ArtSourceTests.cs`
- Modify:
  - `src/Tandem.Server/ServerOptions.cs`: `EnableArt` (default true), `ArtMaxImageBytes` (2 MiB), `ArtCacheBytes` (500 MiB), `ArtMissDays` (7)
  - `src/Tandem.Server/EnvironmentAliases.cs`: `("ART", "EnableArt")`
  - `src/Tandem.Server/ServerSetup.cs`: register the store, the fetcher (hosted service), the named HttpClient `"art"` (timeout 20 s) and the endpoints
  - `src/Tandem.Server/Web/SecurityHeaders.cs`: change `img-src 'self' data:` to `img-src 'self' data: blob:` (the web shows fetched bytes as blob URLs)
  - `tests/Tandem.Server.Tests/Http/HardeningTests.cs`: assert `img-src 'self' data: blob:`
  - `THIRD_PARTY_NOTICES.md`: libretro-thumbnails and GameTDB covers, fetched at runtime, not bundled, turned off with `ART=false`
  - `README.md`: an `ART` row in the env table

**Interfaces:**
- Produces: the endpoint contract in Global Constraints.
- Internal shape, which the implementer may refine:
  - `record ArtRequest(string EmulatorId, string GameKey, string? DisplayName, string? Core)`
  - `interface IArtSource { Task<Uri?> FindAsync(ArtRequest r, CancellationToken ct); }`
  - `ArtStore.TryGet(emulatorId, gameKey) -> ArtLookup { Image(path, contentType, etag) | Miss | Unknown }`
  - `ArtFetcher.Enqueue(ArtRequest)`: a de-duplicated `Channel` worker that tries the sources in order, downloads at most `ArtMaxImageBytes`, checks the PNG/JPEG magic bytes, then stores the image or records a miss with a timestamp.
- **Storage:** under `{DataDirectory}/art/`, with a file name of lowercase hex SHA-256 of `emulatorId + "\n" + gameKey`, plus a `.png` or `.jpg` extension or a `.miss` marker. When the cache goes over `ArtCacheBytes`, the least recently used images are deleted, judged by last-access time touched on each read.
- **Source matching** is the implementer's research. The spec says libretro-thumbnails `Named_Boxarts` for RetroArch, melonDS and older systems, and GameTDB for GameCube/Wii, DS, 3DS and Switch where an ID can be derived. Partial coverage is fine. Document what each source matches in a comment at the top of the source.

- [ ] **Step 1: Write the failing endpoint tests** in `ArtEndpointTests.cs`, using `ServerFactory` and a fake `HttpMessageHandler` on the `"art"` client (see the `Lists` handler in `Games/DiscNamesTests.cs:101` for the pattern):
  - `unknown_game_is_not_found`: a GET for a game the user has no save item for gives 404 with `not_found`.
  - `first_request_is_pending_then_image`: the first GET gives 202 with `art_pending`, `Retry-After: 3` and `Cache-Control: no-store`. After the fetcher drains, which the test awaits through a test hook such as `ArtFetcher.WhenIdleAsync()`, a second GET gives 200 with `image/png`, `ETag`, `Cache-Control: private, max-age=604800`, and the fake PNG's exact bytes.
  - `source_miss_is_remembered`: when every source returns 404, the second GET gives 404 with `no_art` and `max-age=86400`. A third GET within `ArtMissDays` makes no new outbound request (count the handler's calls).
  - `non_image_and_oversized_are_misses`: a source answer of `text/html` bytes, or of more than `ArtMaxImageBytes`, ends as 404 `no_art`, with nothing stored under `art/`.
  - `slash_and_unicode_keys_round_trip`: a save item with gameKey `GBA/Pokémon - Emerald Version (USA).srm`, requested with the web client's encoding, returns 202 and then 200.
  - `disabled_art_is_no_art`: with `EnableArt=false`, the first GET gives 404 `no_art` and there are 0 outbound calls.
  - `device_key_and_web_session_both_work`: both auth kinds get 202 or 200 for the same game.
- [ ] **Step 2: Run** `dotnet test Tandem.slnx --filter FullyQualifiedName~Art`. Expected: FAIL, because the route doesn't exist.
- [ ] **Step 3: Write the source tests** in `ArtSourceTests.cs`. For each source, use 3–5 real-world examples from the owner's library, listed below, and assert the exact URL the source builds:
  - Dolphin `GSXE69` (SSX 3);
  - RetroArch `Pokemon - Emerald Version`;
  - melonDS `Chrono Trigger (US)`;
  - Azahar `Pokémon X`;
  - a Switch title ID.

  A system with no source returns null.
- [ ] **Step 4: Implement** the store, fetcher, sources, endpoint, options, alias, CSP, notices and README. The endpoint looks up the user's save item for (emulatorId, gameKey) to get `DisplayName` and `Core`; there is no outbound call on the request thread.
- [ ] **Step 5: Run** `dotnet test Tandem.slnx`. Expected: everything passes, including `HardeningTests`.
- [ ] **Step 6: Commit** `feat(server): cover art for the focused game, fetched on demand and cached`.

### Task 3: App shelf model (pure, no UI)

**Files:**
- Create:
  - `android/app/src/main/kotlin/dev/tandem/app/ui/state/Shelf.kt`
  - `android/app/src/main/kotlin/dev/tandem/app/ui/theme/Spines.kt`
  - `android/app/src/test/kotlin/dev/tandem/app/ui/state/ShelfTest.kt`
  - `android/app/src/test/kotlin/dev/tandem/app/ui/theme/SpinesTest.kt`
- Modify:
  - `android/app/src/main/kotlin/dev/tandem/app/ui/screens/DeviceViewModel.kt`: build a `ShelfUi` in place of the per-emulator `EmuPane` games pane. Keep `WaitLine`, `ReceivedLine`, `syncedAgo` and `hereCopy`, which the shelf reuses.

**Interfaces:**
- Consumes:
  - `GameRow` (`model/Web.kt`);
  - `presenceOf(g, deviceId)` and the duplicate-name disambiguation in `ui/state/Presence.kt`;
  - `systemLabel`-equivalent derivation in `ui/state/Systems.kt`;
  - the emulator rows (`EmuRowState`) already used by `deviceUi`.
- Produces:
  - `fun shelfOrder(rows: List<GameRow>): List<GameRow>`: the Global Constraints order.
  - `fun railLabel(deviceName: String): String`
  - `data class RailRow(val deviceId: String, val label: String, val name: String)`
  - `fun railRows(rows: List<GameRow>, thisDeviceId: String): List<RailRow>`: at most 3, this device first.
  - `data class Spine(val emulatorId: String, val gameKey: String, val title: String, val unnamed: Boolean, val system: String?, val marks: List<PresenceCell>)`
  - `data class CardLine(val name: String, val presence: Presence, val words: String)`
  - `data class ShelfCard(val kicker: String, val title: String, val unnamed: Boolean, val sentence: String, val tone: Tone, val lines: List<CardLine>)`
  - `data class EmuTab(val id: String, val name: String, val state: String?, val tone: Tone, val dimmed: Boolean)`
  - `data class ShelfUi(val tabs: List<EmuTab>, val emulator: Int, val rail: List<RailRow>, val spines: List<Spine>, val count: Int, val notice: Notice?, val emptyLine: String?)`
  - `data class Notice(val text: String, val tone: Tone, val more: Int)`
  - `class ShelfCursor`: remembers a game key per emulator, with `fun indexFor(emulatorId: String, spines: List<Spine>): Int` (the remembered key's index, or else the nearest earlier index, or 0) and `fun moveTo(emulatorId, gameKey)`.
  - `fun card(spine: Spine, row: GameRow, thisDeviceId: String, emulatorName: String): ShelfCard`
  - `object Spines { fun colour(system: String?): Color; fun ink(system: String?): Color }`: one palette for both themes. The spec's values are GC `#34343A`, GBA `#4C5FA0`, DS `#D2CEC2` (dark text) and 3DS `#3C6E68`. The implementer chooses Switch, PSP, VITA, GB, GBC, NES, SNES, N64, PS1, WII, and unknown (neutral).
- **Card device-line words** reuse the existing per-state copy (`hereCopy` and the web's `deviceStateCopy` wording):
  - Newest: "newest · {relative time}" for the newest device, else "newest";
  - Older: "older copy";
  - Absent: "not on this device" for this device, "not installed" for others;
  - Waiting: "waiting for {Emulator} to close";
  - Coming: "on its way";
  - Conflict: "conflict";
  - Empty: "no save yet".

- [ ] **Step 1: Write the failing tests** in `ShelfTest.kt`:
  - `order_conflicts_then_name_then_unnamed_by_key`: [b, a-conflict, null-name key "Z", c, null-name key "A"] gives [a-conflict, b, c, A, Z].
  - `rail_label_drops_ayn_and_cuts_to_five`: "AYN Thor" gives "THOR", "Odin3" gives "ODIN3", and "Steam Deck" gives "STEAM".
  - `rail_this_device_first_then_by_name_max_three`: 5 devices gives 3 rows, this device first.
  - `duplicate_device_names_are_disambiguated`: two "Odin3" give distinct labels, matching Presence.kt's suffix rule.
  - `card_lists_every_device_even_beyond_rail`: 5 devices gives 5 `CardLine`s.
  - `cursor_remembers_per_emulator`: move on Dolphin to "SSX 3", switch to melonDS and back, and the index is SSX 3's.
  - `cursor_clamps_when_game_disappears`: remember the last game, remove it, and the index becomes the new last; an empty list gives 0.
  - `not_set_up_emulator_still_has_spines`: an emulator that isn't installed here but has server games gives spines, with this device's marks all `Absent` and `dimmed = true`.
  - `empty_emulator_line`: no games gives `emptyLine == "No games for Dolphin yet."`.
  - `notice_latest_wait_or_received_with_more_count`, and `keyless_waiting_notice`, which expects "1 save waiting for Dolphin to close".

  In `SpinesTest.kt`:
  - `every_spine_text_meets_aa`: for every system and unknown, the WCAG contrast ratio of `ink` on `colour` is at least 4.5.
  - `no_spine_near_amber_red_or_purple`: compute each colour's HSL hue. If saturation is above 0.25, the hue is not within ±20° of the theme's wait-amber hue or problem-red hue (read both from `ui/theme`), and not within 260–300°.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ShelfTest*' --tests '*SpinesTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `Shelf.kt`, `Spines.kt` and the `DeviceViewModel` change. Its `ui` StateFlow now exposes `ShelfUi` plus the existing top and revoked fields. The existing `DeviceViewModel` tests are updated to the new shape; keep their assertions about wait and received behaviour.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS, except `DeviceScreen*` UI tests, which Task 4 rewrites. If they don't compile, stub them in this task with `@Ignore("rewritten in Task 4")`.
- [ ] **Step 5: Commit** `feat(app): shelf model (order, rail rows, card, cursor, spine palette)`.

### Task 4: App shelf screen

**Files:**
- Create:
  - `android/app/src/main/kotlin/dev/tandem/app/ui/screens/ShelfScreen.kt` (strip, shelf, spines, card, rail, notice line)
  - `android/app/src/test/kotlin/dev/tandem/app/ui/screens/ShelfScreenTest.kt`
  - `android/app/src/test/kotlin/dev/tandem/app/ui/screens/ShelfScreenshotTest.kt`
- Modify:
  - `android/app/src/main/kotlin/dev/tandem/app/MainActivity.kt:89`: rename the tab to "Library", and route `TAB_DEVICE` to `ShelfScreen`
  - `ui/screens/DeviceScreen.kt`: keep `DeviceActions` and `syncNow`; delete the old two-pane layout
- Delete or replace: `DeviceScreenTest.kt`, `DeviceScreenshotTest.kt` and `DeviceScreenSizeTest.kt`, whose coverage moves to the new tests.

**Interfaces:**
- Consumes: `ShelfUi`, `ShelfCursor` and `card()` from Task 3; `TandemMark` from Task 1; `PresenceMark`, `SystemMark`, `HintBar`, `ConsoleScaffold`, `LocalCueSink`/`Cue` and `OnAction`/`Action` from the existing kit.
- Produces: `@Composable fun ShelfScreen(ui: ShelfUi, top: @Composable RowScope.() -> Unit, actions: DeviceActions, art: @Composable (emulatorId: String, gameKey: String) -> ImageBitmap? = { _, _ -> null })`. The `art` slot is filled by Task 5; until then the card always shows the no-art cover.
- **Layout values** are the spec's starting points: spines 34 dp wide with a 5 dp gap; the card 262 dp wide; a 6 dp shelf edge in ink; cover 112×156 dp; system code BSC 700 at 9 sp; spine title BSC 700 at 14 sp, rotated to read bottom to top; card title BSC 700 at 26 sp, max 2 lines.
- **Input:**
  - Left and right move the cursor (`Cue.Move`). At either end it does not wrap and plays the reject cue the kit already uses at a list edge.
  - Up and down switch emulator.
  - A runs `actions.openGame`, X `actions.syncNow`, Y `actions.settings(emulatorId)`, B `actions.back`.
  - Touch: tapping a spine moves the cursor, tapping the card opens the game, and tapping a strip tab switches emulator. A horizontal drag scrolls the shelf without moving the cursor.
- **Scroll:** keep the card fully visible with one spine of context on each side when possible, using an instant `scrollToItem`-style jump, never an animated scroll.
- **Card motion:** the width animates over 120 ms with ease-out. Use the same reduced-motion check the tab crossfade already uses (find it in `ConsoleScaffold.kt` or `BumperTabs.kt`); when reduced motion is on, the swap is instant.

- [ ] **Step 1: Write the failing tests** in `ShelfScreenTest.kt`, using Robolectric and Compose UI test with the same harness `DeviceScreenTest.kt` uses:
  - `right_moves_cursor_and_card_shows_next_title`;
  - `left_at_start_does_not_wrap`;
  - `down_switches_emulator_and_restores_cursor`;
  - `a_opens_focused_game`;
  - `y_opens_focused_emulator_settings`;
  - `rail_shows_a_mark_under_every_spine`: for N spines and R rail rows, there are N×R marks, found through their content descriptions;
  - `long_and_unknown_titles_stay_inside_spine`: a 60-character title and the key `0100AC20128AB000` give spine text bounds within the spine bounds;
  - `card_title_wraps_to_two_lines_max`;
  - `notice_line_shows_more_count`;
  - `empty_emulator_shows_empty_line`.

  `ShelfScreenshotTest.kt` records light and dark at 832×468 dp xhdpi for these states: a mixed shelf (the mockup's data), the cursor on a waiting game, a conflict game, an empty emulator, and 4 devices.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ShelfScreen*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `ShelfScreen.kt` and the `MainActivity` wiring, and remove the old layout.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest assembleDebug`. Expected: PASS. Open each new screenshot PNG and compare it with `shelf-status.html` option 1 by eye. Check that the rail sits under the right spines, that nothing overlaps, and that dark looks as considered as light. Fix any problems before committing.
- [ ] **Step 5: Commit** `feat(app): Library tab is a per-emulator shelf with a status rail`.

### Task 5: App cover art

**Files:**
- Create:
  - `android/app/src/main/kotlin/dev/tandem/app/ui/art/ArtCache.kt`
  - `android/app/src/test/kotlin/dev/tandem/app/ui/art/ArtCacheTest.kt`
- Modify:
  - `android/app/src/main/kotlin/dev/tandem/app/api/Api.kt` and `ApiClient.kt`: add `suspend fun art(emulatorId: String, gameKey: String): ArtResult`, with a default of `ArtResult.None` in the interface so fakes compile. The path is built exactly as `game()` does: `"/v1/web/art/${encodeSegment(emulatorId)}/${encodeKey(gameKey)}"`.
  - `MainActivity.kt`: pass the `art` slot to `ShelfScreen`.

**Interfaces:**
- Produces:
  - `sealed interface ArtResult { data class Image(val bytes: ByteArray) : ArtResult; data object Pending : ArtResult; data object None : ArtResult }`. A 200 gives `Image`, a 202 gives `Pending`, and a 404 or any error gives `None`.
  - `class ArtCache(dir: File, api: suspend (String, String) -> ArtResult, maxBytes: Long = 50L * 1024 * 1024, clock: () -> Long, delay: suspend (Long) -> Unit)` with:
    - `fun cached(emulatorId, gameKey): ImageBitmap?`: from disk or memory only, with no network;
    - `suspend fun load(emulatorId, gameKey): ImageBitmap?`, which runs: disk hit, then the session miss set, then the API; on `Pending`, it waits 3 s and asks once more; on `None` or a second `Pending`, it notes a session miss (a second `Pending` is not a session miss) and returns null;
    - `fun prefetch(keys: List<Pair<String, String>>)`.
- **Rules:** only images go to disk (`cacheDir/art`, hashed names); 202 and 404 are never stored on disk. Eviction is least recently used by file last-modified time, touched on read. Decoding uses `BitmapFactory`; no new dependency.
- **The `art` slot** loads the focused game and prefetches ±1 neighbours. A focus change cancels the pending retry for the old game.

- [ ] **Step 1: Write the failing tests** in `ArtCacheTest.kt`, with a fake api and a fake delay:
  - `image_is_stored_and_served_from_disk`: a second load makes 0 api calls.
  - `pending_retries_once_after_three_seconds`: `Pending` then `Image` gives a bitmap, 2 api calls and a delay of 3000.
  - `pending_twice_gives_null_and_is_not_a_session_miss`: a later load calls the api again.
  - `none_is_a_session_miss_not_on_disk`: the second load makes 0 api calls, and the `art` directory holds no file for it.
  - `network_error_is_none`: the api throws `IOException`, giving null with no crash.
  - `evicts_least_recently_used_over_cap`.
  - `slash_and_unicode_key_path`, in `ApiClientTest.kt`: a key containing `/` and `é` produces the same encoded path as `game()`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ArtCache*' --tests '*ApiClientTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** the cache, the API method and the slot wiring.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest assembleDebug`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(app): cover art in the shelf card, cached on device`.

### Task 6: Web shelf on the Games page, and the web icon

**Files:**
- Create:
  - `web/src/lib/shelf.ts`: the pure model, mirroring Task 3. The web has no "this device", so rail rows go by name and the card copy is about the newest copy.
  - `web/src/lib/spines.ts`: the palette, the same hex values as `Spines.kt` from Task 3. Copy them; a test pins them.
  - `web/src/lib/ui/Shelf.svelte`, `web/src/lib/ui/ShelfCard.svelte`, `web/src/lib/ui/StatusRail.svelte`
  - `web/public/favicon.svg`, `web/public/favicon-32.png`, `web/public/apple-touch-icon.png`
  - `web/test/shelf.test.ts`
- Modify:
  - `web/src/pages/Games.svelte`: emulator `Tabs` level 2 without "All", the shelf, the rail, the search box, and the "N matches in other emulators" line
  - `web/src/lib/api/endpoints.ts`: `fetchArt`
  - `web/index.html`: icon links
  - the web top bar wordmark component (find where "TANDEM" or the product name is rendered in `web/src/lib/ui/`): add the mark
  - `web/test/pages/Games.test.ts`: rewritten for the shelf

**Interfaces:**
- Consumes: the art endpoint contract (Global Constraints); `presence()`, `deviceStateCopy()` and `PresenceMark.svelte`; `systemLabel()` and `bareKey()` from `systems.ts`; `Tabs`, `HintBar`, `Field` and the gamepad and keyboard input layers (`lib/input/`).
- Produces:
  - `shelfOrder(rows: GameRow[]): GameRow[]`
  - `railLabel(name: string): string`
  - `railRows(rows: GameRow[]): RailRow[]`
  - `cardCopy(g: GameRow, emulatorName: string): { kicker: string; sentence: string; tone: StateTone; lines: { name: string; presence: Presence; words: string }[] }`. The sentence is "Newest on {device} · {relative}", "Waiting to reach {device}" or "Conflict between {a} and {b}".
  - `fetchArt(emu: string, key: string): Promise<{ kind: "image"; url: string } | { kind: "pending" } | { kind: "none" }>`. It uses the same headers as `api()` (`lib/api/http.ts`), returns a `URL.createObjectURL` for 200, and the caller revokes the URL when focus leaves. One retry after 3 s on pending; errors give `none`.
- **Favicon:**
  - `favicon.svg` is the mark with an inline `<style>`: `@media (prefers-color-scheme: dark)` gives a light mark, and the default is dark.
  - The PNGs show the mark `#ECEBE6` on `#131312`.
  - Links in `index.html`: `<link rel="icon" href="/favicon.svg" type="image/svg+xml">`, `<link rel="icon" href="/favicon-32.png" sizes="32x32">`, `<link rel="apple-touch-icon" href="/apple-touch-icon.png">`.
- **Input:**
  - Arrow left and right move; arrow up and down switch emulator; Enter opens the game; Esc goes back. The gamepad does the same, with A open and B back.
  - The mouse wheel or trackpad scrolls the shelf sideways; click a spine to select it, click the card to open.
  - The hint bar shows Open and Back only.
  - Below 600 px wide, the card stacks its cover above its text.
- **Search:** it filters the current emulator's shelf. The line "N matches in other emulators" lists those emulators as tab links, and is shown only when N > 0.

- [ ] **Step 1: Write the failing tests.** In `web/test/shelf.test.ts`, mirror Task 3's model tests (order, rail label and order with max 3, duplicate names, card lines for every device, card sentences) and add:
  - `spine palette matches the app`: hard-coded expected hex values, the same as Task 3;
  - `spine text meets AA`;
  - `fetchArt` cases: 200 gives an image URL; 202 then 200 gives an image after one retry; 202 twice gives pending; 404 gives none; a network error gives none.

  In `web/test/pages/Games.test.ts`:
  - right arrow moves the card to the next title;
  - down switches emulator and restores its cursor;
  - Enter opens the game route;
  - every spine has a rail mark per row;
  - search filters the shelf and shows the other-emulator count;
  - a long title is clipped;
  - a game disappearing on refresh clamps the cursor;
  - art failure shows the no-art cover.
- [ ] **Step 2: Run** `cd web && npm test`. Expected: FAIL.
- [ ] **Step 3: Implement** the model, components, page, `fetchArt`, favicon files, links and wordmark. Generate the PNGs from the SVG with any local tool (for example `rsvg-convert` or Chromium) and commit them.
- [ ] **Step 4: Run** `npm test && npm run check && npm run build`. Expected: PASS. Then run the existing screenshot script (`web/scripts/screens.mjs`) for Games in light and dark at desktop and phone widths, and compare the PNGs by eye with `shelf-status.html` option 1.
- [ ] **Step 5: Commit** `feat(web): Games page is the shelf with a status rail; favicon and wordmark mark`.

### Task 7: Integrate, deploy, check on the devices (controller)

- [ ] **Step 1:** Merge the task branches into `feat/shelf`, then run all three suites and `assembleDebug`. All must pass.
- [ ] **Step 2: Whole-branch review** on the most capable model, against the spec and this plan's Review Focus. Fix its findings in one wave.
- [ ] **Step 3: Deploy to the test server.** Build `docker build -t tandem:shelf .`. Back up the `emusync-e2e-data` volume to `~/.local/share/tandem-backups/`, then swap the `tandem-e2e` container to `tandem:shelf` with the same run flags as now (restart unless-stopped, port 5100, cap drops, `PUBLIC_URL`). Confirm `/healthz` and that both devices reconnect.
- [ ] **Step 4: Install on both devices** over adb (same debug key, `adb install -r`). Screenshot the Library tab on each. Check:
  - the launcher icon and the notification icon;
  - left and right across the shelf;
  - up and down across emulators;
  - art appearing on refocus.
- [ ] **Step 5:** Report to the owner with the screenshots. Do not merge into main without the owner's OK.
