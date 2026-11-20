import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import App from "../src/App.svelte";
import { router } from "../src/lib/router.svelte";
import { dispatch } from "../src/lib/input/actions";
import { activeTab } from "../src/lib/nav";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("App", () => {
  class FakeEventSource { readyState = 0; addEventListener() {} close() {} }
  beforeEach(() => vi.stubGlobal("EventSource", FakeEventSource));
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  const signedIn = (devices: unknown[] = []) =>
    vi.fn(async (url: string) =>
      json(200, url.endsWith("/v1/devices") ? devices : { id: "1", email: "a@b.c", isAdmin: false }));

  it("shows the page tabs and device status when signed in, and r1 moves to the next page", async () => {
    vi.stubGlobal("fetch", signedIn([{
      id: "d1", name: "Thor", platform: "android", lastSeenUtc: new Date().toISOString(),
      shizukuOk: false, isCurrent: false, runningEmulators: [],
    }]));
    router.go("/games");
    render(App);
    const games = await screen.findByRole("link", { name: "Games" });
    expect(games.getAttribute("aria-current")).toBe("page");
    expect(await screen.findByText("Shizuku off")).toBeTruthy();
    dispatch("r1");
    expect(router.path).toBe("/devices");
  });

  it("has no top bar on the sign-in page", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(401, { code: "unauthorized", message: "x" })));
    router.go("/sign-in");
    render(App);
    expect(screen.queryByRole("link", { name: "Games" })).toBeNull();
  });

  it("shows a network failure in place with a retry instead of a blank page", async () => {
    router.go("/vault");
    let up = false;
    vi.stubGlobal("fetch", vi.fn(async () => {
      if (!up) throw new TypeError("offline");
      return json(200, { id: "1", email: "a@b.c", isAdmin: false });
    }));
    render(App);
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("Can't reach the server. Check your connection and try again.");
    up = true;
    await fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("heading", { name: "Vault" })).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("activeTab", () => {
  it("maps every route to its page tab", () => {
    expect(activeTab("/")).toBe("/");
    expect(activeTab("/games/nds/x")).toBe("/games");
    expect(activeTab("/conflicts")).toBe("/");
    expect(activeTab("/devices/d1/setup")).toBe("/devices");
    expect(activeTab("/configs/eden")).toBe("/configs");
    expect(activeTab("/settings")).toBe("/settings");
  });
});
