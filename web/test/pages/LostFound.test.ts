import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import Vault from "../../src/pages/Vault.svelte";
import { router } from "../../src/lib/router.svelte";
import { devices } from "../fixtures/devices";
import { bios } from "../fixtures/vault";
import type { LostItemRow, SaveUploadStatus } from "../../src/lib/api/types";

class FakeEventSource {
  readyState = 0;
  addEventListener() {}
  close() {}
}
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const HOUR = 3600_000;
const save: LostItemRow = {
  id: "lost-save", emulatorId: "retroarch", kind: "Save", gameKey: "retroarch/Pokemon - Emerald Version", slot: "", core: "mgba",
  displayName: "Pokémon Emerald", totalSize: 131072, reason: "game_deleted", lostUtc: new Date(Date.now() - 2 * HOUR).toISOString(),
};
const state: LostItemRow = {
  id: "lost-state", emulatorId: "melonds", kind: "State", gameKey: "nds/platinum", slot: "3", core: "",
  displayName: null, totalSize: 2048, reason: "game_deleted", lostUtc: new Date(Date.now() - 2 * HOUR).toISOString(),
};

function serve(list: LostItemRow[], restore: () => Response = () => json({ status: "Accepted" satisfies SaveUploadStatus, itemId: "i", versionId: "v", headVersionId: "v" })) {
  const calls: { method: string; url: string }[] = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url.endsWith("/v1/vault")) return json([bios]);
    if (method === "GET" && url.endsWith("/v1/devices")) return json(devices);
    if (method === "GET" && url.endsWith("/v1/web/lost")) return json(list);
    calls.push({ method, url });
    if (url.endsWith("/restore")) return restore();
    return new Response(null, { status: 204 });
  }));
  return calls;
}

async function openItem(name: string) {
  await fireEvent.click((await screen.findByText(name)).closest("button") as HTMLElement);
  return screen.findByRole("dialog");
}

describe("Vault: Lost & found", () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.removeAttribute("open"); };
    router.go("/vault?s=lost");
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("is a section of the Vault, next to the system files", async () => {
    serve([save]);
    render(Vault);
    const tabs = await screen.findByRole("navigation", { name: "Sections" });
    expect(within(tabs).getByRole("link", { name: "Lost & found" }).getAttribute("aria-current")).toBe("page");
    await fireEvent.click(within(tabs).getByRole("link", { name: "System files" }));
    expect(await screen.findByText("PlayStation BIOS")).toBeTruthy();
    expect(router.search).toBe("");
  });

  it("says what it is when it is empty", async () => {
    serve([]);
    render(Vault);
    expect(await screen.findByText("Saves from games you delete land here, so nothing is lost by accident.")).toBeTruthy();
  });

  it("lists the game, emulator, what it is, its size and when and why it was lost", async () => {
    serve([save, state]);
    render(Vault);
    const row = within((await screen.findByText("Pokémon Emerald")).closest("button") as HTMLElement);
    expect(row.getByText("RetroArch")).toBeTruthy();
    expect(row.getByText("Save")).toBeTruthy();
    expect(row.getByText("128 KB")).toBeTruthy();
    expect(row.getByText("2 h ago, game deleted")).toBeTruthy();
    // No display name: the key stands in.
    const other = within(screen.getByText("nds/platinum").closest("button") as HTMLElement);
    expect(other.getByText("State · slot 3")).toBeTruthy();
  });

  it("restores the chosen save and says devices get it", async () => {
    const calls = serve([save]);
    render(Vault);
    const dialog = await openItem("Pokémon Emerald");
    const download = within(dialog).getByRole("link", { name: /Download/ });
    expect(download.getAttribute("href")).toBe("/v1/web/lost/lost-save/download");
    await fireEvent.click(within(dialog).getByRole("button", { name: /Restore/ }));
    await waitFor(() => expect(calls).toContainEqual({ method: "POST", url: "/v1/web/lost/lost-save/restore" }));
    expect(await screen.findByText("Pokémon Emerald is back. Every device with the game gets it next time Tandem syncs there.")).toBeTruthy();
  });

  it("says when a restore landed next to a newer save, with the way to the conflict", async () => {
    serve([save], () => json({ status: "Conflict", itemId: "i", versionId: "v2", headVersionId: "v1" }));
    render(Vault);
    const dialog = await openItem("Pokémon Emerald");
    await fireEvent.click(within(dialog).getByRole("button", { name: /Restore/ }));
    expect(await screen.findByText("Pokémon Emerald already has a newer save, so this one is back beside it as a conflict. Choose which to keep.")).toBeTruthy();
    const link = screen.getByRole("link", { name: "Open the conflict" });
    expect(link.getAttribute("href")).toBe("/games/retroarch/retroarch/Pokemon%20-%20Emerald%20Version/conflict");
  });

  it("shows a refused restore in the dialog", async () => {
    serve([save], () => json({ code: "conflict_open", message: "Resolve the open conflict before restoring." }, 409));
    render(Vault);
    const dialog = await openItem("Pokémon Emerald");
    await fireEvent.click(within(dialog).getByRole("button", { name: /Restore/ }));
    expect((await within(dialog).findByRole("alert")).textContent).toBe("This game has two saves waiting for a choice. Resolve that first.");
  });

  it("deletes for good only after a confirm", async () => {
    const calls = serve([save]);
    render(Vault);
    const dialog = await openItem("Pokémon Emerald");
    await fireEvent.click(within(dialog).getByRole("button", { name: "Delete for good…" }));
    const confirm = await screen.findByRole("heading", { name: "Delete Pokémon Emerald for good?" });
    expect(calls.filter((c) => c.method === "DELETE")).toHaveLength(0);
    const box = confirm.closest("dialog") as HTMLElement;
    expect(within(box).getByText("Its files leave the server and can't be brought back.")).toBeTruthy();
    await fireEvent.click(within(box).getByRole("button", { name: "Delete for good" }));
    await waitFor(() => expect(calls).toContainEqual({ method: "DELETE", url: "/v1/web/lost/lost-save" }));
    expect(await screen.findByText("Deleted Pokémon Emerald for good.")).toBeTruthy();
  });
});
