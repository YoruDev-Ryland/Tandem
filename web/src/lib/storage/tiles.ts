import type { StorageUsage } from "../api/types";
import { emulatorName } from "../emulators";
import { spineColour, spineInk } from "../spines";
import { bareKey } from "../systems";
import { mergeSmall } from "./treemap";

export interface Tile {
  id: string;
  label: string;
  value: number;
  kind: "emulator" | "game" | "art" | "database" | "configs" | "lost" | "vault" | "more";
  emulatorId?: string;
  gameKey?: string;
  olderBytes?: number;
  versions?: number;
  /** A game's bytes in its current versions. */
  currentBytes?: number;
  /** A game's versions that are not current: all of them less one per save or state it holds. */
  olderVersions?: number;
  colour?: string;
  ink?: string;
}

/** The console whose colour stands for each emulator; null (RetroArch spans many) gets the neutral colour. */
export const EMULATOR_SYSTEM: Record<string, string | null> = {
  eden: "SWITCH",
  melonds: "DS",
  azahar: "3DS",
  dolphin: "GC",
  ppsspp: "PSP",
  vita3k: "VITA",
  retroarch: null,
};

const systemOf = (emulatorId: string): string | null => EMULATOR_SYSTEM[emulatorId] ?? null;

/**
 * The map's top level, largest emulator first. An emulator's value is its games' bytes; configs and vault are their own
 * tiles so nothing counts twice. Art, database and lost & found are admin-only. Zero values are left out.
 */
export function topTiles(u: StorageUsage, isAdmin: boolean): Tile[] {
  const perEmulator = new Map<string, number>();
  for (const g of u.games) perEmulator.set(g.emulatorId, (perEmulator.get(g.emulatorId) ?? 0) + g.bytes);
  const tiles: Tile[] = [...perEmulator]
    .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1))
    .map(([id, value]) => ({
      id, label: emulatorName(id), value, kind: "emulator" as const, emulatorId: id,
      colour: spineColour(systemOf(id)), ink: spineInk(systemOf(id)),
    }));
  const system: Tile[] = [];
  if (isAdmin && u.server) {
    system.push({ id: "art", label: "Cover art", value: u.server.artBytes, kind: "art" });
    system.push({ id: "database", label: "Database", value: u.server.databaseBytes ?? 0, kind: "database" });
  }
  system.push({ id: "configs", label: "Configs", value: u.byKind.configs, kind: "configs" });
  if (isAdmin) system.push({ id: "lost", label: "Lost & found", value: u.byKind.lostAndFound, kind: "lost" });
  system.push({ id: "vault", label: "Vault", value: u.byKind.vault, kind: "vault" });
  return [...tiles, ...system].filter((t) => t.value > 0);
}

/** One emulator's games, largest first; those under 2 % of its total fold into an "N more" tile (the zoomed map). */
export function gameTiles(u: StorageUsage, emulatorId: string): Tile[] {
  return games(u, emulatorId);
}

/** One emulator's games inside its tile on the whole map: those under 2 % of the map's total fold into "N more". */
export function nestedGameTiles(u: StorageUsage, emulatorId: string, mapTotal: number): Tile[] {
  return games(u, emulatorId, mapTotal);
}

/** One emulator's games as tiles, largest first, none folded. */
export function emulatorGames(u: StorageUsage, emulatorId: string): Tile[] {
  const colour = spineColour(systemOf(emulatorId));
  const ink = spineInk(systemOf(emulatorId));
  return u.games
    .filter((g) => g.emulatorId === emulatorId && g.bytes > 0)
    .sort((a, b) => b.bytes - a.bytes || (a.gameKey < b.gameKey ? -1 : 1))
    .map((g) => ({
      id: `${g.emulatorId}/${g.gameKey}`, label: g.displayName ?? bareKey(g.emulatorId, g.gameKey), value: g.bytes,
      kind: "game" as const, emulatorId: g.emulatorId, gameKey: g.gameKey, olderBytes: g.olderBytes, versions: g.versions,
      currentBytes: g.currentBytes, olderVersions: Math.max(0, g.versions - Math.max(1, g.kinds.length)), colour, ink,
    }));
}

/** The games, with those under `minShare` of `of` folded into one "N more" tile of the emulator's colour. */
export function foldGames(games: Tile[], emulatorId: string, minShare: number, of?: number): Tile[] {
  const { kept, rest } = mergeSmall(games, minShare, of);
  if (!rest) return kept;
  const colour = spineColour(systemOf(emulatorId));
  const ink = spineInk(systemOf(emulatorId));
  return [...kept, { id: `${emulatorId}/more`, label: `${rest.count} more`, value: rest.value, kind: "more", emulatorId, colour, ink }];
}

function games(u: StorageUsage, emulatorId: string, of?: number): Tile[] {
  return foldGames(emulatorGames(u, emulatorId), emulatorId, 0.02, of);
}
