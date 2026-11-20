export interface Cursor {
  readonly index: number;
  move(d: 1 | -1): void;
  set(i: number): void;
}

/** A row cursor over a list of `list()` rows; stays in range when the list shrinks. */
export function cursor(list: () => number, opts: { wrap?: boolean } = {}): Cursor {
  let raw = $state(0);
  const clamp = (i: number) => Math.max(0, Math.min(i, list() - 1));
  return {
    get index() {
      return clamp(raw);
    },
    move(d) {
      const n = list();
      if (n <= 0) return;
      const next = clamp(raw) + d;
      raw = opts.wrap ? (next + n) % n : clamp(next);
    },
    set(i) {
      raw = clamp(i);
    },
  };
}
