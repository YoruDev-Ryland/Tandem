import type { DeviceSummary } from "./api/types";
import { emulatorName } from "./emulators";

export const OFFLINE_AFTER_MS = 2 * 60_000;

export type StatusTone = "ink2" | "ink3" | "wait" | "fault";

/** "offline" past two minutes, then Shizuku off, then what is open, else online. */
export function deviceStatus(d: DeviceSummary, now: number, waitingDeviceIds: string[] = []): { text: string; tone: StatusTone } {
  const seen = d.lastSeenUtc ? Date.parse(d.lastSeenUtc) : NaN;
  if (!(now - seen <= OFFLINE_AFTER_MS)) return { text: "offline", tone: "ink3" };
  if (d.shizukuOk === false) return { text: "Shizuku off", tone: "fault" };
  if (d.runningEmulators.length > 0) {
    const names = d.runningEmulators.map(emulatorName).join(", ");
    return { text: `${names} open`, tone: waitingDeviceIds.includes(d.id) ? "wait" : "ink2" };
  }
  return { text: "online", tone: "ink2" };
}
