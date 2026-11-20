import type { VaultItemDto } from "../../src/lib/api/types";

export const bios: VaultItemDto = {
  id: "v-bios", category: "bios", emulatorId: "retroarch", name: "PlayStation BIOS", headVersionId: "ver2", totalSize: 524288,
  holders: [
    { deviceId: "d-odin", deviceName: "Odin 3", current: true },
    { deviceId: "d-thor", deviceName: "Thor", current: false },
  ],
};
export const keys: VaultItemDto = {
  id: "v-keys", category: "keys", emulatorId: "eden", name: "Switch keys", headVersionId: "ver3", totalSize: 2048, holders: [],
};
export const firmware: VaultItemDto = {
  id: "v-fw", category: "firmware", emulatorId: "eden", name: "Firmware 19.0.1", headVersionId: "ver4", totalSize: 340 * 1024 * 1024,
  holders: [{ deviceId: "d-odin", deviceName: "Odin 3", current: true }],
};
export const vaultItems = [keys, firmware, bios];
