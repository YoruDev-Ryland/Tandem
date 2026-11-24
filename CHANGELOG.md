# Changelog

## Unreleased (0.1.4)

### Done

- **The server reports its real version.** `/v1/server-info` and backups showed 0.1.0 for every release; the image
  now carries the release's version.

### Planned

- **Storage page (web, admin).** One place that shows what Tandem's data takes up and how much room is left:
  - the total, and the free space on the disk it lives on, with the `MIN_FREE_BYTES` floor marked;
  - split by kind: saves and states, configs, Vault, Lost & found, the cover-art cache and the database;
  - per emulator and per game, largest first, so it's clear what to prune;
  - what the retention settings would free.

  It builds on the per-emulator usage the retention settings already compute.
- **Choose where the data lives, and move it there.** An admin setting for the folder that holds saves, states and
  other stored files, for example a bigger disk or a NAS mount added to the container:
  - the new folder is checked first (writable, enough free space, not inside the current one);
  - existing files are copied, each one checked against its hash, and only then does the server switch to the new
    folder; the old copy is removed only after the switch, and only when asked;
  - progress shows on the page; uploads wait while the switch happens; a failed or cancelled move leaves everything
    where it was.

  In Docker the new folder has to be mounted into the container first; the page says which path to mount.
- **Change your email in the web UI.** Settings › Account gets a "Change email" form that asks for the current
  password and refuses an address another account uses. Paired devices stay signed in. An admin command
  `change-email <old> <new>` does the same from the server.
