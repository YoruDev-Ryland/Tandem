import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import Game from "../../src/pages/Game.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { gamePath } from "../../src/lib/api/paths";
import type { GameDetail } from "../../src/lib/api/types";
import { dev, ODIN, THOR } from "../fixtures/home";
import { devices, platinum } from "../fixtures/game";

let emit: (topic: string) => void = () => {};
class FakeEventSource {
  readyState = 0;
  addEventListener(type: string, fn: (e: { data: string }) => void) {
    if (type === "changed") emit = (topic) => fn({ data: JSON.stringify({ topic }) });
  }
  close() {}
}
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

  it("restores for every device: no device picker, the honest copy, and an installed device for the record", async () => {
    const calls = serve();
    render(Game);
    await screen.findByRole("heading", { name: "v4 from Odin 3" });
    await fireEvent.click(screen.getByRole("button", { name: "Restore this version" }));
    expect(await screen.findByRole("heading", { name: "Restore v4 — every device gets it" })).toBeTruthy();
    expect(screen.queryByLabelText("Restore to")).toBeNull();
    expect(screen.queryByText(/Restore to/)).toBeNull();
    expect(screen.getByText("v4 becomes the current save again, and every device that has this game gets it.")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Restore v4" }));
    expect(await screen.findByText(/^v4 is the current save again\. Every device with the game gets it/)).toBeTruthy();
    expect(calls).toContainEqual({ method: "POST", url: "/v1/saves/item-save/versions/v4/restore", body: { deviceId: "d-odin" } });
  });

  it("deletes the game's saves after a confirm that says they move to Lost & found and devices upload them again", async () => {
    const calls = serve();
    render(Game);
    await screen.findByRole("heading", { name: "v4 from Odin 3" });
    await fireEvent.click(screen.getByRole("button", { name: "Delete from server…" }));
    await screen.findByRole("heading", { name: "Delete these saves from the server?" });
    expect(screen.getByText(/including 1 pinned version\. The newest copy of each moves to Lost & found in the Vault, where you can restore or download it\.$/)).toBeTruthy();
    expect(screen.queryByText(/can't be undone/)).toBeNull();
    expect(screen.getByText(
      "Devices keep their files. Devices that still have this save upload it again within about 15 minutes, as a new save without this history.",
    )).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Delete from server" }));
    await waitFor(() => expect(router.path).toBe("/games"));
    expect(calls).toContainEqual({ method: "DELETE", url: "/v1/web/games/melonds/nds/platinum", body: undefined });
  });

  it("shows the uploading device's emulator version quietly in mono", async () => {
    serve(undefined, (d) => { d.items[0]!.versions[0]!.emulatorVersion = "0.9.1"; });
    render(Game);
    await screen.findByRole("heading", { name: "v4 from Odin 3" });
    const ver = screen.getAllByText("0.9.1");
    expect(ver.some((e) => e.classList.contains("mono") && e.classList.contains("ink3"))).toBe(true);
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

  it("sends relative paths for a picked folder, asking first when the file count differs", async () => {
    const calls = serve((c) => (c.url.endsWith("/upload") ? json(200, { status: "Accepted", itemId: "i", versionId: "v", headVersionId: "v" }) : undefined));
    render(Game);
    await screen.findByText("v4");
    const a = new File(["a"], "data.dat");
    const b = new File(["b"], "x.bin");
    Object.defineProperty(a, "webkitRelativePath", { value: "Save/data.dat" });
    Object.defineProperty(b, "webkitRelativePath", { value: "Save/sub/x.bin" });
    await fireEvent.change(screen.getByLabelText("Save folder"), { target: { files: [a, b] } });
    expect(await screen.findByText("This save has 1 file; you're uploading 2. Upload anyway?")).toBeTruthy();
    expect(calls).toHaveLength(0);
    await fireEvent.click(screen.getByRole("button", { name: "Upload anyway" }));
    await screen.findByText(/^Uploaded /);
    const form = calls[0]!.body as FormData;
    expect((form.getAll("files") as File[]).map((f) => f.name)).toEqual(["data.dat", "sub/x.bin"]);
  });

  it("does not send anything when the mismatch is cancelled", async () => {
    const calls = serve();
    render(Game);
    await screen.findByText("v4");
    await fireEvent.change(upload(), { target: { files: [file(), new File(["y"], "b.sav")] } });
    await screen.findByText(/you're uploading 2/);
    await fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(calls).toHaveLength(0);
  });

  const fileEntry = (name: string) => ({ isFile: true, isDirectory: false, name, file: (ok: (f: File) => void) => ok(new File(["x"], name)) });
  const dirEntry = (name: string, children: unknown[], fail = false) => ({
    isFile: false, isDirectory: true, name,
    createReader() {
      let done = false;
      return { readEntries(ok: (e: unknown[]) => void, no: () => void) { if (fail) return no(); ok(done ? [] : ((done = true), children)); } };
    },
  });
  const dropOf = (entries: unknown[]) =>
    fireEvent(window, Object.assign(new Event("drop", { bubbles: true, cancelable: true }), {
      dataTransfer: { types: ["Files"], files: [], items: entries.map((e) => ({ kind: "file", webkitGetAsEntry: () => e })) },
    }));

  it("walks a dropped folder and sends paths relative to it", async () => {
    const calls = serve((c) => (c.url.endsWith("/upload") ? json(200, { status: "Accepted", itemId: "i", versionId: "v", headVersionId: "v" }) : undefined));
    render(Game);
    await screen.findByText("v4");
    await dropOf([dirEntry("Save", [dirEntry("sub", [fileEntry("x.bin")])])]);
    await screen.findByText("Uploaded sub/x.bin.");
    expect((calls[0]!.body as FormData).getAll("files").map((f) => (f as File).name)).toEqual(["sub/x.bin"]);
  });

  it("says so when a dropped folder cannot be read, and sends nothing", async () => {
    const calls = serve();
    render(Game);
    await screen.findByText("v4");
    await dropOf([dirEntry("Save", [], true)]);
    expect((await screen.findByRole("alert")).textContent).toContain("Couldn't read that folder");
    expect(calls).toHaveLength(0);
  });

  it("restores the version chosen when the dialog opened, even if a newer one arrives meanwhile", async () => {
    const calls = serve(undefined, undefined);
    render(Game);
    await screen.findByRole("heading", { name: "v4 from Odin 3" });
    await fireEvent.click(screen.getByRole("button", { name: "Restore this version" }));
    await screen.findByRole("heading", { name: "Restore v4 — every device gets it" });
    // A newer version lands on the server and the page reloads behind the open dialog.
    const next = structuredClone(platinum);
    next.items[0]!.versions.unshift({ ...next.items[0]!.versions[0]!, id: "v5", seq: 5 });
    (fetch as ReturnType<typeof vi.fn>).mockImplementation(async (url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET") return json(200, url.endsWith("/v1/devices") ? devices : next);
      calls.push({ method: init!.method!, url, body: init?.body ? JSON.parse(init.body as string) : undefined });
      return new Response(null, { status: 204 });
    });
    emit("saves");
    await screen.findByText("v5");
    await fireEvent.click(screen.getByRole("button", { name: "Restore v4" }));
    await screen.findByText(/^v4 is the current save again/);
    expect(calls.at(-1)!.url).toBe("/v1/saves/item-save/versions/v4/restore");
  });

  it("keeps the cursor on the same version when a reload inserts one above it", async () => {
    serve();
    render(Game);
    await screen.findByRole("heading", { name: "v4 from Odin 3" });
    dispatch("down");
    await screen.findByRole("heading", { name: "v3 from Thor" });
    const next = structuredClone(platinum);
    next.items[0]!.versions.unshift({ ...next.items[0]!.versions[0]!, id: "v5", seq: 5 });
    (fetch as ReturnType<typeof vi.fn>).mockImplementation(async (url: string) => json(200, url.endsWith("/v1/devices") ? devices : next));
    emit("saves");
    await screen.findByText("v5");
    expect(screen.getByRole("heading", { name: "v3 from Thor" })).toBeTruthy();
  });

  it("goes back in history when the user came from another page, else to Games", async () => {
    serve();
    render(Game);
    await screen.findByText("v4");
    const back = vi.spyOn(router, "back").mockImplementation(() => {});
    expect(router.canGoBack).toBe(true);
    dispatch("b");
    expect(back).toHaveBeenCalled();
    back.mockRestore();
    // A page opened directly has no in-app history behind it.
    history.replaceState(null, "", gamePath("melonds", "nds/platinum"));
    window.dispatchEvent(new PopStateEvent("popstate"));
    expect(router.canGoBack).toBe(false);
    dispatch("b");
    await waitFor(() => expect(router.path).toBe("/games"));
  });

  it("frames the device line: all synced", async () => {
    serve();
    render(Game);
    await screen.findByText("v4");
    expect(document.querySelector(".devices")!.textContent).toBe("installed on Odin 3 and Thor · both have the newest save");
  });

  it("frames the device line: mixed states keep the shared copy and colours", async () => {
    serve(undefined, (d) => { d.row.devices = [dev(ODIN, "Newest"), dev(THOR, "Behind"), dev("d-ayn", "NotInstalled")]; });
    render(Game);
    await screen.findByText("v4");
    const line = document.querySelector(".devices") as HTMLElement;
    expect(line.textContent).toBe("newest on Odin 3 · Thor, older save · not on Ayn");
    expect(within(line).getByText("not on Ayn").classList.contains("ink3")).toBe(true);
  });
});
