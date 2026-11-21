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
    out.push({ key: "disk", tone: "fault", text: "The server is almost out of disk space. New saves are refused until there is room.", href: "/settings?s=storage", action: "See storage" });
  const conflicted = games.filter((g) => g.openConflict);
  if (conflicted.length === 1) {
    const g = conflicted[0]!;
    out.push({ key: "conflict", tone: "fault", text: `${title(g)} has two different saves.`, href: gamePath(g.emulatorId, g.gameKey) + "/conflict", action: "Choose" });
  } else if (conflicted.length > 1) {
    out.push({ key: "conflict", tone: "fault", text: `${conflicted.length} games have two different saves.`, href: "/conflicts", action: "Choose" });
  }
  for (const c of configs)
    out.push({ key: `config/${c.bundleId}`, tone: "fault", text: `${emulatorName(c.emulatorId)} config for ${c.name} has two versions. Changes stop until you choose.`, href: configConflictPath(c.emulatorId, c.bundleId), action: "Choose" });
  // The count arrives before the bundles do; never hide a conflict the server reported.
  const unseen = (a?.configConflicts ?? 0) - configs.length;
  if (configs.length === 0 && unseen > 0)
    out.push({ key: "config", tone: "fault", text: `${plural(unseen, "config has", "configs have")} two versions.`, href: "/configs", action: "Choose" });
  if (a && a.firstSyncReviews > 0)
    out.push({ key: "first-sync", tone: "fault", text: `${plural(a.firstSyncReviews, "save needs", "saves need")} a first-sync review.`, href: "/conflicts", action: "Review" });
  for (const id of a?.shizukuOffDevices ?? [])
    out.push({ key: `shizuku/${id}`, tone: "fault", text: `Shizuku is off on ${deviceName(id)}. Nothing syncs there until it is back on.`, href: `/devices/${encodeURIComponent(id)}`, action: "Open" });
  if (a && a.failedDeliveries24h > 0)
    out.push({ key: "failed", tone: "fault", text: `${plural(a.failedDeliveries24h, "delivery", "deliveries")} failed in the last day.`, href: "/activity", action: "See activity" });

  // ---- Waiting ----
  for (const g of games) {
    for (const d of g.devices) {
      if (d.state !== "Waiting") continue;
      out.push({ key: `wait/${g.emulatorId}/${g.gameKey}/${d.deviceId}`, tone: "wait", text: `${title(g)} reaches ${d.deviceName} when ${emulatorName(g.emulatorId)} closes.`, href: gamePath(g.emulatorId, g.gameKey), action: "Open" });
    }
  }

  // ---- Facts ----
  for (const id of a?.silentDevices ?? []) {
    const d = devices.find((x) => x.id === id);
    const quiet = quietFor(d?.lastSeenUtc ?? null, now);
    out.push({ key: `silent/${id}`, tone: "ink", text: quiet ? `${deviceName(id)} hasn't checked in for ${quiet}.` : `${deviceName(id)} hasn't checked in yet.`, href: `/devices/${encodeURIComponent(id)}`, action: "Open" });
  }

  // ---- Quiet notes ----
  if (a?.backupNotConfigured)
    out.push({ key: "backup", tone: "quiet", text: "Backups of this server aren't set up yet.", href: "/settings?s=backup", action: "Set up" });
  return out;
}

export interface Hero {
  text: string;
  tone: AttentionTone;
  /** The rest of a single item's text, said under the hero. */
  sub?: string;
  /** When the hero is one item, it carries that item's link, and the list leaves the item out. */
  item?: AttentionItem;
}

function single(item: AttentionItem): Hero {
  const i = item.text.indexOf(". ");
  return i < 0 ? { text: item.text, tone: item.tone, item } : { text: item.text.slice(0, i + 1), sub: item.text.slice(i + 2), tone: item.tone, item };
}

/** The one sentence at the top of Home, and its colour. */
export function heroLine(items: AttentionItem[]): Hero {
  const faults = items.filter((i) => i.tone === "fault");
  if (faults.length === 1) return single(faults[0]!);
  if (faults.length > 1) return { text: `${faults.length} things need you.`, tone: "fault" };
  const waits = items.filter((i) => i.tone === "wait");
  if (waits.length === 1) return single(waits[0]!);
  if (waits.length > 1) return { text: `${waits.length} saves are waiting for an emulator to close.`, tone: "wait" };
  const facts = items.filter((i) => i.tone === "ink");
  if (facts.length === 1) return single(facts[0]!);
  if (facts.length > 1) return { text: `${facts.length} devices haven't checked in lately.`, tone: "ink" };
  return { text: "Everything is where it should be.", tone: "ink" };
}
