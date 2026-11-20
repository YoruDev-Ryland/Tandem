import { describe, expect, it, vi } from "vitest";
import { dispatch, onAction } from "../../src/lib/input/actions";

describe("action stack", () => {
  it("gives the newest handler the action first and stops when it consumes", () => {
    const page = vi.fn(() => true);
    const dialog = vi.fn(() => true);
    const off1 = onAction(page);
    const off2 = onAction(dialog);
    expect(dispatch("b")).toBe(true);
    expect(dialog).toHaveBeenCalledWith("b");
    expect(page).not.toHaveBeenCalled();
    off2();
    dispatch("b");
    expect(page).toHaveBeenCalledWith("b");
    off1();
  });

  it("falls through when a handler declines, and reports false when nobody consumes", () => {
    const page = vi.fn(() => true);
    const dialog = vi.fn(() => false);
    const offs = [onAction(page), onAction(dialog)];
    expect(dispatch("up")).toBe(true);
    expect(dialog).toHaveBeenCalled();
    expect(page).toHaveBeenCalled();
    offs.forEach((o) => o());
    expect(dispatch("up")).toBe(false);
  });
});
