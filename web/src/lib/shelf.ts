// The Games shelf, as a pure model: the order on a shelf, the status rail's rows and the pulled-out card's words.
// It mirrors the app's Shelf.kt; the web has no "this device", so rows go by name and the card is about the newest copy.
import type { GameRow } from "./api/types";
import { byName, matrixColumns, nameList, presence, type MatrixColumn, type Presence, type StateTone } from "./deviceStates";
import { systemLabel } from "./systems";
import { relativeTime } from "./time";

/** Open conflicts first, then by name (ignoring case, by code point, as the app), then unnamed games by key. A new array. */
export function shelfOrder(rows: GameRow[]): GameRow[] {
  return [...rows].sort((a, b) => {
    if (a.openConflict !== b.openConflict) return a.openConflict ? -1 : 1;
    if (!a.displayName !== !b.displayName) return a.displayName ? -1 : 1;
    if (a.displayName && b.displayName) {
      const n = byName(a.displayName, b.displayName);
      if (n) return n;
    }
    return a.gameKey < b.gameKey ? -1 : a.gameKey > b.gameKey ? 1 : 0;
  });
}

export const RAIL_MAX = 3;

const NUMBERED = /^(.*) \((\d+)\)$/;
const baseLabel = (name: string) => name.trim().replace(/^ayn\s+/i, "").replace(/\s+/g, "").toUpperCase().slice(0, 5);

/**
 * The same rule as the app's railLabel: drop a leading "AYN ", remove spaces, capitals, cut to five. "AYN Thor" →
 * "THOR", "Odin 3" → "ODIN3", "Steam Deck" → "STEAM". A repeated name ("Odin3 (2)") keeps its number: "ODIN3·2".
 */
export function railLabel(name: string): string {
  const m = NUMBERED.exec(name);
  return m ? `${baseLabel(m[1]!)}·${m[2]}` : baseLabel(name);
}

/** Labels for `names` in order, each different: one an earlier name already has gets the next free number ("ODIN3·2"). */
export function railLabels(names: string[]): string[] {
  const used = new Set<string>();
  return names.map((name) => {
    const label = railLabel(name);
    if (!used.has(label)) {
      used.add(label);
      return label;
    }
    const base = baseLabel(NUMBERED.exec(name)?.[1] ?? name);
    let n = 2;
    while (used.has(`${base}·${n}`)) n++;
    used.add(`${base}·${n}`);
    return `${base}·${n}`;
  });
}

export interface RailRow { deviceId: string; label: string; name: string }

/** The rail: one row per device, by name, at most three. Further devices show only in the card. */
export function railRows(rows: GameRow[]): RailRow[] {
  const columns = matrixColumns(rows);
  const labels = railLabels(columns.map((c) => c.name));
  return columns.slice(0, RAIL_MAX).map((c, i) => ({ deviceId: c.id, label: labels[i]!, name: c.name }));
}

/** The app's table (Shelf.kt): a name only where it says more than the code and still fits the card's line. */
const SYSTEM_NAMES: Record<string, string> = { GC: "GameCube", PSX: "PS1", VITA: "PS Vita", MD: "Mega Drive" };

/** The card kicker's name for a system label (the label itself unless the table names it); null when not known. */
export function systemName(label: string | null): string | null {
  return label ? (SYSTEM_NAMES[label] ?? label) : null;
}

export interface CardLine { deviceId: string; name: string; presence: Presence; words: string }
export interface CardCopy { kicker: string; sentence: string; tone: StateTone; lines: CardLine[] }

function lineWords(p: Presence, g: GameRow, deviceId: string, emulatorName: string, now: number): string {
  switch (p) {
    case "newest":
      return deviceId === g.newestOnDeviceId && g.newestUtc ? `newest · ${relativeTime(g.newestUtc, now)}` : "newest";
    case "older": return "older copy";
    case "absent": return "not installed";
    case "waiting": return `waiting for ${emulatorName} to close`;
    case "coming": return "on its way";
    case "conflict": return "conflict";
    case "empty": return "no save yet";
  }
}

/**
 * The pulled-out card's words for `g`. `all` is every game shown, so the device lines use the rail's devices and
 * names (repeated names numbered); every device gets a line, also those beyond the rail's three rows.
 */
export function cardCopy(g: GameRow, emulatorName: string, all: GameRow[] = [g], now: number = Date.now()): CardCopy {
  const sys = systemName(systemLabel(g.emulatorId, g.gameKey, g.core, g.system));
  const kicker = sys ? `${sys} · ${emulatorName}` : emulatorName;

  const columns: MatrixColumn[] = matrixColumns(all.includes(g) ? all : [...all, g]);
  const lines = columns.map((c) => {
    const p = presence(g, c.id);
    return { deviceId: c.id, name: c.name, presence: p, words: lineWords(p, g, c.id, emulatorName, now) };
  });
  const nameOf = (id: string) => columns.find((c) => c.id === id)?.name ?? "";

  if (g.openConflict) {
    const sides = g.devices.filter((d) => d.state === "Differs").map((d) => nameOf(d.deviceId));
    return { kicker, sentence: sides.length >= 2 ? `Conflict between ${nameList(sides)}` : "Conflict: two saves differ", tone: "fault", lines };
  }
  const waiting = g.devices.filter((d) => d.state === "Waiting").map((d) => nameOf(d.deviceId));
  if (waiting.length) return { kicker, sentence: `Waiting to reach ${nameList(waiting)}`, tone: "wait", lines };
  if (!g.newestUtc) return { kicker, sentence: "No save yet", tone: "ink3", lines };
  const when = relativeTime(g.newestUtc, now);
  const where = g.newestOnDeviceId ? nameOf(g.newestOnDeviceId) || g.newestOnDeviceName : g.newestOnDeviceName;
  return { kicker, sentence: where ? `Newest on ${where} · ${when}` : `Newest save · ${when}`, tone: "", lines };
}

/** Where a shelf's cursor was: the game it was on, and the shelf's keys when it was last seen there. */
export interface ShelfMemory { key: string; keys: string[] }

/**
 * The cursor's index on a shelf of `keys`, as the app's ShelfCursor: the remembered game's place; when that game is
 * gone, the nearest game before it (in the order last seen) that is still there; else 0.
 */
export function cursorIndex(memory: ShelfMemory | undefined, keys: string[]): number {
  if (!memory || keys.length === 0) return 0;
  const i = keys.indexOf(memory.key);
  if (i >= 0) return i;
  const present = new Set(keys);
  for (let j = memory.keys.indexOf(memory.key) - 1; j >= 0; j--) {
    const k = memory.keys[j]!;
    if (present.has(k)) return keys.indexOf(k);
  }
  return 0;
}
