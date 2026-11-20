import type { DeviceSummary, GameDetail, VersionSummary } from "../../src/lib/api/types";
import { AYN, dev, game, ODIN, THOR } from "./home";

export function version(seq: number, over: Partial<VersionSummary> = {}): VersionSummary {
  return {
    id: `v${seq}`,
    seq,
    deviceName: seq % 2 ? "Thor" : "Odin 3",
    receivedUtc: `2026-11-1${seq}T19:02:00Z`,
    totalSize: 524288,
    core: "",
    variant: null,
    pinned: false,
    conflictReason: null,
    isHead: false,
    resolved: false,
    manifestHash: `c4e477bc${seq}0000000000000000`,
    fileCount: 1,
    ...over,
  };
}

export const platinum: GameDetail = {
  row: game({
    emulatorId: "melonds",
    gameKey: "nds/platinum",
    displayName: "Pokémon Platinum",
    installedOn: [ODIN, THOR],
    saveItemId: "item-save",
    stateCount: 1,
    devices: [dev(ODIN, "Newest"), dev(THOR, "HasIt")],
  }),
  items: [
    {
      itemId: "item-save", kind: "Save", slot: "", core: "melonds", headVersionId: "v4", openConflict: false,
      versions: [version(4, { isHead: true }), version(3), version(2, { pinned: true }), version(1, { conflictReason: "FirstSync" })],
    },
    {
      itemId: "item-state", kind: "State", slot: "0", core: "melonds", headVersionId: "s2", openConflict: false,
      versions: [version(2, { id: "s2", isHead: true }), version(1, { id: "s1" })],
    },
  ],
};

export const devices: DeviceSummary[] = [
  { id: ODIN, name: "Odin 3", platform: "android", lastSeenUtc: null, shizukuOk: true, isCurrent: false, runningEmulators: [] },
  { id: THOR, name: "Thor", platform: "android", lastSeenUtc: null, shizukuOk: true, isCurrent: false, runningEmulators: [] },
  { id: AYN, name: "Ayn", platform: "android", lastSeenUtc: null, shizukuOk: true, isCurrent: false, runningEmulators: [] },
];
