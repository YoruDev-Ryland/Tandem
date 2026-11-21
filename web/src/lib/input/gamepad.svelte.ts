import { dispatch, type Action } from "./actions";

const BUTTONS: Record<number, Action> = {
  0: "a", 1: "b", 2: "x", 3: "y", 4: "l1", 5: "r1", 6: "l2", 7: "r2",
  12: "up", 13: "down", 14: "left", 15: "right",
};
const DPAD = new Set([12, 13, 14, 15]);
const REPEAT_DELAY_MS = 400;
const REPEAT_INTERVAL_MS = 120;

/** Button index -> time at which a held D-pad button next repeats. */
export type RepeatState = Map<number, number>;

export function gamepadToActions(
  prev: boolean[],
  now: boolean[],
  repeat: RepeatState = new Map(),
  t: number = performance.now(),
): Action[] {
  const out: Action[] = [];
  for (const [k, action] of Object.entries(BUTTONS)) {
    const i = Number(k);
    const down = !!now[i];
    if (!down) {
      repeat.delete(i);
    } else if (!prev[i]) {
      out.push(action);
      if (DPAD.has(i)) repeat.set(i, t + REPEAT_DELAY_MS);
    } else if (DPAD.has(i) && t >= (repeat.get(i) ?? Infinity)) {
      out.push(action);
      repeat.set(i, t + REPEAT_INTERVAL_MS);
    }
  }
  return out;
}

class GamepadState {
  connected = $state(false);
}
export const gamepad = new GamepadState();

/**
 * The pad to read: the first connected one with the standard mapping. Button indices only mean A/B/X/Y and the D-pad
 * under that mapping; other pads (and some motion devices that report as gamepads) are ignored.
 */
export function standardPad(pads: readonly (Gamepad | null)[]): Gamepad | undefined {
  return pads.find((p): p is Gamepad => !!p && p.connected && p.mapping === "standard");
}

const pads = () => Array.from(navigator.getGamepads?.() ?? []);

/**
 * Listens for connect/disconnect and runs the poll loop while a standard pad is connected. A pad already connected
 * when this runs (the page was reloaded with it plugged in) is picked up straight away. Returns cleanup.
 */
export function installGamepad(): () => void {
  let frame = 0;
  let prev: boolean[] = [];
  const repeat: RepeatState = new Map();

  const poll = () => {
    const pad = standardPad(pads());
    if (pad) {
      const now = pad.buttons.map((b) => b.pressed);
      for (const a of gamepadToActions(prev, now, repeat, performance.now())) dispatch(a);
      prev = now;
    }
    frame = requestAnimationFrame(poll);
  };
  const stopLoop = () => {
    cancelAnimationFrame(frame);
    frame = 0;
  };
  const connect = () => {
    const pad = standardPad(pads());
    if (!pad) return;
    gamepad.connected = true;
    if (!frame) {
      // Buttons already held at connect time must not count as presses.
      prev = pad.buttons.map((b) => b.pressed);
      repeat.clear();
      frame = requestAnimationFrame(poll);
    }
  };
  const disconnect = (e: Event) => {
    const gone = (e as GamepadEvent).gamepad?.index;
    const remaining = pads().some((p) => p?.connected && p.mapping === "standard" && p.index !== gone);
    if (remaining) return;
    gamepad.connected = false;
    stopLoop();
  };

  window.addEventListener("gamepadconnected", connect);
  window.addEventListener("gamepaddisconnected", disconnect);
  connect();
  return () => {
    window.removeEventListener("gamepadconnected", connect);
    window.removeEventListener("gamepaddisconnected", disconnect);
    stopLoop();
    gamepad.connected = false;
  };
}
