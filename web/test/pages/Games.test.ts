import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import Games from "../../src/pages/Games.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { gamePath } from "../../src/lib/api/paths";
import { game, games } from "../fixtures/home";
import type { GameRow } from "../../src/lib/api/types";

let emit: (topic: string) => void = () => {};
class FakeEventSource {
  readyState = 0;
  addEventListener(type: string, fn: (e: { data: string }) => void) {
    if (type === "changed") emit = (topic) => fn({ data: JSON.stringify({ topic }) });
  }
  close() {}
}
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
const noArt = () => new Response(JSON.stringify({ error: "no_art" }), { status: 404, headers: { "Content-Type": "application/json" } });

let rows: GameRow[] = games;
let art: (url: string) => Response | Promise<Response> = () => noArt();
const fetchMock = vi.fn(async (url: string) => (url.startsWith("/v1/web/art/") ? art(url) : json(rows)));

/** The focused game's card. */
const card = () => document.querySelector<HTMLElement>('[aria-current="true"]')!;
const cardTitle = () => card().querySelector(".title")!.textContent;
const spines = () => [...document.querySelectorAll<HTMLElement>(".games > li")];
const tabNames = () => [...screen.getByRole("navigation", { name: "Sections" }).querySelectorAll("a")].map((a) => a.textContent);

describe("Games shelf", () => {
  beforeEach(() => {
    router.go("/games");
    rows = games;
    art = () => noArt();
    fetchMock.mockClear();
    vi.stubGlobal("EventSource", FakeEventSource);
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("has a shelf per emulator in module order, with no All", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    // The spec's module order (the app's ModuleRegistry): Eden, Dolphin, RetroArch, melonDS, Azahar, PPSSPP, Vita3K.
    expect(tabNames()).toEqual(["Eden", "RetroArch", "melonDS", "PPSSPP"]);
    expect(screen.getByRole("heading", { name: /Games/ }).textContent).toContain("3 games");
  });

  it("orders conflicts first, then by name, and pulls out the first game", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    expect(cardTitle()).toBe("Pokémon Brilliant Diamond");
    expect(spines().map((s) => s.textContent?.trim().replace(/\s+/g, " "))).toEqual([
      expect.stringContaining("Pokémon Brilliant Diamond"),
      "SWITCH Four Devices",
      "SWITCH Pokémon Sword",
    ]);
  });

  it("right arrow moves the card to the next title, and stops at the end", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    dispatch("right");
    await waitFor(() => expect(cardTitle()).toBe("Four Devices"));
    dispatch("right");
    dispatch("right");
    await waitFor(() => expect(cardTitle()).toBe("Pokémon Sword"));
    dispatch("left");
    await waitFor(() => expect(cardTitle()).toBe("Four Devices"));
  });

  it("down switches emulator and up brings back its cursor", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    dispatch("right");
    await waitFor(() => expect(cardTitle()).toBe("Four Devices"));
    dispatch("down");
    await waitFor(() => expect(cardTitle()).toBe("Pokémon Emerald"));
    expect(router.search).toContain("e=retroarch");
    dispatch("up");
    await waitFor(() => expect(cardTitle()).toBe("Four Devices"));
  });

  it("Enter (a) opens the focused game's page", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    dispatch("right");
    await waitFor(() => expect(cardTitle()).toBe("Four Devices"));
    dispatch("a");
    expect(router.path).toBe(gamePath("eden", "0100FOUR"));
  });

  it("clicking a spine selects it, and clicking the card opens it", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    await fireEvent.click(screen.getByRole("button", { name: "Pokémon Sword, SWITCH" }), { detail: 1 });
    expect(cardTitle()).toBe("Pokémon Sword");
    expect(router.path).toBe("/games");
    await fireEvent.click(card(), { detail: 1 });
    expect(router.path).toBe(gamePath("eden", "0100ABF008968000"));
  });

  it("puts a rail mark under every spine for every rail row, at most three rows", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    // Four devices in the data; the rail keeps three (by name), the card lists all four.
    expect([...document.querySelectorAll(".labels li")].map((l) => l.textContent)).toEqual(["AYN", "ODIN3", "RETRO"]);
    const cols = [...document.querySelectorAll(".rail .col")];
    expect(cols).toHaveLength(spines().length);
    for (const c of cols) expect(c.querySelectorAll('[role="img"]')).toHaveLength(3);
    dispatch("right");
    await waitFor(() => expect(cardTitle()).toBe("Four Devices"));
    expect(within(card()).getAllByRole("listitem").map((l) => l.querySelector(".name")!.textContent)).toEqual(["Ayn", "Odin 3", "Retroid", "Thor"]);
    expect(document.querySelectorAll(".rail .col")[1]!.querySelector('[role="img"]')!.getAttribute("aria-label")).toBe(
      "Four Devices: Ayn has an older save",
    );
  });

  it("says the newest copy, the wait or the conflict in the card", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    expect(within(card()).getByText("Conflict between Odin 3 and Thor").classList.contains("fault")).toBe(true);
    dispatch("right");
    dispatch("right");
    await waitFor(() => expect(cardTitle()).toBe("Pokémon Sword"));
    expect(within(card()).getByText("Waiting to reach Thor").classList.contains("wait")).toBe(true);
  });

  it("search filters the shelf and links the matches in other emulators", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    await fireEvent.input(screen.getByRole("searchbox", { name: "Search games" }), { target: { value: "pokémon" } });
    expect(spines()).toHaveLength(2);
    expect(screen.queryByText("Four Devices")).toBeNull();
    const line = screen.getByText(/1 match in other emulators/);
    await fireEvent.click(within(line).getByRole("link", { name: /RetroArch/ }));
    await waitFor(() => expect(cardTitle()).toBe("Pokémon Emerald"));
    expect(screen.getByText(/2 matches in other emulators/).textContent).toContain("Eden");
  });

  it("says so when nothing in this emulator matches", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    await fireEvent.input(screen.getByRole("searchbox"), { target: { value: "zzz" } });
    expect(screen.getByText("No games in Eden match.")).toBeTruthy();
  });

  it("cuts a long title inside its spine and keeps the full name for reading", async () => {
    rows = [game({ emulatorId: "melonds", gameKey: "nds/a", displayName: "A first game" }), ...games.filter((g) => g.emulatorId === "melonds")];
    render(Games);
    await screen.findByText("A first game");
    const long = spines()[1]!.querySelector(".spine > span") as HTMLElement;
    expect(long.textContent).toBe("L".repeat(90));
    // jsdom has no layout, but it does cascade the component's CSS (injected in tests): the spine title is cut.
    const cs = getComputedStyle(long);
    expect([cs.maxHeight, cs.overflow, cs.textOverflow, cs.whiteSpace, cs.writingMode]).toEqual(["100%", "hidden", "ellipsis", "nowrap", "vertical-rl"]);
    expect(screen.getByRole("button", { name: `${"L".repeat(90)}, DS` })).toBeTruthy();
  });

  it("clamps a long title in the card to two lines", async () => {
    rows = [game({ emulatorId: "melonds", gameKey: "nds/l", displayName: "L".repeat(90) })];
    render(Games);
    await screen.findByText("L".repeat(90));
    const title = card().querySelector(".text > div:nth-child(2)") as HTMLElement;
    expect(title.textContent).toBe("L".repeat(90));
    expect(title.getAttribute("title")).toBe("L".repeat(90));
    const cs = getComputedStyle(title);
    expect([cs.display, cs.overflow, cs.getPropertyValue("-webkit-line-clamp") || cs.getPropertyValue("line-clamp")]).toEqual(["-webkit-box", "hidden", "2"]);
  });

  it("enter on a spine reached with Tab opens that spine's game, not the cursor's", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    const other = spines()[2]!.querySelector("button")!;
    other.focus();
    await fireEvent.click(other, { detail: 0 });
    expect(router.path).toBe(gamePath("eden", "0100ABF008968000"));
  });

  it("draws waiting by its shape, not only its colour, where the word has no room", async () => {
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    dispatch("right");
    dispatch("right");
    await waitFor(() => expect(cardTitle()).toBe("Pokémon Sword"));   // Thor waits; the card has no room for the word
    const marks = [...document.querySelectorAll<HTMLElement>(".rail .cell, .lines .cell")];
    const waiting = marks.find((m) => m.classList.contains("waiting"))!;
    const older = marks.find((m) => m.classList.contains("older"))!;
    expect(getComputedStyle(waiting.querySelector(".word")!).display).toBe("none");
    expect(waiting.querySelector("polygon")).not.toBeNull();
    expect(older.querySelector("polygon")).toBeNull();
    expect(waiting.querySelector("svg")!.innerHTML).not.toBe(older.querySelector("svg")!.innerHTML);
  });

  it("shows an unnamed game's key in mono, never as a title", async () => {
    rows = [game({ emulatorId: "eden", gameKey: "0100AC20128AB000", displayName: null })];
    render(Games);
    await screen.findByText("0100AC20128AB000");
    expect(card().querySelector(".id")!.textContent).toBe("0100AC20128AB000");
    expect(within(card()).getByText("unknown title")).toBeTruthy();
  });

  it("keeps the cursor near its place when the focused game disappears on refresh", async () => {
    const ok = (k: string, n: string) => game({ emulatorId: "eden", gameKey: k, displayName: n });
    rows = [ok("a", "Alpha"), ok("b", "Bravo"), ok("c", "Charlie")];
    render(Games);
    await screen.findByText("Alpha");
    dispatch("right");
    dispatch("right");
    await waitFor(() => expect(cardTitle()).toBe("Charlie"));
    rows = [ok("a", "Alpha"), ok("b", "Bravo")];
    emit("saves");
    await waitFor(() => expect(cardTitle()).toBe("Bravo"));
    rows = [ok("z", "Zulu"), ok("a", "Alpha"), ok("b", "Bravo")];
    emit("saves");
    await waitFor(() => expect(spines()).toHaveLength(3));
    expect(cardTitle()).toBe("Bravo");
    // A game in the middle goes: the nearest earlier one, as in the app (not the next one).
    rows = [ok("a", "Alpha"), ok("c", "Charlie"), ok("z", "Zulu")];
    emit("saves");
    await waitFor(() => expect(spines().some((s) => s.textContent?.includes("Charlie"))).toBe(true));
    expect(cardTitle()).toBe("Alpha");
  });

  it("shows the no-art cover when art fails, and the image when it comes", async () => {
    art = () => { throw new TypeError("Failed to fetch"); };
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => String(u).startsWith("/v1/web/art/eden/"))).toBe(true));
    expect(within(card()).getByTestId("no-art").textContent).toBe("SWITCH");
    expect(card().querySelector("img")).toBeNull();

    const created = vi.fn(() => "blob:cover");
    const revoked = vi.fn();
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: created, revokeObjectURL: revoked }));
    art = () => new Response(new Uint8Array([137, 80, 78, 71]), { status: 200, headers: { "Content-Type": "image/png" } });
    dispatch("right");
    await waitFor(() => expect(card().querySelector("img")?.getAttribute("src")).toBe("blob:cover"));
    expect(fetchMock.mock.calls.map(([u]) => u)).toContain("/v1/web/art/eden/0100FOUR");
    dispatch("right");
    await waitFor(() => expect(revoked).toHaveBeenCalledWith("blob:cover"));
  });

  it("says when there are no games at all", async () => {
    rows = [];
    render(Games);
    expect(await screen.findByText(/No games yet/)).toBeTruthy();
  });

  it("b goes back", async () => {
    router.go("/");
    router.go("/games");
    const back = vi.spyOn(router, "back");
    render(Games);
    await screen.findByText("Pokémon Brilliant Diamond");
    dispatch("b");
    expect(back).toHaveBeenCalled();
    back.mockRestore();
  });
});
