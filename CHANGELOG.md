# Changelog

## 0.1.4 (2026-11-24)

### Done

- **The server reports its real version.** `/v1/server-info` and backups showed 0.1.0 for every release; the image
  now carries the release's version.
- **The mouse wheel moves through the games shelf** in the web UI, one game per notch, like the arrow keys.
- **A Storage page in Settings.** Shows what Tandem holds and how much room is left, in three views (Map, Bars,
  Blocks; Y switches views on a keyboard or controller): by emulator, game and kind, what older versions take, and, for admins,
  the files location and free disk space with the `MIN_FREE_BYTES` floor.
- **Move the stored files to another folder** from the Storage page, while the server runs. The folder is checked
  first (in Docker it must be a mounted volume), files are copied and verified, uploads pause briefly and handhelds
  send their changes afterwards. The old copy is removed only when you ask. If the folder goes missing, uploads are
  refused and Home shows admins a red line naming the folder; nothing is recreated.
- **Change your email** in Settings › Account (asks for your password; devices stay signed in), and
  `change-email <old> <new>` as an admin command.
