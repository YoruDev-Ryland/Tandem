import type { StorageServer, StorageUsage, StorageView } from "../api/types";
import { emulatorName } from "../emulators";
import { formatSize } from "../format";
import { bareKey } from "../systems";
import { squarify, type Rect } from "./treemap";
import type { Tile } from "./tiles";

export const VIEWS: readonly StorageView[] = ["map", "bars", "blocks"];
export const VIEW_LABEL: Record<StorageView, string> = { map: "Map", bars: "Bars", blocks: "Blocks" };

/** The next view along (L2/R2), wrapping at either end. */
export function cycleView(view: StorageView, d: 1 | -1): StorageView {
  const i = VIEWS.indexOf(view);
  return VIEWS[(i + d + VIEWS.length) % VIEWS.length]!;
}

/** What the page calls "used by Tandem": the account's own data, plus (admins) the cover art and the database. */
export function usedBytes(u: StorageUsage, isAdmin: boolean): number {
  const s = isAdmin ? u.server : null;
  return u.totalBytes + (s ? s.artBytes + (s.databaseBytes ?? 0) : 0);
}

/** "120.0 GB free on a 195.0 GB disk", or null when the disk can't be read (or for non-admins). */
export function diskNote(u: StorageUsage): string | null {
  const s = u.server;
  if (!s || s.diskFreeBytes == null || s.diskTotalBytes == null) return null;
  return `${formatSize(s.diskFreeBytes)} free on a ${formatSize(s.diskTotalBytes)} disk`;
}

export interface KindRow {
  id: "saves" | "states" | "configs" | "vault" | "lost" | "art" | "database";
  label: string;
  bytes: number;
}

/** The "By kind" rows. Lost & found, cover art and the database are admins' (the last two for all accounts). */
export function kindRows(u: StorageUsage, isAdmin: boolean): KindRow[] {
  const k = u.byKind;
  const rows: KindRow[] = [
    { id: "saves", label: "Saves", bytes: k.saves },
    { id: "states", label: "States", bytes: k.states },
    { id: "configs", label: "Configs", bytes: k.configs },
    { id: "vault", label: "Vault", bytes: k.vault },
  ];
  if (isAdmin) rows.push({ id: "lost", label: "Lost & found", bytes: k.lostAndFound });
  if (isAdmin && u.server) {
    rows.push({ id: "art", label: "Cover art", bytes: u.server.artBytes });
    if (u.server.databaseBytes != null) rows.push({ id: "database", label: "Database", bytes: u.server.databaseBytes });
  }
  return rows;
}

/** The older-versions line's parts: the total and the game holding most of it; null when there are none. */
export function olderLine(u: StorageUsage): { size: string; game: string } | null {
  if (u.olderBytes <= 0) return null;
  const top = [...u.games].sort((a, b) => b.olderBytes - a.olderBytes)[0];
  if (!top || top.olderBytes <= 0) return null;
  return { size: formatSize(u.olderBytes), game: top.displayName ?? bareKey(top.emulatorId, top.gameKey) };
}

/** Lines of label a w by h px tile holds: none under 64 by 36, else one, two or three. */
export function labelLines(w: number, h: number): 0 | 1 | 2 | 3 {
  if (w < 64 || h < 36) return 0;
  if (h >= 66) return 3;
  if (h >= 50) return 2;
  return 1;
}

/**
 * Where a tip goes for a point (x, y) in a box: beside the point, flipped to the left or above once the point is past
 * the middle, so it never runs out of the box.
 */
export function tipPlace(x: number, y: number, boxW: number, boxH: number): { left: number; top: number; flipX: boolean; flipY: boolean } {
  const flipX = x > boxW / 2;
  const flipY = y > boxH / 2;
  return { left: flipX ? x - 12 : x + 12, top: flipY ? y - 12 : y + 12, flipX, flipY };
}

// Small steps lighter and darker, so a game stands apart from the next one in its emulator's colour.
const STEPS = [0, 7, -6, 4, -10, 10, -3, 2];

/** The k-th game's shade of its emulator's colour. */
export function shade(colour: string, k: number): string {
  const s = STEPS[k % STEPS.length]!;
  if (s === 0) return colour;
  return `color-mix(in srgb, ${colour} ${100 - Math.abs(s)}%, ${s > 0 ? "#fff" : "#000"})`;
}

export interface Tip {
  title: string;
  lines: string[];
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** What the tip says for a tile on the map or a group on the block card. */
export function tileTip(t: Tile, u: StorageUsage): Tip {
  switch (t.kind) {
    case "game": {
      const lines = [`${emulatorName(t.emulatorId!)} · current ${formatSize(t.currentBytes ?? t.value)}`];
      if ((t.olderBytes ?? 0) > 0)
        lines.push(`${plural(Math.max(1, t.olderVersions ?? 0), "older version", "older versions")}, ${formatSize(t.olderBytes!)}`);
      return { title: t.label, lines };
    }
    case "more":
      return { title: `${t.label} ${t.label.startsWith("1 ") ? "game" : "games"}`, lines: [`${emulatorName(t.emulatorId!)} · ${formatSize(t.value)}`] };
    case "emulator": {
      const n = u.games.filter((g) => g.emulatorId === t.emulatorId && g.bytes > 0).length;
      return { title: t.label, lines: [`${plural(n, "game", "games")} · ${formatSize(t.value)}`] };
    }
    case "art":
      return { title: "Cover art", lines: [`All accounts · ${formatSize(t.value)}`, "Fetched covers and spines; it refills."] };
    case "database":
      return { title: "Database", lines: [`All accounts · ${formatSize(t.value)}`] };
    default:
      return { title: t.label, lines: [formatSize(t.value)] };
  }
}

/** The console each emulator is under, as the Bars rows name it. */
export const SYSTEMS: Record<string, string> = {
  eden: "Switch",
  melonds: "DS",
  azahar: "3DS",
  dolphin: "GameCube · Wii",
  ppsspp: "PSP",
  vita3k: "PS Vita",
  retroarch: "several systems",
};

/** The disk ruler, as fractions of the disk: what isn't Tandem's, Tandem's end, and the kept-free floor's start. */
export function diskRuler(s: StorageServer): { others: number; tandem: number; floor: number; otherBytes: number; tandemBytes: number } | null {
  if (s.diskTotalBytes == null || s.diskFreeBytes == null || s.diskTotalBytes <= 0) return null;
  const total = s.diskTotalBytes;
  const used = Math.max(0, total - s.diskFreeBytes);
  const tandemBytes = s.filesBytes + s.artBytes + (s.databaseBytes ?? 0);
  const otherBytes = Math.max(0, used - tandemBytes);
  const clamp = (f: number) => Math.max(0, Math.min(1, f));
  return { others: clamp(otherBytes / total), tandem: clamp(used / total), floor: clamp(1 - s.minFreeBytes / total), otherBytes, tandemBytes };
}

/**
 * A treemap of a w by h box with `gap` px between tiles and none at the box's edges, on whole pixels: squarify a box a
 * gap larger each way, round each tile's edges, then take the gap off its right and bottom.
 */
export function gapLayout(items: { id: string; value: number }[], w: number, h: number, gap: number): Rect[] {
  return squarify(items, w + gap, h + gap).map((r) => {
    const x = Math.round(r.x), y = Math.round(r.y);
    return { id: r.id, x, y, w: Math.max(0, Math.round(r.x + r.w) - x - gap), h: Math.max(0, Math.round(r.y + r.h) - y - gap) };
  });
}
