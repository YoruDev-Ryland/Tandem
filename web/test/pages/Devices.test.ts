import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import Devices from "../../src/pages/Devices.svelte";
import Device from "../../src/pages/Device.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { ayn, devices, noShizuku, odin, odinDetail, NOW, ODIN_ID, thor } from "../fixtures/devices";
import type { DeviceSummary } from "../../src/lib/api/types";

let emit: (topic: string) => void = () => {};
class FakeEventSource {
  readyState = 0;
  addEventListener(type: string, fn: (e: { data: string }) => void) {
    if (type === "changed") emit = (topic) => fn({ data: JSON.stringify({ topic }) });
  }
  close() {}
}
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

function serve(list: () => DeviceSummary[]) {
  const calls: { method: string; url: string; body?: unknown }[] = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url.endsWith("/v1/devices")) return json(list());
    if (method === "GET" && url.includes("/v1/web/devices/")) return json(odinDetail);
    calls.push({ method, url, body: init?.body ? JSON.parse(init.body as string) : undefined });
    return new Response(null, { status: 204 });
  }));
  return calls;
}

describe("Devices", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"], now: NOW });
    router.go("/devices");
  });
  afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("offers Pair a new device as a button without a gamepad", async () => {
    serve(() => devices);
    render(Devices);
    await screen.findByText("Odin 3");
    // The page's button (the hint bar offers the same action under its X key).
    await fireEvent.click(screen.getAllByRole("button", { name: /Pair a new device/ })[0]!);
    expect(router.path).toBe("/devices/pair");
  });

  it("lists devices with last seen and status", async () => {
    serve(() => devices);
    render(Devices);
    await screen.findByText("Odin 3");
    const row = (n: string) => within(screen.getByText(n).closest("button") as HTMLElement);
    expect(row("Thor").getByText("Eden open")).toBeTruthy();
    expect(row("Retroid").getByText("Shizuku off").classList.contains("fault")).toBe(true);
    expect(row("Ayn").getByText("offline").classList.contains("ink3")).toBe(true);
    expect(row("Odin 3").getByText("online")).toBeTruthy();
  });

  it("opens the detail on A and goes to pairing on X", async () => {
    serve(() => devices);
    render(Devices);
    await screen.findByText("Odin 3");
    await fireEvent.click(screen.getByText("Odin 3").closest("button")!);
    expect(router.path).toBe(`/devices/${ODIN_ID}`);
    router.go("/devices");
    dispatch("x");
    expect(router.path).toBe("/devices/pair");
  });

  it("drops a revoked device after the devices topic", async () => {
    let list = [odin, thor, ayn, noShizuku];
    serve(() => list);
    render(Devices);
    await screen.findByText("Thor");
    list = [odin, ayn, noShizuku];
    emit("devices");
    await waitFor(() => expect(screen.queryByText("Thor")).toBeNull());
    expect(screen.getByText("Odin 3")).toBeTruthy();
  });
});

describe("Device", () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.removeAttribute("open"); };
    router.go(`/devices/${ODIN_ID}`);
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("shows facts, emulators and configs", async () => {
    serve(() => devices);
    render(Device);
    await screen.findByRole("heading", { name: "Odin 3" });
    expect(screen.getByText("5.0 MB")).toBeTruthy();
    expect(screen.getByText("2 changes on the way").classList.contains("wait")).toBe(true);
    expect(screen.getByText("dev.eden.eden_emulator")).toBeTruthy();
    expect(screen.getByText("mgba, gambatte")).toBeTruthy();
    expect(screen.getByText("AYN handhelds")).toBeTruthy();
    expect(screen.getByText("up to date")).toBeTruthy();
    expect(screen.getByText("behind").classList.contains("wait")).toBe(true);
  });

  it("renames through a dialog", async () => {
    const calls = serve(() => devices);
    render(Device);
    await screen.findByRole("heading", { name: "Odin 3" });
    dispatch("a");
    const input = await screen.findByLabelText("Name");
    await fireEvent.input(input, { target: { value: "Odin Pro" } });
    await fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(calls).toContainEqual({ method: "PATCH", url: `/v1/devices/${ODIN_ID}`, body: { name: "Odin Pro" } }));
  });

  it("confirms a revoke with the stop-syncing copy, then returns to the list", async () => {
    const calls = serve(() => devices);
    render(Device);
    await screen.findByRole("heading", { name: "Odin 3" });
    dispatch("y");
    await screen.findByText("Odin 3 stops syncing immediately. Its saves stay on the server.");
    expect(calls.some((c) => c.method === "DELETE")).toBe(false);
    await waitFor(() => expect(document.activeElement).toBe(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" })));
    await fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Revoke" }));
    await waitFor(() => expect(calls).toContainEqual({ method: "DELETE", url: `/v1/devices/${ODIN_ID}`, body: undefined }));
    await waitFor(() => expect(router.path).toBe("/devices"));
  });

  it("goes to set up again on X", async () => {
    serve(() => devices);
    render(Device);
    await screen.findByRole("heading", { name: "Odin 3" });
    dispatch("x");
    expect(router.path).toBe(`/devices/${ODIN_ID}/setup`);
  });
});
