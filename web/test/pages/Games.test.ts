import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import Games from "../../src/pages/Games.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { gamePath } from "../../src/lib/api/paths";
import { games, noHead, waiting } from "../fixtures/home";

class FakeEventSource { readyState = 0; addEventListener() {} close() {} }
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

describe("Games", () => {
  beforeEach(() => {
    router.go("/games");
    vi.stubGlobal("EventSource", FakeEventSource);
    vi.stubGlobal("fetch", vi.fn(async () => json(games)));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("lists every game, including one with no save yet", async () => {
    render(Games);
    await screen.findByText("No Head Yet");
    expect(screen.getByRole("heading", { name: /Games/ }).textContent).toContain("6 games");
  });

  it("search filters by name", async () => {
    render(Games);
    await screen.findByText("Pokémon Sword");
    await fireEvent.input(screen.getByRole("searchbox", { name: "Search games" }), { target: { value: "sword" } });
    expect(screen.queryByText("No Head Yet")).toBeNull();
    expect(screen.getByText("Pokémon Sword")).toBeTruthy();
  });

  it("says so when nothing matches", async () => {
    render(Games);
    await screen.findByText("Pokémon Sword");
    await fireEvent.input(screen.getByRole("searchbox"), { target: { value: "zzz" } });
    expect(screen.getByText("No games match.")).toBeTruthy();
  });

  it("filters by emulator through tabs that follow the module order, and r2 moves along them", async () => {
    render(Games);
    await screen.findByText("Pokémon Sword");
    const tabs = within2().map((a) => a.textContent);
    expect(tabs).toEqual(["All", "Eden", "RetroArch", "PPSSPP", "melonDS"]);
    dispatch("r2");
    await waitFor(() => expect(screen.queryByText("No Head Yet")).toBeNull());
    expect(screen.getByText("Pokémon Sword")).toBeTruthy();
    expect(screen.queryByText("Pokémon Emerald")).toBeNull();
  });

  it("opens the focused game on a", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json([waiting, noHead])));
    render(Games);
    await screen.findByText("Pokémon Sword");
    dispatch("a");
    expect(router.path).toBe(gamePath(noHead.emulatorId, noHead.gameKey));
  });
});

function within2() {
  return [...screen.getByRole("navigation", { name: "Sections" }).querySelectorAll("a")];
}
