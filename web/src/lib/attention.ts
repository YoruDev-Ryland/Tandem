import type { ConfigOverview, DeviceSummary, GameRow, HomeResponse, SyncGroupList } from "./api/types";
import { gamePath } from "./api/paths";
import { emulatorName } from "./emulators";
import { bareKey } from "./systems";

export type AttentionTone = "fault" | "wait" | "ink" | "quiet";

/** One line of Home's attention list: what is wrong, where to go about it, and the word on the way there. */
export interface AttentionItem {
  key: string;
  tone: AttentionTone;
  text: string;
  href?: string;
  action?: string;
  /** The page tab where it gets dealt with; that tab carries a mark while the item stands. */
  tab: string;
}

export interface ConfigConflictRef {
  emulatorId: string;
  bundleId: string;
  /** The group's name, or the device's for a config on its own. */
  name: string;
}

/** Config bundles with an open conflict, with the bundle a group's row doesn't carry taken from the group list. */
export function configConflictRefs(overview: ConfigOverview | undefined, groups: SyncGroupList | undefined): ConfigConflictRef[] {
  const out: ConfigConflictRef[] = [];
  for (const e of overview?.emulators ?? []) {
    for (const g of e.groups) {
      const bundleId = groups?.items.find((s) => s.id === g.id)?.bundleId;
      if (g.openConflict && bundleId) out.push({ emulatorId: e.emulatorId, bundleId, name: g.name });
    }
    for (const s of e.solo) if (s.openConflict) out.push({ emulatorId: e.emulatorId, bundleId: s.bundleId, name: s.device.name });
  }
  return out;
}

export const configConflictPath = (emulatorId: string, bundleId: string) =>
  `/configs/${encodeURIComponent(emulatorId)}/conflict/${encodeURIComponent(bundleId)}`;

const DAY = 24 * 3_600_000;
const HOUR = 3_600_000;

/** "3 days", "1 day", "40 hours": how long a device has been quiet. */
export function quietFor(lastSeenUtc: string | null, now: number): string {
  const t = lastSeenUtc ? Date.parse(lastSeenUtc) : NaN;
  if (Number.isNaN(t)) return "";
  const d = now - t;
  if (d >= 2 * DAY) return `${Math.floor(d / DAY)} days`;
  const h = Math.max(1, Math.floor(d / HOUR));
  return `${h} ${h === 1 ? "hour" : "hours"}`;
}

const title = (g: GameRow) => g.displayName ?? bareKey(g.emulatorId, g.gameKey);
const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** Everything Home should say, most urgent first: problems (red), waits (amber), facts (ink), quiet notes (ink2). */
export function attentionItems(home: HomeResponse | undefined, games: GameRow[], configs: ConfigConflictRef[], now: number): AttentionItem[] {
  const out: AttentionItem[] = [];
  const a = home?.attention;
  const devices: DeviceSummary[] = home?.devices ?? [];
  const deviceName = (id: string) => devices.find((d) => d.id === id)?.name ?? "A device";

  // ---- Needs you ----
  if (a?.diskLow)
    out.push({ tab: "/settings", key: "disk", tone: "fault", text: "The server is almost out of disk space. New saves are refused until there is room.", href: "/settings?s=storage", action: "See storage" });
  const conflicted = games.filter((g) => g.openConflict);
  if (conflicted.length === 1) {
    const g = conflicted[0]!;
    out.push({ tab: "/games", key: "conflict", tone: "fault", text: `${title(g)} has two different saves.`, href: gamePath(g.emulatorId, g.gameKey) + "/conflict", action: "Choose" });
  } else if (conflicted.length > 1) {
    out.push({ tab: "/games", key: "conflict", tone: "fault", text: `${conflicted.length} games have two different saves.`, href: "/conflicts", action: "Choose" });
  }
  for (const c of configs)
    out.push({ tab: "/configs", key: `config/${c.bundleId}`, tone: "fault", text: `${emulatorName(c.emulatorId)} config for ${c.name} has two versions. Changes stop until you choose.`, href: configConflictPath(c.emulatorId, c.bundleId), action: "Choose" });
  // The count arrives before the bundles do; never hide a conflict the server reported.
  const unseen = (a?.configConflicts ?? 0) - configs.length;
  if (configs.length === 0 && unseen > 0)
    out.push({ tab: "/configs", key: "config", tone: "fault", text: `${plural(unseen, "config has", "configs have")} two versions.`, href: "/configs", action: "Choose" });
  if (a && a.firstSyncReviews > 0)
    out.push({ tab: "/games", key: "first-sync", tone: "fault", text: `${plural(a.firstSyncReviews, "save needs", "saves need")} a first-sync review.`, href: "/conflicts", action: "Review" });
  for (const id of a?.shizukuOffDevices ?? [])
    out.push({ tab: "/devices", key: `shizuku/${id}`, tone: "fault", text: `Shizuku is off on ${deviceName(id)}. Nothing syncs there until it is back on.`, href: `/devices/${encodeURIComponent(id)}`, action: "Open" });
  if (a && a.failedDeliveries24h > 0)
    out.push({ tab: "/activity", key: "failed", tone: "fault", text: `${plural(a.failedDeliveries24h, "delivery", "deliveries")} failed in the last day.`, href: "/activity", action: "See activity" });

  // ---- Waiting ----
  for (const g of games) {
    for (const d of g.devices) {
      if (d.state !== "Waiting") continue;
      out.push({ tab: "/games", key: `wait/${g.emulatorId}/${g.gameKey}/${d.deviceId}`, tone: "wait", text: `${title(g)} reaches ${d.deviceName} when ${emulatorName(g.emulatorId)} closes.`, href: gamePath(g.emulatorId, g.gameKey), action: "Open" });
    }
  }

  // ---- Facts ----
  for (const id of a?.silentDevices ?? []) {
    const d = devices.find((x) => x.id === id);
    const quiet = quietFor(d?.lastSeenUtc ?? null, now);
    out.push({ tab: "/devices", key: `silent/${id}`, tone: "ink", text: quiet ? `${deviceName(id)} hasn't checked in for ${quiet}.` : `${deviceName(id)} hasn't checked in yet.`, href: `/devices/${encodeURIComponent(id)}`, action: "Open" });
  }

  // ---- Quiet notes ----
  if (a?.backupNotConfigured)
    out.push({ tab: "/settings", key: "backup", tone: "quiet", text: "Backups of this server aren't set up yet.", href: "/settings?s=backup", action: "Set up" });
  return out;
}

const RANK: Record<AttentionTone, number> = { fault: 0, wait: 1, ink: 2, quiet: 3 };

/** A page tab's mark: how many items stand there, in the colour of the most urgent. */
export interface TabMark {
  tone: AttentionTone;
  count: number;
}

/** The mark each page tab carries, keyed by the tab's path. Tabs with nothing standing are absent. */
export function tabMarks(items: AttentionItem[]): Record<string, TabMark> {
  const out: Record<string, TabMark> = {};
  for (const it of items) {
    const m = out[it.tab];
    if (!m) out[it.tab] = { tone: it.tone, count: 1 };
    else {
      m.count++;
      if (RANK[it.tone] < RANK[m.tone]) m.tone = it.tone;
    }
  }
  return out;
}
