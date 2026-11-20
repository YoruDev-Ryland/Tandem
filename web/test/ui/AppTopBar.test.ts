import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/svelte";
import AppTopBar from "../../src/lib/ui/AppTopBar.svelte";
import { router } from "../../src/lib/router.svelte";
import { game, dev, THOR, ODIN } from "../fixtures/home";
import type { Topic } from "../../src/lib/api/types";

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
const devices = [
  { id: THOR, name: "Thor", platform: "android", lastSeenUtc: new Date().toISOString(), shizukuOk: true, isCurrent: false, runningEmulators: ["eden"] },
];
const waitingRow = game({ emulatorId: "eden", gameKey: "k", devices: [dev(ODIN, "Newest"), dev(THOR, "Waiting")] });
const idleRow = game({ emulatorId: "eden", gameKey: "k", devices: [dev(ODIN, "Newest"), dev(THOR, "HasIt")] });

let emit: (t: Topic) => void;
class FakeEventSource {
  readyState = 1;
  private l: ((e: Event) => void)[] = [];
  constructor() { emit = (topic) => this.l.forEach((f) => f(Object.assign(new Event("changed"), { data: JSON.stringify({ topic }) }))); }
  addEventListener(type: string, f: (e: Event) => void) { if (type === "changed") this.l.push(f); }
  close() {}
}

describe("AppTopBar waiting state", () => {
  let rows = [waitingRow];
  beforeEach(() => {
    rows = [waitingRow];
    vi.stubGlobal("EventSource", FakeEventSource);
    vi.stubGlobal("fetch", vi.fn(async (url: string) => json(url.includes("/v1/devices") ? devices : rows)));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("shows Eden open in --wait on a non-Home route, and clears it when nothing waits", async () => {
    router.go("/devices");
    render(AppTopBar);
    const el = await screen.findByText("Eden open");
    await vi.waitFor(() => expect(el.classList.contains("wait")).toBe(true));
    rows = [idleRow];
    emit("saves");
    await vi.waitFor(() => expect(screen.getByText("Eden open").classList.contains("wait")).toBe(false));
  });
});
