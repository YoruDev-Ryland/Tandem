import type { FileDiff } from "../api/types";

export type DiffRow =
  | { kind: "line"; op: " " | "+" | "-"; text: string; n: number }
  | { kind: "gap"; count: number };

/** Unchanged runs longer than this collapse to a gap row, keeping one line of context beside each change. */
const MAX_RUN = 3;

/** Flattens a file's hunks into display rows: numbered lines plus gap rows for unchanged stretches. */
export function diffRows(file: FileDiff): DiffRow[] {
  const out: DiffRow[] = [];
  const gap = (count: number) => {
    if (count <= 0) return;
    const last = out[out.length - 1];
    if (last?.kind === "gap") last.count += count;
    else out.push({ kind: "gap", count });
  };

  let oldNext = 1; // first old-file line not yet accounted for
  for (const hunk of file.hunks) {
    gap(hunk.oldStart - oldNext);
    let o = hunk.oldStart;
    let n = hunk.newStart;
    const lines = hunk.lines;
    for (let i = 0; i < lines.length; ) {
      const line = lines[i]!;
      if (line.op !== " ") {
        out.push({ kind: "line", op: line.op, text: line.text, n: line.op === "-" ? o++ : n++ });
        i++;
        continue;
      }
      let end = i;
      while (end < lines.length && lines[end]!.op === " ") end++;
      const run = end - i;
      const keepHead = i > 0 ? 1 : 0;
      const keepTail = end < lines.length ? 1 : 0;
      for (let k = 0; k < run; k++) {
        const hidden = run > MAX_RUN && k >= keepHead && k < run - keepTail;
        if (hidden) {
          if (k === keepHead) gap(run - keepHead - keepTail);
        } else {
          out.push({ kind: "line", op: " ", text: lines[i + k]!.text, n });
        }
        o++;
        n++;
      }
      i = end;
    }
    oldNext = o;
  }
  return out;
}
