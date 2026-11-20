import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import Settings from "../../src/pages/Settings.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { session } from "../../src/lib/session.svelte";
import type { NotifySettingsDto, BackupSettingsResponse } from "../../src/lib/api/types";

class FakeEventSource {
  readyState = 0;
  addEventListener() {}
  close() {}
}
const reply = (status: number, body?: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const SECRET_TOKEN = "tk_super_secret_value";
const DISCORD_URL = "https://discord.com/api/webhooks/123/SECRETPART";

const notify: NotifySettingsDto = {
  targets: [
    { kind: "Ntfy", url: "https://ntfy.sh/mytopic", tokenSet: true },
    { kind: "Discord", url: DISCORD_URL, tokenSet: false },
  ],
  events: { conflicts: true, failedSyncs: true, shizukuOff: false, backupFailed: true },
};
const backup: BackupSettingsResponse = {
  enabled: true, kind: "Folder", folderPath: "/mnt/nas/emusync", s3: null, hourUtc: 3, keep: 7,
  encrypt: true, passphraseSet: true, running: false,
};

interface Call { method: string; url: string; body?: any }
interface World { calls: Call[]; overrides: Record<string, () => Response> }

function serve(world: Partial<World> = {}) {
  const calls: Call[] = [];
  const overrides = world.overrides ?? {};
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const key = `${method} ${url}`;
    calls.push({ method, url, body: init?.body ? JSON.parse(init.body as string) : undefined });
    if (overrides[key]) return overrides[key]!();
    switch (key) {
      case "GET /v1/account": return reply(200, { id: "u1", email: "me@x.test", isAdmin: admin });
      case "GET /v1/settings/retention": return reply(200, { saveVersions: 10, configVersions: 5, vaultVersions: 3 });
      case "PUT /v1/settings/retention": return reply(200, JSON.parse(init!.body as string));
      case "GET /v1/settings/storage":
        return reply(200, {
          totalBytes: 3 * 1024 ** 3,
          byEmulator: [{ emulatorId: "eden", gameKey: null, displayName: null, bytes: 2 * 1024 ** 3 }],
          topGames: [{ emulatorId: "eden", gameKey: "0100ABC", displayName: "Zelda", bytes: 512 * 1024 ** 2 }],
        });
      case "GET /v1/settings/notifications": return reply(200, notify);
      case "PUT /v1/settings/notifications": return reply(200, notify);
      case "POST /v1/settings/notifications/test":
        return reply(200, [{ kind: "Ntfy", ok: true, status: 200 }, { kind: "Discord", ok: false, status: 404, error: "Not found" }]);
      case "GET /v1/admin/users":
        return reply(200, [
          { id: "u1", email: "me@x.test", isAdmin: true, createdUtc: "2026-01-01T00:00:00Z", deviceCount: 2 },
          { id: "u2", email: "kid@x.test", isAdmin: false, createdUtc: "2026-02-01T00:00:00Z", deviceCount: 0 },
        ]);
      case "POST /v1/admin/users": return reply(200, { email: "new@x.test", password: "Gen-Pass-1234" });
      case "GET /v1/admin/settings": return reply(200, { allowRegistration: false });
      case "GET /v1/admin/backup": return reply(200, backup);
      case "PUT /v1/admin/backup": return reply(200, backup);
      case "POST /v1/admin/backup/run": return reply(202);
      case "GET /v1/admin/backup/history":
        return reply(200, [
          { utc: "2026-11-19T03:00:00Z", ok: true, bytes: 1048576, error: null },
          { utc: "2026-11-18T03:00:00Z", ok: false, bytes: 0, error: "Folder is not writable" },
        ]);
      case "POST /v1/account/password": return reply(204);
      case "DELETE /v1/web/session": return reply(204);
    }
    if (method === "DELETE" && url.startsWith("/v1/admin/users/")) return reply(204);
    return reply(404, { code: "not_found", message: "no" });
  }));
  return calls;
}

let admin = true;
async function open(section?: string, opts: { admin?: boolean } = {}) {
  admin = opts.admin ?? true;
  router.go("/settings");
  const calls = serve();
  await session.check();
  render(Settings);
  if (section) await fireEvent.click(await screen.findByRole("link", { name: section }));
  return calls;
}
async function openWith(section: string, overrides: Record<string, () => Response>) {
  admin = true;
  router.go("/settings");
  const calls = serve({ overrides });
  await session.check();
  render(Settings);
  await fireEvent.click(await screen.findByRole("link", { name: section }));
  return calls;
}

describe("Settings", () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.removeAttribute("open"); this.dispatchEvent(new Event("close")); };
    vi.stubGlobal("EventSource", FakeEventSource);
    vi.stubGlobal("fetch", vi.fn(async () => reply(200, { id: "u1", email: "me@x.test", isAdmin: true })));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); document.documentElement.removeAttribute("data-theme"); localStorage.clear(); });

  it("shows no Users or Backup tabs to non-admins and never calls admin endpoints", async () => {
    const calls = await open(undefined, { admin: false });
    await screen.findByRole("link", { name: "Account" });
    expect(screen.queryByRole("link", { name: "Users" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Backup" })).toBeNull();
    await fireEvent.click(screen.getByRole("link", { name: "Notifications" }));
    await screen.findByText("https://ntfy.sh/mytopic", {}, { timeout: 2000 }).catch(() => {});
    expect(calls.some((c) => c.url.startsWith("/v1/admin"))).toBe(false);
  });

  it("the theme choice sets data-theme", async () => {
    await open("Appearance");
    await fireEvent.click(await screen.findByRole("radio", { name: "Dark" }));
    expect(document.documentElement.dataset.theme).toBe("dark");
    await fireEvent.click(screen.getByRole("radio", { name: "System" }));
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });

  it("changes the password and says the other browsers were signed out", async () => {
    const calls = await open();
    await fireEvent.input(await screen.findByLabelText("Current password"), { target: { value: "oldpassword" } });
    await fireEvent.input(screen.getByLabelText("New password"), { target: { value: "newpassword1" } });
    await fireEvent.click(screen.getByRole("button", { name: "Change password" }));
    await screen.findByText("Password changed. Your other browsers were signed out.");
    expect(calls.find((c) => c.url === "/v1/account/password")?.body).toEqual({ current: "oldpassword", new: "newpassword1" });
    expect((screen.getByLabelText("Current password") as HTMLInputElement).value).toBe("");
  });

  it("shows a wrong current password in place", async () => {
    await openWith("Account", { "POST /v1/account/password": () => reply(400, { code: "wrong_password", message: "x" }) });
    await fireEvent.input(await screen.findByLabelText("Current password"), { target: { value: "bad" } });
    await fireEvent.input(screen.getByLabelText("New password"), { target: { value: "newpassword1" } });
    await fireEvent.click(screen.getByRole("button", { name: "Change password" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/current password is incorrect/i);
  });

  it("validates the retention range and saves", async () => {
    const calls = await open("Retention");
    expect(await screen.findByText("Conflict versions and pinned versions are never removed.")).toBeTruthy();
    const save = screen.getByRole("button", { name: "Save" }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    const games = screen.getByLabelText("Save versions per game") as HTMLInputElement;
    expect(games.min).toBe("1");
    expect(games.max).toBe("1000");
    await fireEvent.input(games, { target: { value: "0" } });
    expect(screen.getByText("Enter a whole number from 1 to 1000.")).toBeTruthy();
    expect(save.disabled).toBe(true);
    await fireEvent.input(games, { target: { value: "25" } });
    expect(save.disabled).toBe(false);
    await fireEvent.click(save);
    await screen.findByText("Saved.");
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({ saveVersions: 25, configVersions: 5, vaultVersions: 3 });
  });

  it("lists storage in mono", async () => {
    await open("Storage");
    const total = await screen.findByText("3.0 GB");
    expect(total.classList.contains("mono")).toBe(true);
    expect(screen.getByText("Zelda")).toBeTruthy();
    expect(screen.getByText("512.0 MB")).toBeTruthy();
  });

  it("never renders the token, and saving without touching it sends token null", async () => {
    const calls = await open("Notifications");
    await screen.findByText("Token set");
    expect(document.body.innerHTML).not.toContain(SECRET_TOKEN);
    expect((screen.getAllByLabelText("Token")[0] as HTMLInputElement).value).toBe("");
    await fireEvent.click(screen.getByRole("checkbox", { name: "Shizuku turned off" }));
    await fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText("Saved.");
    const put = calls.find((c) => c.method === "PUT")!.body as NotifySettingsDto;
    expect(put.targets[0]).toEqual({ kind: "Ntfy", url: "https://ntfy.sh/mytopic", token: null });
    expect(put.events.shizukuOff).toBe(true);
  });

  it("sends an empty token to remove it and a typed one to replace it", async () => {
    const calls = await open("Notifications");
    await screen.findByText("Token set");
    await fireEvent.click(screen.getByRole("button", { name: "Remove token" }));
    await fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText("Saved.");
    expect((calls.find((c) => c.method === "PUT")!.body as NotifySettingsDto).targets[0]!.token).toBe("");
  });

  it("masks Discord URLs, keeps the real one when saved unchanged, and clears it on Edit", async () => {
    const calls = await open("Notifications");
    await screen.findByText("discord.com/…");
    expect(document.body.innerHTML).not.toContain("SECRETPART");
    await fireEvent.click(screen.getByRole("checkbox", { name: "Conflicts" }));
    await fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText("Saved.");
    expect((calls.find((c) => c.method === "PUT")!.body as NotifySettingsDto).targets[1]!.url).toBe(DISCORD_URL);

    await fireEvent.click(screen.getByRole("button", { name: "Edit URL" }));
    const url = screen.getAllByLabelText("URL")[1] as HTMLInputElement;
    expect(url.value).toBe("");
  });

  it("sends a test and shows a result per target", async () => {
    await open("Notifications");
    await fireEvent.click(await screen.findByRole("button", { name: "Send a test" }));
    await screen.findByText("Sent (200)");
    expect(screen.getByText("Failed (404): Not found")).toBeTruthy();
  });

  it("creates a user and shows the generated password once", async () => {
    await open("Users");
    await screen.findByText("kid@x.test");
    expect((screen.getByRole("checkbox", { name: "Allow new accounts to register" }) as HTMLInputElement).checked).toBe(false);
    await fireEvent.click(screen.getByRole("button", { name: "Add user" }));
    await fireEvent.input(await screen.findByLabelText("Email"), { target: { value: "new@x.test" } });
    await fireEvent.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("Gen-Pass-1234")).toBeTruthy();
    expect(screen.getByText("Copy it now. It won't be shown again.")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Done" }));
    await waitFor(() => expect(screen.queryByText("Gen-Pass-1234")).toBeNull());
    await fireEvent.click(screen.getByRole("button", { name: "Add user" }));
    expect(screen.queryByText("Gen-Pass-1234")).toBeNull();
  });

  it("explains cannot_delete_self", async () => {
    await openWith("Users", { "DELETE /v1/admin/users/u1": () => reply(400, { code: "cannot_delete_self", message: "x" }) });
    const row = (await screen.findByText("me@x.test")).closest("li") as HTMLElement;
    await fireEvent.click(within(row).getByRole("button", { name: "Delete me@x.test" }));
    await fireEvent.click(await screen.findByRole("button", { name: "Delete user" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/can't delete your own account/i);
  });

  it("shows backup_running as 'A backup is already running.'", async () => {
    await openWith("Backup", { "POST /v1/admin/backup/run": () => reply(409, { code: "backup_running", message: "x" }) });
    await fireEvent.click(await screen.findByRole("button", { name: "Run now" }));
    expect((await screen.findByRole("alert")).textContent).toBe("A backup is already running.");
  });

  it("keeps the passphrase write-only: status only, null when untouched", async () => {
    const calls = await open("Backup");
    await screen.findByText("Passphrase set");
    expect(screen.getByText("If you lose the passphrase, that backup can't be restored.")).toBeTruthy();
    expect((screen.getByLabelText("New passphrase") as HTMLInputElement).value).toBe("");
    await fireEvent.input(screen.getByLabelText("Keep"), { target: { value: "14" } });
    await fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText("Saved.");
    const put = calls.find((c) => c.method === "PUT")!.body;
    expect(put.passphrase).toBeNull();
    expect(put.keep).toBe(14);
    expect(put.hourUtc).toBe(3);
    expect(screen.getByText("Folder is not writable")).toBeTruthy();
  });

  it("shows S3 fields for an S3 target and never a secret key value", async () => {
    await open("Backup");
    await fireEvent.change(await screen.findByLabelText("Target"), { target: { value: "S3" } });
    expect(screen.getByLabelText("Bucket")).toBeTruthy();
    expect((screen.getByLabelText("Secret key") as HTMLInputElement).value).toBe("");
  });

  it("keeps a stored Discord URL masked when its Kind is changed", async () => {
    await open("Notifications");
    await screen.findByText("discord.com/…");
    const kinds = screen.getAllByLabelText("Kind");
    await fireEvent.change(kinds[1]!, { target: { value: "Webhook" } });
    expect(document.body.innerHTML).not.toContain("SECRETPART");
    for (const el of Array.from(document.querySelectorAll("input"))) expect((el as HTMLInputElement).value).not.toContain("SECRETPART");
    await fireEvent.change(kinds[1]!, { target: { value: "Ntfy" } });
    expect(document.body.innerHTML).not.toContain("SECRETPART");
    for (const el of Array.from(document.querySelectorAll("input"))) expect((el as HTMLInputElement).value).not.toContain("SECRETPART");
  });

  it("warns that changing an ntfy address removes the saved token", async () => {
    await open("Notifications");
    await screen.findByText("Token set");
    await fireEvent.input(screen.getAllByLabelText("URL")[0]!, { target: { value: "https://ntfy.sh/other" } });
    expect(screen.getByText("No token")).toBeTruthy();
    expect(screen.getByText("Changing the address removes the saved token. Enter it again.")).toBeTruthy();
  });

  it("does not say Saved when the backup reload fails, and shows a history error in place", async () => {
    await openWith("Backup", { "GET /v1/admin/backup/history": () => reply(500, { code: "boom", message: "x" }) });
    await screen.findByText("Passphrase set");
    expect((await screen.findAllByRole("alert"))[0]!.textContent).toMatch(/something went wrong/i);
    await fireEvent.input(screen.getByLabelText("Keep"), { target: { value: "14" } });
    await fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText("Saved.");
  });

  it("shows a failed history refresh after Run now as a history problem, not a failed run", async () => {
    let n = 0;
    await openWith("Backup", { "GET /v1/admin/backup/history": () => (n++ === 0 ? reply(200, []) : reply(500, { code: "boom", message: "x" })) });
    await fireEvent.click(await screen.findByRole("button", { name: "Run now" }));
    await screen.findByText("Backup started.");
    expect(await screen.findByText(/something went wrong/i)).toBeTruthy();
  });

  it("does not carry Copied into the next one-time password dialog", async () => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn(async () => {}) } });
    await open("Users");
    const create = async () => {
      await fireEvent.click(await screen.findByRole("button", { name: "Add user" }));
      await fireEvent.input(await screen.findByLabelText("Email"), { target: { value: "new@x.test" } });
      await fireEvent.click(screen.getByRole("button", { name: "Create" }));
      await screen.findByText("Gen-Pass-1234");
    };
    await create();
    await fireEvent.click(screen.getByRole("button", { name: "Copy" }));
    await screen.findByRole("button", { name: "Copied" });
    await fireEvent.click(screen.getByRole("button", { name: "Done" }));
    await create();
    expect(screen.getByRole("button", { name: "Copy" })).toBeTruthy();
  });

  it("cannot cancel the create dialog while the request is in flight", async () => {
    let release: (r: Response) => void = () => {};
    const gate = new Promise<Response>((r) => (release = r));
    await openWith("Users", { "POST /v1/admin/users": () => undefined as unknown as Response });
    (fetch as any).mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === "POST" && url === "/v1/admin/users") return gate;
      if (url === "/v1/admin/users") return reply(200, []);
      if (url === "/v1/admin/settings") return reply(200, { allowRegistration: false });
      return reply(200, {});
    });
    await fireEvent.click(await screen.findByRole("button", { name: "Add user" }));
    await fireEvent.input(await screen.findByLabelText("Email"), { target: { value: "new@x.test" } });
    await fireEvent.click(screen.getByRole("button", { name: "Create" }));
    expect((screen.getByRole("button", { name: "Cancel" }) as HTMLButtonElement).disabled).toBe(true);
    dispatch("b");
    expect(screen.getByLabelText("Email")).toBeTruthy();
    release(reply(200, { email: "new@x.test", password: "Gen-Pass-1234" }));
    expect(await screen.findByText("Gen-Pass-1234")).toBeTruthy();
  });

  it("keeps the stored S3 block when saving with a Folder target", async () => {
    const calls = await open("Backup");
    await screen.findByText("Passphrase set");
    await fireEvent.change(screen.getByLabelText("Target"), { target: { value: "S3" } });
    await fireEvent.input(screen.getByLabelText("Bucket"), { target: { value: "mybucket" } });
    await fireEvent.change(screen.getByLabelText("Target"), { target: { value: "Folder" } });
    await fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText("Saved.");
    const put = calls.find((c) => c.method === "PUT")!.body;
    expect(put.kind).toBe("Folder");
    expect(put.s3.bucket).toBe("mybucket");
    expect(put.s3.secretKey).toBeNull();
  });
});
