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
