import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import Vault from "../../src/pages/Vault.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { devices } from "../fixtures/devices";
import { bios, vaultItems } from "../fixtures/vault";
import type { VaultItemDto } from "../../src/lib/api/types";

class FakeEventSource {
  readyState = 0;
  addEventListener() {}
  close() {}
}
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

function serve(list: VaultItemDto[]) {
  const calls: { method: string; url: string; body?: unknown }[] = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url.endsWith("/v1/vault")) return json(list);
    if (method === "GET" && url.endsWith("/v1/devices")) return json(devices);
    if (url.endsWith("/v1/blobs/missing")) return json({ missing: [] });
    const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    calls.push({ method, url, body });
    if (url.endsWith("/send")) return json({ deliveryId: "dl", versionId: "ver2" });
    if (url.endsWith("/v1/vault/upload")) return json({ itemId: "i", versionId: "v", unchanged: false });
    return new Response(null, { status: 204 });
  }));
  return calls;
}

describe("Vault", () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.removeAttribute("open"); };
    router.go("/vault");
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("shows an empty vault in one sentence", async () => {
    serve([]);
    render(Vault);
    expect(await screen.findByText("Nothing in the vault yet. Upload BIOS, keys or firmware from this PC.")).toBeTruthy();
  });

  it("groups by category and lists holders, marking older copies", async () => {
    serve(vaultItems);
    render(Vault);
    const row = within((await screen.findByText("PlayStation BIOS")).closest("button") as HTMLElement);
    expect(row.getByText("Odin 3").classList.contains("ink2")).toBe(false);
    expect(row.getByText("Thor, older").classList.contains("ink2")).toBe(true);
    expect(row.getByText("BIOS · RetroArch")).toBeTruthy();
    const names = screen.getAllByRole("button").map((b) => b.textContent ?? "");
    expect(names.findIndex((t) => t.includes("PlayStation BIOS"))).toBeLessThan(names.findIndex((t) => t.includes("Switch keys")));
  });

  it("sends the focused item to the chosen device and disables devices that have it", async () => {
    const calls = serve([bios]);
    render(Vault);
    await screen.findByText("PlayStation BIOS");
    dispatch("a");
    const dialog = await screen.findByRole("dialog");
    const odin = within(dialog).getByRole("button", { name: /Odin 3/ }) as HTMLButtonElement;
    expect(odin.disabled).toBe(true);
    expect(odin.textContent).toContain("has it");
    await fireEvent.click(within(dialog).getByRole("button", { name: /Thor/ }));
    await waitFor(() => expect(calls.find((c) => c.url.endsWith("/send"))).toBeTruthy());
    const send = calls.find((c) => c.url.endsWith("/send"))!;
    expect(send.url).toBe("/v1/vault/v-bios/send");
    expect(send.body).toEqual({ deviceId: "d-thor" });
    expect(await screen.findByText("Sent to Thor. It goes in next time EmuSync syncs there.")).toBeTruthy();
  });

  it("uploads a picked file: hashes it, then registers the manifest", async () => {
    const calls = serve([bios]);
    render(Vault);
    await screen.findByText("PlayStation BIOS");
    await fireEvent.click(screen.getByRole("button", { name: /Upload from this PC/ }));
    const dialog = await screen.findByRole("dialog");
    const input = within(dialog).getByLabelText("Files") as HTMLInputElement;
    await fireEvent.change(input, { target: { files: [new File(["abc"], "scph1001.bin")] } });
    const name = within(dialog).getByLabelText("Name") as HTMLInputElement;
    expect(name.value).toBe("scph1001.bin");
    await fireEvent.input(name, { target: { value: "PlayStation BIOS" } });
    await fireEvent.change(within(dialog).getByLabelText("Emulator (optional)"), { target: { value: "retroarch" } });
    expect(within(dialog).getByText("This replaces PlayStation BIOS. The old version stays in history.")).toBeTruthy();
    await fireEvent.click(within(dialog).getByRole("button", { name: "Upload" }));
    await waitFor(() => expect(calls.find((c) => c.url.endsWith("/v1/vault/upload"))).toBeTruthy());
    expect(calls.find((c) => c.url.endsWith("/v1/vault/upload"))!.body).toEqual({
      category: "bios",
      emulatorId: "retroarch",
      name: "PlayStation BIOS",
      manifest: [{ path: "scph1001.bin", hash: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", size: 3 }],
    });
  });

  it("explains in place that a vault upload needs HTTPS when crypto.subtle is missing, and sends nothing", async () => {
    const calls = serve([bios]);
    vi.stubGlobal("crypto", { getRandomValues: crypto.getRandomValues.bind(crypto) });
    render(Vault);
    await screen.findByText("PlayStation BIOS");
    await fireEvent.click(screen.getByRole("button", { name: /Upload from this PC/ }));
    const dialog = await screen.findByRole("dialog");
    await fireEvent.change(within(dialog).getByLabelText("Files") as HTMLInputElement, { target: { files: [new File(["abc"], "scph1001.bin")] } });
    const before = calls.length;
    await fireEvent.click(within(dialog).getByRole("button", { name: "Upload" }));
    expect((await within(dialog).findByRole("alert")).textContent).toBe(
      "Vault uploads need the web UI over HTTPS (or on this machine as localhost). Saves upload fine either way.",
    );
    expect(calls.length).toBe(before);
    expect(within(dialog).queryByText(/Hashing/)).toBeNull();
  });

  it("moves through the upload dialog with the D-pad, skipping the hidden file inputs and the disabled Upload", async () => {
    serve([bios]);
    render(Vault);
    await screen.findByText("PlayStation BIOS");
    await fireEvent.click(screen.getByRole("button", { name: /Upload from this PC/ }));
    const dialog = await screen.findByRole("dialog");
    await waitFor(() => expect(document.activeElement).toBe(within(dialog).getByLabelText("Kind")));
    const order = ["Emulator (optional)", "Name"].map((l) => within(dialog).getByLabelText(l));
    order.push(...["Choose files", "Choose a folder", "Cancel"].map((n) => within(dialog).getByRole("button", { name: n })));
    for (const want of order) {
      dispatch("down");
      expect(document.activeElement).toBe(want);
    }
  });

  describe("upload with blobs to send", () => {
    const ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    type Call = { method: string; url: string };

    function serveBlobs(putStatus: () => number, gate?: { missing?: Promise<void>; register?: Promise<void> }) {
      const calls: Call[] = [];
      vi.stubGlobal("EventSource", FakeEventSource);
      vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
        const method = init?.method ?? "GET";
        calls.push({ method, url });
        if (method === "GET" && url.endsWith("/v1/vault")) return json([bios]);
        if (method === "GET" && url.endsWith("/v1/devices")) return json(devices);
        if (url.endsWith("/v1/blobs/missing")) { await gate?.missing; return json({ missing: [ABC] }); }
        if (method === "PUT") {
          const st = putStatus();
          return st === 204 ? new Response(null, { status: 204 }) : new Response(JSON.stringify({ code: "error", message: "x" }), { status: st, headers: { "Content-Type": "application/json" } });
        }
        if (url.endsWith("/v1/vault/upload")) { await gate?.register; return json({ itemId: "i", versionId: "v", unchanged: false }); }
        return new Response(null, { status: 204 });
      }));
      return calls;
    }

    async function pickAndOpen() {
      render(Vault);
      await screen.findByText("PlayStation BIOS");
      await fireEvent.click(screen.getByRole("button", { name: /Upload from this PC/ }));
      const dialog = await screen.findByRole("dialog");
      await fireEvent.change(within(dialog).getByLabelText("Files"), { target: { files: [new File(["abc"], "scph.bin")] } });
      return dialog;
    }
    const puts = (c: Call[]) => c.filter((x) => x.method === "PUT").map((x) => x.url);
    const registers = (c: Call[]) => c.filter((x) => x.url.endsWith("/v1/vault/upload"));

    it("does not register when a blob PUT fails, shows the error, and a retry sends only that blob", async () => {
      let status = 500;
      const calls = serveBlobs(() => status);
      const dialog = await pickAndOpen();
      await fireEvent.click(within(dialog).getByRole("button", { name: "Upload" }));
      await waitFor(() => expect(within(dialog).getByRole("alert")).toBeTruthy());
      expect(puts(calls)).toEqual([`/v1/blobs/${ABC}`]);
      expect(registers(calls)).toHaveLength(0);
      status = 204;
      await fireEvent.click(within(dialog).getByRole("button", { name: "Upload" }));
      await waitFor(() => expect(registers(calls)).toHaveLength(1));
      expect(puts(calls)).toEqual([`/v1/blobs/${ABC}`, `/v1/blobs/${ABC}`]);
    });

    it("shows the too-large copy for a 413", async () => {
      const calls = serveBlobs(() => 413);
      const dialog = await pickAndOpen();
      await fireEvent.click(within(dialog).getByRole("button", { name: "Upload" }));
      expect((await within(dialog).findByRole("alert")).textContent).toBe("That file is too large for the server's upload limit.");
      expect(registers(calls)).toHaveLength(0);
    });

    it("shows hashing and uploading progress", async () => {
      let openMissing!: () => void;
      let openRegister!: () => void;
      const gate = { missing: new Promise<void>((r) => (openMissing = r)), register: new Promise<void>((r) => (openRegister = r)) };
      serveBlobs(() => 204, gate);
      const dialog = await pickAndOpen();
      await fireEvent.click(within(dialog).getByRole("button", { name: "Upload" }));
      await within(dialog).findByText("Uploading…");
      openMissing();
      await within(dialog).findByText("Uploading 1 of 1…");
      openRegister();
    });
  });
});
