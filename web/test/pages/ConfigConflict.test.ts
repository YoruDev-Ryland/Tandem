import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import ConfigConflict from "../../src/pages/ConfigConflict.svelte";
import Configs from "../../src/pages/Configs.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { flash } from "../../src/lib/ui/flash.svelte";
import type { ConfigOverview, ConfigVersionSummary } from "../../src/lib/api/types";
import { AYN_BUNDLE, cversion, diff, groups, overview, rpVersions, RP_BUNDLE } from "../fixtures/configs";

class FakeEventSource { readyState = 0; addEventListener() {} close() {} }
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

interface Call { method: string; url: string; body: unknown }

// The group's current version is v7 (from Odin 3); Thor's v8 arrived on top of v6 and waits for a choice.
const versions: ConfigVersionSummary[] = [
  cversion(8, { deviceName: "Thor", conflictReason: "Concurrent" }),
  cversion(7),
  cversion(6),
];

function serve(write: (c: Call) => Response | undefined = () => undefined, list = versions) {
  const data: ConfigOverview = structuredClone(overview);
  data.emulators[0]!.groups[0]!.openConflict = true;
  const calls: Call[] = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const c = { method, url, body: init?.body ? JSON.parse(init.body as string) : undefined };
    calls.push(c);
    const answer = write(c);
    if (answer) return answer;
    if (method === "GET") {
      if (url === "/v1/web/configs") return json(200, data);
      if (url === "/v1/configs/groups") return json(200, groups);
      if (url === `/v1/configs/bundles/${AYN_BUNDLE}/versions`) return json(200, list);
      if (url === `/v1/configs/bundles/${RP_BUNDLE}/versions`) return json(200, rpVersions);
      if (url.startsWith("/v1/configs/diff")) return json(200, diff);
    }
    return new Response(null, { status: 204 });
  }));
  return calls;
}

describe("Config conflict", () => {
  beforeEach(() => router.go(`/configs/retroarch/conflict/${AYN_BUNDLE}`));
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); flash.clear(); });

  it("shows both versions, the current one first, and what differs between them", async () => {
    const calls = serve();
    render(ConfigConflict);
    expect(await screen.findByRole("heading", { name: "Two versions of the RetroArch config" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: /^Odin 3/ }).textContent).toContain("v7 · current");
    expect(screen.getByRole("heading", { name: /^Thor/ }).textContent).toContain("v8");
    expect(screen.getByText(/Odin 3 and Thor changed it before syncing/)).toBeTruthy();
    await waitFor(() => expect(calls.some((c) => c.url === "/v1/configs/diff?a=cv7&b=cv8")).toBe(true));
    expect(screen.getByText("Nothing has been overwritten. The other version stays in history either way.")).toBeTruthy();
  });

  it("keeps the chosen version, says so in the hint bar, and returns to Configs", async () => {
    const calls = serve();
    render(ConfigConflict);
    await screen.findByRole("heading", { name: "Two versions of the RetroArch config" });
    await fireEvent.click(screen.getByRole("button", { name: "Keep Thor's" }));
    await waitFor(() => expect(router.path).toBe("/configs/retroarch"));
    expect(calls).toContainEqual({
      method: "POST", url: `/v1/configs/bundles/${AYN_BUNDLE}/conflicts/resolve`,
      body: { choice: "KeepVersion", keepVersionId: "cv8", variantName: null },
    });
    expect(flash.text).toBe("Kept Thor's RetroArch config · every device in AYN handhelds gets it");
  });

  it("moves between the two with left and right and keeps the focused one on A", async () => {
    const calls = serve();
    render(ConfigConflict);
    await screen.findByRole("heading", { name: "Two versions of the RetroArch config" });
    dispatch("right");
    dispatch("left");
    dispatch("a");
    await waitFor(() => expect(calls.some((c) => c.method === "POST")).toBe(true));
    expect(calls.find((c) => c.method === "POST")!.body).toMatchObject({ keepVersionId: "cv7" });
  });

  it("shows a refusal in place and stays", async () => {
    serve((c) => (c.method === "POST" ? json(409, { code: "no_conflict", message: "This config has no open conflict." }) : undefined));
    render(ConfigConflict);
    await screen.findByRole("heading", { name: "Two versions of the RetroArch config" });
    await fireEvent.click(screen.getByRole("button", { name: "Keep Odin 3's" }));
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(router.path).toBe(`/configs/retroarch/conflict/${AYN_BUNDLE}`);
  });

  it("says there is nothing to decide once the conflict is gone", async () => {
    serve(undefined, [cversion(7), cversion(6)]);
    render(ConfigConflict);
    expect(await screen.findByText("Nothing to decide for this config.")).toBeTruthy();
  });
});

describe("Configs links its conflict", () => {
  beforeEach(() => {
    router.go("/configs/retroarch");
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("offers Choose a version for a config with two versions", async () => {
    serve();
    render(Configs);
    await fireEvent.click(await screen.findByRole("button", { name: "Choose a version" }));
    expect(router.path).toBe(`/configs/retroarch/conflict/${AYN_BUNDLE}`);
  });
});
