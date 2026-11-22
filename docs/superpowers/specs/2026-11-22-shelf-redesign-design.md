# Shelf redesign and app icon

Date: 2026-11-22. Status: approved direction, written up for planning.
Mockups: `.superpowers/brainstorm/959321-1795369930/content/directions.html` (direction C) and `shelf-status.html` (option 1).

## Why

The audit's design pass refined the Console look but changed little you could see. The owner looked at three directions and chose the **Shelf**. Each game is a case spine on a shelf, coloured by system. The game under the cursor slides out to show its cover and its state. It is the most "games" and the least "admin tool" of the three.

The one thing the owner will not give up is today's at-a-glance answer to "what is synced where", with no need to select a game. The **status rail** keeps it: one row of presence marks per device under the shelf edge, each mark directly below its game. It also fills the empty space under the shelf.

The product also has no icon. The launcher shows Android's default icon and the notification shows the stock sync arrows. The owner chose **icon 1, "two marks"**: a filled square next to an outlined square. These are the app's own "has the newest / has a copy" marks.

## Goals

- The handheld app's main tab ("This device", to be renamed "Library") becomes the shelf with a status rail.
- The web Games page becomes the same shelf with the same rail, which keeps the two in step.
- Each device's sync state for every visible game can be read without moving the cursor. It should be at least as fast to read as today's table.
- Cover art appears for the focused game only, in the pulled-out card.
- One icon for the Android launcher (adaptive, with the Android 13+ themed monochrome layer), the notification small icon, the web favicon, and the wordmark in both top bars.

## Non-goals

- No change to sync behaviour, the protocol semantics or the server's state rules. The rail shows the `GameRow.Devices` states that already exist.
- Web Home, Devices, Activity, Conflicts and Settings keep their current layouts. Only Games changes.
- No cover art anywhere except the focused card: no art grids, art backgrounds or art on spines.
- No "last played" or playtime data.

## Anti-goals (a failure even if it works)

- **Status that needs the cursor.** If a person has to select a game to learn whether the Thor has it, the redesign has failed.
- **Slop tells**, from the owner's rules:
  - purple, gradients, glow, blur, bouncy or springy motion;
  - boxes in boxes, rounded alert banners, status pills on healthy rows;
  - an accent colour "for life".
  - Spine colours are per-system *identity*. Amber still means waiting and red still means a problem, and no system colour may be close to either.
- **Unreadable spines.** The cursor's own title is always shown horizontally in the card, so the rotated text only has to work for scanning. It must not be the only way to read a title.
- **Fake art.** A game without art gets the no-art card, never a placeholder picture or a generic cover.

## Constraints

- **Devices:** AYN Thor and Odin 3, at 832×468 dp, driven by gamepad first, with touch as a secondary input.
- **Themes:** dark and light are equal citizens, and reduced motion is respected.
- **Design language:** Console. Barlow, Barlow Semi Condensed and JetBrains Mono, a 3 px radius, and hairlines.
- **Inputs today:** L1/R1 switch tabs; A opens, X syncs now, Y opens emulator settings, B goes back. These stay.
- **Server:** it is self-hosted; outbound fetches must be optional, as with `TITLE_NAMES=false`. Cover art is copyrighted. It is fetched at runtime and cached, never committed or bundled, and is noted in THIRD_PARTY_NOTICES like GameTDB.
- **Security:** the protocol header, device-key auth for the app and session auth for the web all apply to any new endpoint.

## Approaches considered

**Direction (owner's choice: C).**
- **A, "Console, pushed":** monochrome, with a big focus panel and a rail of system codes.
- **B, "Boot screen":** dark, with one lime signature colour and a full-height cover.
- **C, "Shelf":** per-system colour and spines.

C won: it was the only one that looked clearly different.

**How status shows on the shelf (owner's choice: 1).**
- **1, status rail:** one row of marks per device, under the shelf.
- **2, spine caps:** the marks sit on top of each spine.
- **3, physical:** the case style shows this device's state, and the other devices are marked under the edge.

Option 1 won because it is the current table turned sideways: read across a row for one device, or down a column for one game. It scales to more devices by adding rows, and it uses the empty space under the shelf. Option 3's ghost cases for "not on this device" were offered as an add-on and are left out for now (see "Left out").

**Organisation (owner's choice: one shelf per emulator).**
- One shelf with all games and system sections.
- One shelf per emulator.
- Recently played first, systems mixed.

One shelf per emulator matches today's model: emulator state such as "Open", "not set up" and the Y emulator settings belongs to an emulator.

**Web (owner's choice: the Games page gets the shelf too).**

## The design

### Screen anatomy (app, 832×468 dp)

The screen is built top to bottom:

1. **Top bar (as today).** The wordmark is now the icon plus "TANDEM", followed by the bumper tabs. On the right are the device name, battery and clock.
2. **Emulator strip.** One line naming the emulators in module order: Eden, Dolphin, RetroArch, melonDS, Azahar, PPSSPP, Vita3K.
   - The current one is inverted, in the cursor style, and its state follows the name: "Dolphin · Open", "· 1 waiting" in amber, or "· not set up" in ink3.
   - Emulators that are not set up on this device stay in the strip, dimmed.
   - Selecting one still shows its shelf and rail, built from the server's games for it (games played on another
     device). This device's row reads "not on this device" throughout. There is no separate empty state. A emulator
     with no games at all shows one line: "No games for Dolphin yet."
   - A count sits at the right, for example "11 games".
3. **Shelf.** It holds a row of spines and the pulled-out card for the cursor game, resting on a 6 dp shelf edge in `--ink`. The mockups used heights of about 300 dp; that tuning is the builder's.
4. **Status rail.** One row per device, under the shelf edge, with the device's mark directly below each spine. Under the card, the marks sit centred in the card's width.
   - This device's row comes first, then the others by name.
   - Each row is labelled in a left gutter, in JetBrains Mono caps, shortened to fit. Any "AYN " prefix is dropped, and the name is cut to about 5 characters: "ODIN3", "THOR".
   - The marks are the existing `PresenceMark` / `Presence` mapping (`ui/state/Presence.kt`, `ui/kit/Marks.kt`), unchanged. The rail is a new *layout* of the existing states, not new states.
   - With more devices than fit, the rail shows the first 3 rows and the card lists the rest.
5. **Notice line.** One line between the rail and the hint bar. It takes today's `WaitLine` and `ReceivedLine`
   sentences unchanged:
   - waiting, in amber: "Close Dolphin to get the newer SSX 3 save from Odin 3. Nothing is written while Dolphin is open.";
   - received, in ink: "SSX 3 from Odin 3 is in place.".
   It shows the most recent one, and "+N more" in ink3 when there are several.
   A waiting delivery with no game key shows here too ("1 save waiting for Dolphin to close").
   When nothing is pending, the line is empty, not filled with filler.
6. **Hint bar (as today).** It shows "Synced N min ago", then the A/X/Y/B hints. The hint-bar API is unchanged.

### Spines

- **Size and title.** About 34 dp wide with a 5 dp gap. The system code (GC, GBA, DS, 3DS, SWITCH, PSP, VITA, …) is in BSC 700 at 9 sp at the top. The game name is rotated to read bottom to top, in BSC 700 at 14 sp, cut with an ellipsis.
- **Colour.** The spine colour comes from the game's *system*: the same derivation as `SystemMark` and `Systems.kt`, including RetroArch's core. It is not the emulator's.
  - The palette is a token per system for each theme, with text colours that meet WCAG AA contrast (4.5:1 on the spine).
  - Proposed light-theme values from the mockup: GC #34343A, GBA #4C5FA0, DS #D2CEC2 (dark text), 3DS #3C6E68.
  - The builder sets the rest (Switch, PSP, Vita, GB/GBC, NES, SNES, N64, PS1, Wii, and unknown) under these rules: no purple, nothing close to wait-amber or problem-red, and a neutral spine for unknown systems.
- **Unknown names.** A game whose name is unknown shows its key in mono on the spine and in the card, as `GameTitle` does now.

### The pulled-out card (the cursor game)

- About 262 dp wide and as tall as the shelf.
- **Cover:** about 112×156 dp. Without art, the cover slot shows the system code large in BSC on the system colour. No placeholder art.
- **Text:** a kicker line with system · emulator; the title in BSC 700 at 26 sp, up to 2 lines; and a one-sentence state, as `hereCopy` does today.
- **Device lines:** one per device, each with its mark, name and state words ("newest · 2 h ago", "older copy", "not on this device", "waiting for Dolphin to close", "conflict"). Version numbers are not shown: `GameRow` doesn't carry per-device versions, and adding them is not required.

### Movement and input (app)

**Keys:**
- **D-pad / stick left and right:** move the cursor one game. The cursor moves instantly; the tick haptic and the optional move sound stay.
- **Up and down:** previous or next emulator in the strip. The cursor returns to the game last selected on that emulator, or the first game.
- **A:** open the game's versions page, as today. **X:** sync now. **Y:** emulator settings. **B:** back. **L1/R1:** switch tabs. All unchanged.
- **Wrapping:** no wrap at the ends. At the end of the shelf the B-at-edge reject cue plays.

**Motion:**
- The card slides open: the old card's width collapses and the new one expands over about 120 ms, ease-out.
- Under the system "remove animations" setting it swaps instantly. No springs.

**Scrolling and order:**
- The shelf scrolls sideways to keep the card fully visible, with at least one spine of context on each side where possible. The scroll jump is instant and is not animated.
- Order follows today's Games page:
  - games with an open conflict first;
  - then by name;
  - unnamed games last, by key.

  The same sort is used on the app and the web. Today the app keeps the server's order, so it adopts this sort.

**Touch:**
- Tapping a spine moves the cursor to it; tapping the card opens the game.
- A horizontal swipe scrolls the shelf without moving the cursor. Tapping an emulator in the strip switches emulator.

### Web Games page

- **Same anatomy as the app,** in the web's Console tokens: emulator tabs (the existing `Tabs` level 2, which already exist, with "All" removed), shelf, rail and hint bar.
- **Keyboard:** left and right arrows move the cursor, up and down switch emulator, and Enter opens the game. The gamepad mapping matches the app.
- **Mouse:** the wheel and trackpad scroll the shelf sideways, clicking a spine selects it, and clicking the card opens the game.
- **Search:** the search box stays. While a query is typed, the shelf shows only the matching games in the current emulator, and an "N matches in other emulators" line links to them.
- **Narrow windows:** the shelf scrolls, and below about 600 px wide the card stacks its cover above its text. On phones the shelf stays usable by swiping. The old list layout is not kept as a fallback.
- **No "this device" on the web.**
  - Rail rows are ordered by device name.
  - The card sentence is written about the newest copy, not "here": "Newest on AYN Thor · 2 h ago", "Waiting to reach Odin3", "Conflict between AYN Thor and Odin3".
  - The hint bar offers Open (Enter / A) and Back (Esc / B) only. X and Y have no web action, so their hints are not shown.
  - There is no "Synced" line.
- The existing Games page tests are rewritten for the shelf. The shared presence logic (`deviceStates.ts`, `PresenceMark.svelte`) is reused, not duplicated.

### Cover art

**Endpoints.** Two new endpoints serve the same bytes:
- `GET /v1/art/{emulatorId}/{*gameKey}` with device-key auth, for the app;
- `GET /v1/web/art/{emulatorId}/{*gameKey}` with session auth, for the web.

**Responses.**
- **200:** an image (PNG or JPEG), with `Cache-Control: private, max-age=604800` and an ETag.
- **202 `art_pending`:** a fetch is queued or running. Sent with `Retry-After: 3` and `Cache-Control: no-store`.
  While the game is still focused, the client shows the no-art card and asks again once after the Retry-After.
  If the answer is still 202, it gives up until the game is focused again.
- **404 `no_art`:** the sources were tried and have nothing. Sent with `Cache-Control: private, max-age=86400`, so a
  client may remember the miss for a day. The server keeps its own 7-day miss record.

**Server behaviour.**
- **Lookup:** fetched on demand, at most once per game per 7 days for a miss, then cached under `/data/art/`. Size caps: 2 MB per image, 500 MB in total, with the least recently used removed first.
- **Background fetching:** the first request for a game queues a fetch and returns 202 at once, so it never blocks.
  A later request gets the image, or 404 if nothing was found.
- **Sources** are tried in order, and source matching is the builder's research:
  - libretro-thumbnails `Named_Boxarts`, by system plus No-Intro-style name, for RetroArch, melonDS and older systems;
  - GameTDB covers, by game ID, for GameCube/Wii (Dolphin keys are game IDs), DS, 3DS and Switch, where an ID can be derived.
  - Partial coverage is expected and fine.
- **Toggle:** `ART=false` (alias of `Tandem__Art`) turns off all outbound art fetches; everything then shows the no-art card.

**Clients.**
- The app keeps its own disk cache of images only, about 50 MB with least-recently-used eviction; 202 and 404 answers are never stored on disk, and known misses are kept in memory for the session only. It asks only for the focused game's art, plus 1–2 neighbours prefetched.
- The web relies on the browser's HTTP cache.

**Notices.** The art sources are added to THIRD_PARTY_NOTICES.

### Icon

- **The mark:** two squares on a 48-unit grid, taken from the mockup.
  - The filled one: x 5, y 14, 18 wide by 20 tall.
  - The outlined one: x 26.5, y 15.5, 15 wide by 17 tall, with a 3-unit stroke.
  - The builder may adjust the optical balance by 1–2 units but keeps the idea: one filled, one outlined, side by side.
- **Android launcher:** an adaptive icon.
  - Background: solid `#131312` (the dark ink).
  - Foreground: the mark in `#ECEBE6`, inside the 66 dp safe zone.
  - Monochrome layer: the mark, for Android 13+ themed icons.
  - Also round and legacy mipmaps, generated from the vector.
- **Notification small icon:** the mark as a white-on-transparent vector drawable, used for both channels in `Notifier.kt` in place of `android.R.drawable.stat_notify_sync`.
- **Web:** `favicon.svg`, with dark and light handled by an inline `prefers-color-scheme` media query, plus a 32 px PNG fallback and `apple-touch-icon` at 180 px. A `<link rel="icon">` goes in `index.html`.
- **Wordmark:** the mark at the cap height of the word, before "TANDEM", in both top bars.

## Left to the builder

- Exact spine, card and rail sizes and spacings once seen on the device, within the anatomy above.
- The full per-system palette, under the stated rules.
- How art sources match names and IDs, and the order sources are tried.
- How the app's art cache is implemented. Coil, which is already common in Compose, is acceptable if added as a dependency with its licence noted; so is a small hand-rolled cache.
- Whether the web shelf's sideways scroll uses native overflow or a transform.

## Left out (on purpose)

- Option 3's "ghost case" for games that are not on this device. The rail already says it. Revisit after living with the shelf.
- Art on spines, art backgrounds and art on other pages.
- Showing a version number per device in the card.
