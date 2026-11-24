# Storage page, moving the files, and changing email (0.1.4)

## Intent

The owner runs Tandem on a Docker host with plenty of room and small saves. They want to **see** what Tandem holds
(graphs, not just a list), to have the **option** of putting the stored files on another disk later without running
commands, and to change their account email from the web UI. The move is a future-proofing feature: it must be safe
above all, then low-interruption; it will rarely run.

## Goals

1. Settings › Storage shows Tandem's storage as graphs, with three selectable views (Map, Bars, Blocks), Map default.
2. An admin can move the stored files (blobs) to another folder from that page. Devices keep working; uploads pause
   only for a short final hold, and handhelds hold their changes and send them afterwards.
3. Settings › Account gets "Change email"; the admin CLI gets `change-email <old> <new>`.

## Non-goals

- Moving the database. It stays in the data directory (`/data`). SQLite on a network share can corrupt itself.
- Moving the cover-art cache, the title-name cache or upload temp files. They stay under `/data`.
- Adding Docker mounts from inside the server (impossible); the page explains the one line to add instead.
- A "clear art cache" button, quotas, per-user limits.
- Admins changing another user's email from the web UI (the CLI covers it).

## Anti-goals (a failure even if it works)

- Any path where a save that exists on the server or a device is lost or overwritten: a half-copied file used as the
  real one, files written into the container's own layer (gone on the next Watchtower recreate), or the server
  starting over in an empty folder because a NAS was not mounted.
- The old copy being deleted automatically.
- Handhelds showing an error (red) for the hold; it is a wait (amber).
- Generic chart-library look: no accent colours, gradients, rounded cards, pills, legends floating in boxes.

## Constraints

- Server ASP.NET Core / .NET 10, EF Core (SQLite + Postgres), single instance. Web Svelte 5 + Vitest. App Kotlin +
  Compose + Robolectric.
- UI tokens only (`web/src/styles/tokens.css`): greys, `--wait` amber = wait, `--fault` red = problem. Console spine
  colours from `web/src/lib/spines.ts` (`SPINES`, body colour) are the only other colours. Both themes, reduced motion.
- Fonts: Barlow, Barlow Semi Condensed (headings), JetBrains Mono (sizes).
- Controller/keyboard navigation (`lib/input`): arrows/d-pad, A (Enter), B (Escape), L2/R2 (`{` `}`) for sub-tabs.
- Never delete what can't be recreated without the user asking. Never write blobs outside a verified location.
- Older app versions must stay safe (they see a failed upload and retry on their next scan).

## Approaches considered

**Moving the files**

- A. *Live move from the page* — chosen, with a hold: copy while running, then a short hold for the last changes,
  switch, release. Devices treat the hold as wait-and-retry. No commands for the owner.
- B. A `FILES_DIR` setting plus README steps (stop, copy, edit, start). Least code; all manual and unchecked.
- C. B plus an offline `move-files` CLI command. Safe and small, but needs commands, which the owner rejected.

The owner picked A after asking for the device hold.

**Storage graphs**

Three directions were mocked with real data (`.superpowers/brainstorm/…/storage-directions.html`): A Bars (capacity
bar, disk ruler, ranked bars), B Blocks (memory-card grid, 1 block = a unit of MB, spine colours), C Map (treemap of
emulators then games). The owner chose **C**, then asked for all three as a selectable view. Map is the default.

## Design

### 1. Storage data (server)

Extend `GET /v1/settings/storage` (any signed-in user; `RetentionEndpoints.StorageAsync`). Keep the existing fields
(`totalBytes`, `byEmulator`, `topGames`) so nothing that reads them breaks, and add:

- `games`: **every** game of the user (not only the top 10): `emulatorId`, `gameKey`, `displayName`, `bytes` (unique
  blobs across all its save/state versions), `currentBytes` (unique blobs of its items' head versions), `olderBytes`
  (blobs only in non-head versions), `versions` (count of versions), `kinds` (which of Save/State it has).
- `byKind`: `saves`, `states`, `configs` (config versions + raw config uploads), `vault`, `lostAndFound` (blobs in
  `LostItem` manifests). Each is the unique bytes referenced by that kind; a blob shared between kinds counts in each
  (as today's rows do), and `totalBytes` stays the true unique total.
- `olderBytes`: unique bytes referenced only by non-head save/state versions (what retention could eventually free).
- Scope: everything above is **the signed-in user's own** data, for admins too. Server-wide numbers live only in
  `server` and are labelled as all accounts on the page.
- `server` (admins only, `null` otherwise): `filesBytes` (all accounts' stored files: what a move copies), `diskTotalBytes`, `diskFreeBytes` (of the files location), `minFreeBytes`,
  `artBytes` (size of `/data/art`), `databaseBytes` (SQLite: the db file + `-wal`; Postgres: `null`, not shown),
  `filesLocation` (path), `defaultLocation` (bool), `move` (the move status, §3, or `null`).

Sizes are computed from the database (`UserBlobs`, manifests), not by walking the disk, except `artBytes` and
`databaseBytes` (file sizes, cheap) and the disk numbers (`IFreeSpaceProbe` gains total).

### 2. Storage page (web)

`web/src/pages/settings/Storage.svelte` becomes a shell: heading row with the view switch, the chosen view, then the
shared lower section.

**View switch**: a three-way segmented control `Map · Bars · Blocks` to the right of the "Storage" heading. L2/R2 (or
`{`/`}`) cycles it. The choice is saved per account as a user setting `storage.view` (`map` | `bars` | `blocks`) via a
small `GET/PUT /v1/settings/prefs/storage-view` (or the existing settings pattern), so it follows the user across
browsers. Default `map`.

**Map (default)**

- Squarified treemap (Bruls et al.) of the top level, in a fixed-height area (300 px desktop; full width).
  - Admin top level: one tile per emulator, plus Cover art, Database, Configs, Lost & found, Vault (each only when
    non-zero). Non-admin: one tile per emulator, plus Configs and Vault.
  - Emulator tiles: body colour of the emulator's main console (`SPINES`), with its text colour; system tiles use the
    hatch (cover art) / dots (database) / plain grey patterns from the mockup.
- Inside an emulator tile, its games are laid out too (nested squarify). Games under ~2 % of the map's area are merged
  into one "N more" tile.
- Click or A on an emulator tile zooms the map into that emulator (its games fill the area); a trail
  `All › Eden` appears above; B / Escape / the trail goes back. A on a game tile opens the game page. Arrow keys and
  the d-pad move between tiles (nearest tile in that direction by centre).
- Hover or cursor focus shows a tip: game name, emulator, current size, "N older versions, X MB" when there are any.
- Tiles too small for a label show none; the tip still works.

**Bars**: the mockup's direction A: big total, one capacity bar by kind with a legend, the disk ruler (admin only:
other data, Tandem's mark, the `MIN_FREE_BYTES` tick in amber), emulators ranked with bars in spine colour and Export,
largest games with current solid / older hatched (top 10, with "Show all").

**Blocks**: the mockup's direction B: a grid of square blocks, coloured by emulator, then configs/lost (grey), cover
art (hatch), database (dots), then empty blocks to a round row. One block = 1, 10, 100 or 1000 MB, the smallest that
keeps the used blocks at or under 200; the heading says "1 block = 10 MB". Blocks holding older versions are notched.
Hover/cursor on a block shows the same tip as the Map. A key list on the right with block counts; the focused group
is raised.

**Shared lower section** (all views):

- "By kind" list with sizes.
- Older versions line: "Older versions take 15.2 MB, most of it Trails in the Sky." with a link to Retention.
- "Export everything" (existing), and per-emulator Export (Bars rows; Map's zoomed trail; Blocks' key list).
- Admin: **Files** block: location path, free space on that disk with the 1 GB floor, and **Move files…**. While a
  move runs, the block shows its progress instead (§3).

**Empty state**: "Nothing is stored yet." and no graph.

### 3. Moving the files (server + web)

**Location model.** A server setting `files.location` (absolute path) in `ServerSettings`; absent = the default
`<DataDirectory>/blobs` (today's layout, so existing installs need no migration). A singleton `FilesLocation` holds
the current root, loaded at startup, and is what `BlobStore`, `UserDeletion`, `BackupService`, `BackupRestore` and
the pruner use instead of building `DataDirectory/blobs` themselves. The layout under the root is unchanged:
`<root>/<userId:N>/<aa>/<bb>/<hash>`.

**Marker.** Every location holds `.tandem-files` (JSON: a location id GUID). The same id is stored with
`files.location` in the setting; a missing or different marker means the folder is not the one the server used. It is written when a
location is first used (the default location gets one at startup if missing). At startup, for a non-default location,
the server checks the marker matches; if the folder or marker is missing, the server does **not** create it: blob
uploads and downloads answer 503 `files_unavailable`, the Home attention list shows a red line "The stored files
folder /x is missing. Nothing is stored until it is back." (admin), and a re-check runs every minute.

**Checks before a move** (`POST /v1/admin/storage/move/check` with `{ path }`, also run again at start):

- absolute path; not inside the current location, and the current location not inside it; not inside
  `DataDirectory` other than the default location itself (moving back to the default is allowed);
- exists or can be created; writable (create, rename, list and delete a probe file);
- free space ≥ the bytes to copy + `MIN_FREE_BYTES`;
- **a real mount** when running in a container (`/.dockerenv` exists): the longest mount point in
  `/proc/self/mountinfo` that contains the path must not be `/`. Refused otherwise with: "This folder is inside the
  container and would be lost on the next update. Mount a folder there first:" followed by the compose line
  `- /your/host/folder:<path>`.
- empty, or holding only a marker from this server with no blobs (a previous cancelled move). Refused if it holds
  other files.

The check returns each item's result (ok / problem text) and the bytes to copy, for the dialog.

**The move job** (`POST /v1/admin/storage/move` `{ path }` → 202; `GET …/move` status; `POST …/move/cancel`):
one at a time, runs in the background. It does **not** hold `MaintenanceLock` for the copy (that would hang device
forget and account deletion for as long as the copy runs). Instead, while a move runs:

- the pruner skips its runs, and a scheduled backup waits for the next check (both resume after the move);
- the few actions that delete blob files (account deletion, device forget's blob cleanup if any, remove-old) answer
  `503 server_moving` at once; the app already retries forget, and the web shows the message;
- every blob write goes through one async gate in `BlobStore`: writes take the shared side, the hold takes the
  exclusive side.

1. *Copy*: write the marker; for every file under the current root, copy to the same relative path under the target
   via a temp name, fsync, verify SHA-256 against the file name (blobs are named by hash), rename. Uploads continue
   into the old location. Progress: files and bytes done/total.
2. *Hold*: set the hold flag. Blob PUTs from devices and web file uploads (the requests that send file bodies) answer
   `503 server_moving` with `Retry-After: 15`. Manifest uploads that only reference blobs already stored are still
   accepted. Blob writes the server makes itself (config expansion and edits in `ConfigTransform`) are not refused:
   they wait on the gate, which the hold takes exclusively once in-flight writes finish (abort the move if that takes
   over 60 s). Under the exclusive gate, copy and verify anything added since step 1.
3. *Switch*: store `files.location` = target, swap `FilesLocation`, release the hold. Downloads read from the old
   location until the swap and the new one after; both hold the same files.
4. *Done*: the status keeps `oldLocation` and its size. **Remove old copy** (`POST …/move/remove-old`) deletes the
   old files (never the database or anything outside the old root); only on request.

Failure or cancel at any point before the switch: release the hold, leave the setting unchanged, delete the partial
copy in the target (only the files this job wrote, plus its marker), and report the error. A server restart mid-move
is the same as a cancel: on startup, a move recorded as running is marked failed and its partial copy cleaned.

**Devices during the hold.**

- Server: an SSE event `storage` with `{ moving: true|false }` when the hold starts and ends.
- App: `503 server_moving` on a blob or upload call is not logged as an error: the item stays queued (as today), the
  engine sets a `serverMoving` flag, and the status line reads "Server is moving files: N changes held" (amber in the
  app UI). When the `storage` event says `moving: false`, or after `Retry-After`, the app runs a scan, which sends
  what it held. Older app versions just see an error and retry on their next scan.

**Web during a move.** The Files block shows: amber "Copying 48 of 148 files, 31 of 79 MB" with Cancel; then
"Holding uploads, finishing…"; then "Files are now in /x. The old copy (79 MB) is still in /data/blobs." with
**Remove old copy**; or red with the error and "Nothing was changed." The `storage` SSE topic keeps it live.

**Low-space guard.** `StorageMonitor` probes both the data directory and the files location; uploads are refused
(507, as today) when either is under `MIN_FREE_BYTES`, and the page names the disk that is low.

**Backups.** A backup reads blobs through `FilesLocation`. `files.location` is dropped from exported server settings,
so a restored server uses the default location.

### 4. Change email

- `POST /v1/account/email` `{ newEmail, password }` for the signed-in user. Validates and normalises the address the
  way registration does; 400 `bad_email`; 403 `bad_password` (counts toward `LoginThrottle` like a failed sign-in);
  409 `email_taken`. On success, updates `Users.Email`, keeps device keys and the current web session, signs out the
  user's other web sessions, and records an activity event.
- Web: Settings › Account, a "Change email" form (new email, current password, Save). On success the "Signed in as"
  line updates and a quiet "Email changed." line shows.
- CLI: `change-email <old> <new>` in `AdminCli`, same validation and conflict rules, prints the result.

## What's left to the builder

- Treemap and focus-navigation details (exact padding, label fitting rules, tie-breaks) within the look of the mockup.
- The preference endpoint shape (as long as it is per account).
- Exact copy of error lines, following the house style (plain, specific, no "Oops").
- Postgres: `databaseBytes` null; everything else must work on both providers.

## Calls I made (the owner can overrule)

- A restored or rebuilt server uses the default location; a folder holding another location's files is never adopted
  (a move into a non-empty folder is refused). Recovery is by restoring a backup.
- The admin's graphs show their own data like everyone's; the Cover art and Database tiles and the Files block are the
  server-wide parts, labelled as such.

- Only the blobs move; art, title cache and temp stay in `/data`.
- The hold's `Retry-After` is 15 s and the hold aborts the move if in-flight writes don't finish in 60 s.
- Other web sessions are signed out on an email change; devices stay paired.
- The Blocks unit steps 1/10/100/1000 MB with at most 200 used blocks.
