export interface Rect { id: string; x: number; y: number; w: number; h: number }

/** Worst aspect ratio of a row of areas laid along a side of length `side`. */
function worst(row: number[], side: number): number {
  const sum = row.reduce((a, b) => a + b, 0);
  const max = Math.max(...row);
  const min = Math.min(...row);
  return Math.max((side * side * max) / (sum * sum), (sum * sum) / (side * side * min));
}

/** Squarified treemap (Bruls et al.): rects tile the w by h box, area proportional to value, largest first. Zero values are dropped. */
export function squarify(items: { id: string; value: number }[], w: number, h: number): Rect[] {
  const list = items.filter((i) => i.value > 0).sort((a, b) => b.value - a.value);
  const total = list.reduce((s, i) => s + i.value, 0);
  if (!list.length || w <= 0 || h <= 0) return [];
  const areas = list.map((i) => (i.value / total) * w * h);
  const out: Rect[] = [];
  let x = 0, y = 0, bw = w, bh = h, start = 0;
  while (start < list.length) {
    const side = Math.min(bw, bh);
    let end = start + 1;
    while (end < list.length && worst(areas.slice(start, end + 1), side) <= worst(areas.slice(start, end), side)) end++;
    const row = areas.slice(start, end);
    const sum = row.reduce((a, b) => a + b, 0);
    const last = end === list.length;
    if (bw >= bh) {
      // A column on the left, as wide as the row's area needs.
      const cw = last ? bw : sum / bh;
      let cy = y;
      row.forEach((a, k) => {
        const rh = last && k === row.length - 1 ? y + bh - cy : a / cw;
        out.push({ id: list[start + k]!.id, x, y: cy, w: cw, h: rh });
        cy += rh;
      });
      x += cw; bw -= cw;
    } else {
      const rh = last ? bh : sum / bw;
      let cx = x;
      row.forEach((a, k) => {
        const rw = last && k === row.length - 1 ? x + bw - cx : a / rh;
        out.push({ id: list[start + k]!.id, x: cx, y, w: rw, h: rh });
        cx += rw;
      });
      y += rh; bh -= rh;
    }
    start = end;
  }
  return out;
}

/** Items under `minShare` of the total fold into `rest` (null when none do). Kept items stay in their order. */
export function mergeSmall<T extends { value: number }>(items: T[], minShare = 0.02): { kept: T[]; rest: { count: number; value: number } | null } {
  const total = items.reduce((s, i) => s + i.value, 0);
  const kept: T[] = [];
  let count = 0, value = 0;
  for (const i of items) {
    if (total > 0 && i.value / total < minShare) { count++; value += i.value; } else kept.push(i);
  }
  return { kept, rest: count ? { count, value } : null };
}

/** The id of the rect nearest (centre to centre) to `fromId` in the given direction, or null at the edge. */
export function neighbour(rects: Rect[], fromId: string, dir: "up" | "down" | "left" | "right"): string | null {
  const from = rects.find((r) => r.id === fromId);
  if (!from) return null;
  const fx = from.x + from.w / 2, fy = from.y + from.h / 2;
  let best: string | null = null, bestScore = Infinity;
  for (const r of rects) {
    if (r.id === fromId) continue;
    const dx = r.x + r.w / 2 - fx, dy = r.y + r.h / 2 - fy;
    const along = dir === "right" ? dx : dir === "left" ? -dx : dir === "down" ? dy : -dy;
    if (along <= 0) continue;
    const across = dir === "left" || dir === "right" ? Math.abs(dy) : Math.abs(dx);
    // Off-axis distance counts double so a tile in line wins over a nearer diagonal one.
    const score = along * along + 4 * across * across;
    if (score < bestScore) { bestScore = score; best = r.id; }
  }
  return best;
}
