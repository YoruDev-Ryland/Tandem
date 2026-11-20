import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import Setup from "../../src/pages/Setup.svelte";
import { router } from "../../src/lib/router.svelte";
import { checklist, odin, odinDetail, ODIN_ID } from "../fixtures/devices";
import type { ConfigOverview, SetupChecklist } from "../../src/lib/api/types";

let emit: (topic: string) => void = () => {};
class FakeEventSource {
  readyState = 0;
  addEventListener(type: string, fn: (e: { data: string }) => void) {
    if (type === "changed") emit = (topic) => fn({ data: JSON.stringify({ topic }) });
  }
  close() {}
}
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

const overview: ConfigOverview = {
  emulators: [{
    emulatorId: "eden",
    groups: [
      { id: "g1", name: "Handhelds", members: [{ id: "d-thor", name: "Thor" }], headVersionId: "v9", openConflict: false },
      { id: "g2", name: "Mine already", members: [{ id: ODIN_ID, name: "Odin 3" }], headVersionId: "v8", openConflict: false },
    ],
    solo: [
      { device: { id: ODIN_ID, name: "Odin 3" }, bundleId: "b2", headVersionId: "v2", openConflict: false },
      { device: { id: "d-ayn", name: "Ayn" }, bundleId: "b3", headVersionId: "v3", openConflict: false },
    ],
  }],
};

function serve(list: SetupChecklist = checklist) {
  const calls: { method: string; url: string }[] = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url.endsWith("/setup")) return json(list);
    if (method === "GET" && url.includes("/v1/web/devices/")) return json(odinDetail);
    if (method === "GET" && url.endsWith("/v1/web/configs")) return json(overview);
    if (method === "GET" && url.endsWith("/v1/configs/groups")) return json({ items: [] });
    calls.push({ method, url });
    return new Response(null, { status: 204 });
  }));
  return calls;
}

describe("Setup", () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.removeAttribute("open"); };
    router.go(`/devices/${ODIN_ID}/setup`);
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("titles the page and counts the done steps", async () => {
    serve();
    render(Setup);
    await screen.findByRole("heading", { name: "Set up Odin 3 again" });
    expect(screen.getByText("2").closest("p")!.textContent).toBe("2 of 7 done");
  });

  it("uses the plain title for a device with no history", async () => {
    serve();
    const empty = { ...odinDetail, storageBytes: 0, installs: [], configs: [] };
    (fetch as ReturnType<typeof vi.fn>).mockImplementation(async (url: string) => {
      if (url.endsWith("/setup")) return json(checklist);
      return json(url.includes("/v1/web/devices/") ? empty : overview);
    });
    render(Setup);
    await screen.findByRole("heading", { name: "Set up Odin 3" });
  });

  it("groups the steps in two columns by kind, in order", async () => {
    serve();
    render(Setup);
    await screen.findByText("Install Eden");
    const left = screen.getByRole("region", { name: "Install" });
    const right = screen.getByRole("region", { name: "On the device" });
    expect(within(left).getAllByRole("heading").map((h) => h.textContent)).toEqual(["Emulators 1 of 2", "Cores 1 of 1", "Folders 0 of 1"]);
    expect(within(right).getAllByRole("heading").map((h) => h.textContent)).toEqual(["Vault 0 of 1", "Restore saves 0 of 1", "Configs 0 of 1"]);
    expect(within(left).getByText("Install Dolphin")).toBeTruthy();
    expect(within(right).getByText("Choose a Eden config")).toBeTruthy();
    expect(within(left).getByText("SD/ROMs")).toBeTruthy();
  });

  it("draws a tick for done rows and no unicode check", async () => {
    serve();
    render(Setup);
    const row = (await screen.findByText("Install Eden")).closest("li")!;
    expect(row.querySelector("svg")).toBeTruthy();
    expect(row.textContent).not.toMatch(/[✓✔]/);
  });

  it("calls keepOwn from a ChooseConfig row", async () => {
    const calls = serve();
    render(Setup);
    await screen.findByText("Choose a Eden config");
    await fireEvent.click(screen.getByRole("button", { name: "Keep this device's own" }));
    await waitFor(() => expect(calls).toContainEqual({ method: "POST", url: `/v1/web/devices/${ODIN_ID}/setup/keep-own/eden` }));
  });

  it("opens the apply dialog for a ChooseConfig row", async () => {
    serve();
    render(Setup);
    await screen.findByText("Choose a Eden config");
    await fireEvent.click(screen.getByRole("button", { name: "Use a config…" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByRole("heading", { name: "Use a Eden config on Odin 3" })).toBeTruthy();
    const options = within(dialog).getAllByRole("option").map((o) => o.textContent);
    expect(options).toEqual(["Handhelds · latest version", "Ayn · latest version"]);
    await fireEvent.click(within(dialog).getByRole("button", { name: "Continue" }));
    await waitFor(() => expect(screen.getByRole("heading", { name: "Use a Eden config on Odin 3" })).toBeTruthy());
    const apply = (await screen.findAllByRole("dialog")).find((d) => within(d).queryByText("Linked"))!;
    // The target is this device, frozen when the dialog opened.
    expect(within(apply).getByRole("option", { name: "Odin 3" })).toBeTruthy();
  });

  it("disables 'Use a config' with a reason when no other config exists", async () => {
    serve();
    (fetch as ReturnType<typeof vi.fn>).mockImplementation(async (url: string) => {
      if (url.endsWith("/setup")) return json(checklist);
      if (url.endsWith("/v1/web/configs")) return json({ emulators: [] });
      return json(odinDetail);
    });
    render(Setup);
    await screen.findByText("Choose a Eden config");
    const btn = screen.getByRole("button", { name: "Use a config…" }) as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    expect(screen.getByText("No other Eden config to use yet.")).toBeTruthy();
  });

  it("builds labels from the kind and the emulator name, with no regex on ids", async () => {
    serve({ steps: [
      { kind: "InstallEmulator", emulatorId: "a+b(", label: "Install a+b(", done: false, detail: null },
      { kind: "InstallCores", emulatorId: "eden", label: "Install eden cores", done: false, detail: "mgba, gambatte" },
      { kind: "PlaceVault", emulatorId: "eden", label: "Place eden system files", done: false, detail: "keys" },
    ] });
    render(Setup);
    await screen.findByText("Install a+b(");
    expect(screen.getByText("Install Eden cores")).toBeTruthy();
    expect(screen.getByText("mgba, gambatte")).toBeTruthy();
    expect(screen.getByText("Place Eden system files")).toBeTruthy();
  });

  it("reloads on the configs topic", async () => {
    serve();
    render(Setup);
    await screen.findByText("Install Dolphin");
    const f = fetch as ReturnType<typeof vi.fn>;
    const before = f.mock.calls.length;
    emit("configs");
    await waitFor(() => expect(f.mock.calls.length).toBeGreaterThan(before));
  });
  void odin;
});
