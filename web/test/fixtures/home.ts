import type { DeviceGameState, GameDeviceState, GameRow, HomeResponse } from "../../src/lib/api/types";

export const ODIN = "d-odin";
export const THOR = "d-thor";
export const AYN = "d-ayn";
export const RP = "d-rp";

const NAMES: Record<string, string> = { [ODIN]: "Odin 3", [THOR]: "Thor", [AYN]: "Ayn", [RP]: "Retroid" };

export function dev(deviceId: string, state: DeviceGameState): GameDeviceState {
  return { deviceId, deviceName: NAMES[deviceId]!, state };
}

export function game(over: Partial<GameRow> & Pick<GameRow, "gameKey" | "emulatorId">): GameRow {
  return {
    displayName: null,
    installedOn: [],
    saveItemId: "item-" + over.gameKey,
    newestOnDeviceId: ODIN,
    newestOnDeviceName: "Odin 3",
    newestUtc: "2026-11-20T10:00:00Z",
    stateCount: 0,
    openConflict: false,
    devices: [dev(ODIN, "Newest"), dev(THOR, "HasIt")],
    core: null,
    headVersionId: null,
    ...over,
  };
}

export const conflict = game({
  emulatorId: "eden",
  gameKey: "0100000011D90000",
  displayName: "Pokémon Brilliant Diamond",
  openConflict: true,
  newestOnDeviceId: null,
  newestOnDeviceName: null,
  newestUtc: "2026-11-20T11:00:00Z",
  devices: [dev(ODIN, "Differs"), dev(THOR, "Differs")],
});
export const waiting = game({
  emulatorId: "eden",
  gameKey: "0100ABF008968000",
  displayName: "Pokémon Sword",
  newestUtc: "2026-11-20T09:00:00Z",
  devices: [dev(ODIN, "Newest"), dev(THOR, "Waiting")],
});
export const notInstalled = game({
  emulatorId: "retroarch",
  gameKey: "gba/Pokemon Emerald",
  displayName: "Pokémon Emerald",
  newestUtc: "2026-11-17T09:00:00Z",
  devices: [dev(ODIN, "Newest"), dev(THOR, "NotInstalled")],
});
export const fourDevices = game({
  emulatorId: "eden",
  gameKey: "0100FOUR",
  displayName: "Four Devices",
  newestUtc: "2026-11-19T09:00:00Z",
  devices: [dev(ODIN, "Newest"), dev(THOR, "Pending"), dev(AYN, "Behind"), dev(RP, "HasIt")],
});
export const longName = game({
  emulatorId: "melonds",
  gameKey: "nds/long",
  displayName: "L".repeat(90),
  newestUtc: "2026-11-16T09:00:00Z",
});
export const noHead = game({
  emulatorId: "ppsspp",
  gameKey: "NOHEAD",
  displayName: "No Head Yet",
  newestOnDeviceId: null,
  newestOnDeviceName: null,
  newestUtc: null,
  devices: [dev(ODIN, "HasIt")],
});

export const games: GameRow[] = [notInstalled, waiting, conflict, longName, fourDevices, noHead];

export function home(firstSyncReviews = 0, over: Partial<HomeResponse["attention"]> = {}): HomeResponse {
  return {
    continue: [],
    recent: [],
    devices: [
      { id: ODIN, name: "Odin 3", platform: "android", lastSeenUtc: new Date(Date.now() - 2 * 60_000).toISOString(), shizukuOk: true, isCurrent: false, runningEmulators: [] },
      { id: THOR, name: "Thor", platform: "android", lastSeenUtc: new Date(Date.now() - 3_600_000).toISOString(), shizukuOk: true, isCurrent: false, runningEmulators: ["eden"] },
    ],
    attention: { openConflicts: 1, firstSyncReviews, shizukuOffDevices: [], failedDeliveries24h: 0, configConflicts: 0, diskLow: false, backupNotConfigured: false, silentDevices: [], filesMissing: false, filesMissingPath: null, ...over },
  };
}
