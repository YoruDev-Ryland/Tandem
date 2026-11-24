import { describe, expect, it } from "vitest";
import { fitStep, TITLE_STEPS } from "../src/lib/fitText";

describe("card title steps", () => {
  it("runs two lines 26→18, then three lines 20→17", () => {
    expect(TITLE_STEPS[0]).toEqual({ px: 26, lines: 2 });
    expect(TITLE_STEPS.filter((s) => s.lines === 2).map((s) => s.px)).toEqual([26, 25, 24, 23, 22, 21, 20, 19, 18]);
    expect(TITLE_STEPS.filter((s) => s.lines === 3).map((s) => s.px)).toEqual([20, 19, 18, 17]);
    expect(TITLE_STEPS.findIndex((s) => s.lines === 3)).toBe(9);
  });
  it("keeps 26 px on two lines when the title fits", () => {
    expect(fitStep(() => true)).toEqual({ px: 26, lines: 2 });
  });
  it("prefers a smaller two-line size over a third line", () => {
    expect(fitStep((s) => s.px <= 21)).toEqual({ px: 21, lines: 2 });
  });
  it("takes a third line when two lines fit at no size", () => {
    expect(fitStep((s) => s.lines === 3 && s.px <= 19)).toEqual({ px: 19, lines: 3 });
  });
  it("ends at 17 px on three lines when nothing fits", () => {
    expect(fitStep(() => false)).toEqual({ px: 17, lines: 3 });
  });
});
