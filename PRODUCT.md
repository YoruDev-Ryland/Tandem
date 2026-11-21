# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

The web UI and the Android app share one visual identity: a console-style system UI. Decided by the owner on
2026-11-20, replacing an earlier "native Material 3" choice. The app is built natively (Compose), but uses the shared
look, not stock Material styling. Controller hints are real controls: bumper tabs and A/B/X/Y actions. On the web
they show only while a gamepad is connected.

## Users

- People who self-host and play emulated games on more than one Android handheld (for example the AYN Thor and
  Odin 3), with a Steam Deck client planned. They pick up whichever device is nearest.
- The owner uses it first. It is meant for public release, so the wording and onboarding must work for a stranger
  who installs it on their own server. Users know their emulators; they shouldn't need to know how sync works.
- Accounts: one server can have several users (admin, registration toggle).

## Product Purpose

- Sync saves and save states between devices, and back up and sync emulator configs, so play continues on any device
  and a wiped device can be rebuilt without redoing its setup.
- Success means the user forgets it exists:
  - saves are simply there on the device they pick up;
  - nothing is ever silently overwritten;
  - when something does need them, they see it immediately and can decide it quickly.

## Positioning

- No launcher. The app watches emulator folders through Shizuku, so the user keeps playing the way they already do.
- Saves go only to devices that have the game installed.
- Configs sync as whole-file groups, with only folder paths kept per device.
- Everything is self-hosted: one Docker container on the user's own server.

## Operating Context

People open the web UI in four situations:
1. **Something went wrong:** a notification sent them there about a conflict, a failed sync, or Shizuku being off.
2. **Checking in:** a glance to see which device has the newest save and whether everything is synced.
3. **Setup and tinkering:** config groups, diffs, editing configs in the browser, the vault, setting up a new or
   wiped device.
4. **Rarely:** the app does the work, so the web UI should stay out of the way.

The app runs on handhelds held landscape, often driven by D-pad or gamepad rather than touch, and is opened briefly
between play sessions.

## Capabilities and Constraints

- **Emulators in v1:** Eden, RetroArch, Azahar, PPSSPP, Vita3K, Dolphin, melonDS. Each has one profile per device.
- **Pages:** Home, Games, Devices, Configs, Vault, Activity, Settings, and New-device setup. Spec:
  docs/superpowers/specs/2026-11-19-emulator-save-config-sync-design.md §8–9.
- **Terms:**
  - *save* and *state* (save state);
  - *version*, *pinned*, *variant* (a kept alternative of a save);
  - *conflict* and *first-sync review*;
  - *config group*; *on its own* (solo);
  - *linked* vs *copy once*;
  - *vault*: BIOS, keys, firmware and system files;
  - *delivery waiting*: held until the emulator closes.
- **Rule:** files are never written while the emulator is running, and the UI must say so wherever a write waits.
- **Web stack:** Svelte 5 + Vite, served by the ASP.NET Core server.
- **Name:** Tandem (chosen 2026-11-20; the working name was EmuSync, which another project already uses).

## Evidence on Hand

- Real device data from the on-device test:
  - games: Pokémon Sword, Brilliant Diamond, Platinum, FireRed, Emerald, Crystal, Xenoblade Chronicles X;
  - devices: Thor (Android 13) and Odin 3 (Android 15, SD card).
  - Source: docs/superpowers/research/2026-11-20-device-e2e.md (on feat/android).
- No users, testimonials, logos or box art. Do not fabricate them.

## Product Principles

1. **Never lose a save, and show it.** Every overwrite keeps the old version, and the UI makes that visible at the
   moment of decision.
2. **Quiet until it matters.** The normal state is calm. Problems are unmistakable and lead straight to one decision.
3. **Speak in games and devices, not sync internals.** For example "Thor has the newest Pokémon Sword save", not
   "v4 head on device 2".
4. **Respect how people already play.** No launcher, no forced workflow. On the handheld: controller-first and brief.

## Accessibility & Inclusion

- Text meets WCAG AA contrast.
- Status never relies on color alone.
- The Android app is fully operable by D-pad or gamepad, with a clearly visible focus state.
