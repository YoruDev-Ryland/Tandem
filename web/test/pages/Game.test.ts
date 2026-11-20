import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import Game from "../../src/pages/Game.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { gamePath } from "../../src/lib/api/paths";
import type { GameDetail } from "../../src/lib/api/types";
import { dev, ODIN, THOR } from "../fixtures/home";
import { devices, platinum } from "../fixtures/game";

class FakeEventSource { readyState = 0; addEventListener() {} close() {} }
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

interface Call { method: string; url: string; body: unknown }

/** Serves the game and devices; `write` answers anything else (pin, restore, upload). */
function serve(write: (c: Call, detail: GameDetail) => Response | undefined = () => undefined, tweak: (d: GameDetail) => void = () => {}) {
  const detail: GameDetail = structuredClone(platinum);
  tweak(detail);
  const calls: Call[] = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url.includes("/v1/web/games/")) return json(200, detail);
    if (method === "GET" && url.endsWith("/v1/devices")) return json(200, devices);
    const c = { method, url, body: init?.body instanceof FormData ? init.body : init?.body ? JSON.parse(init.body as string) : undefined };
    calls.push(c);
    return write(c, detail) ?? new Response(null, { status: 204 });
  }));
  return calls;
}

const at = () => router.go(gamePath("melonds", "nds/platinum"));

describe("Game", () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.removeAttribute("open"); };
    at();
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("shows the focused version's fingerprint as the first 8 characters of its manifest hash", async () => {
    serve();
    render(Game);
    await screen.findByRole("heading", { name: "v4 from Odin 3" });
    expect(screen.getByText("c4e477bc")).toBeTruthy();
    dispatch("down");
    await screen.findByRole("heading", { name: "v3 from Thor" });
    expect(screen.getByText("c4e477bc")).toBeTruthy();
    expect(screen.queryByText(/c4e477bc3/)).toBeNull();
  });

  it("titles the game and reads the device line in the shared vocabulary", async () => {
    serve(undefined, (d) => { d.row.devices = [dev(ODIN, "Newest"), dev(THOR, "Waiting")]; });
    render(Game);
    expect(await screen.findByRole("heading", { level: 1, name: "Pokémon Platinum" })).toBeTruthy();
    const line = document.querySelector(".devices")!;
    expect(line.textContent).toContain("DS");
    expect(line.textContent).toContain("melonDS");
    expect(within(line as HTMLElement).getByText("Thor, until melonDS closes").classList.contains("wait")).toBe(true);
  });

  it("lists save versions with current and pinned notes, and slots with a version count", async () => {
    serve();
    render(Game);
    await screen.findByText("v4");
    expect(screen.getByText("current")).toBeTruthy();
    expect(screen.getByText("pinned")).toBeTruthy();
    expect(screen.getByText("first sync")).toBeTruthy();
    expect(screen.getByText("2 versions")).toBeTruthy();
  });

  it("enters a slot's versions with a, and shows them under the slot", async () => {
    serve();
    render(Game);
    await screen.findByText("2 versions");
    for (let i = 0; i < 4; i++) dispatch("down");
    expect(dispatch("a")).toBe(true);
    await waitFor(() => expect(screen.getAllByText(/^v[12]$/)).toHaveLength(4));
  });

  it("lists only installed devices in Restore, and restores to the chosen one", async () => {
    const calls = serve();
    render(Game);
    await screen.findByRole("heading", { name: "v4 from Odin 3" });
    await fireEvent.click(screen.getByRole("button", { name: /Restore to/ }));
    const select = (await screen.findByLabelText("Restore to")) as HTMLSelectElement;
    expect([...select.options].map((o) => o.text)).toEqual(["Odin 3", "Thor"]);
    expect(screen.getByText(/Odin 3's current save is kept as a new version first\. If melonDS is open there, it waits until it closes\./)).toBeTruthy();
    await fireEvent.change(select, { target: { value: "d-thor" } });
    await fireEvent.click(screen.getByRole("button", { name: "Restore" }));
    expect(await screen.findByText("Sent to Thor. It goes in when melonDS is closed there.")).toBeTruthy();
    expect(calls).toContainEqual({ method: "POST", url: "/v1/saves/item-save/versions/v4/restore", body: { deviceId: "d-thor" } });
  });

  it("pins a version and flips the label to Unpin once the list reloads", async () => {
    const calls = serve((c, d) => {
      if (c.url.endsWith("/pin/v4")) {
        d.items[0]!.versions[0]!.pinned = c.method === "POST";
        return new Response(null, { status: 204 });
      }
    });
    render(Game);
    const pin = await screen.findByRole("button", { name: "Pin" });
    await fireEvent.click(pin);
    expect(await screen.findByRole("button", { name: "Unpin" })).toBeTruthy();
    expect(calls[0]).toMatchObject({ method: "POST", url: "/v1/saves/item-save/pin/v4" });
    expect(dispatch("x")).toBe(true);
    await screen.findByRole("button", { name: "Pin" });
    expect(calls[1]).toMatchObject({ method: "DELETE" });
  });

  it("downloads through a plain link to the version's download url", async () => {
    serve();
    render(Game);
    await screen.findByRole("heading", { name: "v4 from Odin 3" });
    const a = screen.getByRole("link", { name: "Download" });
    expect(a.getAttribute("href")).toBe("/v1/saves/versions/v4/download");
    expect(a.hasAttribute("download")).toBe(true);
  });

  const file = () => new File(["x"], "save.sav");
  const upload = () => screen.getByLabelText("Save file", { selector: "input" }) as HTMLInputElement;

  it("uploads the chosen file as the focused item's kind, slot and core", async () => {
    const calls = serve((c) => (c.url.endsWith("/upload") ? json(200, { status: "Accepted", itemId: "i", versionId: "v", headVersionId: "v" }) : undefined));
    render(Game);
    await screen.findByRole("heading", { name: "v4 from Odin 3" });
    await fireEvent.change(upload(), { target: { files: [file()] } });
    await screen.findByText("Uploaded save.sav.");
    const form = calls[0]!.body as FormData;
    expect(calls[0]!.url).toBe("/v1/web/saves/upload");
    expect([form.get("emulatorId"), form.get("kind"), form.get("gameKey"), form.get("slot"), form.get("core")])
      .toEqual(["melonds", "Save", "nds/platinum", "", "melonds"]);
    expect((form.getAll("files") as File[]).map((f) => f.name)).toEqual(["save.sav"]);
  });

  it("shows a 413 in place and leaves the version list as it was", async () => {
    serve((c) => (c.url.endsWith("/upload") ? json(413, { code: "error", message: "big" }) : undefined));
    render(Game);
    await screen.findByText("v4");
    await fireEvent.change(upload(), { target: { files: [file()] } });
    expect((await screen.findByRole("alert")).textContent).toContain("That file is too large");
    expect(screen.getAllByText(/^v[1-4]$/)).toHaveLength(4);
  });

  it("explains a 409 conflict_open and links to the conflict", async () => {
    serve((c) => (c.url.endsWith("/upload") ? json(409, { code: "conflict_open", message: "x" }) : undefined));
    render(Game);
    await screen.findByText("v4");
    await fireEvent.change(upload(), { target: { files: [file()] } });
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("This game has two saves waiting for a choice. Resolve that first.");
    const link = within(alert.parentElement!).getByRole("link", { name: /conflict/i });
    expect(link.getAttribute("href")).toBe(gamePath("melonds", "nds/platinum") + "/conflict");
  });

  it("goes back to Games on b", async () => {
    serve();
    render(Game);
    await screen.findByText("v4");
    expect(dispatch("b")).toBe(true);
    await waitFor(() => expect(router.path).toBe("/games"));
  });
});
