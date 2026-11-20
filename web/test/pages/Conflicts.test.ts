import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import Conflicts from "../../src/pages/Conflicts.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { gamePath } from "../../src/lib/api/paths";
import type { ConflictRow } from "../../src/lib/api/types";
import { concurrent, firstSync, row, side, thorSide } from "../fixtures/conflicts";
import { AYN, ODIN, THOR } from "../fixtures/home";

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

describe("Conflicts", () => {
  beforeEach(() => router.go("/conflicts"));
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("lists every conflict with what differs, in fault", async () => {
    serve([firstSync, concurrent]);
    render(Conflicts);
    await screen.findByText("Pokémon Brilliant Diamond");
    expect(screen.getByText("Pokémon Sword")).toBeTruthy();
    expect(screen.getByText("Before first sync").classList.contains("fault")).toBe(true);
    expect(screen.getByText("Changed on both").classList.contains("fault")).toBe(true);
  });

  it("opens a conflict's page", async () => {
    serve([firstSync]);
    render(Conflicts);
    const b = (await screen.findByText("Pokémon Brilliant Diamond")).closest("button")!;
    await fireEvent.click(b);
    expect(router.path).toBe(gamePath("eden", "0100000011D90000") + "/conflict");
  });

  it("with no conflicts says there is nothing to decide", async () => {
    serve([]);
    render(Conflicts);
    await screen.findByText("Nothing to decide.");
  });

  it("with two devices, the bulk actions sit on X and Y and call bulkFirstSync for that device", async () => {
    const calls = serve([firstSync, row({ itemId: "item-2", gameKey: "K2", displayName: "Two" }), concurrent]);
    render(Conflicts);
    await screen.findByText("Pokémon Brilliant Diamond");
    dispatch("x");
    await waitFor(() => expect(calls.length).toBe(1));
    expect(calls[0]).toEqual({
      method: "POST",
      url: "/v1/conflicts/first-sync/resolve",
      body: { deviceId: THOR, choice: "UseDevice", itemIds: ["item-bd", "item-2"] },
    });
    await new Promise((r) => setTimeout(r, 0));
    dispatch("y");
    await waitFor(() => expect(calls.length).toBe(2));
    expect(calls[1]!.body.deviceId).toBe(ODIN);
  });

  it("with more than two devices, the bulk actions are buttons", async () => {
    const third = side({ versionId: "v-ayn", deviceId: AYN, deviceName: "Ayn" });
    const calls = serve([row({ sides: [thorSide, { ...thorSide, versionId: "v-o", deviceId: ODIN, deviceName: "Odin 3" }, third] })]);
    render(Conflicts);
    await screen.findByText("Pokémon Brilliant Diamond");
    await fireEvent.click(screen.getByRole("button", { name: "Use Ayn's for all first-sync games" }));
    await waitFor(() => expect(calls.length).toBe(1));
    expect(calls[0]!.body).toEqual({ deviceId: AYN, choice: "UseDevice", itemIds: ["item-bd"] });
  });

  it("offers no bulk action when no conflict is a first sync", async () => {
    serve([concurrent]);
    render(Conflicts);
    await screen.findByText("Pokémon Sword");
    expect(screen.queryByText(/for all first-sync games/)).toBeNull();
  });
});
