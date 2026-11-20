import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import Conflict from "../../src/pages/Conflict.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { gamePath } from "../../src/lib/api/paths";
import type { ConflictRow } from "../../src/lib/api/types";
import { concurrent, firstSync, odinSide, row, side, thorSide } from "../fixtures/conflicts";
import { AYN } from "../fixtures/home";

class FakeEventSource { readyState = 0; addEventListener() {} close() {} }
const json = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

interface Call { method: string; url: string; body: any }
function serve(rows: ConflictRow[]) {
  const calls: Call[] = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET") return json(rows);
    calls.push({ method, url, body: init?.body ? JSON.parse(init.body as string) : undefined });
    return new Response(null, { status: 204 });
  }));
  return calls;
}
const at = (r: ConflictRow) => router.go(gamePath(r.emulatorId, r.gameKey) + "/conflict");

describe("Conflict", () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.removeAttribute("open"); };
    at(firstSync);
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("titles the page in fault and shows both sides with size, count and fingerprint", async () => {
    serve([firstSync]);
    render(Conflict);
    const h = await screen.findByRole("heading", { name: "Two different saves" });
    expect(h.classList.contains("fault")).toBe(true);
    expect(screen.getByRole("heading", { name: "Thor" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Odin 3" })).toBeTruthy();
    expect(screen.getAllByText("1.0 MB · 3 files").length).toBe(2);
    expect(screen.getByText("9f2c41a0")).toBeTruthy();
    expect(screen.getByText("3b7e08d5")).toBeTruthy();
    expect(screen.getAllByText("Received").length).toBe(2);
    expect(screen.getByText("Nothing has been overwritten.")).toBeTruthy();
    expect(screen.getByText("Both saves stay in history either way.")).toBeTruthy();
  });

  it("reads differently for first sync and concurrent edits", async () => {
    serve([firstSync]);
    render(Conflict);
    await screen.findByText("Thor and Odin 3 each had a save before they first synced.");
    cleanup();
    at(concurrent);
    serve([concurrent]);
    render(Conflict);
    await screen.findByText("Both devices changed this save before syncing.");
    expect(screen.queryByText(/first synced/)).toBeNull();
  });

  it("Keep Thor's resolves with that side and goes back to the game page", async () => {
    const calls = serve([firstSync]);
    render(Conflict);
    await fireEvent.click(await screen.findByRole("button", { name: "Keep Thor's" }));
    await waitFor(() => expect(router.path).toBe(gamePath("eden", "0100000011D90000")));
    expect(calls).toEqual([{
      method: "POST",
      url: "/v1/conflicts/item-bd/resolve",
      body: { choice: "KeepVersion", keepVersionId: "v-thor", variantName: null },
    }]);
  });

  it("A keeps the focused side and left/right move between sides", async () => {
    const calls = serve([firstSync]);
    render(Conflict);
    await screen.findByRole("button", { name: "Keep Thor's" });
    dispatch("right");
    dispatch("a");
    await waitFor(() => expect(calls.length).toBe(1));
    expect(calls[0]!.body.keepVersionId).toBe("v-odin");
  });

  it("Keep both defaults the name to '{other device} run' and sends it, keeping the focused side", async () => {
    const calls = serve([firstSync]);
    render(Conflict);
    const name = (await screen.findByLabelText("Name the other")) as HTMLInputElement;
    expect(name.value).toBe("Odin 3 run");
    await fireEvent.click(screen.getByRole("button", { name: "Keep both" }));
    await fireEvent.click(await screen.findByRole("button", { name: "Keep both and continue" }));
    await waitFor(() => expect(calls.length).toBe(1));
    expect(calls[0]!.body).toEqual({ choice: "KeepBoth", keepVersionId: "v-thor", variantName: "Odin 3 run" });
  });

  it("Keep both with the other side focused names the first side and sends an edited name", async () => {
    const calls = serve([firstSync]);
    render(Conflict);
    const name = (await screen.findByLabelText("Name the other")) as HTMLInputElement;
    dispatch("right");
    await waitFor(() => expect(name.value).toBe("Thor run"));
    await fireEvent.input(name, { target: { value: "Backup" } });
    dispatch("x");
    await fireEvent.click(await screen.findByRole("button", { name: "Keep both and continue" }));
    await waitFor(() => expect(calls.length).toBe(1));
    expect(calls[0]!.body).toEqual({ choice: "KeepBoth", keepVersionId: "v-odin", variantName: "Backup" });
  });

  it("offers a plain download link for each side", async () => {
    serve([firstSync]);
    render(Conflict);
    await screen.findByRole("heading", { name: "Thor" });
    const links = screen.getAllByRole("link", { name: /Download/ });
    expect(links.map((l) => l.getAttribute("href"))).toEqual([
      "/v1/saves/versions/v-thor/download",
      "/v1/saves/versions/v-odin/download",
    ]);
  });

  it("shows a section per item, save first, for a game with a state slot too", async () => {
    const slot = row({ itemId: "item-slot", kind: "State", slot: "2" });
    serve([slot, firstSync, concurrent]);
    render(Conflict);
    await screen.findByRole("heading", { name: "Two different saves" });
    const heads = screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent);
    expect(heads).toEqual(["Save", "Thor", "Odin 3", "Slot 2", "Thor", "Odin 3"]);
  });

  it("lays out three sides", async () => {
    const third = side({ versionId: "v-ayn", deviceId: AYN, deviceName: "Ayn", manifestHash: "aaaa1111bbbb" });
    serve([row({ sides: [thorSide, odinSide, third] })]);
    render(Conflict);
    await screen.findByText("Thor, Odin 3 and Ayn each had a save before they first synced.");
    expect(screen.getByRole("button", { name: "Keep Ayn's" })).toBeTruthy();
  });

  it("B goes back to the game page when the page was opened directly", async () => {
    serve([firstSync]);
    history.replaceState({ i: 0 }, "", router.path);
    dispatchEvent(new PopStateEvent("popstate"));
    render(Conflict);
    await screen.findByRole("heading", { name: "Thor" });
    dispatch("b");
    expect(router.path).toBe(gamePath("eden", "0100000011D90000"));
  });

  it("B steps back through history when there is some", async () => {
    serve([firstSync]);
    const back = vi.spyOn(history, "back").mockImplementation(() => {});
    render(Conflict);
    await screen.findByRole("heading", { name: "Thor" });
    dispatch("b");
    expect(back).toHaveBeenCalled();
    back.mockRestore();
  });

  it("says so when the game has no conflict", async () => {
    serve([]);
    render(Conflict);
    await screen.findByText("Nothing to decide for this game.");
  });

  it("acts on the second item when the cursor moves down to it", async () => {
    const slot = row({
      itemId: "item-slot", kind: "State", slot: "2",
      sides: [{ ...thorSide, versionId: "slot-thor" }, { ...odinSide, versionId: "slot-odin" }],
    });
    const calls = serve([firstSync, slot]);
    render(Conflict);
    await screen.findByRole("heading", { name: "Two different saves" });
    dispatch("down");
    dispatch("right");
    dispatch("a");
    await waitFor(() => expect(calls.length).toBe(1));
    expect(calls[0]!.url).toContain("item-slot");
    expect(calls[0]!.body.keepVersionId).toBe("slot-odin");
  });

  it("Keep both on the second item sends that item and its side", async () => {
    const slot = row({
      itemId: "item-slot", kind: "State", slot: "2",
      sides: [{ ...thorSide, versionId: "slot-thor" }, { ...odinSide, versionId: "slot-odin" }],
    });
    const calls = serve([firstSync, slot]);
    render(Conflict);
    await screen.findByRole("heading", { name: "Two different saves" });
    await fireEvent.click(screen.getAllByRole("button", { name: "Keep both" })[1]!);
    await fireEvent.click(await screen.findByRole("button", { name: "Keep both and continue" }));
    await waitFor(() => expect(calls.length).toBe(1));
    expect(calls[0]!.url).toContain("item-slot");
    expect(calls[0]!.body).toEqual({ choice: "KeepBoth", keepVersionId: "slot-thor", variantName: "Odin 3 run" });
  });

  it("with three sides, Keep both lets the server label each kept-aside save and the dialog names them all", async () => {
    const third = side({ versionId: "v-ayn", deviceId: AYN, deviceName: "Ayn", manifestHash: "aaaa1111bbbb" });
    const calls = serve([row({ sides: [thorSide, odinSide, third] })]);
    render(Conflict);
    await screen.findByRole("heading", { name: "Thor" });
    expect(screen.queryByLabelText("Name the other")).toBeNull();
    await fireEvent.click(screen.getByRole("button", { name: "Keep both" }));
    await screen.findByText(/Odin 3's and Ayn's stay on the server/);
    await fireEvent.click(screen.getByRole("button", { name: "Keep both and continue" }));
    await waitFor(() => expect(calls.length).toBe(1));
    expect(calls[0]!.body).toEqual({ choice: "KeepBoth", keepVersionId: "v-thor", variantName: null });
  });
});
