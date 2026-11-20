import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import Configs from "../../src/pages/Configs.svelte";
import { router } from "../../src/lib/router.svelte";
import { dispatch } from "../../src/lib/input/actions";
import type { ConfigOverview } from "../../src/lib/api/types";
import { ODIN, RP, THOR } from "../fixtures/home";
import { AYN_BUNDLE, AYN_GROUP, aynVersions, deviceDetail, diff, groups, overview, RP_BUNDLE, rpVersions } from "../fixtures/configs";

class FakeEventSource {
  readyState = 0;
  addEventListener() {}
  close() {}
}
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

interface Call { method: string; url: string; body: unknown }

/** Serves the configs fixtures; `write` answers anything else (edit, apply, group calls). */
function serve(write: (c: Call) => Response | undefined = () => undefined, tweak: (o: ConfigOverview) => void = () => {}) {
  const data: ConfigOverview = structuredClone(overview);
  tweak(data);
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
      if (url === `/v1/configs/bundles/${AYN_BUNDLE}/versions`) return json(200, aynVersions);
      if (url === `/v1/configs/bundles/${RP_BUNDLE}/versions`) return json(200, rpVersions);
      if (url.startsWith("/v1/configs/diff")) return json(200, diff);
      if (url === `/v1/web/devices/${THOR}`) return json(200, deviceDetail(THOR, "cv5"));
      if (url.startsWith("/v1/configs/versions/")) return new Response('video_shader_enable = "true"\n', { status: 200, headers: { "Content-Type": "text/plain" } });
    }
    return new Response(null, { status: 204 });
  }));
  return calls;
}

const urls = (calls: Call[], prefix: string) => calls.filter((c) => c.url.startsWith(prefix)).map((c) => c.url);

describe("Configs", () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.removeAttribute("open"); };
    router.go("/configs");
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("puts the emulator sub-tabs in the module order", async () => {
    serve();
    render(Configs);
    await screen.findByText("AYN handhelds");
    const nav = screen.getByRole("navigation", { name: "Sections" });
    expect(within(nav).getAllByRole("link").map((a) => a.textContent)).toEqual(
      ["Eden", "RetroArch", "Azahar", "PPSSPP", "Vita3K", "Dolphin", "melonDS"],
    );
    // The first emulator with configs is shown.
    expect(within(nav).getByRole("link", { name: "RetroArch" }).getAttribute("aria-current")).toBe("page");
  });

  it("shows groups with their members and the per-device paths line, and solo devices with the on-its-own line", async () => {
    serve();
    render(Configs);
    expect(await screen.findByText("AYN handhelds")).toBeTruthy();
    expect(screen.getByText("Odin 3 and Thor")).toBeTruthy();
    expect(screen.getByText("Folder paths stay per device.")).toBeTruthy();
    expect(screen.getByText("Retroid")).toBeTruthy();
    expect(screen.getByText("On its own: backed up, never changed by other devices.")).toBeTruthy();
  });

  it("names each version's source in the history", async () => {
    serve();
    render(Configs);
    await screen.findByText("v7");
    expect(screen.getByText("edited in browser")).toBeTruthy();
    expect(screen.getByText("copied from another device")).toBeTruthy();
  });

  it("compares the head against the version before it by default", async () => {
    const calls = serve();
    render(Configs);
    expect(await screen.findByRole("heading", { name: /v6 → v7/ })).toBeTruthy();
    await waitFor(() => expect(urls(calls, "/v1/configs/diff")).toContain("/v1/configs/diff?a=cv6&b=cv7"));
    expect(await screen.findByText("2 files changed")).toBeTruthy();
    expect(screen.getByText('video_shader_enable = "true"')).toBeTruthy();
  });

  it("compares with a device's current files through ?device=", async () => {
    const calls = serve();
    render(Configs);
    const select = (await screen.findByLabelText("Compare with")) as HTMLSelectElement;
    const option = [...select.options].find((o) => o.text === "Thor's current files")!;
    expect(option).toBeTruthy();
    await fireEvent.change(select, { target: { value: option.value } });
    await waitFor(() => expect(urls(calls, "/v1/configs/diff")).toContain(`/v1/configs/diff?a=cv5&b=cv7&device=${THOR}`));
    expect(await screen.findByRole("heading", { name: /Thor's current files → v7/ })).toBeTruthy();
  });

  it("compares from the history row chosen with a", async () => {
    const calls = serve();
    render(Configs);
    await screen.findByRole("heading", { name: /v6 → v7/ });
    dispatch("down"); // the group's history: v7
    dispatch("down"); // v6
    expect(dispatch("a")).toBe(true);
    expect(await screen.findByRole("heading", { name: /v5 → v6/ })).toBeTruthy();
    await waitFor(() => expect(urls(calls, "/v1/configs/diff")).toContain("/v1/configs/diff?a=cv5&b=cv6"));
  });

  it("switches the changed file with left and right while the diff pane has focus", async () => {
    serve();
    render(Configs);
    await screen.findByText('video_shader_enable = "true"');
    expect(dispatch("right")).toBe(true); // into the diff pane
    expect(dispatch("right")).toBe(true); // next changed file
    expect(await screen.findByText('mgba_skip_bios = "ON"')).toBeTruthy();
    dispatch("left"); // back to the first file
    expect(await screen.findByText('video_shader_enable = "true"')).toBeTruthy();
  });

  it("shows the in-place message when an edit was overtaken", async () => {
    const calls = serve((c) => c.method === "PUT" ? json(409, { code: "stale_base", message: "stale" }) : undefined);
    render(Configs);
    await screen.findByText('video_shader_enable = "true"');
    expect(dispatch("y")).toBe(true);
    const dialog = await screen.findByRole("dialog");
    const area = (await within(dialog).findByLabelText("retroarch.cfg")) as HTMLTextAreaElement;
    await waitFor(() => expect(area.value).toBe('video_shader_enable = "true"\n'));
    await fireEvent.input(area, { target: { value: 'video_shader_enable = "false"\n' } });
    await fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByText("Someone changed this config since you opened it. Reload to see their change.")).toBeTruthy();
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.url).toBe(`/v1/configs/bundles/${AYN_BUNDLE}/files/retroarch.cfg?device=${ODIN}`);
    expect(put.body).toEqual({ baseVersionId: "cv7", text: 'video_shader_enable = "false"\n' });
  });

  it("applies the focused version Linked to the chosen device", async () => {
    const calls = serve((c) => c.url === "/v1/configs/apply" ? json(200, { bundleId: AYN_BUNDLE, headVersionId: "cv7", groupId: AYN_GROUP }) : undefined);
    render(Configs);
    await screen.findByRole("heading", { name: /v6 → v7/ });
    expect(dispatch("x")).toBe(true);
    const dialog = await screen.findByRole("dialog");
    expect((within(dialog).getByLabelText("Device") as HTMLSelectElement).value).toBe(RP);
    expect(within(dialog).getByText("Joins AYN handhelds and keeps getting its changes.")).toBeTruthy();
    expect(within(dialog).queryByText(/back to this version/)).toBeNull();
    await fireEvent.click(within(dialog).getByRole("button", { name: "Apply" }));
    await waitFor(() => expect(calls.find((c) => c.url === "/v1/configs/apply")?.body).toEqual(
      { targetDeviceId: RP, emulatorId: "retroarch", sourceVersionId: "cv7", mode: "Linked" },
    ));
  });

  it("says Linked rolls the group back when the version is older, and explains Copy once", async () => {
    serve();
    render(Configs);
    await screen.findByRole("heading", { name: /v6 → v7/ });
    dispatch("down");
    dispatch("down");
    dispatch("a"); // compare v6
    await screen.findByRole("heading", { name: /v5 → v6/ });
    dispatch("x");
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("This also changes Odin 3 and Thor back to this version.")).toBeTruthy();
    await fireEvent.click(within(dialog).getByLabelText("Copy once"));
    expect(within(dialog).getByText("Starts from this version, then stays on its own.")).toBeTruthy();
    expect(within(dialog).getByText("The device's current config is saved as a version first. RetroArch must be closed; until then it waits.")).toBeTruthy();
  });

  it("shows an open group conflict in place when applying Linked", async () => {
    serve((c) => c.url === "/v1/configs/apply" ? json(409, { code: "conflict_open", message: "x" }) : undefined);
    render(Configs);
    await screen.findByRole("heading", { name: /v6 → v7/ });
    dispatch("x");
    const dialog = await screen.findByRole("dialog");
    await fireEvent.click(within(dialog).getByRole("button", { name: "Apply" }));
    expect(await within(dialog).findByText(/two versions waiting for a choice/)).toBeTruthy();
  });

  it("pins a config with two versions to the top as a problem row", async () => {
    serve(undefined, (o) => { o.emulators[0]!.solo[0]!.openConflict = true; });
    render(Configs);
    const problem = await screen.findByText("Two versions — choose");
    expect(problem.classList.contains("fault")).toBe(true);
    const rows = screen.getAllByRole("button").filter((b) => b.classList.contains("row"));
    expect(rows[0]!.textContent).toContain("Retroid");
  });

  it("makes a group from a solo device", async () => {
    const calls = serve();
    render(Configs);
    await screen.findByText("v4");
    for (let i = 0; i < 5; i++) dispatch("down"); // past the group's history to Retroid
    dispatch("a"); // shows Retroid's history
    expect(await screen.findByText("joined group")).toBeTruthy();
    dispatch("a"); // a again on the shown device opens its group dialog
    const dialog = await screen.findByRole("dialog");
    await fireEvent.input(within(dialog).getByLabelText("Group name"), { target: { value: "Retroid set" } });
    await fireEvent.click(within(dialog).getByRole("button", { name: "Make a group" }));
    await waitFor(() => expect(calls.find((c) => c.method === "POST" && c.url === "/v1/configs/groups")?.body).toEqual(
      { emulatorId: "retroarch", name: "Retroid set", seedDeviceId: RP },
    ));
  });

  it("deletes a group after the confirm, taking every member out first", async () => {
    const calls = serve();
    render(Configs);
    await screen.findByText("AYN handhelds");
    dispatch("a");
    const dialog = await screen.findByRole("dialog");
    await fireEvent.click(within(dialog).getByRole("button", { name: "Delete group" }));
    expect(within(dialog).getByText("Members keep their current files and go back to being on their own.")).toBeTruthy();
    await fireEvent.click(within(dialog).getByRole("button", { name: "Delete AYN handhelds" }));
    await waitFor(() => expect(calls.filter((c) => c.method === "DELETE").map((c) => c.url)).toEqual([
      `/v1/configs/groups/${AYN_GROUP}/members/${ODIN}`,
      `/v1/configs/groups/${AYN_GROUP}/members/${THOR}`,
      `/v1/configs/groups/${AYN_GROUP}`,
    ]));
  });

  it("says so when an emulator has no configs", async () => {
    serve();
    render(Configs);
    const nav = await screen.findByRole("navigation", { name: "Sections" });
    await fireEvent.click(within(nav).getByRole("link", { name: "Azahar" }));
    expect(await screen.findByText("No Azahar configs yet.")).toBeTruthy();
  });
});
