import { afterEach, describe, expect, it, vi } from "vitest";
import { flushSync } from "svelte";
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

  it("shows keyboard keys for every hint without a gamepad: Enter, X, Y, Esc", () => {
    render(HintBar, { status: "Synced 2 min ago", hints });
    const bar = screen.getByRole("contentinfo");
    expect(bar.textContent).toContain("Synced 2 min ago");
    expect(screen.getByText("Enter")).toBeTruthy();
    expect(screen.getByText("Esc")).toBeTruthy();
    expect(bar.textContent).toContain("Open");
    expect(bar.textContent).toContain("Back");
    expect(bar.textContent).toContain("Restore");
    expect(bar.textContent).toContain("Download");
    expect(screen.getByText("X")).toBeTruthy();
    expect(screen.getByText("Y")).toBeTruthy();
  });

  it("puts a success line in place of the status for five seconds", async () => {
    const { flash, FLASH_MS } = await import("../../src/lib/ui/flash.svelte");
    vi.useFakeTimers();
    render(HintBar, { status: "Synced 2 min ago", hints });
    flash.say("Kept Thor's save");
    flushSync();
    expect(screen.getByRole("status").textContent).toContain("Kept Thor's save");
    expect(screen.queryByText("Synced 2 min ago")).toBeNull();
    vi.advanceTimersByTime(FLASH_MS);
    flushSync();
    expect(screen.queryByText("Kept Thor's save")).toBeNull();
    expect(screen.getByText("Synced 2 min ago")).toBeTruthy();
    vi.useRealTimers();
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
