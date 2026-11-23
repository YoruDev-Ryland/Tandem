# Case Spines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shelf spines that look like the real game cases: real spines cut from GameTDB case scans (GameCube, Wii, DS,
3DS), built case-style spines (console band and a middle slice of the cover) elsewhere, plain red for Switch, and a
system-colour fallback, all in real case proportions, on the app and the web.

**Architecture:**
- **Server:** learns a GameTDB id per DS/3DS install (the app reads it from the ROM header). It downloads the full
  case scan, cuts the spine strip out with ImageSharp, caches it beside the covers, and serves it from
  `/v1/web/spine/...` with the art endpoint's contract. `GameRow.spine` tells clients which games have one to ask for.
- **Clients:** each client picks a spine kind per game (Real, Plain, Built, Fallback), draws Built, Plain and Fallback
  spines itself, sizes every spine by its system's case ratio, and prefetches spine images and covers for the visible
  shelf.

**Tech Stack:**
- Server: ASP.NET Core .NET 10 minimal APIs, EF Core (SQLite and Postgres migrations), SixLabors.ImageSharp 3.1.12.
- App: Kotlin with Compose, Robolectric tests.
- Web: Svelte 5 with Vitest.

**Spec:** `docs/superpowers/specs/2026-11-23-case-spines-design.md` (approved 2026-11-23). It builds on
`docs/superpowers/specs/2026-11-22-shelf-redesign-design.md`.

## Global Constraints

**Tooling and safety:**
- `export DOTNET_ROOT=$HOME/.dotnet PATH=$HOME/.dotnet:$PATH` before any dotnet command. Never install a system .NET.
- Never push. Never merge into main.
- Never commit fetched cover art, case scans or other copyrighted images: tests use generated pixels only.
- Never put the owner's email or any personal detail in code, tests or docs.

**Design rules:**
- No purple, gradients, glow or springs. The built spine's darkening is a flat `rgba(0,0,0,0.42)` layer, not a
  gradient.
- Amber means wait and red means problem.
- Spines look the same in both themes; only the shelf edge and the rail follow the theme.
- Reduced motion: the fallback-to-image swap is an instant replace. Otherwise it is at most a 120 ms opacity fade.
- Console names are set in Barlow Semi Condensed Bold, never as logo images. Real spines are shown exactly as
  scanned, with no overlay.
- Built, Plain and Fallback spine titles read top to bottom, rotated 90° clockwise. Real spines carry their own title.

**Spine kind,** first that applies:
1. SWITCH → Plain.
2. `row.spine` and the spine image loaded → Real.
3. The cover loaded → Built.
4. Otherwise → Fallback.

While an image is pending, show the Fallback; the width never changes on the swap.

**Case ratio** (spine width = shelf height × ratio):

| System | Ratio |
|---|---|
| GC, WII | 0.074 |
| DS, 3DS | 0.10 |
| SWITCH | 0.065 |
| PSP | 0.078 |
| Everything else, and unknown | 0.10 |

**Band:**
- Height is 27% of the spine (20% for SWITCH).
- The text is set vertically, in Barlow Semi Condensed Bold, at 2.2% of the spine height (1.8% for SWITCH).

| System | Band colour | Name | Name colour |
|---|---|---|---|
| GBA | #F4F2EA | GAME BOY ADVANCE | #2A2925 |
| GBC | #F4F2EA | GAME BOY COLOR | #2A2925 |
| GB | #F4F2EA | GAME BOY | #2A2925 |
| NES | #1B1A17 | NES | #F4F2EA |
| SNES | #8E8C92 | SUPER NINTENDO | #F4F2EA |
| N64 | #1B1A17 | NINTENDO 64 | #F4F2EA |
| MD | #1B1A17 | GENESIS | #F4F2EA |
| PSX | #1B1A17 | PLAYSTATION | #F4F2EA |
| PSP | #141414 | PSP | #F4F2EA |
| VITA | #2D4A6B | PS VITA | #F4F2EA |
| GC | #1B1A17 | GAMECUBE | #F4F2EA |
| WII | #E4E2DA | Wii | #2A2925 |
| DS | #F4F2EA | NINTENDO DS | #2A2925 |
| 3DS | #F4F2EA | NINTENDO 3DS | #2A2925 |
| SWITCH | #E60012 (whole spine) | NINTENDO SWITCH | #FFFFFF |
| unknown | none | none | none |

An unknown system gets no band; its title starts at 6%.

**Spine bodies:**
- **Built:** the cover scaled to the spine height, centred horizontally and clipped to the spine, under the flat
  42% black layer. White title from 30% to 97% of the height. Titles are Barlow Semi Condensed Bold at 5% of the
  spine height, with an ellipsis when cut.
- **Fallback:** the body is the system's palette colour (the existing `Spines.colour` / `spineColour`), with the
  band on top and the title in the palette's ink colour, from 30% down.
- **Plain SWITCH:** red all over, the band, and a white title from 23% down.

**Server endpoint:** `GET /v1/web/spine/{emulatorId}/{*gameKey}`:
- Same auth, `Tandem-Protocol: 1` header, Cache-Control values, 202 `spine_pending` with `Retry-After: 3`, and
  404 `not_found` / `art_unavailable` rules as `/v1/web/art`.
- 404 `no_spine` when the game has no spine, or when art is off.
- Error bodies carry both `code` and `error`.

**Prefetch:**
- Scope: the visible spines plus 12 either side.
- At most 4 requests in flight per client.
- Each image is asked for at most once per screen visit.
- For a game with `row.spine`, fetch `/spine` and fetch its cover only if the spine says `no_spine` or it becomes
  the selected game.
- For any other game, fetch its cover.

## Review Focus

1. **A scan with no clear spine edges** (a plain white Wii spine, a blurry scan). The cutter falls back to the
   expected position and still trims, so no neighbouring art shows. *Task 2:
   `Falls_back_to_the_expected_strip_without_edges`.*
2. **GameTDB answers with something that isn't a full wrap** (a front-only or tiny image). The cutter rejects it
   (aspect under 1.2 or height under 300 px), it is recorded as a miss, and the game gets a Built spine. *Task 2:
   `Rejects_images_that_are_not_a_full_wrap`; Task 3: `A_scan_that_cannot_be_cut_is_no_spine`.*
3. **Art turned off (`ART=false`) or the server offline.** Every spine is a Fallback at its system's width, and the
   rail still has one column per spine under each spine. *Task 5: `fallback_shelf_keeps_widths_and_rail`; Task 6:
   `with no art every spine is a fallback at its case width`.*
4. **A long title on a 36 px built spine.** It ends in an ellipsis inside the spine and never overflows into the
   next one. *Task 5: `long_title_ellipsizes_inside_a_narrow_spine`; Task 6: `cuts a long title inside a narrow
   spine`.*
5. **A RetroArch shelf mixing GBA, SNES and unknown games.** Spines of different widths, rail columns matching each
   spine's width, and the cursor stepping one spine at a time. *Task 5: `mixed_widths_align_rail_and_cursor`; Task 6:
   `mixed systems give per-spine widths and aligned rail columns`.*

---

### Task 1: GameTDB ids on installs, and `GameRow.spine` (server)

**Files:**
- Modify: `src/Tandem.Protocol/Messages.cs` (`InstalledGame`)
- Modify: `src/Tandem.Protocol/Web.cs` (`GameRow`)
- Modify: `src/Tandem.Server/Data/Entities.cs` (`InstallRecord`)
- Modify: `src/Tandem.Server/Devices/DeviceService.cs` (`ReplaceInstalls`)
- Modify: `src/Tandem.Server/Web/WebQueryService.cs` (`BuildRow`)
- Create: `src/Tandem.Server/Art/SpineEligibility.cs`
- Create: migrations `AddInstallGameId` under `Data/Migrations/Sqlite` and `Data/Migrations/Postgres`, made with
  `dotnet ef migrations add AddInstallGameId --context SqliteTandemDbContext --output-dir Data/Migrations/Sqlite`,
  then the same with `PostgresTandemDbContext`.
- Test: `tests/Tandem.Server.Tests/WebApiTests.cs`, `tests/Tandem.Server.Tests/Art/ArtSourceTests.cs`

**Interfaces:**
- Produces:
  - `InstalledGame(string GameKey, string? DisplayName, string? Crc32, string? System = null, string? GameId = null)`.
  - `GameRow(..., string? System = null, bool Spine = false)`.
  - `InstallRecord.GameId` (`string?`).
  - `SpineEligibility.NormalizeGameId(string? id) -> string?`: uppercase; null unless it matches `^[A-Z0-9]{4}$`.
  - `SpineEligibility.Has(string emulatorId, string gameKey, string? system, string? gameId, bool artEnabled) -> bool`.
    True only when art is on and one of these holds:
    - Dolphin, and the key's last segment is a disc id (`TitleNames.IsDiscId`);
    - the system is DS or 3DS and `gameId` is not null.

- [ ] **Step 1: Write the failing tests**

  In `WebApiTests`, add `A_game_has_a_spine_when_its_scan_can_be_found`:
  - Register a device.
  - PUT installs for `melonds`: `InstalledGame("nds/Pokemon - Platinum Version (US)", null, null, null, "cpue")`
    and `InstalledGame("nds/NoId", null, null)`.
  - PUT installs for `azahar`: `InstalledGame("0004000000055D00", null, null, null, "EKJE")`.
  - Upload a Dolphin save `GXBE69`, and an Eden save.
  - Assert `Spine` on each row:
    - Platinum: true;
    - NoId: false;
    - the 3DS game: true;
    - GXBE69: true;
    - Eden: false.
  - Assert ids that aren't 4 alphanumerics (`"CPU"`, `"CPUE!"`, `"CTR-P-EKJE"`) are stored as null, so the row has
    `Spine` false.

  In `ArtSourceTests`, add `Spine_eligibility_follows_system_and_id`:
  - `Has("dolphin","RMCE","WII",null,true)` is true.
  - `Has("dolphin","GXBE69","GC",null,false)` is false (art off).
  - `Has("retroarch","retroarch/x","GBA","AAAA",true)` is false.

- [ ] **Step 2: Run them and see them fail**

  Run: `dotnet test --filter "FullyQualifiedName~Spine"`
  Expected: compile errors for `GameId` and `Spine`.

- [ ] **Step 3: Implement**

  - `NormalizeGameId` and `Has` in `SpineEligibility.cs`.
  - In `DeviceService.ReplaceInstalls`, set `GameId = SpineEligibility.NormalizeGameId(g.GameId)` on update and on
    insert.
  - Add `g.GameId is { Length: > 32 }` to the length guard.
  - In `BuildRow`, pass `Spine` from `Has(...)`, using the latest install's non-null `GameId` and the row's settled
    system. `BuildRow` is static: pass `artEnabled` in from `GamesAsync`, which reads `ServerOptions.EnableArt`.
  - Generate both migrations. Check each `Up` only adds the `GameId` column.

- [ ] **Step 4: Run the whole server suite**

  Run: `dotnet test`
  Expected: `Passed!`, no failures.

- [ ] **Step 5: Commit** with `feat(server): GameTDB ids on installs and GameRow.spine`.

---

### Task 2: Spine cutter (server)

**Files:**
- Modify: `src/Tandem.Server/Tandem.Server.csproj`: add `<PackageReference Include="SixLabors.ImageSharp" Version="3.1.12" />`.
- Create: `src/Tandem.Server/Art/SpineCutter.cs`
- Test: `tests/Tandem.Server.Tests/Art/SpineCutterTests.cs`

**Interfaces:**
- Produces:
  - `SpineCutter.Cut(byte[] scan, string system) -> byte[]?`: a PNG, or null when the image isn't a usable full wrap.
  - `SpineCutter.ScanRatio(string system) -> double`: the spine's expected width in the scan, as a fraction of the
    scan's height. 0.0735 for GC and WII; 0.103 for DS and 3DS.
  - `SpineCutter.OutputHeight = 680`.
  - `SpineCutter.OutputWidth(string system) -> int`: `round(680 × case ratio)` from the Global Constraints. 50 for
    GC and WII; 68 for DS and 3DS.

**Algorithm** (the tests don't pin all of it):
1. Decode with ImageSharp (PNG or JPEG). Return null on a decode error, when `width / height < 1.2`, or when
   `height < 300`.
2. Convert to luminance `L(x, y)`. Let `c = width / 2` and `w = height × ScanRatio(system)`.
3. Edge strength: `E(x) = mean over y of |L(x, y) − L(x − 1, y)|`.
4. Find the left edge in `[c − 0.75w, c − 0.25w]` and the right edge in `[c + 0.25w, c + 0.75w]`, taking the column
   of highest `E` on each side. The result is confident when:
   - each peak is at least twice the median `E` of its window, and
   - `right − left` is between `0.8w` and `1.2w`.

   Otherwise use `left = c − w/2` and `right = c + w/2`.
5. Trim `max(2, round(0.04 × (right − left)))` px from each side.
6. Crop the strip. Scale it to cover `OutputWidth × 680`, keeping its aspect, then centre-crop to exactly that size.
   Encode as PNG.

- [ ] **Step 1: Write the failing tests**

  The tests generate scans with ImageSharp: 1024×680 for GC, 1616×680 for DS. Each is filled with a mid-grey
  noise-free "back" and "front", and has a solid red spine strip (#C00000) at known columns.

  - `Cuts_the_strip_between_the_edges`: a GC scan with red at x 487..537. The output is 50×680. Every pixel is red
    within ±8 per channel, so no grey leaks in.
  - `Finds_an_off_centre_spine`: a DS scan with red at x 766..836. The output is 68×680 and all red.
  - `Falls_back_to_the_expected_strip_without_edges`: a GC scan of one flat colour with a faint 1-level step at
    x 500. The output is 50×680, taken from around the centre: the result equals cropping x 487..537 of the flat
    image (all one colour).
  - `Rejects_images_that_are_not_a_full_wrap`:
    - a 512×512 image returns null;
    - a 600×250 image returns null;
    - bytes that aren't an image return null.

- [ ] **Step 2: Run them and see them fail**

  Run: `dotnet test --filter "FullyQualifiedName~SpineCutterTests"`
  Expected: compile error, `SpineCutter` not found.

- [ ] **Step 3: Implement `SpineCutter`** as above. Use `Image.Load<Rgb24>`. Compute luminance as
  `0.299R + 0.587G + 0.114B`.

- [ ] **Step 4: Run them and see them pass**

  Run: `dotnet test --filter "FullyQualifiedName~SpineCutterTests"`
  Expected: 4 passed.

- [ ] **Step 5: Commit** with `feat(server): cut a spine strip out of a full case scan`.

---

### Task 3: Spine source, fetch path and `/v1/web/spine` (server)

**Files:**
- Modify: `src/Tandem.Server/Art/IArtSource.cs`: add `ArtKind`, and add `Kind` and `GameId` to `ArtRequest`.
- Create: `src/Tandem.Server/Art/GameTdbSpineSource.cs`
- Modify: `src/Tandem.Server/Art/ArtStore.cs`: a spine namespace.
- Modify: `src/Tandem.Server/Art/ArtFetcher.cs`: the spine path.
- Modify: `src/Tandem.Server/Art/ArtEndpoints.cs`: map `/spine/{emulatorId}/{*gameKey}`.
- Modify: DI registration where `GameTdbCoverSource` is registered (find it with `grep -rn "GameTdbCoverSource" src`).
- Test: `tests/Tandem.Server.Tests/Art/SpineEndpointTests.cs`

**Interfaces:**
- Consumes: Task 1's `SpineEligibility.Has` and `InstallRecord.GameId`; Task 2's `SpineCutter.Cut`.
- Produces:
  - `enum ArtKind { Cover, Spine }`.
  - `ArtRequest(string EmulatorId, string GameKey, string? DisplayName, string? Core, string? System = null, ArtKind Kind = ArtKind.Cover, string? GameId = null)`.
  - `ArtStore.KeyFor(ArtKind kind, string gameKey) -> string`: `gameKey` for Cover, `"\u0001spine\n" + gameKey`
    for Spine.
  - Every store call for a spine uses `KeyFor(Spine, key)`, so spine files, misses and ETags never collide with
    covers.
  - `GameTdbSpineSource.Candidates(ArtRequest r, TitleNames? titles) -> IReadOnlyList<Uri>`:
    - Dolphin: `{Base}wii/coverfullHQ/{region}/{ID6}.png`. A 4-character Wii id is widened as
      `GameTdbCoverSource` does.
    - DS: `{Base}ds/coverfullHQ/{region}/{ID4}.jpg`.
    - 3DS: `{Base}3ds/coverfullHQ/{region}/{ID4}.jpg`.
    - The region comes from the id's 4th character, with the same table and EN fallback as `GameTdbCoverSource`.
      Factor its `Region` and fallback into one shared internal helper rather than copying them.

- [ ] **Step 1: Write the failing tests**

  Model them on `ArtEndpointTests`, using its `FakeSources`, `FakePng` and `Drain` helpers. Make those `internal`
  if they aren't. A generated 1024×680 PNG with a red strip, as in Task 2, is the fake scan.

  - `Real_spine_is_pending_then_a_cut_image`:
    - Upload a Dolphin `GXBE69` save. Fake `wii/coverfullHQ/US/GXBE69.png` with the scan.
    - The first GET of `/v1/web/spine/dolphin/GXBE69` gives 202 `spine_pending`, `Retry-After: 3`.
    - After `Drain`, it gives 200 `image/png`, the image is 50×680 (decode with ImageSharp), and
      `Cache-Control: private, max-age=604800`.
    - The cover endpoint for the same game is still 202, then a separate fetch: spine and cover don't share files.
  - `Ds_spine_uses_the_reported_id`: a melonDS install with `GameId` `CPUE`. Fake `ds/coverfullHQ/US/CPUE.jpg` with
    a JPEG scan. Expect 200 and 68×680.
  - `A_game_without_a_spine_is_no_spine`:
    - an Eden game gives 404 `no_spine`, with no outbound calls;
    - a melonDS install with no id gives 404 `no_spine`;
    - `ART=false` gives 404 `no_spine`.
  - `A_scan_that_cannot_be_cut_is_no_spine`: GameTDB answers with a 512×512 PNG. After the drain it gives 404
    `no_spine`, and a second GET makes no new outbound call (the miss is cached).
  - `Another_users_game_is_not_found_for_spines`: as the art test of that name.

- [ ] **Step 2: Run them and see them fail**

  Run: `dotnet test --filter "FullyQualifiedName~SpineEndpointTests"`
  Expected: 404s and compile errors.

- [ ] **Step 3: Implement**

  - **Endpoint:** looks the game up as `/art` does (save items or the user's active installs).
    - When `Has(...)` is false, it returns `no_spine` with `MissCache`.
    - Otherwise it reads `store.TryGet(emu, KeyFor(Spine, key))`: Image → 200, Miss → `no_spine` /
      `art_unavailable`, Unknown → enqueue `ArtRequest(..., Kind: Spine, GameId: latestInstallGameId)` and return 202
      `spine_pending`.
  - **Fetcher:** for `Kind == Spine`, take candidates from `GameTdbSpineSource` only (not the cover sources).
    - For each download, run `SpineCutter.Cut(bytes, system)`. Save the first non-null result as `.png` under
      `KeyFor(Spine, key)`.
    - If no candidate cuts, record a normal (not transient) miss; unreachable sources still give a transient one.
    - The pending-dedupe key includes the kind.

- [ ] **Step 4: Run the whole server suite**

  Run: `dotnet test`
  Expected: `Passed!`.

- [ ] **Step 5: Commit** with `feat(server): real spines from GameTDB case scans at /v1/web/spine`.

---

### Task 4: The app reports DS and 3DS GameTDB ids

**Files:**
- Modify: `android/app/src/main/kotlin/dev/tandem/app/model/Protocol.kt`: add `gameId` to `InstalledGame`.
- Modify: `android/app/src/main/kotlin/dev/tandem/app/modules/melonds/MelonDsModule.kt` (`installedGames`)
- Modify: `android/app/src/main/kotlin/dev/tandem/app/modules/azahar/AzaharModule.kt` (`installedGames` and the
  header parser)
- Test: `android/app/src/test/kotlin/dev/tandem/app/modules/melonds/MelonDsModuleTest.kt`,
  `.../azahar/AzaharModuleTest.kt`

**Interfaces:**
- Produces:
  - `InstalledGame(gameKey, displayName = null, crc32 = null, system = null, gameId: String? = null)`.
  - `MelonDsModule.Companion.gameCode(input: InputStream): String?`: 4 bytes at 0x0C. Null unless they are 4 ASCII
    `[A-Z0-9]`.
  - `AzaharModule.Companion.productCode(input: InputStream): String?`: from the NCCH header that `programId` already
    locates (offset 0x150, 16 bytes ASCII, for example `CTR-P-EKJE`). The last 4 characters, when the code matches
    `^(CTR|KTR)-[A-Z]-[A-Z0-9]{4}$`; otherwise null.

- [ ] **Step 1: Write the failing tests** (generated bytes, no real ROMs)

  - `MelonDsModuleTest.installed_games_carry_the_rom_game_code`:
    - Write `roms/Pokemon - Platinum Version (US).nds` with 0x200 bytes and `CPUE` at 0x0C. The install has
      `gameId == "CPUE"`.
    - A file with `cp\u0000E` there has `gameId == null`.
    - A 6-byte file has `gameId == null`, and the game is still listed.
  - `AzaharModuleTest.product_code_from_ncch_and_ncsd`:
    - A bare NCCH image (`NCCH` at 0x100, program id at 0x118, `CTR-P-EKJE` at 0x150) gives `EKJE`.
    - An NCSD image (partition 0 offset 0x20 sectors in the header at 0x120, then that NCCH) gives `EKJE`.
    - The installed game for that file has `gameId == "EKJE"`.
    - A title installed only as a title dir has `gameId == null`.

- [ ] **Step 2: Run them and see them fail**

  Run: `cd android && ./gradlew -q :app:testDebugUnitTest --tests '*MelonDsModuleTest*' --tests '*AzaharModuleTest*'`
  Expected: compile errors.

- [ ] **Step 3: Implement**

  - Read headers through the module's existing `fs.openRead`, wrapped in the same `FsUnavailableException`
    rethrow / skip-on-other-error pattern.
  - In Azahar, factor the header walk out of `programId` so both functions share it. Open each file once and read
    both values.

- [ ] **Step 4: Run the app suite**

  Run: `./gradlew -q :app:testDebugUnitTest`
  Expected: 0 failures.

- [ ] **Step 5: Commit** with `feat(app): report DS and 3DS GameTDB ids with installs`.

---

### Task 5: Case spines on the app shelf

**Files:**
- Modify: `android/app/src/main/kotlin/dev/tandem/app/model/Web.kt`: `GameRow.spine: Boolean = false`.
- Modify: `android/app/src/main/kotlin/dev/tandem/app/ui/theme/Spines.kt`: add `ratio(system)`, `band(system)` and
  `SpineKind`.
- Modify: `android/app/src/main/kotlin/dev/tandem/app/ui/state/Shelf.kt`: carry `spine` and the system into `Spine`.
- Modify: `android/app/src/main/kotlin/dev/tandem/app/ui/art/ArtCache.kt`: add the kind, prefetch and concurrency.
- Modify: `android/app/src/main/kotlin/dev/tandem/app/ui/screens/ShelfScreen.kt`: `SpineView`, the widths, the rail
  and the scroll maths.
- Test: `android/app/src/test/kotlin/dev/tandem/app/ui/state/ShelfTest.kt`,
  `.../ui/screens/ShelfScreenTest.kt`, `.../ui/art/ArtCacheTest.kt`

**Interfaces:**
- Consumes: `GameRow.spine` (Task 1) and `/v1/web/spine` (Task 3).
- Produces:
  - `enum class SpineKind { Real, Plain, Built, Fallback }`.
  - `fun spineKind(system: String?, hasSpine: Boolean, spineImage: Boolean, cover: Boolean): SpineKind`, with the
    rule from the Global Constraints.
  - `Spines.ratio(system: String?): Float`.
  - `Spines.band(system: String?): Band?`, where
    `data class Band(val colour: Color, val name: String, val ink: Color, val heightFraction: Float)`.
  - `enum class ArtKind { Cover, Spine }`. `ArtCache.load(emulatorId, gameKey, kind = ArtKind.Cover)` and
    `cached(…, kind)`; the Spine path is `/v1/web/spine/...`.
  - `ArtCache.prefetch(keys: List<Triple<String, String, ArtKind>>)`, at most 4 at a time. The memory cache holds at
    least 80 images.

- [ ] **Step 1: Write the failing tests**

  `ShelfTest`:
  - `spine_kind_rules`: SWITCH with a cover is Plain; spine and image is Real; spine without the image but with a
    cover is Built; nothing is Fallback.
  - `ratios_and_bands_follow_the_table`:
    - `ratio("GC") == 0.074f`, `ratio("SWITCH") == 0.065f`, `ratio(null) == 0.10f`;
    - `band("GBA")` has name `GAME BOY ADVANCE`, colour #F4F2EA and height 0.27;
    - `band("SWITCH")` has height 0.20;
    - `band(null) == null`.

  `ShelfScreenTest`:
  - `fallback_shelf_keeps_widths_and_rail`:
    - Show a Dolphin shelf of 3 games and a melonDS shelf of 2 games with no art.
    - Each spine node's width equals the shelf height × ratio (±1 px): GC 0.074, DS 0.10.
    - Each rail column's width and x position match its spine's.
  - `long_title_ellipsizes_inside_a_narrow_spine`: a GC Fallback spine titled with 80 characters. The title's
    layout is ellipsized, and its bounds sit inside the spine's.
  - `mixed_widths_align_rail_and_cursor`: a RetroArch shelf of GBA, SNES and null-system games.
    - The spine widths follow the ratios.
    - Pressing right moves the cursor one spine at a time, and the card follows.
    - The rail columns line up with the spines.
  - `built_spine_reads_top_to_bottom_under_its_band`:
    - With a fake ArtCache returning a cover for a GBA game, the spine has a node tagged `spine-band:<key>` with text
      `GAME BOY ADVANCE`.
    - The title is rotated +90° (assert the graphicsLayer rotation, or its tag `spine-title-ttb:<key>`).
  - `switch_is_plain_red_even_with_art`: the Eden spine's background is #E60012 and has no cover image node.

  `ArtCacheTest`:
  - `spine_kind_uses_the_spine_path_and_its_own_entry`: a cover and a spine for the same key are separate cache
    entries and separate URLs.
  - `prefetch_runs_at_most_four_at_once`: a fake client counts concurrent requests; it never exceeds 4.

- [ ] **Step 2: Run them and see them fail**

  Run: `./gradlew -q :app:testDebugUnitTest --tests '*ShelfTest*' --tests '*ShelfScreenTest*' --tests '*ArtCacheTest*'`

- [ ] **Step 3: Implement**

  - **SpineView:** draws by `spineKind`.
    - **Real:** the image with `ContentScale.FillBounds` over the whole spine.
    - **Built:** the cover with `ContentScale.Crop` and `Alignment.Center` (the middle slice), under a flat
      `Color.Black.copy(alpha = 0.42f)` layer.
    - **Band:** a Box of `band.heightFraction` with the name rotated +90°.
    - **Title:** rotated +90° (top to bottom), sized and placed as in the Global Constraints.
  - **Widths:** replace `SPINE_W` with a per-spine width, `shelfHeight × Spines.ratio(system)`. The scroll-into-view
    and x maths sum per-spine widths. Rail columns take each spine's width.
  - **Prefetch:** from the shelf, the visible indices ± 12. Keys are `(emu, key, Spine)` for `row.spine` games and
    `(emu, key, Cover)` for the rest. A spine result of no-spine queues the cover.
  - The cursor and the card are unchanged.

- [ ] **Step 4: Run the app suite and build**

  Run: `./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
  Expected: 0 failures, and the build succeeds.

- [ ] **Step 5: Screenshot check**

  Render the Robolectric shelf screenshots (the existing `ShelfScreenshotTest`, extended with a GBA Built and a GC
  Fallback fixture). Open the PNGs and compare them with the mockup at
  `.superpowers/brainstorm/1674832-1795466545/content/real-spines-v3.html`.

- [ ] **Step 6: Commit** with `feat(app): case spines on the shelf`.

---

### Task 6: Case spines on the web Games shelf

**Files:**
- Modify: `web/src/lib/api/types.ts`: `GameRow.spine?: boolean`.
- Modify: `web/src/lib/api/endpoints.ts`: `fetchArt(emu, key, opts & { kind?: "cover" | "spine" })`, with
  `kind: "spine"` using `/v1/web/spine/`.
- Modify: `web/src/lib/spines.ts`: add `spineRatio(system)`, `spineBand(system)` and `spineKind(...)`.
- Create: `web/src/lib/spineArt.svelte.ts`: the shared spine and cover image store for the shelf.
- Modify: `web/src/lib/ui/Shelf.svelte` and `web/src/pages/Games.svelte`: draw by kind, use per-spine widths, and
  take the card's cover from the store.
- Test: `web/test/shelf.test.ts`, `web/test/pages/Games.test.ts`, `web/test/spineArt.test.ts`

**Interfaces:**
- Consumes: `GameRow.spine` (Task 1) and `/v1/web/spine` (Task 3).
- Produces:
  - `spineKind(system: string | null, hasSpine: boolean, spineImage: boolean, cover: boolean): "real" | "plain" | "built" | "fallback"`,
    with the same rule as the app.
  - `spineRatio(system: string | null): number`.
  - `spineBand(system: string | null): { colour: string; name: string; ink: string; height: number } | null`.
  - `createSpineArt(fetcher = fetchArt, limit = 4)` returns
    `{ want(ids: {emu: string; key: string; kind: "cover" | "spine"}[]): void; url(emu, key, kind): string | null; release(keep: Set<string>): void }`.
    `release` revokes blob URLs that aren't in `keep`.

- [ ] **Step 1: Write the failing tests**

  `shelf.test.ts`: `spine kind rules` and `ratios and bands follow the table`, with the same cases as Task 5.

  `spineArt.test.ts`:
  - `asks each image once and at most four at a time`: with 10 ids and a deferred fake fetcher, there are never
    more than 4 in flight, and asking again doesn't refetch.
  - `release revokes what is no longer kept`.

  `Games.test.ts`:
  - `with no art every spine is a fallback at its case width`: with art answering `no_art`, each `.games > li`'s
    inline `--spine-w` equals `calc(var(--shelf-h) * <ratio>)` for its system. The rail has as many columns as
    spines, each with the same `--spine-w`.
  - `mixed systems give per-spine widths and aligned rail columns`: a RetroArch shelf of GBA, SNES and unknown
    games has the ratios 0.1, 0.1 and 0.1, and a Dolphin shelf has 0.074.
  - `a built spine shows its band and a top-to-bottom title`: art answers an image for a GBA game. Its spine has
    `.band` text `GAME BOY ADVANCE`, a `.art` element with a `background-image` of the blob URL and
    `background-position: 50% 0`, and a title whose computed `writing-mode` is `vertical-rl` with no 180°
    rotation.
  - `a real spine replaces the fallback when it arrives`: with `spine: true`, `/v1/web/spine` answers an image. The
    spine gets class `real`, its `img` src is the blob, and there is no `.band`.
  - `switch is plain red`: the Eden spine has background `rgb(230, 0, 18)`, and no `/v1/web/art` request is made
    for it.
  - `cuts a long title inside a narrow spine`: the title has `overflow: hidden` and `text-overflow: ellipsis`, as
    the existing long-title test checks.

- [ ] **Step 2: Run them and see them fail**

  Run: `cd web && npx vitest run test/shelf.test.ts test/spineArt.test.ts test/pages/Games.test.ts`

- [ ] **Step 3: Implement**

  - **Spine CSS** with these classes: `.spine.real`, `.spine.built`, `.spine.plain`, `.spine.fallback`, `.band`,
    `.art`, `.dim`.
    - The width comes from `--spine-w: calc(var(--shelf-h) * <ratio>)` as an inline style.
    - Titles use `writing-mode: vertical-rl` without the current 180° rotation.
  - **Games.svelte:** drives `want(...)` from the cursor's window (±12 spines plus the visible ones) and calls
    `release` with the kept set.
  - **Card:** takes its cover from the store. For a `spine` game whose cover isn't loaded, `want` its cover when it
    is selected.

- [ ] **Step 4: Run the web checks**

  Run: `npx vitest run && npx svelte-check && npm run build`
  Expected: all tests pass, 0 svelte-check errors, and the build succeeds.

- [ ] **Step 5: Browser check**

  Run the Playwright script at
  `<scratchpad>/title-fit/ra.mjs`,
  adjusted for the Dolphin and RetroArch shelves, against `vite --port 5173`, which proxies to the live server.
  Open the PNGs and compare them with the mockup.

- [ ] **Step 6: Commit** with `feat(web): case spines on the Games shelf`.

---

### Task 7: Integrate, deploy, verify on devices

- [ ] **Step 1: Run all three suites**
  - `dotnet test`
  - `cd web && npx vitest run && npx svelte-check && npm run build`
  - `cd android && ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`

  All must pass.

- [ ] **Step 2: Back up the live data**

  `docker run --rm -v emusync-e2e-data:/data -v $HOME/.local/share/tandem-backups:/b alpine tar czf /b/e2e-data-pre-spines-$(date +%Y%m%d-%H%M).tgz -C /data .`

  Then rebuild `tandem:shelf` and recreate `tandem-e2e` with the same `docker run` flags as the current container.
  Check `/healthz` is 200 and the logs show the migration was applied.

- [ ] **Step 3: Install the APK on every device adb lists**

  `adb install -r` on each. Debug-signed only; never a release build. Wake each screen and open the Library. Screenshot:
  - the Dolphin shelf (real spines);
  - the RetroArch shelf (built spines);
  - the Eden shelf (red);
  - the melonDS shelf (real spines once the device has rescanned).

  Open the screenshots and check them.

- [ ] **Step 4: Whole-branch review** on the most capable model. Fix any Critical or Important findings, then
  re-run the suites.

- [ ] **Step 5: Report to the owner with the screenshots.** Do not merge into main without the owner's OK.
