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

/** Listens for connect/disconnect and runs the poll loop while connected. Returns cleanup. */
export function installGamepad(): () => void {
  let frame = 0;
  let prev: boolean[] = [];
  const repeat: RepeatState = new Map();

  const poll = () => {
    const pad = Array.from(navigator.getGamepads?.() ?? []).find((p) => p?.connected);
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
    gamepad.connected = true;
    if (!frame) {
      // Buttons already held at connect time must not count as presses.
      prev = Array.from(navigator.getGamepads?.() ?? []).find((p) => p?.connected)?.buttons.map((b) => b.pressed) ?? [];
      repeat.clear();
      frame = requestAnimationFrame(poll);
    }
  };
  const disconnect = () => {
    gamepad.connected = false;
    stopLoop();
  };

  window.addEventListener("gamepadconnected", connect);
  window.addEventListener("gamepaddisconnected", disconnect);
  return () => {
    window.removeEventListener("gamepadconnected", connect);
    window.removeEventListener("gamepaddisconnected", disconnect);
    stopLoop();
    gamepad.connected = false;
  };
}
