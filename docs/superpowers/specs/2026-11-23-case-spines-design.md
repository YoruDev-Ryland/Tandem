# Case spines: shelf spines that look like the real game cases

Status: approved by the owner (2026-11-23). Builds on the shelf redesign
(`docs/superpowers/specs/2026-11-22-shelf-redesign-design.md`), which this changes only where it says so.

## Intent

The Library shelf (app) and the Games shelf (web) show one spine per game. Today every spine is a flat block in its
system's colour with the system code and the title. The owner wants the shelf to look like their real shelf of
cases: each spine looks like that console's retail case spine.

- **Where a real spine exists, use it.** A scan of the actual spine (GameCube, Wii, DS, 3DS) is the goal; the
  SSX 3 scan showed this is possible.
- **Where no real spine can be had, build one in that console's case style.** The console's band, then a slice
  from the middle of the game's front cover (the owner chose the middle over the left edge after seeing both),
  with the title.
- **Consoles whose real spines are plain stay plain.** Switch is red with a white title; no cover art.

Success: a shelf of GameCube games reads as a shelf of GameCube cases; a RetroArch shelf of GBA games reads as a
row of fan-made GBA cases; the sync status under every spine (the status rail) works exactly as today.

## Goals

1. Real spines for GameCube and Wii (Dolphin), DS (melonDS) and 3DS (Azahar), cut from GameTDB's full case scans.
2. Built spines for every other system that has cover art: console band + middle slice of the front cover + title.
3. Plain spines where the real case is plain (Switch), and the current system-colour spine, at the new size, for
   games with no art.
4. Spines in real case proportions: thinner than today, so more games fit on a shelf.
5. Same look on the app and the web.

## Non-goals

- Copying console logos. Console names are set in our own type (`GAME BOY ADVANCE`, `GAMECUBE`); the real logos
  appear only where they are part of a real spine scan.
- Back covers, 3D boxes, or any change to the card's cover (it already shows the whole front cover, fitted).
- New art sources beyond GameTDB and libretro-thumbnails.
- Spines for the Configs, Vault or other pages.

## Anti-goals (a failure even if it works)

- A title you can't read. Every built spine keeps its title legible over any art.
- A sync state you can't see. The status rail stays under every spine, one column per spine, as today.
- A slow or janky shelf: spines must not pop in one by one in a way that jolts the layout, and moving the cursor
  stays instant.
- Anything the design rules ban: no purple, gradients, glow or springs; amber means wait, red means problem.

## What the owner decided

- Real spines where they exist; built spines elsewhere; plain where the console's case is plain.
- The built spine's art is a slice from the **middle** of the front cover.
- The console band carries the console name; the rest of the spine is the game's art.
- Switch: fully red, white title, no art.
- GBA and other cartridge systems follow the fan-made case style (like DS: console name in a box at the top, game
  art below).

## Approaches considered

**Where the real spine is cut out of the scan.**
- *Server cuts it (chosen).* The server downloads the full scan once, finds the spine strip, saves it as its own
  image and serves it like a cover. The clients get a small ready image. The cutting logic lives in one place.
- *Clients cut it.* Each client downloads the whole scan (about 1024×680) and crops. Twice the logic (Kotlin and
  TypeScript), about 20 times the bytes per spine.
- *Fixed coordinates only.* Cheapest, but the scans are not aligned the same way. In the mockup, fixed cuts left
  slivers of the neighbouring art on several spines.

**How a built spine is drawn.**
- *Clients compose it (chosen).* The band, the art slice (the cover image placed and clipped), the darkening and
  the title are ordinary UI on each client. The text stays sharp at any density, follows our fonts, and needs no
  image library on the server.
- *Server renders an image.* One look everywhere, but titles are baked into pixels, fonts must be on the server,
  and every wording change means re-rendering.

These are the builder's calls, made for the owner. They don't change what the owner sees.

## Design

### Which spine a game gets

In order, first that applies:

1. **Real spine:** the system has scans (GC, WII, DS, 3DS), the game's GameTDB id is known, and the server has a
   spine image for it.
2. **Plain spine:** the system's case spine is plain (SWITCH).
3. **Built spine:** the game has a front cover (the existing art endpoint).
4. **Fallback spine:** today's system-colour spine, at the new proportions, with the console band when the system
   is known.

While a game's art or spine is still being fetched (202 art_pending), it shows its fallback spine and swaps in
place when the image arrives. The spine's width does not change on the swap, because width comes from the
system, not the image.

### Proportions

A spine's width is its height times the case ratio of its system. The height is the shelf height, as today.

| System | Ratio (width / height) | Source |
|---|---|---|
| GC, WII | 0.074 | Real case: 14 mm spine on a 190 mm case |
| DS, 3DS | 0.10 | Real case, measured from the scans |
| SWITCH | 0.065 | Real case |
| PSP | 0.078 | UMD case |
| GBA, GBC, GB, NES, SNES, N64, MD, PSX, unknown | 0.10 | Fan-made style, like DS |

At the Thor's 1080p shelf (about 495 px tall), GameCube spines are about 36 px wide and DS spines about 50 px.
Today's spines are 78 px.

### Real spines (server)

- **Source:** GameTDB full case scans, `https://art.gametdb.com/{wii|ds|3ds}/coverfullHQ/{region}/{ID}.{png|jpg}`.
  GameCube games are under `wii`. Regions are tried in the same order the existing GameTDB cover source uses.
- **Game ids:**
  - Dolphin: the key is already the id.
  - DS: the app reads the 4-character game code from the ROM header (offset 0x0C) and reports it with the install.
  - 3DS: the app reads the product code from the ROM's NCCH header ("CTR-P-EKJE" → EKJE) and reports it with the
    install.

  A new optional `GameId` on `InstalledGame`, stored on `InstallRecord`, is the same pattern as `System` added on
  2026-11-23.
- **Cutting:** the spine sits in the middle of the scan, between the back and the front cover. The server finds it
  by looking for the two strong vertical edges nearest the centre, inside a window sized by the system's ratio. If
  it can't find them confidently, it cuts at the system's expected position and trims a few pixels off each side
  so no neighbouring art shows. The result is saved as its own PNG in the art cache.
- **Endpoint:** `GET /v1/web/spine/{emulatorId}/{*gameKey}`, with the same auth, protocol header, status codes,
  caching, miss markers and `ART=false` switch as `/v1/web/art`. 404 `no_spine` sends the client to the next kind
  of spine.

### Built spines (clients)

- **Console band:** the top 27% of the spine (20% for Switch). A solid block in the console's band colour with the
  console name set vertically in Barlow Semi Condensed Bold.
- **Art:** the game's front cover, scaled to the spine's height and centred horizontally, clipped to the spine.
  This is the middle slice. A flat 42% black layer sits over it.
- **Title:** white, Barlow Semi Condensed Bold, reading **top to bottom** (as printed on North American cases),
  below the band. It is cut with an ellipsis when it doesn't fit.

  Real spines, by contrast, carry their own printed title. Fallback spines also read top to bottom (owner,
  2026-11-23).
- **Band per system:**

  | System | Band colour | Name on the band | Name colour |
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
  | GC, WII, DS, 3DS without a real spine | GC #1B1A17, WII #E4E2DA, DS #F4F2EA, 3DS #F4F2EA | GAMECUBE, Wii, NINTENDO DS, NINTENDO 3DS | light on dark, dark on light |

- **Plain Switch:** #E60012 all over, a 20% band carrying `NINTENDO SWITCH` in white, and the title in white below
  it.

### Cover art for every spine

Today the clients ask for art only for the selected game. Spines now need it for every game on the visible shelf.
- Each client asks for the spine and cover of the visible spines plus one screen's worth either side, at most 4
  requests at a time.
- Images go through the existing caches: the app's ArtCache and the web's blob cache, both of which honour the
  server's Cache-Control.
- A 202 is retried after its Retry-After, as the card already does.

### Status rail and cursor

- The rail keeps one column per spine, each column as wide as its spine.
- Rail marks stay the current size (8 dp square). They are centred under spines that are now 36–50 px wide, so
  they still fit.
- The pulled-out card is unchanged.
- The cursor's focus treatment on a spine stays as today.

### Both themes, motion

Spines are physical objects and look the same in the light and dark themes. Only the shelf edge and the rail follow
the theme, as today. The image swap from fallback to real or built spine is an instant replace, with no fade under
reduced motion and a short fade otherwise.

## Calls made after the builder check (the builder's, not the owner's)

- **GameId for games already known:** the app re-reports a device's whole install list on every scan, so DS and
  3DS ids arrive on the first scan after the app update. No backfill. Until then those games show built spines.
- **ROM formats:** best effort. The app reads the id from whatever the module already opens:
  - DS: `.nds`, game code at 0x0C;
  - 3DS: `.3ds`/`.cci`/`.cxi`, the product code in the first NCCH header, as `AzaharModule` already reads the
    program id there.

  Anything else (`.cia`, encrypted, unreadable) reports no id and gets a built spine.
- **Fetch bursts:** spine fetches go through the existing `ArtFetcher` queue, one outbound fetch at a time, with the
  same miss markers. `/v1/web/spine` answers 202 `spine_pending` (Retry-After 3) exactly as art does. A spine fetch
  is separate from the cover fetch. The cover is fetched only when the client asks for it, which for a real-spine
  game is only when it becomes the selected game (the card), or if the spine turns out to be `no_spine`.
- **Which games ask for a spine:** `GameRow` gains `spine: bool`, true when the system is GC, WII, DS or 3DS and a
  GameTDB id is known (Dolphin keys always; DS and 3DS once reported). Clients only call `/v1/web/spine` for those,
  so no game costs a useless 404.
- **Fitting a real spine:** the cutter's output is normalised. It is scaled to a fixed height (680 px) and then
  cropped evenly from both sides to the system's ratio. That trim is wanted anyway, as it removes slivers of the
  neighbouring art. Clients draw the image to fill the spine exactly.
- **Fallback spine:** the body keeps today's system colour from the palette, and the band uses the band table.
  This gives, for example, a cream GBA band over the blue GBA body, which is the fan-made look. Switch games
  always get the plain red spine, art or not, so the grey Switch palette entry is no longer used.
- **Variable widths and navigation:** the cursor still steps one spine at a time. Scroll-into-view and the
  prefetch window are measured in spines (the visible ones plus 12 either side). No behaviour depends on a fixed
  78 px width except layout maths, which moves to per-spine widths.

## What's left to the builder

- **Exact numbers:** the edge-finding thresholds; the region order for GameTDB scans; how many pixels to trim.
- **App-side image loading and layout:** how the app clips and draws the art slice (Compose), and how the web
  does it (CSS backgrounds or `<img>` with object-position).
- **Prefetch:** the exact prefetch window and concurrency within the limits above.
- **Storage:** migration names and storage for `GameId`.

## Owner's answers (2026-11-23)

1. **Real spines stay exactly as scanned.** No title is laid over them, even when the printed one is small.
2. **Fallback spines read top to bottom**, like built spines. Today's bottom-to-top direction goes away on the
   shelf.
