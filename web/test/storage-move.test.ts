import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import Storage from "../src/pages/settings/Storage.svelte";
import Settings from "../src/pages/Settings.svelte";
import { router } from "../src/lib/router.svelte";
import { session } from "../src/lib/session.svelte";
import { moveLine } from "../src/lib/storage/move";
import type { MoveCheckResult, MoveStatus, StorageUsage } from "../src/lib/api/types";
import { storageUsage } from "../src/pages/_storageFixture";

const MB = 1024 * 1024;
const base: MoveStatus = {
  phase: "Copying", from: "/data/blobs", to: "/x", filesDone: 48, filesTotal: 148, bytesDone: 31 * MB, bytesTotal: 79 * MB,
  error: null, oldLocation: null, oldBytes: 0, startedUtc: "2026-11-24T10:00:00Z", oldKept: 0,
};

describe("moveLine", () => {
  it("counts files and bytes while copying", () => {
    expect(moveLine(base)).toEqual({ tone: "wait", text: "Copying 48 of 148 files, 31.0 MB of 79.0 MB" });
  });
  it("says uploads are held while finishing", () => {
    expect(moveLine({ ...base, phase: "Holding" })).toEqual({ tone: "wait", text: "Holding uploads, finishing…" });
  });
  it("names the new folder and the old copy when done", () => {
    expect(moveLine({ ...base, phase: "Done", oldLocation: "/data/blobs", oldBytes: 79 * MB })).toEqual({
      tone: "ink",
      text: "Files are now in /x. The old copy (79.0 MB) is still in /data/blobs.",
    });
  });
  it("drops the old copy once it is removed", () => {
    expect(moveLine({ ...base, phase: "Done", oldLocation: null, oldBytes: 0 })).toEqual({ tone: "ink", text: "Files are now in /x." });
  });
  it("reports a failure and that nothing changed", () => {
    expect(moveLine({ ...base, phase: "Failed", error: "The disk is full." })).toEqual({ tone: "fault", text: "The disk is full. Nothing was changed." });
    expect(moveLine({ ...base, phase: "Failed", error: null })).toEqual({ tone: "fault", text: "The move failed. Nothing was changed." });
  });
  it("does not say it twice when the server's message already does", () => {
    const error = "Copying stopped: the disk is full. Nothing was changed.";
    expect(moveLine({ ...base, phase: "Failed", error })).toEqual({ tone: "fault", text: error });
  });
});

// ---- The page ----

const listeners: Record<string, (e: MessageEvent) => void> = {};
class FakeEventSource {
  readyState = 0;
  addEventListener(name: string, f: (e: MessageEvent) => void) { listeners[name] = f; }
  close() {}
}
const reply = (status: number, body?: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

interface Call { method: string; url: string; body?: any }
type Handler = (body: any) => Response;

const goodCheck: MoveCheckResult = {
  path: "/mnt/big", ok: true, bytesToCopy: 79 * MB, mountLine: null,
  items: [{ name: "absolute", ok: true, problem: null }, { name: "writable", ok: true, problem: null }],
};
const badCheck: MoveCheckResult = {
  path: "/srv/blobs", ok: false, bytesToCopy: 79 * MB,
  mountLine: "- /your/host/folder:/srv/blobs",
  items: [
    { name: "absolute", ok: true, problem: null },
    { name: "mount", ok: false, problem: "This folder is inside the container and would be lost on the next update. Mount a folder there first:" },
  ],
};

function withMove(move: MoveStatus | null): StorageUsage {
  return { ...storageUsage, server: { ...storageUsage.server!, move } };
}

function serve(usage: StorageUsage, handlers: Record<string, Handler> = {}): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ method, url, body });
    const key = `${method} ${url}`;
    if (handlers[key]) return handlers[key]!(body);
    switch (key) {
      case "GET /v1/account": return reply(200, { id: "u1", email: "me@x.test", isAdmin: true });
      case "GET /v1/settings/storage": return reply(200, usage);
      case "GET /v1/settings/storage-view": return reply(200, { view: "bars" });
      case "GET /v1/server-info": return reply(200, { name: "Tandem", protocol: 1, registrationOpen: false, version: "1.2.3" });
    }
    return reply(404, { code: "not_found", message: "no" });
  }));
  return calls;
}

async function show(usage: StorageUsage, handlers: Record<string, Handler> = {}) {
  router.go("/settings?s=storage");
  const calls = serve(usage, handlers);
  await session.check();
  render(Storage);
  await screen.findByRole("heading", { name: "Files" });
  return calls;
}

describe("moving the stored files", () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.removeAttribute("open"); this.dispatchEvent(new Event("close")); };
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); });

  async function openDialog() {
    await fireEvent.click(await screen.findByRole("button", { name: "Move files…" }));
    return within(await screen.findByRole("dialog", { name: "Move files" }));
  }

  it("checks a folder, then moves only once every check is ok", async () => {
    let started = false;
    const calls = await show(withMove(null), {
      "POST /v1/admin/storage/move/check": () => reply(200, goodCheck),
      "POST /v1/admin/storage/move": () => { started = true; return reply(202); },
      "GET /v1/admin/storage/move": () => reply(200, { ...base, to: "/mnt/big" }),
    });
    const d = await openDialog();
    const move = d.getByRole("button", { name: "Move" }) as HTMLButtonElement;
    expect(move.disabled).toBe(true);
    await fireEvent.input(d.getByLabelText("New folder"), { target: { value: "/mnt/big" } });
    await fireEvent.click(d.getByRole("button", { name: "Check" }));
    expect(await d.findByText("Writable")).toBeTruthy();
    expect(d.getByText("Absolute path")).toBeTruthy();
    expect(d.queryByText("writable")).toBeNull();
    expect(d.getByText("79.0 MB to copy")).toBeTruthy();
    expect(calls).toContainEqual({ method: "POST", url: "/v1/admin/storage/move/check", body: { path: "/mnt/big" } });
    expect(move.disabled).toBe(false);
    await fireEvent.click(move);
    await waitFor(() => expect(started).toBe(true));
    expect(await screen.findByText(/Copying 48 of 148 files/)).toBeTruthy();
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("shows a refused folder's problems with the mount line, and keeps Move off", async () => {
    await show(withMove(null), { "POST /v1/admin/storage/move/check": () => reply(200, badCheck) });
    const d = await openDialog();
    await fireEvent.input(d.getByLabelText("New folder"), { target: { value: "/srv/blobs" } });
    await fireEvent.click(d.getByRole("button", { name: "Check" }));
    expect(await d.findByText(/inside the container/)).toBeTruthy();
    expect(d.getByText("- /your/host/folder:/srv/blobs")).toBeTruthy();
    expect(d.getByRole("button", { name: "Copy" })).toBeTruthy();
    expect((d.getByRole("button", { name: "Move" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("re-checks and shows the items when the server refuses the path at start", async () => {
    let checks = 0;
    await show(withMove(null), {
      "POST /v1/admin/storage/move/check": () => reply(200, ++checks === 1 ? goodCheck : badCheck),
      "POST /v1/admin/storage/move": () => reply(400, { code: "bad_path", message: "no" }),
    });
    const d = await openDialog();
    await fireEvent.input(d.getByLabelText("New folder"), { target: { value: "/mnt/big" } });
    await fireEvent.click(d.getByRole("button", { name: "Check" }));
    await fireEvent.click(await d.findByRole("button", { name: "Move" }).then((b) => b as HTMLButtonElement).then(async (b) => { await waitFor(() => expect(b.disabled).toBe(false)); return b; }));
    expect(await d.findByText(/inside the container/)).toBeTruthy();
    expect((d.getByRole("button", { name: "Move" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("shows an unknown check as its id", async () => {
    await show(withMove(null), { "POST /v1/admin/storage/move/check": () => reply(200, { ...goodCheck, items: [{ name: "inodes", ok: true, problem: null }] }) });
    const d = await openDialog();
    await fireEvent.input(d.getByLabelText("New folder"), { target: { value: "/mnt/big" } });
    await fireEvent.click(d.getByRole("button", { name: "Check" }));
    expect(await d.findByText("inodes")).toBeTruthy();
  });

  it("enables Move when the server normalised the path it was given", async () => {
    await show(withMove(null), { "POST /v1/admin/storage/move/check": () => reply(200, goodCheck) });
    const d = await openDialog();
    await fireEvent.input(d.getByLabelText("New folder"), { target: { value: " /mnt/a/../big/ " } });
    await fireEvent.click(d.getByRole("button", { name: "Check" }));
    await d.findByText("Writable");
    expect((d.getByRole("button", { name: "Move" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("disables Move again when the path is edited after a good check", async () => {
    await show(withMove(null), { "POST /v1/admin/storage/move/check": () => reply(200, goodCheck) });
    const d = await openDialog();
    const input = d.getByLabelText("New folder");
    await fireEvent.input(input, { target: { value: "/mnt/big/" } });
    await fireEvent.click(d.getByRole("button", { name: "Check" }));
    await d.findByText("Writable");
    const move = d.getByRole("button", { name: "Move" }) as HTMLButtonElement;
    expect(move.disabled).toBe(false);
    await fireEvent.input(input, { target: { value: "/mnt/other" } });
    expect(move.disabled).toBe(true);
  });

  it("renders one or two items as they come", async () => {
    await show(withMove(null), {
      "POST /v1/admin/storage/move/check": () => reply(200, { path: "data", ok: false, bytesToCopy: 0, mountLine: null, items: [{ name: "absolute", ok: false, problem: "Use a full path that starts with /." }] }),
    });
    const d = await openDialog();
    await fireEvent.input(d.getByLabelText("New folder"), { target: { value: "data" } });
    await fireEvent.click(d.getByRole("button", { name: "Check" }));
    expect(await d.findByText("Use a full path that starts with /.")).toBeTruthy();
    expect(d.getAllByRole("listitem")).toHaveLength(1);
  });

  it("shows the copy and cancels it", async () => {
    const calls = await show(withMove(base), { "POST /v1/admin/storage/move/cancel": () => reply(204) });
    expect(await screen.findByText("Copying 48 of 148 files, 31.0 MB of 79.0 MB")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(calls).toContainEqual({ method: "POST", url: "/v1/admin/storage/move/cancel", body: undefined }));
  });

  it("offers no Cancel while holding uploads", async () => {
    await show(withMove({ ...base, phase: "Holding" }));
    expect(await screen.findByText("Holding uploads, finishing…")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Cancel" })).toBeNull();
  });

  it("polls every two seconds while copying and stops once done", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    let phase: MoveStatus["phase"] = "Copying";
    const now = (): MoveStatus => ({ ...base, phase, filesDone: phase === "Done" ? 148 : 100, oldLocation: "/data/blobs", oldBytes: 79 * MB });
    const calls = await show(withMove(base), {
      "GET /v1/settings/storage": () => reply(200, withMove(phase === "Done" ? now() : base)),
      "GET /v1/admin/storage/move": () => reply(200, now()),
    });
    const polls = () => calls.filter((c) => c.url === "/v1/admin/storage/move" && c.method === "GET").length;
    await vi.advanceTimersByTimeAsync(2000);
    expect(await screen.findByText(/Copying 100 of 148/)).toBeTruthy();
    expect(polls()).toBe(1);
    phase = "Done";
    await vi.advanceTimersByTimeAsync(2000);
    expect(await screen.findByText("Files are now in /x. The old copy (79.0 MB) is still in /data/blobs.")).toBeTruthy();
    await vi.advanceTimersByTimeAsync(10000);
    expect(polls()).toBe(2);
  });

  it("stops polling when the page goes away", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const calls = await show(withMove(base), { "GET /v1/admin/storage/move": () => reply(200, base) });
    cleanup();
    await vi.advanceTimersByTimeAsync(10000);
    expect(calls.filter((c) => c.url === "/v1/admin/storage/move")).toHaveLength(0);
  });

  it("shows a failed move in the fault colour", async () => {
    await show(withMove({ ...base, phase: "Failed", error: "The disk is full." }));
    const line = await screen.findByText("The disk is full. Nothing was changed.");
    expect(line.className).toContain("fault");
  });

  const done: MoveStatus = { ...base, phase: "Done", oldLocation: "/data/blobs", oldBytes: 79 * MB };

  it("removes the old copy after confirming, and says what was kept", async () => {
    let removed = false;
    const calls = await show(withMove(done), {
      "POST /v1/admin/storage/move/remove-old": () => { removed = true; return reply(204); },
      "GET /v1/admin/storage/move": () => reply(200, { ...done, oldBytes: removed ? 2 * MB : done.oldBytes, oldKept: removed ? 3 : 0 }),
    });
    await fireEvent.click(await screen.findByRole("button", { name: "Remove old copy" }));
    const d = within(await screen.findByRole("dialog"));
    expect(d.getByText("Delete the old copy in /data/blobs? The files in /x are kept.")).toBeTruthy();
    await fireEvent.click(d.getByRole("button", { name: "Delete old copy" }));
    await waitFor(() => expect(calls.some((c) => c.url === "/v1/admin/storage/move/remove-old")).toBe(true));
    expect(await screen.findByText("3 files the new folder doesn't hold were kept in /data/blobs.")).toBeTruthy();
    expect(screen.getByText("Files are now in /x. The old copy (2.0 MB) is still in /data/blobs.")).toBeTruthy();
  });

  it("still offers Remove old copy after a partial removal, and says what was kept", async () => {
    await show(withMove({ ...done, oldKept: 3 }));
    expect(await screen.findByText("3 files the new folder doesn't hold were kept in /data/blobs.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Remove old copy" })).toBeTruthy();
  });

  it("refreshes the Files block when the storage topic fires", async () => {
    let phase: MoveStatus["phase"] = "Copying";
    await show(withMove(base), { "GET /v1/settings/storage": () => reply(200, withMove({ ...base, phase })) });
    expect(await screen.findByText(/Copying 48 of 148/)).toBeTruthy();
    phase = "Holding";
    listeners.changed!(new MessageEvent("changed", { data: JSON.stringify({ topic: "storage" }) }));
    expect(await screen.findByText("Holding uploads, finishing…")).toBeTruthy();
  });

  it("shows the server's message when the old copy cannot be removed", async () => {
    await show(withMove(done), { "POST /v1/admin/storage/move/remove-old": () => reply(409, { code: "files_unavailable", message: "The new folder is missing." }) });
    await fireEvent.click(await screen.findByRole("button", { name: "Remove old copy" }));
    const d = within(await screen.findByRole("dialog"));
    await fireEvent.click(d.getByRole("button", { name: "Delete old copy" }));
    expect((await d.findByRole("alert")).textContent).toBe("The new folder is missing.");
  });
});

describe("changing the email", () => {
  beforeEach(() => vi.stubGlobal("EventSource", FakeEventSource));
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  async function open(handler: Handler) {
    router.go("/settings");
    const calls = serve(storageUsage, { "POST /v1/account/email": handler });
    await session.check();
    render(Settings);
    await fireEvent.click(await screen.findByRole("link", { name: "Account" }));
    const form = within(await screen.findByRole("form", { name: "Change email" }));
    await fireEvent.input(form.getByLabelText("New email"), { target: { value: "new@x.test" } });
    await fireEvent.input(form.getByLabelText("Current password"), { target: { value: "hunter22" } });
    return calls;
  }
  const save = () => fireEvent.click(within(screen.getByRole("form", { name: "Change email" })).getByRole("button", { name: "Save" }));

  it("updates Signed in as and says so quietly", async () => {
    const calls = await open(() => reply(200, { id: "u1", email: "new@x.test", isAdmin: true }));
    await save();
    expect(await screen.findByText("Email changed.")).toBeTruthy();
    expect(calls).toContainEqual({ method: "POST", url: "/v1/account/email", body: { newEmail: "new@x.test", password: "hunter22" } });
    expect(session.account?.email).toBe("new@x.test");
    expect(screen.getByText("new@x.test", { selector: ".note .mono" })).toBeTruthy();
  });

  it.each([
    [400, "invalid_email", "That isn't a valid email address."],
    [403, "bad_password", "That password isn't right."],
    [409, "email_taken", "Another account already uses that address."],
  ])("explains %i %s", async (status, code, text) => {
    await open(() => reply(status as number, { code, message: "x" }));
    await save();
    expect((await screen.findByRole("alert")).textContent).toBe(text);
  });

  it("falls back to the general error line for a throttle", async () => {
    await open(() => reply(429, { code: "rate_limited", message: "x" }));
    await save();
    expect((await screen.findByRole("alert")).textContent).toBe("Too many attempts. Wait a minute and try again.");
  });
});
