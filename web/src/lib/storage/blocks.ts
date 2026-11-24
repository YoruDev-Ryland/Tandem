import type { StorageUsage } from "../api/types";
import { emulatorGames, foldGames, topTiles, type Tile } from "./tiles";

// Binary, as formatSize shows sizes, so "1 block = 1 MB" and the sizes on the page agree.
const MB = 1024 * 1024;
const MAX_USED_BLOCKS = 200;

/** MB per block: the smallest of 1, 10, 100 and 1000 that keeps the used blocks at or under 200. */
export function blockUnit(totalBytes: number): number {
  for (const unit of [1, 10, 100]) if (totalBytes / (unit * MB) <= MAX_USED_BLOCKS) return unit;
  return 1000;
}

/**
 * One entry per block, group after group. A non-empty group gets at least one block. The group's last blocks are
 * notched, as many as its older versions' bytes fill (at least one when it has any).
 */
export function blockLayout(groups: { id: string; bytes: number; olderBytes: number }[], unit: number): { group: string; notched: boolean }[] {
  const size = unit * MB;
  const out: { group: string; notched: boolean }[] = [];
  for (const g of groups) {
    if (g.bytes <= 0) continue;
    const n = Math.max(1, Math.round(g.bytes / size));
    const older = g.olderBytes > 0 ? Math.min(n, Math.max(1, Math.round(g.olderBytes / size))) : 0;
    for (let i = 0; i < n; i++) out.push({ group: g.id, notched: i >= n - older });
  }
  return out;
}

/** A run of blocks: one game (or an emulator's small games together), the rest, cover art or the database. */
export interface BlockGroup {
  tile: Tile;
  /** The key-list line it counts towards: the emulator's id, "other", "art" or "database". */
  key: string;
}

/**
 * The block card's groups and unit. Games go emulator by emulator, largest emulator first; an emulator's games under
 * half a block go together as "N more". Then configs, vault and (admins) lost & found as one grey group, then (admins)
 * cover art and the database. The unit comes from these groups' bytes, so the 200-block cap holds.
 */
export function blockGroups(u: StorageUsage, isAdmin: boolean): { unit: number; groups: BlockGroup[] } {
  const top = topTiles(u, isAdmin);
  const emulators = top.filter((t) => t.kind === "emulator");
  const other = top.filter((t) => t.kind === "configs" || t.kind === "vault" || t.kind === "lost");
  const system = top.filter((t) => t.kind === "art" || t.kind === "database");
  const unit = blockUnit(top.reduce((s, t) => s + t.value, 0));
  const groups: BlockGroup[] = [];
  for (const e of emulators)
    for (const tile of foldGames(emulatorGames(u, e.id), e.id, 0.5, unit * MB)) groups.push({ tile, key: e.id });
  if (other.length)
    groups.push({
      key: "other",
      tile: { id: "other", label: other.map((t) => t.label).join(", "), value: other.reduce((s, t) => s + t.value, 0), kind: "configs" },
    });
  for (const tile of system) groups.push({ tile, key: tile.id });
  return { unit, groups };
}
