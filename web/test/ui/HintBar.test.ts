import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/svelte";
import HintBar from "../../src/lib/ui/HintBar.svelte";
import { gamepad } from "../../src/lib/input/gamepad.svelte";

const hints = [
  { glyph: "a" as const, label: "Open" },
  { glyph: "x" as const, label: "Restore" },
  { glyph: "y" as const, label: "Download" },
  { glyph: "b" as const, label: "Back" },
];

describe("HintBar", () => {
  afterEach(() => { cleanup(); gamepad.connected = false; });

  it("shows keyboard keys for a and b and hides x and y without a gamepad", () => {
    render(HintBar, { status: "Synced 2 min ago", hints });
    const bar = screen.getByRole("contentinfo");
    expect(bar.textContent).toContain("Synced 2 min ago");
    expect(screen.getByText("Enter")).toBeTruthy();
    expect(screen.getByText("Esc")).toBeTruthy();
    expect(bar.textContent).toContain("Open");
    expect(bar.textContent).toContain("Back");
    expect(bar.textContent).not.toContain("Restore");
    expect(bar.textContent).not.toContain("Download");
  });

  it("shows face glyphs for every hint while a gamepad is connected", () => {
    gamepad.connected = true;
    render(HintBar, { hints });
    for (const g of ["A", "X", "Y", "B"]) expect(screen.getByText(g)).toBeTruthy();
    expect(screen.queryByText("Enter")).toBeNull();
    expect(screen.getByRole("contentinfo").textContent).toContain("Restore");
  });

  it("always renders, even with no hints", () => {
    render(HintBar, { hints: [] });
    expect(screen.getByRole("contentinfo")).toBeTruthy();
  });
});
