import type { GameRow } from "../api/types";

let ids = $state<string[]>([]);

/** Devices that have a game waiting for an emulator to close; the top bar shows their "open" state in --wait. */
export function waitingDeviceIds(): string[] {
  return ids;
}

/** Pages that load the games list call this each time it loads. */
export function updateWaiting(rows: GameRow[]): void {
  const next = new Set<string>();
  for (const r of rows) for (const d of r.devices) if (d.state === "Waiting") next.add(d.deviceId);
  const list = [...next];
  if (list.length !== ids.length || list.some((id, i) => id !== ids[i])) ids = list;
}
