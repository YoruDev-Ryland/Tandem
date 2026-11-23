import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/svelte";
import Tabs from "../../src/lib/ui/Tabs.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { router } from "../../src/lib/router.svelte";

const items = [
  { label: "Eden", path: "/configs/eden" },
  { label: "RetroArch", path: "/configs/retroarch" },
];

describe("Tabs", () => {
  afterEach(cleanup);

  it("level 2 tabs move on r2 and ignore r1", () => {
    router.go("/configs/eden");
    render(Tabs, { items, active: "/configs/eden", level: 2 });
    expect(screen.getByRole("link", { name: "Eden" }).getAttribute("aria-current")).toBe("page");
    expect(dispatch("r1")).toBe(false);
    dispatch("r2");
    expect(router.path).toBe("/configs/retroarch");
  });

  it("marks a tab with a count and says what it means", () => {
    render(Tabs, { items, active: "/configs/eden", level: 1, marks: { "/configs/retroarch": { tone: "fault", count: 2 } } });
    const link = screen.getByRole("link", { name: /^RetroArch\s*2 needs you$/ });
    const mark = link.querySelector("[data-testid=tab-mark]")!;
    expect(mark.classList.contains("fault")).toBe(true);
    expect(screen.getByRole("link", { name: "Eden" }).querySelector("[data-testid=tab-mark]")).toBeNull();
  });
});
