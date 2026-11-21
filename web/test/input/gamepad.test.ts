import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { gamepadToActions, gamepad, installGamepad } from "../../src/lib/input/gamepad.svelte";
import { onAction, type Action } from "../../src/lib/input/actions";

const down = (...idx: number[]) => Array.from({ length: 17 }, (_, i) => idx.includes(i));
const none = down();

describe("gamepadToActions", () => {
  it("fires on press edges using the standard mapping", () => {
    const map: [number, Action][] = [
      [0, "a"], [1, "b"], [2, "x"], [3, "y"], [4, "l1"], [5, "r1"], [6, "l2"], [7, "r2"],
      [12, "up"], [13, "down"], [14, "left"], [15, "right"],
    ];
    for (const [i, a] of map) expect(gamepadToActions(none, down(i))).toEqual([a]);
    expect(gamepadToActions(none, down(8))).toEqual([]);
  });

  it("does not refire for a held face button or a release", () => {
    expect(gamepadToActions(down(0), down(0), new Map(), 1000)).toEqual([]);
    expect(gamepadToActions(down(0), none)).toEqual([]);
  });

  it("repeats a held D-pad after 400 ms, then every 120 ms", () => {
    const rep = new Map<number, number>();
    expect(gamepadToActions(none, down(13), rep, 0)).toEqual(["down"]);
    expect(gamepadToActions(down(13), down(13), rep, 399)).toEqual([]);
    expect(gamepadToActions(down(13), down(13), rep, 400)).toEqual(["down"]);
    expect(gamepadToActions(down(13), down(13), rep, 519)).toEqual([]);
    expect(gamepadToActions(down(13), down(13), rep, 520)).toEqual(["down"]);
    gamepadToActions(down(13), none, rep, 530);
    expect(gamepadToActions(none, down(13), rep, 540)).toEqual(["down"]);
    expect(gamepadToActions(down(13), down(13), rep, 900)).toEqual([]);
  });
});

describe("gamepad loop", () => {
  let pad: boolean[];
  /** No pad is reported until a test plugs one in. */
  let plugged: boolean;
  let mapping: string;
  const plug = () => {
    plugged = true;
    window.dispatchEvent(new Event("gamepadconnected"));
  };
  let stop: () => void;
  const got: Action[] = [];
  let off: () => void;

  beforeEach(() => {
    vi.useFakeTimers();
    pad = none;
    plugged = false;
    mapping = "standard";
    got.length = 0;
    Object.defineProperty(navigator, "getGamepads", {
      configurable: true,
      value: () => (plugged ? [{ connected: true, mapping, buttons: pad.map((pressed) => ({ pressed })) }] : []),
    });
    off = onAction((a) => { got.push(a); return true; });
    stop = installGamepad();
  });
  afterEach(() => { stop(); off(); vi.restoreAllMocks(); vi.useRealTimers(); });

  it("polls only while connected and feeds dispatch", () => {
    const raf = vi.spyOn(window, "requestAnimationFrame");
    pad = down(0);
    vi.advanceTimersByTime(100);
    expect(got).toEqual([]);
    expect(raf).not.toHaveBeenCalled();

    pad = none;
    plug();
    expect(gamepad.connected).toBe(true);
    vi.advanceTimersByTime(50);
    pad = down(0);
    vi.advanceTimersByTime(50);
    expect(got).toEqual(["a"]);

    window.dispatchEvent(new Event("gamepaddisconnected"));
    expect(gamepad.connected).toBe(false);
    pad = none;
    vi.advanceTimersByTime(50);
    pad = down(1);
    vi.advanceTimersByTime(100);
    expect(got).toEqual(["a"]);
  });

  it("keeps polling while another pad is still connected", () => {
    const pads: ({ connected: boolean; mapping: string; buttons: { pressed: boolean }[] } | null)[] = [
      { connected: true, mapping: "standard", buttons: none.map((pressed) => ({ pressed })) },
      { connected: true, mapping: "standard", buttons: none.map((pressed) => ({ pressed })) },
    ];
    Object.defineProperty(navigator, "getGamepads", { configurable: true, value: () => pads });
    const gone = (index: number) => Object.assign(new Event("gamepaddisconnected"), { gamepad: { index } });

    plug();
    vi.advanceTimersByTime(50);

    pads[0] = null;
    window.dispatchEvent(gone(0));
    expect(gamepad.connected).toBe(true);
    pads[1]!.buttons = down(0).map((pressed) => ({ pressed }));
    vi.advanceTimersByTime(50);
    expect(got).toEqual(["a"]);

    pads[1] = null;
    window.dispatchEvent(gone(1));
    expect(gamepad.connected).toBe(false);
  });

  it("ignores a pad without the standard mapping", () => {
    mapping = "";
    plug();
    expect(gamepad.connected).toBe(false);
    pad = down(0);
    vi.advanceTimersByTime(100);
    expect(got).toEqual([]);
  });

  it("picks up a pad that was already connected when the page loaded", () => {
    stop();
    plugged = true;
    pad = down(0); // held at load: not a press
    stop = installGamepad();
    expect(gamepad.connected).toBe(true);
    vi.advanceTimersByTime(50);
    expect(got).toEqual([]);
    pad = none;
    vi.advanceTimersByTime(50);
    pad = down(1);
    vi.advanceTimersByTime(50);
    expect(got).toEqual(["b"]);
  });

  it("repeats a held D-pad in real loop time", () => {
    plug();
    vi.advanceTimersByTime(50);
    pad = down(15);
    vi.advanceTimersByTime(1000);
    expect(got.length).toBeGreaterThanOrEqual(5);
    expect(got.every((a) => a === "right")).toBe(true);
    window.dispatchEvent(new Event("gamepaddisconnected"));
  });
});
