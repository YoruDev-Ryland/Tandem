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

/** One emulator's games, largest first; those under 2 % of its total fold into an "N more" tile. */
export function gameTiles(u: StorageUsage, emulatorId: string): Tile[] {
  const colour = spineColour(systemOf(emulatorId));
  const ink = spineInk(systemOf(emulatorId));
  const games = u.games
    .filter((g) => g.emulatorId === emulatorId && g.bytes > 0)
    .sort((a, b) => b.bytes - a.bytes || (a.gameKey < b.gameKey ? -1 : 1))
    .map((g) => ({
      value: g.bytes,
      tile: {
        id: `${g.emulatorId}/${g.gameKey}`, label: g.displayName ?? bareKey(g.emulatorId, g.gameKey), value: g.bytes,
        kind: "game" as const, emulatorId: g.emulatorId, gameKey: g.gameKey, olderBytes: g.olderBytes, versions: g.versions, colour, ink,
      } satisfies Tile,
    }));
  const { kept, rest } = mergeSmall(games);
  const tiles: Tile[] = kept.map((k) => k.tile);
  if (rest) tiles.push({ id: `${emulatorId}/more`, label: `${rest.count} more`, value: rest.value, kind: "more", emulatorId, colour, ink });
  return tiles;
}
