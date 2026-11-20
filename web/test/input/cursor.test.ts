import { describe, expect, it } from "vitest";
import { cursor } from "../../src/lib/input/cursor.svelte";

describe("cursor", () => {
  it("moves within bounds without wrapping by default", () => {
    let n = 3;
    const c = cursor(() => n);
    c.move(-1);
    expect(c.index).toBe(0);
    c.move(1); c.move(1); c.move(1);
    expect(c.index).toBe(2);
  });

  it("wraps only when asked", () => {
    const c = cursor(() => 3, { wrap: true });
    c.move(-1);
    expect(c.index).toBe(2);
    c.move(1);
    expect(c.index).toBe(0);
  });

  it("set clamps, and the index follows a shrinking list", () => {
    let n = 5;
    const c = cursor(() => n);
    c.set(4);
    expect(c.index).toBe(4);
    n = 2;
    expect(c.index).toBe(1);
    n = 0;
    expect(c.index).toBe(0);
    c.move(1);
    expect(c.index).toBe(0);
    n = 5;
    c.set(99);
    expect(c.index).toBe(4);
  });
});
