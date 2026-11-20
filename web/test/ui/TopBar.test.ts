import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/svelte";
import TopBar from "../../src/lib/ui/TopBar.svelte";
import { gamepad } from "../../src/lib/input/gamepad.svelte";
import { dispatch } from "../../src/lib/input/actions";
import { router } from "../../src/lib/router.svelte";
import type { DeviceSummary } from "../../src/lib/api/types";

const now = new Date().toISOString();
const device = (over: Partial<DeviceSummary>): DeviceSummary => ({
  id: "d1", name: "Thor", platform: "android", lastSeenUtc: now, shizukuOk: true, isCurrent: false, runningEmulators: [],
  ...over,
});
const tabs = [
  { label: "Home", path: "/" },
  { label: "Games", path: "/games" },
  { label: "Devices", path: "/devices" },
];
const stateOf = (name: string) => screen.getByText(name).closest(".device")!.querySelector(".state")!;

describe("TopBar", () => {
  afterEach(() => { cleanup(); gamepad.connected = false; });

  it("shows Shizuku off in --fault", () => {
    render(TopBar, { tabs, active: "/", devices: [device({ shizukuOk: false })] });
    const state = stateOf("Thor");
    expect(state.textContent).toBe("Shizuku off");
    expect(state.classList.contains("fault")).toBe(true);
  });

  it("says which emulator is open, in --wait only when that device has a waiting game", () => {
    const devices = [
      device({ id: "a", name: "Thor", runningEmulators: ["eden"] }),
      device({ id: "b", name: "Odin 3", runningEmulators: ["retroarch"] }),
    ];
    render(TopBar, { tabs, active: "/", devices, waitingDeviceIds: ["a"] });
    expect(stateOf("Thor").textContent).toBe("Eden open");
    expect(stateOf("Thor").classList.contains("wait")).toBe(true);
    expect(stateOf("Odin 3").textContent).toBe("RetroArch open");
    expect(stateOf("Odin 3").classList.contains("wait")).toBe(false);
  });

  it("shows offline in --ink3 once a device has not been seen for over 2 minutes", () => {
    const old = new Date(Date.now() - 3 * 60_000).toISOString();
    render(TopBar, { tabs, active: "/", devices: [device({ lastSeenUtc: old, runningEmulators: ["eden"] })] });
    expect(stateOf("Thor").textContent).toBe("offline");
    expect(stateOf("Thor").classList.contains("ink3")).toBe(true);
  });

  it("shows bumper hints only with a gamepad", async () => {
    render(TopBar, { tabs, active: "/", devices: [] });
    expect(screen.queryByText("L1")).toBeNull();
    gamepad.connected = true;
    await Promise.resolve();
    expect(screen.getByText("L1")).toBeTruthy();
    expect(screen.getByText("R1")).toBeTruthy();
  });

  it("moves to the next and previous page tab on r1 and l1", () => {
    router.go("/games");
    render(TopBar, { tabs, active: "/games", devices: [] });
    expect(dispatch("r1")).toBe(true);
    expect(router.path).toBe("/devices");
    dispatch("l1");
    expect(router.path).toBe("/");
  });
});
