const MB = 1_000_000;
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
