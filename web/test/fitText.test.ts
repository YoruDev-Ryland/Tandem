import { describe, expect, it } from "vitest";
import { fitSize } from "../src/lib/fitText";

describe("fitSize", () => {
  it("keeps 26 px when the title fits", () => {
    expect(fitSize(() => true)).toBe(26);
  });
  it("steps down to the first size that fits", () => {
    expect(fitSize((px) => px <= 21)).toBe(21);
  });
  it("stops at 17 px when nothing fits", () => {
    const tried: number[] = [];
    expect(fitSize((px) => (tried.push(px), false))).toBe(17);
    expect(Math.min(...tried)).toBe(18);
  });
});
