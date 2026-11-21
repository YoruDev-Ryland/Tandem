import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/svelte";
import Home from "../../src/pages/Home.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { gamePath } from "../../src/lib/api/paths";
import { conflict, fourDevices, game, games, home, longName, noHead, notInstalled, ODIN, THOR, waiting, dev } from "../fixtures/home";
import type { Attention, ConfigOverview, GameRow, SyncGroupList } from "../../src/lib/api/types";

const json = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

class FakeEventSource { readyState = 0; addEventListener() {} close() {} }

const overview: ConfigOverview = {
  emulators: [{ emulatorId: "eden", groups: [{ id: "g1", name: "Handhelds", members: [{ id: ODIN, name: "Odin 3" }, { id: THOR, name: "Thor" }], headVersionId: "cv3", openConflict: true }], solo: [] }],
};
const groupList: SyncGroupList = { items: [{ id: "g1", emulatorId: "eden", name: "Handhelds", bundleId: "bundle-1", headVersionId: "cv3", memberDeviceIds: [ODIN, THOR] }] };

function serve(rows: GameRow[], firstSync = 0, attention: Partial<Attention> = {}) {
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    if (url.includes("/v1/web/home")) return json(home(firstSync, { openConflicts: 0, ...attention }));
    if (url.includes("/v1/web/configs")) return json(overview);
    if (url.includes("/v1/configs/groups")) return json(groupList);
    return json(rows);
  }));
}
const rowOf = (name: string) => screen.getByText(name).closest("button") as HTMLElement;
const heading = () => screen.getByRole("heading", { level: 1 });

describe("Home", () => {
  beforeEach(() => router.go("/"));
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("says everything is where it should be when nothing needs the user", async () => {
    serve([notInstalled, longName]);
    render(Home);
    await screen.findByText("Pokémon Emerald");
    expect(heading().textContent).toBe("Everything is where it should be.");
    expect(heading().classList.contains("fault")).toBe(false);
    expect(screen.queryByRole("list", { name: "Needs attention" })).toBeNull();
  });

  it("leads with a single problem in fault and links straight to it", async () => {
    serve([conflict, notInstalled]);
    render(Home);
    await screen.findByText("Pokémon Emerald");
    expect(heading().textContent).toBe("Pokémon Brilliant Diamond has two different saves.");
    expect(heading().classList.contains("fault")).toBe(true);
    const link = screen.getByRole("link", { name: /Choose/ });
    expect(link.getAttribute("href")).toBe(gamePath(conflict.emulatorId, conflict.gameKey) + "/conflict");
  });

  it("counts several problems and lists each with its way out", async () => {
    serve([conflict, waiting], 2, { diskLow: true });
    render(Home);
    await screen.findByText("Pokémon Sword");
    expect(heading().textContent).toBe("3 things need you.");
    const list = within(await screen.findByRole("list", { name: "Needs attention" }));
    expect(list.getByText(/almost out of disk space/)).toBeTruthy();
    expect(list.getByText("2 saves need a first-sync review.").closest("a")!.getAttribute("href")).toBe("/conflicts");
    // The wait is listed in amber below the problems.
    const wait = list.getByText("Pokémon Sword reaches Thor when Eden closes.");
    expect(wait.closest("li")!.classList.contains("wait")).toBe(true);
  });

  it("links a config conflict to its page, found through the config overview", async () => {
    serve([notInstalled], 0, { configConflicts: 1 });
    render(Home);
    await screen.findByRole("heading", { name: "Eden config for Handhelds has two versions." });
    const link = screen.getByRole("link", { name: /Choose/ });
    expect(link.getAttribute("href")).toBe("/configs/eden/conflict/bundle-1");
  });

  it("shows a waiting save in amber when it is the only thing going on", async () => {
    serve([waiting]);
    render(Home);
    await screen.findByText("Pokémon Sword");
    expect(heading().textContent).toBe("Pokémon Sword reaches Thor when Eden closes.");
    expect(heading().classList.contains("wait")).toBe(true);
  });

  it("names a device that has gone quiet, and keeps 'backups not set up' as a quiet line", async () => {
    serve([notInstalled], 0, { silentDevices: [THOR], backupNotConfigured: true });
    render(Home);
    await screen.findByText("Pokémon Emerald");
    expect(heading().textContent).toMatch(/^Thor hasn't checked in for \d+ (hour|hours)\.$/);
    const backup = screen.getByText("Backups of this server aren't set up yet.");
    expect(backup.closest("li")!.classList.contains("quiet")).toBe(true);
    expect(backup.closest("a")!.getAttribute("href")).toBe("/settings?s=backup");
  });

  it("lists only games with a save under Recent, the conflict first, then newest first", async () => {
    serve(games);
    render(Home);
    await screen.findByText("Pokémon Sword");
    const buttons = screen.getAllByRole("button").filter((b) => b.classList.contains("row"));
    expect(buttons[0]!.textContent).toContain("Pokémon Brilliant Diamond");
    expect(within(buttons[0]!).getByText("Two saves").classList.contains("fault")).toBe(true);
    expect(buttons[1]!.textContent).toContain("Pokémon Sword");
    expect(screen.queryByText("No Head Yet")).toBeNull();
    expect(screen.getByText("5 games with saves")).toBeTruthy();
    expect(screen.getByText("1 without")).toBeTruthy();
    expect(screen.getByRole("link", { name: /All games/ }).getAttribute("href")).toBe("/games");
  });

  it("keeps Recent to twelve games", async () => {
    const many = Array.from({ length: 20 }, (_, i) =>
      game({ emulatorId: "eden", gameKey: `k${i}`, displayName: `Game ${i}`, newestUtc: new Date(Date.parse("2026-11-20T10:00:00Z") - i * 60_000).toISOString() }));
    serve(many);
    render(Home);
    await screen.findByText("Game 0");
    expect(screen.getAllByRole("button").filter((b) => b.classList.contains("row"))).toHaveLength(12);
    expect(screen.queryByText("Game 12")).toBeNull();
  });

  it("draws one presence cell per device, labelled in words", async () => {
    serve([waiting, fourDevices]);
    render(Home);
    await screen.findByText("Four Devices");
    const sword = within(rowOf("Pokémon Sword"));
    expect(sword.getByRole("img", { name: "Odin 3 has the newest save" })).toBeTruthy();
    const wait = sword.getByRole("img", { name: "The newest save reaches Thor when Eden closes" });
    expect(wait.classList.contains("waiting")).toBe(true);
    const four = within(rowOf("Four Devices"));
    expect(four.getByRole("img", { name: "Ayn has an older save" })).toBeTruthy();
    expect(four.getByRole("img", { name: "The newest save is on its way to Thor" })).toBeTruthy();
    expect(four.getByRole("img", { name: "Retroid has the newest save" })).toBeTruthy();
    // A device the game isn't on is a dash, not a word.
    expect(four.getAllByRole("img").length).toBe(4);
  });

  it("marks both sides of a conflict ×2 in fault", async () => {
    serve([conflict]);
    render(Home);
    await screen.findByText("Pokémon Brilliant Diamond");
    const cells = within(rowOf("Pokémon Brilliant Diamond")).getAllByRole("img", { name: /one of two different saves/ });
    expect(cells).toHaveLength(2);
    expect(cells.every((c) => c.classList.contains("conflict") && c.textContent === "×2")).toBe(true);
  });

  it("shows an unnamed game's id quietly, marked unknown title, never as the bold title", async () => {
    serve([game({ emulatorId: "retroarch", gameKey: "retroarch/Pokemon - Emerald Version", displayName: null })]);
    render(Home);
    const id = await screen.findByText("Pokemon - Emerald Version");
    expect(id.classList.contains("id")).toBe(true);
    expect(id.closest(".name")).toBeNull();
    expect(screen.getByText("unknown title")).toBeTruthy();
  });

  it("shows the system as a cartridge-label tag, and a dash when it can't be told", async () => {
    serve([notInstalled, game({ emulatorId: "retroarch", gameKey: "retroarch/Mystery", displayName: "Mystery" })]);
    render(Home);
    await screen.findByText("Pokémon Emerald");
    expect(within(rowOf("Pokémon Emerald")).getByText("GBA").classList.contains("tag")).toBe(true);
    expect(within(rowOf("Mystery")).getByLabelText("System not known")).toBeTruthy();
    expect(within(rowOf("Mystery")).queryByText("RetroArch")).toBeNull();
  });

  it("shows the empty library sentence", async () => {
    serve([]);
    render(Home);
    expect(await screen.findByText("No games yet. Start the app on a handheld and its saves appear here.")).toBeTruthy();
  });

  it("says so when games exist but none has a save yet", async () => {
    serve([noHead]);
    render(Home);
    expect(await screen.findByText("No saves yet. Games appear here once a handheld uploads a save.")).toBeTruthy();
  });

  it("reports 'Synced' from the newest device check-in", async () => {
    serve(games);
    render(Home);
    expect(await screen.findByText("Synced 2 min ago")).toBeTruthy();
  });

  it("opens the focused game on a, and on x", async () => {
    serve(games);
    render(Home);
    await screen.findByText("Pokémon Sword");
    expect(dispatch("a")).toBe(true);
    expect(router.path).toBe(gamePath(conflict.emulatorId, conflict.gameKey));
    router.go("/");
    expect(dispatch("x")).toBe(true);
    expect(router.path).toBe(gamePath(conflict.emulatorId, conflict.gameKey));
  });

  it("shows a load failure in place", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("offline"); }));
    render(Home);
    expect((await screen.findByRole("alert")).textContent).toMatch(/Try again/);
  });

  it("never lists the newest device as anything but newest", async () => {
    const g = game({ emulatorId: "eden", gameKey: "dup", displayName: "Dup", newestOnDeviceId: ODIN, devices: [dev(ODIN, "Newest"), dev(THOR, "HasIt")] });
    serve([g]);
    render(Home);
    await screen.findByText("Dup");
    expect(within(rowOf("Dup")).getAllByRole("img", { name: /has the newest save/ })).toHaveLength(2);
  });
});
