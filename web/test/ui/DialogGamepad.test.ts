import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/svelte";
import { flushSync, tick } from "svelte";
import RestoreDialog from "../../src/lib/ui/RestoreDialog.svelte";
import ApplyDialog from "../../src/lib/ui/ApplyDialog.svelte";
import GroupDialog from "../../src/lib/ui/GroupDialog.svelte";
import { dispatch, type Action } from "../../src/lib/input/actions";

beforeAll(() => {
  const proto = HTMLDialogElement.prototype as HTMLDialogElement & Record<string, unknown>;
  proto.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
  proto.close ??= function (this: HTMLDialogElement) {
    if (!this.hasAttribute("open")) return;
    this.removeAttribute("open");
    this.dispatchEvent(new Event("close"));
  };
});

interface Call { method: string; url: string; body: unknown }
function serve(answer: (c: Call) => Response) {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const c = { method: init?.method ?? "GET", url, body: init?.body ? JSON.parse(init.body as string) : undefined };
    calls.push(c);
    return answer(c);
  }));
  return calls;
}
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

function press(...actions: Action[]) {
  for (const a of actions) {
    expect(dispatch(a)).toBe(true);
    flushSync();
  }
}

describe("Dialog with a gamepad", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("completes a restore with the D-pad and A only", async () => {
    const calls = serve(() => json({ jobId: "j1" }));
    const onrestored = vi.fn();
    render(RestoreDialog, {
      open: true, itemId: "i1", versionId: "v1", label: "v3", emulatorName: "melonDS",
      devices: [{ id: "d1", name: "Odin 3" }, { id: "d2", name: "Thor" }], onrestored,
    });
    await tick();
    const select = screen.getByLabelText("Restore to") as HTMLSelectElement;
    expect(document.activeElement).toBe(select);
    press("right");
    expect(select.value).toBe("d2");
    expect(screen.getByText(/Thor's current save is kept/)).toBeTruthy();
    press("left", "right", "right");
    expect(select.value).toBe("d2");
    press("down");
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Restore" }));
    press("up");
    expect(document.activeElement).toBe(select);
    press("a");
    await waitFor(() => expect(onrestored).toHaveBeenCalledWith("Thor"));
    expect(calls).toEqual([{ method: "POST", url: "/v1/saves/i1/versions/v1/restore", body: { deviceId: "d2" } }]);
  });

  it("completes an apply with the D-pad and A only, switching the mode radio", async () => {
    const calls = serve(() => json({ bundleId: "b", headVersionId: "h", groupId: null }));
    const onapplied = vi.fn();
    render(ApplyDialog, {
      open: true, emulatorId: "eden", emulatorName: "Eden", versionId: "cv1", label: "v7",
      groupName: "Handhelds", ownerName: null, rollback: [],
      linkTargets: [{ id: "d1", name: "Odin 3" }],
      copyTargets: [{ id: "d2", name: "Thor" }, { id: "d3", name: "Deck" }],
      onapplied,
    });
    await tick();
    const linked = screen.getByLabelText("Linked") as HTMLInputElement;
    expect(document.activeElement).toBe(linked);
    press("right");
    const copy = screen.getByLabelText("Copy once") as HTMLInputElement;
    expect(copy.checked).toBe(true);
    expect(document.activeElement).toBe(copy);
    press("down");
    const select = screen.getByLabelText("Device") as HTMLSelectElement;
    expect(document.activeElement).toBe(select);
    press("right");
    expect(select.value).toBe("d3");
    press("down");
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Apply" }));
    press("right");
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Cancel" }));
    press("left", "a");
    await waitFor(() => expect(onapplied).toHaveBeenCalledWith("Deck", "Copy"));
    expect(calls[0]!.body).toEqual({ targetDeviceId: "d3", emulatorId: "eden", sourceVersionId: "cv1", mode: "Copy" });
  });

  it("does not submit from a field while the form's submit button is disabled", async () => {
    const calls = serve(() => json({}));
    render(ApplyDialog, {
      open: true, emulatorId: "eden", emulatorName: "Eden", versionId: "cv1", label: "v7",
      groupName: null, ownerName: "Odin 3", rollback: [], linkTargets: [], copyTargets: [], onapplied: vi.fn(),
    });
    await tick();
    press("a");
    await tick();
    expect(calls).toEqual([]);
  });

  it("focuses Keep the group, not the destructive button, when a group delete asks to confirm", async () => {
    serve(() => json({}));
    render(GroupDialog, {
      props: {
        open: true, emulatorId: "eden", emulatorName: "Eden",
        target: { kind: "group", id: "g1", name: "Handhelds", members: [] },
        groups: [], solo: [], onchanged: vi.fn(),
      },
    });
    await tick();
    screen.getByRole("button", { name: "Delete group" }).click();
    flushSync();
    await tick();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Keep the group" }));
  });
});
