import type { GameRow } from "../api/types";

/** Devices that have a game waiting for an emulator to close; the top bar shows their "open" state in --wait. */
export function waitingDevices(rows: GameRow[]): string[] {
  const ids = new Set<string>();
  for (const r of rows) for (const d of r.devices) if (d.state === "Waiting") ids.add(d.deviceId);
  return [...ids];
}
