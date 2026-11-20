import type { ConflictRow, ConflictSide } from "../../src/lib/api/types";
import { ODIN, THOR } from "./home";

export function side(over: Partial<ConflictSide> & Pick<ConflictSide, "versionId" | "deviceId" | "deviceName">): ConflictSide {
  return {
    receivedUtc: "2026-11-19T21:48:00Z",
    totalSize: 1048576,
    fileCount: 3,
    manifestHash: "9f2c41a0deadbeef",
    isHead: false,
    ...over,
  };
}

export const thorSide = side({ versionId: "v-thor", deviceId: THOR, deviceName: "Thor" });
export const odinSide = side({
  versionId: "v-odin", deviceId: ODIN, deviceName: "Odin 3",
  receivedUtc: "2026-11-17T10:12:00Z", manifestHash: "3b7e08d5cafef00d",
});

export function row(over: Partial<ConflictRow> = {}): ConflictRow {
  return {
    itemId: "item-bd",
    emulatorId: "eden",
    gameKey: "0100000011D90000",
    displayName: "Pokémon Brilliant Diamond",
    kind: "Save",
    slot: null,
    core: null,
    reason: "FirstSync",
    sides: [thorSide, odinSide],
    ...over,
  };
}

export const firstSync = row();
export const concurrent = row({
  itemId: "item-sw", gameKey: "0100ABF008968000", displayName: "Pokémon Sword", reason: "Concurrent",
});
