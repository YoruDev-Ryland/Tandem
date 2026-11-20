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

const list = (names: string[]) => (names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}` : (names[0] ?? ""));

/** The line under a game's title: what each device holds, framed so bare names mean something. */
export function deviceLine(g: GameRow): { text: string; tone: StateTone }[] {
  const holders = g.devices.filter((d) => d.state === "Newest" || d.state === "HasIt");
  const names = holders.map((d) => d.deviceName);
  const synced = g.devices.length > 0 && holders.length === g.devices.length;
  if (synced) {
    const both = names.length === 1 ? "" : names.length === 2 ? "both have the newest save" : "all have the newest save";
    const parts = [{ text: `installed on ${list(names)}`, tone: "" as StateTone }];
    if (g.newestUtc && both) parts.push({ text: both, tone: "" });
    return parts;
  }
  const parts: { text: string; tone: StateTone }[] = [];
  if (g.newestUtc && names.length > 0) parts.push({ text: `newest on ${list(names)}`, tone: "" });
  for (const d of g.devices) {
    if (d.state === "Newest" || d.state === "HasIt") {
      if (!g.newestUtc) parts.push({ text: `on ${d.deviceName}`, tone: "" });
      continue;
    }
    parts.push(deviceStateCopy(d, g.emulatorId));
  }
  return parts;
}
