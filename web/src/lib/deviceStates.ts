import type { GameDeviceState, GameRow } from "./api/types";
import { emulatorName } from "./emulators";

export type StateTone = "" | "wait" | "ink2" | "ink3" | "fault";

/** The shared device-state vocabulary: the words and the ink a device's state is shown in. */
export function deviceStateCopy(d: GameDeviceState, emulatorId: string): { text: string; tone: StateTone } {
  switch (d.state) {
    case "Pending": return { text: `${d.deviceName}, on its way`, tone: "" };
    case "Waiting": return { text: `${d.deviceName}, until ${emulatorName(emulatorId)} closes`, tone: "wait" };
    case "Behind": return { text: `${d.deviceName}, older save`, tone: "ink2" };
    case "NotInstalled": return { text: `not on ${d.deviceName}`, tone: "ink3" };
    case "Differs": return { text: `${d.deviceName} differs`, tone: "fault" };
    default: return { text: d.deviceName, tone: "" };
  }
}

/** "Odin 3 and Thor differ" for a game with two saves waiting for a choice. */
export function differing(g: GameRow): string {
  const names = g.devices.filter((d) => d.state === "Differs").map((d) => d.deviceName);
  const list = names.length >= 2 ? names : g.devices.map((d) => d.deviceName);
  return list.length > 1 ? `${list.slice(0, -1).join(", ")} and ${list[list.length - 1]} differ` : `${list[0] ?? "Saves"} differ`;
}

/** Devices other than the one holding the newest save. */
export function otherDevices(g: GameRow): GameDeviceState[] {
  return g.devices.filter((d) => d.state !== "Newest" && d.deviceId !== g.newestOnDeviceId);
}

/** "Odin 3", "Odin 3 and Thor", "Ayn, Odin 3 and Thor". */
export const nameList = (names: string[]) => (names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}` : (names[0] ?? ""));

// ---- The device presence matrix ----

/** How a device's copy of a game draws in the matrix: the shape carries the meaning, colour only adds to it. */
export type Presence = "newest" | "older" | "coming" | "waiting" | "empty" | "absent" | "conflict";

export interface MatrixColumn { id: string; name: string }

/** One column per device that appears in any row, by name; repeated names get a number. */
export function matrixColumns(rows: GameRow[]): MatrixColumn[] {
  const seen = new Map<string, string>();
  for (const r of rows) for (const d of r.devices) if (!seen.has(d.deviceId)) seen.set(d.deviceId, d.deviceName);
  const cols = [...seen]
    .map(([id, name]) => ({ id, name }))
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" }) || a.id.localeCompare(b.id));
  const count = new Map<string, number>();
  return cols.map((c) => {
    const n = (count.get(c.name) ?? 0) + 1;
    count.set(c.name, n);
    return n === 1 ? c : { ...c, name: `${c.name} (${n})` };
  });
}

export function presence(g: GameRow, deviceId: string): Presence {
  const d = g.devices.find((x) => x.deviceId === deviceId);
  if (!d) return "absent";
  switch (d.state) {
    case "Differs": return "conflict";
    case "Newest": return "newest";
    // HasIt is "has the current save"; for a game with no save yet it only means installed.
    case "HasIt": return g.newestUtc ? "newest" : "empty";
    case "Behind": return "older";
    case "Pending": return "coming";
    case "Waiting": return "waiting";
    default: return "absent";
  }
}

/** The words for a cell: its aria-label and tooltip. */
export function presenceText(p: Presence, device: string, emulatorId: string): string {
  switch (p) {
    case "newest": return `${device} has the newest save`;
    case "older": return `${device} has an older save`;
    case "coming": return `The newest save is on its way to ${device}`;
    case "waiting": return `The newest save reaches ${device} when ${emulatorName(emulatorId)} closes`;
    case "empty": return `Installed on ${device}, no save yet`;
    case "absent": return `Not installed on ${device}`;
    case "conflict": return `${device} has one of two different saves`;
  }
}

/** For a device: of the games with a save that it has installed, how many hold the newest save, and where it made it. */
export function deviceSaveCounts(rows: GameRow[], deviceId: string): { current: number; total: number; newestHere: number } {
  let current = 0;
  let total = 0;
  let newestHere = 0;
  for (const g of rows) {
    if (!g.newestUtc) continue;
    const p = presence(g, deviceId);
    if (p === "absent" || p === "empty") continue;
    total++;
    if (p === "newest") current++;
    if (g.newestOnDeviceId === deviceId) newestHere++;
  }
  return { current, total, newestHere };
}

export const PRESENCE_LEGEND =
  "Filled square: has the newest save. Outlined: an older save. Half filled: on its way. " +
  "wait: arrives when the emulator closes. Dashed: installed, no save yet. Dash: not installed. ×2: two saves, choose one.";

/** The line under a game's title: what each device holds, framed so bare names mean something. */
export function deviceLine(g: GameRow): { text: string; tone: StateTone }[] {
  const holders = g.devices.filter((d) => d.state === "Newest" || d.state === "HasIt");
  const names = holders.map((d) => d.deviceName);
  const synced = g.devices.length > 0 && holders.length === g.devices.length;
  if (synced) {
    const both = names.length === 1 ? "" : names.length === 2 ? "both have the newest save" : "all have the newest save";
    const parts = [{ text: `installed on ${nameList(names)}`, tone: "" as StateTone }];
    if (g.newestUtc && both) parts.push({ text: both, tone: "" });
    return parts;
  }
  const parts: { text: string; tone: StateTone }[] = [];
  if (g.newestUtc && names.length > 0) parts.push({ text: `newest on ${nameList(names)}`, tone: "" });
  for (const d of g.devices) {
    if (d.state === "Newest" || d.state === "HasIt") {
      if (!g.newestUtc) parts.push({ text: `on ${d.deviceName}`, tone: "" });
      continue;
    }
    parts.push(deviceStateCopy(d, g.emulatorId));
  }
  return parts;
}
