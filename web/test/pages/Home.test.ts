import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/svelte";
import Home from "../../src/pages/Home.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { gamePath } from "../../src/lib/api/paths";
import { conflict, fourDevices, games, home, longName, noHead, THOR, waiting } from "../fixtures/home";
import type { GameRow } from "../../src/lib/api/types";

const json = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

class FakeEventSource { readyState = 0; addEventListener() {} close() {} }

function serve(rows: GameRow[], firstSync = 0) {
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string) => json(url.includes("/v1/web/home") ? home(firstSync) : rows)));
}
const rowOf = (name: string) => screen.getByText(name).closest("button") as HTMLElement;

describe("Home", () => {
  beforeEach(() => router.go("/"));
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("titles the page with the game count and pins the conflict first, in fault", async () => {
    serve(games);
    render(Home);
    await screen.findByText("Pokémon Sword");
    expect(screen.getByRole("heading", { name: /Newest saves/ }).textContent).toContain("6 games");
    const buttons = screen.getAllByRole("button").filter((b) => b.classList.contains("row"));
    expect(buttons[0]!.textContent).toContain("Pokémon Brilliant Diamond");
    const r = within(buttons[0]!);
    expect(r.getByText("Two saves").classList.contains("fault")).toBe(true);
    expect(r.getByText("Odin 3 and Thor differ").classList.contains("fault")).toBe(true);
    // then newest first: Four Devices (19th) before Sword (20th 09:00)? Sword is newer.
    expect(buttons[1]!.textContent).toContain("Pokémon Sword");
    expect(buttons[5]!.textContent).toContain("No Head Yet");
  });

  it("reads a Waiting device as 'Thor, until Eden closes' in --wait", async () => {
    serve([waiting]);
    render(Home);
    const el = await screen.findByText("Thor, until Eden closes");
    expect(el.classList.contains("wait")).toBe(true);
  });

  it("reads NotInstalled as 'not on Thor' in --ink3", async () => {
    serve(games);
    render(Home);
    const el = await screen.findByText("not on Thor");
    expect(el.classList.contains("ink3")).toBe(true);
  });

  it("lists every other device with its state copy", async () => {
    serve([fourDevices]);
    render(Home);
    await screen.findByText("Four Devices");
    const cell = rowOf("Four Devices").textContent!;
    expect(cell).toContain("Thor, on its way");
    expect(cell).toContain("Ayn, older save");
    expect(cell).toContain("Retroid");
    expect(screen.getByText("Ayn, older save").classList.contains("ink2")).toBe(true);
  });

  it("shows no newest device for a game with no head, and leaves the others at plain ink2", async () => {
    serve([noHead]);
    render(Home);
    await screen.findByText("No Head Yet");
    const r = within(rowOf("No Head Yet"));
    const dashes = r.getAllByText("—"); // newest on, saved
    expect(dashes).toHaveLength(2);
    expect(dashes.every((d) => d.classList.contains("ink3"))).toBe(true);
    const odin = r.getByText("Odin 3");
    for (const tone of ["ink3", "wait", "fault", "strong"]) expect(odin.classList.contains(tone)).toBe(false);
  });

  it("gives a 90-character name the ellipsis class and the full name as title", async () => {
    serve([longName]);
    render(Home);
    const name = await screen.findByText("L".repeat(90));
    expect(name.classList.contains("ellipsis")).toBe(true);
    expect(name.getAttribute("title")).toBe("L".repeat(90));
  });

  it("labels systems and emulators", async () => {
    serve(games);
    render(Home);
    await screen.findByText("Pokémon Emerald");
    const r = within(rowOf("Pokémon Emerald"));
    expect(r.getByText("GBA")).toBeTruthy();
    expect(r.getByText("RetroArch")).toBeTruthy();
  });

  it("shows the empty library sentence", async () => {
    serve([]);
    render(Home);
    expect(await screen.findByText("No saves yet. Start the app on a handheld and they'll appear here.")).toBeTruthy();
  });

  it("shows the first-sync review row only when there are reviews, linking to /conflicts", async () => {
    serve(games, 2);
    render(Home);
    const link = await screen.findByRole("link", { name: /Review first sync \(2\)/ });
    expect(link.getAttribute("href")).toBe("/conflicts");
    expect(link.classList.contains("fault")).toBe(true);
  });

  it("has no review row when there are none", async () => {
    serve(games, 0);
    render(Home);
    await screen.findByText("Pokémon Sword");
    expect(screen.queryByText(/Review first sync/)).toBeNull();
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

  it("keeps plain other-device text on the ink2 class so the focused row can remap it (AA)", async () => {
    serve([fourDevices]);
    render(Home);
    await screen.findByText("Four Devices");
    expect(screen.getByText("Retroid").classList.contains("ink2")).toBe(true);
    expect(screen.getByText("Thor, on its way").classList.contains("ink2")).toBe(true);
  });

  it("gives the review link and loading dashes their own colour rules and a focus style", async () => {
    const src = (await import("node:fs")).readFileSync("src/pages/Home.svelte", "utf8");
    expect(src).toMatch(/\.review\s*\{[^}]*color:\s*var\(--fault\)/);
    expect(src).toMatch(/\.loading\s*\{[^}]*color:\s*var\(--ink3\)/);
    expect(src).toMatch(/\.review:focus-visible/);
    expect(src).not.toMatch(/\.others\s*\{/);
  });
});
