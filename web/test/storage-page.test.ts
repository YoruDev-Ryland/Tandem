import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import Storage from "../src/pages/settings/Storage.svelte";
import Settings from "../src/pages/Settings.svelte";
import { router } from "../src/lib/router.svelte";
import { dispatch } from "../src/lib/input/actions";
import { session } from "../src/lib/session.svelte";
import { gamePath } from "../src/lib/api/paths";
import type { StorageUsage } from "../src/lib/api/types";
import { cycleView, deepSpine, diskRuler, gapLayout, kindRows, labelLines, olderLine, shade, tileTip, tipPlace, usedBytes } from "../src/lib/storage/page";
import { SPINES } from "../src/lib/spines";
import { storageUsage } from "../src/pages/_storageFixture";

const MB = 1024 * 1024;
const GB = 1024 * MB;

describe("storage page helpers", () => {
  it("cycles the views both ways, wrapping", () => {
    expect(cycleView("map", 1)).toBe("bars");
    expect(cycleView("blocks", 1)).toBe("map");
    expect(cycleView("map", -1)).toBe("blocks");
  });

  it("counts the cover art and the database as used by Tandem for an admin only", () => {
    expect(usedBytes(storageUsage, true)).toBe(storageUsage.totalBytes + 32 * MB + Math.round(5.3 * MB));
    expect(usedBytes(storageUsage, false)).toBe(storageUsage.totalBytes);
  });

  it("lists kinds, with lost & found, art and the database for admins", () => {
    expect(kindRows(storageUsage, false).map((r) => r.label)).toEqual(["Saves", "States", "Configs", "Vault"]);
    expect(kindRows(storageUsage, true).map((r) => r.label)).toEqual(["Saves", "States", "Configs", "Vault", "Lost & found", "Cover art", "Database"]);
    const postgres = { ...storageUsage, server: { ...storageUsage.server!, databaseBytes: null } };
    expect(kindRows(postgres, true).map((r) => r.id)).not.toContain("database");
  });

  it("names the game holding most of the older versions", () => {
    expect(olderLine(storageUsage)).toEqual({ size: "14.3 MB", game: "Trails in the Sky 1st Chapter" });
    expect(olderLine({ ...storageUsage, olderBytes: 0 })).toBeNull();
  });

  it("labels only tiles of at least 64 by 36, with more lines as they grow taller", () => {
    expect(labelLines(63, 100)).toBe(0);
    expect(labelLines(100, 35)).toBe(0);
    expect(labelLines(64, 36)).toBe(1);
    expect(labelLines(64, 50)).toBe(2);
    expect(labelLines(64, 66)).toBe(3);
  });

  it("flips the tip left and up past the middle of the box", () => {
    expect(tipPlace(10, 10, 800, 300)).toEqual({ left: 22, top: 22, flipX: false, flipY: false });
    expect(tipPlace(700, 250, 800, 300)).toEqual({ left: 688, top: 238, flipX: true, flipY: true });
  });

  it("shades a game from its emulator's spine towards the spine's own ink, the first one unchanged", () => {
    expect(shade("#44484E", "#F4F2EA", 0)).toBe("#44484E");
    expect(shade("#44484E", "#F4F2EA", 1)).toBe("color-mix(in srgb, #44484E 92%, #F4F2EA)");
    expect(shade("#D2CEC2", "#2A2925", 2)).toBe("color-mix(in srgb, #D2CEC2 97%, #2A2925)");
  });

  it("marks the spines that sink into the dark page", () => {
    expect(deepSpine(SPINES.PSP![0])).toBe(true);
    expect(deepSpine(SPINES.GC![0])).toBe(true);
    expect(deepSpine(SPINES.SWITCH![0])).toBe(false);
    expect(deepSpine(SPINES.DS![0])).toBe(false);
  });

  it("lays tiles out with a gap between them and none at the edges", () => {
    const rects = gapLayout([{ id: "a", value: 1 }, { id: "b", value: 1 }], 803, 300, 3);
    expect(rects).toEqual([
      { id: "a", x: 0, y: 0, w: 400, h: 300 },
      { id: "b", x: 403, y: 0, w: 400, h: 300 },
    ]);
  });

  it("says what a game holds in its tip", () => {
    const tile = { id: "eden/t", label: "Trails", value: 17 * MB, kind: "game" as const, emulatorId: "eden", currentBytes: 4 * MB, olderBytes: 13 * MB, olderVersions: 3 };
    expect(tileTip(tile, storageUsage)).toEqual({ title: "Trails", lines: ["Eden · current 4.0 MB", "3 older versions, 13.0 MB"] });
    expect(tileTip({ ...tile, olderBytes: 0, olderVersions: 0 }, storageUsage).lines).toEqual(["Eden · current 4.0 MB"]);
  });

  it("places the disk ruler's marks as fractions of the disk", () => {
    const r = diskRuler({ ...storageUsage.server!, diskTotalBytes: 100 * GB, diskFreeBytes: 60 * GB, minFreeBytes: GB, filesBytes: 10 * GB, artBytes: 0, databaseBytes: 0 })!;
    expect(r.others).toBeCloseTo(0.3);
    expect(r.tandem).toBeCloseTo(0.4);
    expect(r.floor).toBeCloseTo(0.99);
    expect(diskRuler({ ...storageUsage.server!, diskTotalBytes: null })).toBeNull();
  });
});

// ---- The page ----

class FakeEventSource {
  readyState = 0;
  addEventListener() {}
  close() {}
}
const reply = (status: number, body?: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

interface Call { method: string; url: string; body?: any }
let admin = true;

function serve(usage: StorageUsage, view = "map", failPut = false): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    calls.push({ method, url, body: init?.body ? JSON.parse(init.body as string) : undefined });
    switch (`${method} ${url}`) {
      case "GET /v1/account": return reply(200, { id: "u1", email: "me@x.test", isAdmin: admin });
      case "GET /v1/settings/storage": return reply(200, admin ? usage : { ...usage, server: null });
      case "GET /v1/settings/storage-view": return reply(200, { view });
      case "PUT /v1/settings/storage-view": return failPut ? reply(500, { code: "error", message: "no" }) : reply(204);
      case "GET /v1/server-info": return reply(200, { name: "Tandem", protocol: 1, registrationOpen: false, version: "1.2.3" });
    }
    return reply(404, { code: "not_found", message: "no" });
  }));
  return calls;
}

async function show(opts: { admin?: boolean; usage?: StorageUsage; view?: string; failPut?: boolean } = {}) {
  admin = opts.admin ?? true;
  router.go("/settings?s=storage");
  const calls = serve(opts.usage ?? storageUsage, opts.view, opts.failPut);
  await session.check();
  render(Storage);
  return calls;
}

const views = () => screen.getByRole("group", { name: "View" });
const pressed = () => within(views()).getAllByRole("button").find((b) => b.getAttribute("aria-pressed") === "true")?.textContent?.trim();

describe("Storage page", () => {
  beforeEach(() => vi.stubGlobal("EventSource", FakeEventSource));
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("opens on the view the account saved", async () => {
    await show({ view: "bars" });
    expect(await screen.findByRole("heading", { name: "By emulator" })).toBeTruthy();
    expect(pressed()).toBe("Bars");
  });

  it("switches views from the control and from Y, saving each choice, and leaves L2/R2 alone", async () => {
    const calls = await show();
    await screen.findByRole("group", { name: "Storage map" });
    expect(pressed()).toBe("Map");
    await fireEvent.click(within(views()).getByRole("button", { name: "Blocks" }));
    expect(pressed()).toBe("Blocks");
    expect(dispatch("y")).toBe(true);
    await waitFor(() => expect(pressed()).toBe("Map"));
    dispatch("y");
    await waitFor(() => expect(pressed()).toBe("Bars"));
    expect(dispatch("r2")).toBe(false);
    expect(dispatch("l2")).toBe(false);
    const puts = calls.filter((c) => c.method === "PUT").map((c) => c.body.view);
    expect(puts).toEqual(["blocks", "map", "bars"]);
    expect(within(views()).getByText("Y")).toBeTruthy();
  });

  it("says so quietly when the view can't be saved", async () => {
    await show({ failPut: true });
    await screen.findByRole("group", { name: "Storage map" });
    await fireEvent.click(within(views()).getByRole("button", { name: "Bars" }));
    const line = await screen.findByText("This view couldn't be saved for next time.");
    expect(line.classList.contains("note")).toBe(true);
    expect(pressed()).toBe("Bars");
  });

  it("shows the same total on every view", async () => {
    const totals: string[] = [];
    for (const view of ["map", "bars", "blocks"]) {
      await show({ view });
      await screen.findByRole("heading", { name: "By kind" });
      const hero = document.querySelector(".hero")!;
      totals.push(view === "blocks" ? hero.querySelector(".note")!.textContent!.split(" · ")[0]! : hero.querySelector("b")!.textContent!);
      cleanup();
    }
    expect(totals).toEqual(["116.0 MB", "116.0 MB", "116.0 MB"]);
  });

  it("labels the disk ruler's Tandem mark as all accounts", async () => {
    await show({ view: "bars" });
    expect(await screen.findByText("All accounts 117.5 MB")).toBeTruthy();
  });

  it("zooms the map into an emulator, then back with B", async () => {
    await show();
    const map = await screen.findByRole("group", { name: "Storage map" });
    await fireEvent.click(within(map).getByRole("button", { name: /^Eden,/ }));
    expect(screen.getByRole("button", { name: "All" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Export Eden" }).getAttribute("href")).toBe("/v1/export.zip?emulator=eden");
    expect(within(map).getByRole("button", { name: /^The Legend of Zelda: Tears of the Kingdom,/ })).toBeTruthy();
    expect(dispatch("b")).toBe(true);
    await waitFor(() => expect(screen.queryByRole("button", { name: "All" })).toBeNull());
    expect(within(map).getByRole("button", { name: /^Eden,/ })).toBeTruthy();
    // At the top, B is left to the page.
    expect(dispatch("b")).toBe(false);
  });

  it("the_d_pad_enters_the_map_when_nothing_is_focused", async () => {
    await show();
    const map = await screen.findByRole("group", { name: "Storage map" });
    (document.activeElement as HTMLElement | null)?.blur();
    expect(dispatch("right")).toBe(true);
    // The first press lights the cursor where it is and focuses it; it doesn't move yet.
    const eden = within(map).getByRole("button", { name: /^Eden,/ });
    await waitFor(() => expect(document.activeElement).toBe(eden));
    expect(map.classList.contains("lit")).toBe(true);
    expect(dispatch("right")).toBe(true);
    await waitFor(() => expect(eden.classList.contains("cur")).toBe(false));
  });

  it("leaves the arrows with a focused control outside the map", async () => {
    await show();
    const map = await screen.findByRole("group", { name: "Storage map" });
    expect(dispatch("a")).toBe(false);
    within(views()).getByRole("button", { name: "Bars" }).focus();
    expect(dispatch("left")).toBe(false);
    screen.getByRole("link", { name: "Export everything" }).focus();
    expect(dispatch("down")).toBe(false);
    screen.getByRole("button", { name: "Move files…" }).focus();
    expect(dispatch("up")).toBe(false);
    within(map).getByRole("button", { name: /^Eden,/ }).focus();
    expect(dispatch("right")).toBe(true);
  });

  it("moves the cursor with the arrows and opens a game with A", async () => {
    await show();
    const map = await screen.findByRole("group", { name: "Storage map" });
    // The cursor starts on the largest tile, Eden; A zooms in and puts it on Eden's largest game.
    const eden = within(map).getByRole("button", { name: /^Eden,/ });
    expect(eden.classList.contains("cur")).toBe(true);
    eden.focus();
    dispatch("a");
    const zelda = await within(map).findByRole("button", { name: /^The Legend of Zelda: Tears of the Kingdom,/ });
    expect(zelda.classList.contains("cur")).toBe(true);
    // Focus follows the cursor onto the zoomed tiles, so the arrows stay with the map.
    await waitFor(() => expect(document.activeElement).toBe(zelda));
    expect(dispatch("right") || dispatch("down")).toBe(true);
    await waitFor(() => expect(zelda.classList.contains("cur")).toBe(false));
    const cur = map.querySelector(".cur") as HTMLElement;
    const tip = screen.getByRole("tooltip");
    expect(tip.textContent).toContain(cur.getAttribute("aria-label")!.split(",")[0]);
    expect(cur.getAttribute("aria-describedby")).toBe(tip.id);
    dispatch("a");
    expect(router.path.startsWith("/games/eden/")).toBe(true);
  });

  it("shows a game's tip on hover, with its older versions", async () => {
    await show();
    const map = await screen.findByRole("group", { name: "Storage map" });
    await fireEvent.click(within(map).getByRole("button", { name: /^Eden,/ }));
    const trails = within(map).getByRole("button", { name: /^Trails in the Sky 1st Chapter,/ });
    await fireEvent.pointerMove(trails, { clientX: 20, clientY: 20 });
    const tip = screen.getByRole("tooltip");
    expect(tip.textContent).toContain("Trails in the Sky 1st Chapter");
    expect(tip.textContent).toContain("Eden · current 4.3 MB");
    expect(tip.textContent).toContain("3 older versions, 12.8 MB");
    await fireEvent.pointerLeave(map);
    expect(screen.queryByRole("tooltip")).toBeNull();
  });

  it("opens a game from its zoomed tile", async () => {
    await show();
    const map = await screen.findByRole("group", { name: "Storage map" });
    await fireEvent.click(within(map).getByRole("button", { name: /^Eden,/ }));
    await fireEvent.click(within(map).getByRole("button", { name: /^13 Sentinels: Aegis Rim,/ }));
    expect(router.path).toBe(gamePath("eden", "01008D300C50C000"));
  });

  it("shows the lower section: by kind, older versions, export and the admin's files", async () => {
    await show();
    await screen.findByRole("heading", { name: "By kind" });
    expect(screen.getByText("Older versions take", { exact: false }).textContent).toBe("Older versions take 14.3 MB, most of it Trails in the Sky 1st Chapter. Retention");
    expect(screen.getByRole("link", { name: "Retention" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Export everything" }).getAttribute("href")).toBe("/v1/export.zip");
    const files = screen.getByText("/data/files").closest("p")!;
    expect(files.textContent!.replace(/\s+/g, " ").trim()).toBe("/data/files · 120.0 GB free, 1.0 GB kept free");
    expect(screen.getByRole("button", { name: "Move files…" })).toBeTruthy();
    expect(screen.queryByText(/almost full/)).toBeNull();
  });

  it("names the disk that is running out", async () => {
    await show({ usage: { ...storageUsage, server: { ...storageUsage.server!, lowDisk: "/mnt/nas" } } });
    const line = await screen.findByText("The disk holding /mnt/nas is almost full. New saves are refused until there is room.");
    expect(line.classList.contains("fault")).toBe(true);
  });

  it("shows no server-wide parts to a non-admin", async () => {
    await show({ admin: false });
    const map = await screen.findByRole("group", { name: "Storage map" });
    expect(within(map).queryByRole("button", { name: /^Cover art,/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "Move files…" })).toBeNull();
    expect(screen.queryByText("Lost & found")).toBeNull();
  });

  it("leaves out the older line when nothing older is kept", async () => {
    await show({ usage: { ...storageUsage, olderBytes: 0, games: storageUsage.games.map((g) => ({ ...g, olderBytes: 0 })) } });
    await screen.findByRole("heading", { name: "By kind" });
    expect(screen.queryByText("Older versions take", { exact: false })).toBeNull();
  });

  it("says nothing is stored yet, with no graph", async () => {
    const empty: StorageUsage = { ...storageUsage, totalBytes: 0, byEmulator: [], games: [], olderBytes: 0, byKind: { saves: 0, states: 0, configs: 0, vault: 0, lostAndFound: 0 } };
    await show({ usage: empty, admin: false });
    expect(await screen.findByText("Nothing is stored yet.")).toBeTruthy();
    expect(screen.queryByRole("group", { name: "Storage map" })).toBeNull();
  });

  it("draws the blocks with the unit in the heading and a key with counts", async () => {
    await show({ view: "blocks" });
    expect(await screen.findByText("1 block = 1 MB", { exact: false })).toBeTruthy();
    const key = screen.getByRole("list", { name: "Key" });
    expect(within(key).getByText("Eden")).toBeTruthy();
    expect(within(key).getByRole("link", { name: "Export Eden" })).toBeTruthy();
    expect(screen.getByText(/blocks with a notch/)).toBeTruthy();
  });

  it("the_d_pad_enters_the_blocks_when_nothing_is_focused", async () => {
    await show({ view: "blocks" });
    const card = await screen.findByRole("group", { name: "Storage blocks" });
    (document.activeElement as HTMLElement | null)?.blur();
    expect(dispatch("down")).toBe(true);
    const zelda = within(card).getByRole("button", { name: /^The Legend of Zelda: Tears of the Kingdom,/ });
    await waitFor(() => expect(document.activeElement).toBe(zelda));
    screen.getByRole("link", { name: "Export Eden" }).focus();
    expect(dispatch("down")).toBe(false);
  });

  it("gives each block group a button, moved between with the arrows, with its tip linked", async () => {
    await show({ view: "blocks" });
    const card = await screen.findByRole("group", { name: "Storage blocks" });
    const zelda = within(card).getByRole("button", { name: "The Legend of Zelda: Tears of the Kingdom, 18 blocks, 17.8 MB" });
    expect(within(card).getByRole("button", { name: "Pokémon Platinum, 20 blocks, 20.3 MB" })).toBeTruthy();
    // Only the cursor's group is in the tab order.
    expect(zelda.tabIndex).toBe(0);
    zelda.focus();
    expect(dispatch("right")).toBe(true);
    const trails = within(card).getByRole("button", { name: /^Trails in the Sky 1st Chapter, 17 blocks/ });
    await waitFor(() => expect(trails.tabIndex).toBe(0));
    expect(zelda.tabIndex).toBe(-1);
    const tip = screen.getByRole("tooltip");
    expect(tip.textContent).toContain("Trails in the Sky 1st Chapter");
    expect(trails.getAttribute("aria-describedby")).toBe(tip.id);
  });

  it("ranks games in Bars, ten at first, then all", async () => {
    await show({ view: "bars" });
    const games = await screen.findByRole("list", { name: "Largest games" });
    expect(within(games).getAllByRole("listitem")).toHaveLength(10);
    await fireEvent.click(screen.getByRole("button", { name: `Show all ${storageUsage.games.length}` }));
    expect(within(games).getAllByRole("listitem")).toHaveLength(storageUsage.games.length);
    expect(within(games).getByText("Eden · 4 versions,", { exact: false }).textContent).toContain("12.8 MB older");
  });
});

describe("Storage inside Settings", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("B zooms the map out before it leaves the page, and L2/R2 move between sections", async () => {
    admin = true;
    router.go("/settings?s=storage");
    serve(storageUsage);
    await session.check();
    render(Settings);
    const map = await screen.findByRole("group", { name: "Storage map" });
    await fireEvent.click(within(map).getByRole("button", { name: /^Eden,/ }));
    dispatch("b");
    await waitFor(() => expect(screen.queryByRole("button", { name: "All" })).toBeNull());
    expect(router.path).toBe("/settings");
    dispatch("r2");
    await waitFor(() => expect(router.search).toBe("?s=notifications"));
  });

  it("the Retention link opens the Retention section", async () => {
    admin = true;
    router.go("/settings?s=storage");
    serve(storageUsage);
    await session.check();
    render(Settings);
    await screen.findByRole("heading", { name: "By kind" });
    const link = screen.getAllByRole("link", { name: "Retention" }).find((a) => !a.closest("nav"))!;
    await fireEvent.click(link);
    await waitFor(() => expect(router.search).toBe("?s=retention"));
    expect(await screen.findByRole("heading", { name: "Retention" })).toBeTruthy();
  });
});
