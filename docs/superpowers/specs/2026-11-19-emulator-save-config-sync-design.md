# Emulator save & config sync: design

Status: draft for review · 2026-11-19
Working name: Tandem (folder name only; a GPL project called Tandem already exists, so pick a new name before
any public release).

## 1. Intent

The owner plays on several Android handhelds (AYN Thor, AYN Odin 3) and picks up whichever one is at hand.
They want:

- **Saves (and save states) to follow them**: save on the Thor, pick up the Odin, carry on.
- **Emulator configs never to be rebuilt by hand again**: after a wipe, corruption, or on a new handheld.
- **Control over config sharing**: some emulators' configs shared between similar devices, others kept
  separate per device, all of them backed up.
- **A web UI** on the server to see what's where and manage it.

It is a self-hosted service in Docker with an Android app on each handheld. A Steam Deck client is part 2 (see §12).

This is a **separate, standalone project**. It may reuse ideas and patterns from the owner's PKForge Sync
server (same stack, similar sync model), but it shares no code, repo, or dependency with PKForge.

## 2. Goals

1. Saves and save states upload within seconds of the emulator writing them, with no launcher involved.
2. A save or state only goes to devices that have that game installed. When a game is installed later, its
   newest save arrives then. The server keeps every save even if no device has the game.
3. Every device's config for every supported emulator is backed up with version history, whatever its sync
   setting.
4. Per emulator, the user can put devices into a **sync group**. Group configs are live: a change on any
   member goes to all members.
5. A config backup from one device can be applied to another, either **linked** (the target joins that
   device's group) or **copied** (a one-off copy that then drifts as its own config).
6. Nothing is silently lost. No clocks are trusted for ordering, and conflicts keep both versions.
7. A web UI shows devices, games, saves, configs, groups and activity, and does everything listed in §8.
8. A new or wiped device can be brought back with a guided checklist.

## 3. Non-goals (v1)

- **Not a game launcher or frontend.** It never starts games and replaces nothing in the user's launcher setup.
- **No ROM management.** ROMs are never uploaded or stored. The server knows which games a device has, not
  their files.
- **No save conversion between emulators** (e.g. mGBA ↔ RetroArch). A save belongs to one emulator.
- **No per-setting merging of configs.** Group sync is whole-file. The only exception is the path handling
  in §6.4.
- **No touch-overlay or UI-state sync** where the emulator keeps it in internal storage.
- **No Steam Deck, PC or iOS client in v1.**
- **No save inspection** (e.g. reading Pokémon data inside saves).

## 4. Anti-goals (failure even if it technically works)

- **A save on any device is overwritten by an older or different one without a copy existing on the server.**
- **A synced config breaks an emulator on another device** because of a path that only exists on the first
  device.
- **Files are written into an emulator's folder while it is running.**
- **It requires root, or replacing the user's launcher.**
- **Setup is so fiddly that Syncthing would have been easier.**

## 5. Constraints and known facts

- **Devices:** Android 13–15 handhelds, normally not rooted.
- **File access goes through [Shizuku](https://shizuku.rikka.app/)** (shell-level access, granted over
  wireless debugging).
  - Shizuku can read and write `/sdcard/Android/data/<pkg>` and shared storage.
  - It **cannot** reach app-internal storage (`/data/data/<pkg>`).
  - Shizuku stops on reboot and must be restarted via wireless debugging. The app must detect this and walk
    the user through it.
- **Folder permission links do not move between devices.** Emulators store user-picked folders (ROM dirs,
  some data roots) as Android permission links (`content://…/tree/…`). These only work on the device that
  created them, and Eden deletes links it can't use. Each device must grant its own ROM folders inside each
  emulator once.
- **Emulators write files on exit or while running.** Downloads may only be written while the emulator is
  not running on that device.
- **v1 emulator list:** Eden, RetroArch, Azahar, PPSSPP, Vita3K, Dolphin, melonDS. Researched storage
  facts are in §7.
- **Server:** Docker, single container, works on x64 and arm64 (like PKForge Sync).
- **Server stack:** ASP.NET Core on .NET 10, a Svelte 5 + Vite web app, SQLite by default with optional
  PostgreSQL, matching what the owner already builds and runs.

## 6. Core model

### 6.1 Things the server tracks

| Concept | Meaning |
|---|---|
| **User** | Account. Multiple users per server; first account is admin. Each user's data is private. |
| **Device** | A paired handheld belonging to one user. Has a name, platform (`android` now, `linux`/`windows` later), last seen, emulator versions, and its own device key. |
| **Emulator** | A supported emulator module (`eden`, `retroarch`, `azahar`, `ppsspp`, `vita3k`, `dolphin`, `melonds`). The module defines where files are and how games are identified (§7). |
| **Game** | Identified per emulator by the most stable ID available: Switch/3DS/Vita title ID, PSP/GC/Wii game ID, else ROM file name (RetroArch, melonDS). A game has a display name if known. |
| **Install record** | "Device D has game G for emulator E", reported by the device from scanning. |
| **Save item** | One syncable thing for a game: `(user, emulator, game, kind, slot)`. `kind` is `save` or `state`. `slot` is the state slot or save slot where the emulator has them. |
| **Config item** | One emulator's config bundle on one device: the set of config files the module lists (main config, per-game configs, input profiles, cheats/patches/shaders if present). |
| **Sync group** | Per user + emulator: a set of member devices that share one config bundle. A device is in at most one group per emulator, or none ("solo"). |
| **Vault item** | BIOS, keys (Eden `prod.keys`/`title.keys`), Azahar `sysdata`, firmware, RetroArch `system/` files. Stored once per user, offered to devices on request, never synced continuously. |
| **Version** | An immutable snapshot of a save item, config item/group, or vault item. Stores content hash(es), the version it was based on, the device it came from, server receive time, and size. |

### 6.2 Storage

- **File content** goes in a content-addressed blob store on the data volume (SHA-256 named), so identical
  files are stored once.
- **Metadata** goes in SQLite (or PostgreSQL).
- **Multi-file items** (an Eden save folder, a Dolphin GCI folder, a config bundle) are stored as a manifest
  of `relative path → blob hash`, so a version is always a complete set.

### 6.3 Ordering and conflicts (no clocks)

- Every upload names the version it was based on: the last version that device saw for that item.
  - **Base equals the server's current head:** it becomes the new head.
  - **Base is older:** it's a **conflict**. Both versions are kept, the item is flagged, a notification is
    sent, and nothing is pushed to other devices until it's resolved.
- Conflicts can be resolved in the web UI or on the device: keep A, keep B, or keep both. Keep both makes B a
  separate slot or copy so neither is lost.
- **A device must never overwrite a local save that hasn't been uploaded yet.** The local version is
  uploaded first, then the conflict rules apply.
- Before the app overwrites any local file, the file being replaced must already exist on the server as a
  version. Builder's call: also keep a small local backup copy on the device.

### 6.4 Config sync groups and paths

- **Group sync is whole-bundle.** Applying a group version to a member replaces that member's config files.
  There's one exception for **path values**:
  - **Plain paths under a known root** are turned into placeholders on upload and expanded per device on
    apply. Known roots are the device's internal storage, the SD card volume, and the emulator's own data
    root. Example: `/storage/1234-ABCD/ROMs/psx` becomes `{SD}/ROMs/psx`.
  - **Permission links (`content://…`) and paths outside known roots are device-local.** When applying a group
    version, the member keeps its own current value for that key. If it has none, the key is left empty and
    the new-device checklist tells the user to set that folder in the emulator.
- **Path detection is generic, not per emulator:** a value is a path if it starts with `/`, `content://` or
  `~`, or is a Windows drive path (for part 2). Each config **file format** (INI-style `key = value`,
  RetroArch `key = "value"`, YAML, XML, JSON) needs one small adapter that can list and rewrite values.
- **Live sync:** when a member's config bundle changes (the emulator saved settings), the device uploads it
  as a new group version based on the group head. The server then pushes it to the other members, who apply
  it the next time that emulator is not running.
- **Conflicts:** two members change the group config before seeing each other's change. This is handled at
  bundle level, same rules as §6.3. Builder's call: auto-merge when the two changes touch different files in
  the bundle.
- **Joining a group as "linked":** the device's current bundle is backed up as its last solo version, then
  the group version is applied.
- **"Copy" from another device:** that device's (or group's) version is applied once, and the target stays
  solo from then on.
- **Leaving a group:** the device keeps its current files and becomes solo.

### 6.5 What syncs where

| Item | Default behaviour |
|---|---|
| Saves, states | Sync to every device of the user that has an install record for that game + emulator. States are marked in the UI "may not load on a different emulator version". |
| Config bundle | Always backed up. Synced only within its sync group. |
| Cheats, patches, shaders | Part of the emulator's config bundle (they follow its group or stay solo with it). |
| Vault items | Backed up. Placed on a device only when the user asks, from the web UI or the setup checklist. |

## 7. Emulator modules (v1)

Each module defines:

- package names to look for
- how to find its data root
- save, state and config file locations
- how to list installed games
- what it can't reach

All facts below were checked against current source on 2026-11-19 unless marked *(inferred)*. **Verify each
one on a real device** in the first milestone (§11).

| Emulator | Package(s) | Data root | Saves / states | Config bundle | Installed games | Can't reach |
|---|---|---|---|---|---|---|
| **Eden** | `dev.eden.eden_emulator` (+`.nightly`, legacy `dev.legacy.eden_emulator`) | `Android/data/<pkg>/files`; NAND/SDMC/save dirs can be overridden by plain paths in `config.ini [Paths]` | `<nand>/user/save/0000000000000000/<profile>/<TitleID>/` (also check `user/save/account/<uuid>`); honour `save_directory` | `config/config.ini`, `config/custom/<TITLEID>.ini`, `config/input/*.ini` | Scan ROM dirs + `nand/user/Contents/registered`, plus save title IDs | Game list cache (not needed). `gamedirs` are permission links → device-local |
| **RetroArch** | `com.retroarch`, `com.retroarch.aarch64`, `com.retroarch.ra32` | `retroarch.cfg` at `Android/data/<pkg>/files/retroarch.cfg`; data root probed at `Android/media/<pkg>/RetroArch/`, `/sdcard/RetroArch/`, `Android/data/<pkg>/files/RetroArch` | Per `savefile_directory`/`savestate_directory` and sort options; `"default"` means the module must compute the path like RetroArch does | `retroarch.cfg`, `config/` (core/game/content-dir overrides, core options), `config/remaps/`, cheats, shaders | Playlists (`.lpl` JSON) + ROM dir scan | Cores (internal): record the core list for the checklist, never copy `.so` files |
| **Azahar** | `org.azahar_emu.azahar`, `io.github.lime3ds.android` | A folder the user picked; its location is internal, so **the app asks the user once** (or detects `config/config.ini` + `sdmc/`) | `sdmc/Nintendo 3DS/<id0>/<id1>/title/<high>/<low>/data/`; extdata alongside; states in `states/` | `config/config.ini`, `config/custom/*.ini` | Scan ROM dir + `sdmc/.../title/00040000/*` | Touch overlay, swap-screens toggle, theme; root and ROM dir links → device-local |
| **PPSSPP** | `org.ppsspp.ppsspp`, `org.ppsspp.ppssppgold` | The memstick folder; its location is internal, so **find it** by locating `PSP/SYSTEM/ppsspp.ini` (or ask) | `PSP/SAVEDATA/<GAMEID…>/`, states `PSP/PPSSPP_STATE/` | `PSP/SYSTEM/ppsspp.ini`, `controls.ini`, `<GAMEID>_ppsspp.ini` | ROM dir scan, `[Recent]` in ini | `memstick_dir.txt` |
| **Vita3K** | `org.vita3k.emulator` | `Android/data/<pkg>/files`; Vita filesystem defaults to `files/vita/`, movable via `pref-path` in `config.yml` | `<vita>/ux0/user/<id>/savedata/<id>/` | `config.yml`, `config/config_<TITLEID>.xml` | `ux0/app/*` | Overlay controls, UI language |
| **Dolphin** | `org.dolphinemu.dolphinemu` | `Android/data/<pkg>/files` (legacy `/sdcard/dolphin-emu`) | GC: GCI folders `GC/<region>/Card A/*.gci` (default) or `GC/MemoryCardA.<region>.raw`; Wii: `Wii/title/00010000/<id>/data/` *(inferred)*; states `StateSaves/<ID>.sNN` *(inferred)* | `Config/*.ini`, `GameSettings/<ID>.ini` | Rescan `ISOPath0..N` dirs | Touch overlay. `ISOPath*` are permission links → device-local |
| **melonDS** | `me.magnum.melonds` | Settings all internal | `.sav` next to the ROM by default, or the SRAM dir; states `.ml0`–`.ml8` | **Via melonDS's own settings export**: the user exports to a folder the app watches; that export is the config bundle. Restore = place the export and the user imports it in melonDS | ROM dir scan (`.nds`/`.dsi`) | Everything in internal storage. Export omits directory links (re-grant on each device) |

- **Game IDs:**
  - Where the module only has a ROM file name (RetroArch, melonDS), the game key is the file name without
    extension, plus the platform/core.
  - The app should also send the ROM file's CRC32 when cheap, so the web UI can show when two devices have
    "the same name, different dump".
- **The ROM folder for saves next to ROMs** (melonDS default, RetroArch `savefiles_in_content_dir`) is a
  device-local path. The user tells the app where their ROM folders are once per device, and the app can
  usually read them via Shizuku.

## 8. Web UI

Svelte 5 + Vite, served by the server at its root URL, sign-in with the user's account. Pages:

```
 Home · Games · Devices · Configs · Vault · Activity · Settings
┌ Home ──────────────────────────────────────────────┐
│ Continue playing: Pokémon Sword · newest on Thor, 2h ago │
│ Devices: Odin 3 ● online   Thor ● 3 pending        │
│ Needs attention: 1 conflict · Thor's Shizuku is off │
│ Activity: Thor uploaded Sword save · Odin joined Eden group │
└────────────────────────────────────────────────────┘
```

- **Home:** continue-playing list (recent games, which device has the newest save), device status,
  things that need attention (conflicts, devices with Shizuku off, failed syncs), recent activity.
- **Games:** every game the user has a save or install for. Per game:
  - which devices have it installed
  - saves and states with version history (device, time, size)
  - restore any version to a device, or download it (single file or zip)
  - upload a save from the PC
  - resolve conflicts (side by side, keep one or both)
- **Devices:**
  - per device: last seen, online state, Shizuku state, emulator versions, what it syncs, pending changes,
    storage used
  - pair a new device (QR + 8-character code, like PKForge Sync)
  - rename or revoke a device
- **Configs:** per emulator:
  - groups and solo devices
  - create, join and leave groups
  - version history per bundle
  - **diff viewer** between any two versions, or between two devices
  - **edit in browser:** text edit of a config file, saved as a new version and pushed to the device or group
  - **apply to device:** choose any device's or group's version, then "linked" or "copy"
- **Vault:** BIOS, keys, firmware, Azahar sysdata, RetroArch system files.
  - Upload from PC or from a device.
  - Send to a device.
  - Shows which devices have which files (by hash).
- **New-device setup:** a checklist for one device:
  1. emulators to install
  2. RetroArch cores to download (from the recorded core list)
  3. ROM folders to grant in each emulator
  4. vault items to place
  5. Restore: saves for installed games, and the chosen config per emulator (linked or copy)

  Ticks itself off as the device reports progress.
- **Activity:** full log of uploads, downloads, conflicts, group changes and restores; filterable by device,
  game and emulator.
- **Settings:**
  - **Account:** password, plus admin user management and the registration toggle.
  - **Retention:** keep the last N versions per save and per config (defaults are the builder's call).
    Conflict versions and versions the user pins are never pruned. Storage use is shown per emulator and game.
  - **Off-server backup:** scheduled export of the database and blobs to a mounted folder (NAS) or an
    S3-compatible bucket.
  - **Notifications:** ntfy and Discord/webhook targets for conflicts, failed syncs, Shizuku off for a long
    time, and backup failures.

## 9. Android app

- **Kotlin, native Android** (builder's call on UI toolkit). Talks to the server over HTTPS, holding a device
  key from pairing.
- **Setup:**
  1. server address and sign in, or pairing code/QR
  2. Shizuku check, with step-by-step help
  3. detect installed emulators and their data roots; ask for Azahar's folder and the ROM folders where needed
  4. per emulator: back up only, join a group, or copy from another device
  5. first sync
- **Watching for changes** (no launcher):
  - **Detect emulator running/closed.** Builder's call: through a Shizuku process list or Usage Access.
  - **Detect file changes.** Primary: file-change notifications on the save/config paths via a Shizuku
    process. Fallback if those don't fire for other apps' writes: check timestamps and sizes of the module's
    known paths every few seconds while an emulator runs, and once when it closes.
  - **Debounce:** an item uploads once its files have been unchanged for ~2–3 s (tunable). Multi-file items
    upload as one complete set.
  - **Config bundles** upload when the emulator closes. Most emulators write settings on exit.
- **Receiving changes:**
  - A foreground service keeps a live connection to the server (server-sent events, as in PKForge Sync),
    with a periodic check as fallback.
  - Incoming saves, states and configs are written only while that emulator is not running.
  - If an emulator is open and a newer save is waiting for one of its games, show a notification: "Newer
    Pokémon Sword save from Thor: close and reopen the game to get it".
- **Installed games:** rescan ROM folders and emulator stores at startup, when an emulator closes, and
  periodically. Report changes to the server.
- **On-device UI:** status per emulator, pending items, recent activity, conflicts (resolve simple ones on
  the device), Shizuku state.
- **Shizuku off (e.g. after reboot):** persistent notification plus server status, so the web UI shows it.
  Changes are queued once access is back.
- **Offline:** everything works locally. Changes queue and upload when the server is reachable.

## 10. Server and deployment

- One Docker image with the server and the web app, for x64 and arm64, data in `/data`. It works behind a
  reverse proxy (Traefik, as the owner uses). Settings are environment variables in the style of PKForge
  Sync: `PUBLIC_URL`, `ALLOW_REGISTRATION`, `PUID`/`PGID`, `DATABASE_PROVIDER`, `TRUST_FORWARDED_HEADERS`,
  SMTP optional.
- **API:**
  - versioned (`/v1/...`)
  - device auth via the device key, web auth via the user session
  - live updates via server-sent events
  - uploads and downloads stream blobs, and upload only the blobs the server doesn't already have (by hash)
- **Admin CLI** via `docker exec` (create user, reset password), as in PKForge Sync.
- **The protocol must not assume Android:** device platform, path placeholders and module IDs are generic,
  so part 2 can add a Linux client without server changes.

## 11. Approaches considered

| Approach | Trade-off | Decision |
|---|---|---|
| **A. Background sync app** watching emulator folders | Works with any launcher. Can't block a game from starting on an older save, so it relies on fast upload/push and a "newer save waiting" notification | **Chosen.** Owner wants no launcher and immediate upload on save change |
| B. Own launcher (Argosy-style) | Most reliable pre-launch fetch, but replaces the user's launcher and adds frontend scope | Rejected: owner doesn't want a launcher |
| C. A plus an optional launch step through the user's launcher | Adds a pre-launch fetch for games started through it | Not in v1 (owner prefers watching only). Could be revisited later |

**Config sync granularity:**
- Whole-file with generic path handling: **chosen**. Keeps each emulator cheap to add.
- Per-setting "device-local keys" lists: rejected. Too much work per emulator.

**First milestone: a device spike** before building the full app. On the Odin 3 and the Thor, prove:
1. Shizuku read/write of each emulator's paths in §7.
2. Whether file-change notifications fire for other apps' writes, or whether polling is needed.
3. Detecting emulator start/close.
4. Finding Azahar's and PPSSPP's folders.

The answers fix §9's watching design.

**Device test results: AYN Thor, Android 13, tested over adb as the shell user (= Shizuku access), 2026-11-19:**

- **Access:** the shell user (groups incl. `ext_data_rw`, `sdcard_rw`) can list, read and write
  `Android/data/<pkg>` for Eden and Dolphin. It can't read `/data/media/0`, which isn't needed.
- **File watching works for other apps' writes.** An Eden in-game save produced inotify events on the shell's
  `/sdcard/...` view of the folder.
  - Only **modify** events arrive: 143 of them over about 3 s for a 2 MB `data.dat`. There's **no
    close-after-write** event.
  - So "upload when finished" must use a quiet-period debounce (no changes for N seconds), never a close event.
  - inotify isn't recursive, so the app adds a watch on every subfolder and on new folders as they appear.
    The Thor needed 93 watches.
- **Eden writes the save at the moment of the in-game save**, while still running, not on exit. Saves can
  upload mid-session. Downloads must still wait until the emulator is closed.
- **Foreground detection:** `dumpsys usagestats` (readable by shell) shows `ACTIVITY_RESUMED`/`STOPPED`
  per package with times, e.g. Eden's `EmulationActivity`. `pidof <pkg>` shows whether the process is alive.
  Note that Eden kept its process alive in the background long after the game was left.
- **Owner's real layout on the Thor:**
  - ROMs in `/sdcard/Games/<platform>/`
  - Eden `save_directory=/storage/emulated/0/Games/switch-saves` (plain path), `gamedirs` = a `content://`
    link
  - Azahar root at `/sdcard/Games/3ds/user` (has `config/`, `sdmc/`, `sysdata/`)
  - melonDS `.sav` files next to the ROMs in `/sdcard/Games/nds`
  - no SD card
  - Saves are owned by different app users (`u0_a109` for the shared folder vs `u0_a117` in `Android/data`),
    and the shell reads both.
- **Eden variants installed:** `dev.eden.eden_emulator`, `dev.eden.eden_emulator.combo`, and the dual-screen
  fork `dev.igawa6.edenduo` (same data layout). The module should cover all three package names, and the user
  picks the active one.
- **Not yet checked:**
  - PPSSPP and Vita3K have never been opened on the Thor.
  - RetroArch (1.22.2, `com.retroarch.aarch64`) was opened briefly and created **no** `Android/data` folder.
    Its config is likely in internal storage until `Android/data/<pkg>/files/retroarch.cfg` exists. To verify:
    create that file through Shizuku and confirm RetroArch then uses it, and test Odin 3 behaviour.
  - Shizuku itself isn't installed yet. It provides the same shell access, but confirm once it is.

**Release scope (owner's decision):**
- v1 ships **all at once**. It's released only when everything in §6–§10 works.
- Work can still be built and tested internally in any order. There's no partial public release, so the
  protocol must cover config groups, the vault and the setup checklist before the first release, not later.

## 12. Part 2: Steam Deck (out of scope here)

- A Linux background service: a user systemd unit, working in Desktop and Gaming Mode.
- Path profiles for EmuDeck, RetroDECK and plain Flatpak installs.
- Possibly a Decky plugin for status.
- Uses the same server and protocol. Deck configs would normally stay solo or in their own groups.

## 13. Calls made after design review

These were settled by Claude, not the owner. The owner can override any of them.

- **"Keep both" on emulators with one save per game:**
  - The device gets only the chosen version.
  - The other version stays on the server as a named **variant** of that save item, shown in the web UI,
    downloadable, and restorable later.
  - `slot` in §6.1 means a real emulator slot only. Variants are a server-side label.
  - "Continue playing" shows the current version.
- **First sync of a device that already has saves:**
  - No flood of conflicts. Setup shows one **review screen** listing every game whose device save differs
    from the server's, with "use this device's" or "use the server's" for all at once or per game.
  - Both copies are always kept as versions, so the choice is reversible.
  - File modification times may be shown as a hint only.
  - The same applies to config bundles when joining a group.
- **RetroArch/melonDS game keys:**
  - **Saves:** platform + ROM file name (no extension). Switching cores, or different cores on two devices,
    keeps sync working.
  - **States:** platform + ROM file name + core. States only sync between devices using the same core.
  - The core is recorded on every version.
- **Emulator profiles** (owner confirmed they use one profile each):
  - v1 assumes **one profile/user per emulator per device**: Eden profile UUID, Vita3K user ID, Azahar
    id0/id1. The module finds the active one on each device and maps between them, so saves are keyed by
    game only.
  - Multiple profiles are out of scope for v1. The schema should leave room for a profile field later.
- **Two variants of one emulator on a device** (Eden stable + nightly, RetroArch aarch64 + ra32): the user
  picks **one active variant per emulator per device** during setup. The others are ignored. The choice can
  be changed later in the app.
- **Vault encryption:**
  - Vault items are stored like other blobs, unencrypted on the owner's own server.
  - Off-server backups (NAS/S3) can optionally be encrypted with a passphrase set in Settings.
  - Losing the passphrase means losing that backup, and the UI says so.

## 14. Left to the builder

- Database schema details, API shapes, blob chunking, and how to stream large items (Switch firmware,
  big save folders).
- Android UI toolkit and app architecture.
- Process detection method and polling intervals.
- Retention defaults.
- Auto-merging config conflicts when the two changes touch different files.
- Local on-device backup copies.
- Web UI visual design is **not** left to the builder alone. Before any web or Android UI is built, mock up
  the main web pages (Home, Games, Configs, New-device setup) and the Android app's main screens, and have
  the owner review them. The owner has asked for this.
- How the module definitions are expressed (code vs data files).
