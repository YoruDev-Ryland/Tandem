import { tick } from "svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import Activity from "../../src/pages/Activity.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { devices } from "../fixtures/devices";
import type { ActivityItem, ActivityPage } from "../../src/lib/api/types";

const listeners: ((e: MessageEvent) => void)[] = [];
class FakeEventSource {
  readyState = 1;
  addEventListener(type: string, fn: (e: MessageEvent) => void) { if (type === "changed") listeners.push(fn); }
  close() {}
}
const topic = (t: string) => listeners.forEach((l) => l({ data: JSON.stringify({ topic: t }) } as MessageEvent));
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

const NOW = Date.now();
const item = (id: number, over: Partial<ActivityItem> = {}): ActivityItem => ({
  id, utc: new Date(NOW - id * 60_000).toISOString(), kind: "save.uploaded", message: `Event ${id}`,
  deviceId: "d-odin", deviceName: "Odin 3", emulatorId: "eden", gameKey: "k1", ...over,
});

let pages: (url: URL) => ActivityPage;
let calls: URL[];
let fail: ((u: URL) => boolean) | undefined;

describe("Activity", () => {
  beforeEach(() => {
    router.go("/activity");
    listeners.length = 0;
    calls = [];
    fail = undefined;
    pages = () => ({ items: [item(3), item(2)], nextBefore: 2 });
    vi.stubGlobal("EventSource", FakeEventSource);
    vi.stubGlobal("fetch", vi.fn(async (u: string) => {
      const url = new URL(u, "http://x");
      if (url.pathname === "/v1/devices") return json(devices);
      if (url.pathname === "/v1/web/games") return json([{ emulatorId: "melonds", gameKey: "nds/Pokemon - Platinum Version (USA)", displayName: "Pokémon Platinum" }]);
      if (fail?.(url)) return new Response(JSON.stringify({ code: "boom" }), { status: 500, headers: { "Content-Type": "application/json" } });
      calls.push(url);
      return json(pages(url));
    }));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("builds the query from the filters and keeps them in the URL without adding history", async () => {
    router.go("/activity?emulator=eden&game=k1");
    render(Activity);
    await screen.findByText("Event 3");
    expect(calls[0]!.searchParams.get("emulator")).toBe("eden");
    expect(calls[0]!.searchParams.get("game")).toBe("k1");
    expect(calls[0]!.searchParams.get("limit")).toBe("50");
    expect(screen.getByText("Only k1")).toBeTruthy();

    const len = history.length;
    await fireEvent.change(screen.getByLabelText("Device"), { target: { value: "d-thor" } });
    await waitFor(() => expect(calls.at(-1)!.searchParams.get("device")).toBe("d-thor"));
    expect(calls.at(-1)!.searchParams.get("before")).toBeNull();
    expect(router.search).toContain("device=d-thor");
    expect(history.length).toBe(len);
  });

  it("Show all drops the game filter", async () => {
    router.go("/activity?emulator=eden&game=k1");
    render(Activity);
    await screen.findByText("Only k1");
    await fireEvent.click(screen.getByRole("button", { name: "Show all" }));
    await waitFor(() => expect(calls.at(-1)!.searchParams.get("game")).toBeNull());
    expect(screen.queryByText("Only k1")).toBeNull();
  });

  it("Load older passes before and appends; then nothing older", async () => {
    pages = (u) => (u.searchParams.get("before") === "2" ? { items: [item(1)], nextBefore: null } : { items: [item(3), item(2)], nextBefore: 2 });
    render(Activity);
    await screen.findByText("Event 3");
    const btn = screen.getByRole("button", { name: "Load older" });
    await fireEvent.pointerMove(btn, { clientX: 5, clientY: 5 }); // the cursor moves to it, as for any row
    await fireEvent.click(btn);
    await screen.findByText("Event 1");
    expect(calls.at(-1)!.searchParams.get("before")).toBe("2");
    expect(screen.getByText("Event 3")).toBeTruthy();
    const n = calls.length;
    await fireEvent.click(screen.getByText("Nothing older"));
    expect(calls.length).toBe(n);
  });

  it("a topic prepends newer items, dedupes, and keeps the focused item", async () => {
    render(Activity);
    await screen.findByText("Event 3");
    dispatch("down");
    await tick();
    const focused = () => document.querySelector('[aria-current="true"]')!.textContent;
    expect(focused()).toContain("Event 2");

    pages = () => ({ items: [item(5), item(4), item(3), item(2)], nextBefore: 2 });
    topic("activity");
    await screen.findByText("Event 5");
    expect(screen.getAllByText("Event 3")).toHaveLength(1);
    expect(focused()).toContain("Event 2");
  });

  it("says so when nothing matches", async () => {
    pages = () => ({ items: [], nextBefore: null });
    render(Activity);
    expect(await screen.findByText("No activity matches these filters.")).toBeTruthy();
  });

  it("shows Server for deviceless rows, an opened conflict in fault, and message as text", async () => {
    pages = () => ({ items: [item(2, { kind: "conflict_opened", deviceName: null, message: "<b>x</b> clash" })], nextBefore: null });
    render(Activity);
    const msg = await screen.findByText("<b>x</b> clash");
    expect(msg.classList.contains("fault")).toBe(true);
    expect(screen.getByText("Server")).toBeTruthy();
  });

  it("paints only conflict_opened in fault, never a resolved conflict", async () => {
    pages = () => ({ items: [
      item(3, { kind: "conflict_opened", message: "Opened" }),
      item(2, { kind: "conflict_resolved", message: "Resolved" }),
    ], nextBefore: null });
    render(Activity);
    expect((await screen.findByText("Opened")).classList.contains("fault")).toBe(true);
    expect(screen.getByText("Resolved").classList.contains("fault")).toBe(false);
  });

  it("keeps rows and shows an error in place when Load older fails", async () => {
    render(Activity);
    await screen.findByText("Event 3");
    fail = (u) => u.searchParams.has("before");
    const btn = screen.getByRole("button", { name: "Load older" });
    await fireEvent.pointerMove(btn, { clientX: 5, clientY: 5 });
    await fireEvent.click(btn);
    expect((await screen.findByRole("alert")).textContent).toContain("Couldn't load older activity. Try again.");
    expect(screen.getByText("Event 3")).toBeTruthy();
  });

  it("drops the old rows when filters change, and shows the error if that fetch fails", async () => {
    render(Activity);
    await screen.findByText("Event 3");
    fail = (u) => u.searchParams.get("device") === "d-thor";
    await fireEvent.change(screen.getByLabelText("Device"), { target: { value: "d-thor" } });
    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    expect(screen.queryByText("Event 3")).toBeNull();
  });

  it("names the game by its display name", async () => {
    router.go("/activity?emulator=melonds&game=" + encodeURIComponent("nds/Pokemon - Platinum Version (USA)"));
    render(Activity);
    expect(await screen.findByText("Only Pokémon Platinum")).toBeTruthy();
  });

  it("renders Nothing older as plain text, and disables Load older while it loads", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    pages = () => ({ items: [item(3)], nextBefore: 3 });
    render(Activity);
    await screen.findByText("Event 3");
    const real = globalThis.fetch;
    vi.stubGlobal("fetch", vi.fn(async (u: string, i: RequestInit) => {
      if (new URL(u, "http://x").searchParams.has("before")) { await gate; return json({ items: [], nextBefore: null }); }
      return real(u, i);
    }));
    const btn = screen.getByRole("button", { name: "Load older" });
    await fireEvent.pointerMove(btn, { clientX: 5, clientY: 5 });
    await fireEvent.click(btn);
    await waitFor(() => expect(screen.getByRole("button", { name: /Loading/ }).getAttribute("aria-disabled")).toBe("true"));
    release();
    await screen.findByText("Nothing older");
    expect(screen.getByText("Nothing older").closest("button")).toBeNull();
  });
});
