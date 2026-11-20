import type { DeviceDetail, DeviceSummary, SetupChecklist, SetupStep } from "../../src/lib/api/types";

export const ODIN_ID = "d-odin";
export const NOW = Date.parse("2026-11-20T12:00:00Z");
export const MIN_AGO = "2026-11-20T11:59:00Z";

export const odin: DeviceSummary = { id: ODIN_ID, name: "Odin 3", platform: "Android", lastSeenUtc: MIN_AGO, shizukuOk: true, isCurrent: false, runningEmulators: [] };
export const thor: DeviceSummary = { id: "d-thor", name: "Thor", platform: "Android", lastSeenUtc: MIN_AGO, shizukuOk: true, isCurrent: false, runningEmulators: ["eden"] };
export const ayn: DeviceSummary = { id: "d-ayn", name: "Ayn", platform: "Android", lastSeenUtc: "2026-11-20T11:00:00Z", shizukuOk: true, isCurrent: false, runningEmulators: [] };
export const noShizuku: DeviceSummary = { id: "d-rp", name: "Retroid", platform: "Android", lastSeenUtc: MIN_AGO, shizukuOk: false, isCurrent: false, runningEmulators: [] };
export const devices = [odin, thor, ayn, noShizuku];

export const odinDetail: DeviceDetail = {
  summary: odin,
  emulators: [
    { emulatorId: "eden", package: "dev.eden.eden_emulator", version: "0.0.3", cores: null },
    { emulatorId: "retroarch", package: "com.retroarch.aarch64", version: "1.19.1", cores: ["mgba", "gambatte"] },
  ],
  roots: { eden: "SD/ROMs" },
  configs: [
    { emulatorId: "retroarch", bundleId: "b1", groupId: "g1", groupName: "AYN handhelds", appliedVersionId: "v1", headVersionId: "v1", upToDate: true, openConflict: false },
    { emulatorId: "eden", bundleId: "b2", groupId: null, groupName: null, appliedVersionId: "v2", headVersionId: "v3", upToDate: false, openConflict: false },
  ],
  pendingDeliveries: 2,
  storageBytes: 5 * 1024 * 1024,
  installs: [{ emulatorId: "eden", games: 4 }],
};

const step = (kind: SetupStep["kind"], emulatorId: string, label: string, done: boolean, detail: string | null = null): SetupStep => ({ kind, emulatorId, label, done, detail });

export const checklist: SetupChecklist = {
  steps: [
    step("ChooseConfig", "eden", "Choose a eden config", false),
    step("InstallEmulator", "eden", "Install eden", true),
    step("InstallEmulator", "dolphin", "Install dolphin", false),
    step("InstallCores", "retroarch", "Install mgba", true),
    step("GrantFolders", "eden", "Grant folder access for eden", false, "SD/ROMs"),
    step("PlaceVault", "eden", "Place eden system files", false, "keys"),
    step("RestoreSaves", "eden", "Restore eden saves", false, "3 waiting"),
  ],
};
