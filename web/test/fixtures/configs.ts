import type { ConfigDiffResponse, ConfigOverview, ConfigVersionSummary, DeviceDetail, SyncGroupList } from "../../src/lib/api/types";
import { ODIN, RP, THOR } from "./home";

export const AYN_GROUP = "g-ayn";
export const AYN_BUNDLE = "b-ayn";
export const RP_BUNDLE = "b-rp";

export function cversion(seq: number, over: Partial<ConfigVersionSummary> = {}): ConfigVersionSummary {
  return {
    id: `cv${seq}`,
    seq,
    source: "Device",
    deviceName: seq % 2 ? "Odin 3" : "Thor",
    receivedUtc: `2026-11-1${seq}T19:02:00Z`,
    totalSize: 40960,
    pinned: false,
    conflictReason: null,
    resolved: false,
    ...over,
  };
}

export const overview: ConfigOverview = {
  emulators: [
    {
      emulatorId: "retroarch",
      groups: [
        {
          id: AYN_GROUP,
          name: "AYN handhelds",
          members: [{ id: ODIN, name: "Odin 3" }, { id: THOR, name: "Thor" }],
          headVersionId: "cv7",
          openConflict: false,
        },
      ],
      solo: [{ device: { id: RP, name: "Retroid" }, bundleId: RP_BUNDLE, headVersionId: "rv2", openConflict: false }],
    },
    { emulatorId: "azahar", groups: [], solo: [] },
  ],
};

export const groups: SyncGroupList = {
  items: [
    { id: AYN_GROUP, emulatorId: "retroarch", name: "AYN handhelds", bundleId: AYN_BUNDLE, headVersionId: "cv7", memberDeviceIds: [ODIN, THOR] },
  ],
};

export const aynVersions: ConfigVersionSummary[] = [
  cversion(7),
  cversion(6),
  cversion(5, { source: "WebEdit", deviceName: null }),
  cversion(4, { source: "Copy", deviceName: null }),
];

export const rpVersions: ConfigVersionSummary[] = [
  cversion(2, { id: "rv2", deviceName: "Retroid" }),
  cversion(1, { id: "rv1", deviceName: "Retroid", source: "Join" }),
];

export const diff: ConfigDiffResponse = {
  files: [
    {
      path: "retroarch.cfg",
      status: "Changed",
      hunks: [
        {
          oldStart: 412,
          newStart: 412,
          lines: [
            { op: " ", text: 'video_scale_integer = "true"' },
            { op: "-", text: 'video_shader_enable = "false"' },
            { op: "+", text: 'video_shader_enable = "true"' },
            { op: " ", text: 'video_smooth = "false"' },
          ],
        },
      ],
    },
    {
      path: "config/mGBA/mGBA.opt",
      status: "Changed",
      hunks: [{ oldStart: 3, newStart: 3, lines: [{ op: "-", text: 'mgba_skip_bios = "OFF"' }, { op: "+", text: 'mgba_skip_bios = "ON"' }] }],
    },
    { path: "config/remaps/mGBA/mGBA.rmp", status: "Same", hunks: [] },
  ],
};

export function deviceDetail(id: string, appliedVersionId: string | null): DeviceDetail {
  return {
    summary: { id, name: id, platform: "android", lastSeenUtc: null, shizukuOk: true, isCurrent: false, runningEmulators: [] },
    emulators: [],
    roots: {},
    configs: [
      {
        emulatorId: "retroarch",
        bundleId: AYN_BUNDLE,
        groupId: AYN_GROUP,
        groupName: "AYN handhelds",
        appliedVersionId,
        headVersionId: "cv7",
        upToDate: appliedVersionId === "cv7",
        openConflict: false,
      },
    ],
    pendingDeliveries: 0,
    storageBytes: 0,
    installs: [],
  };
}
