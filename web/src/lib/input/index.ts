import { installGamepad } from "./gamepad.svelte";
import { installKeyboard } from "./keyboard";

/** Installs the global keyboard listener and gamepad loop once. Returns cleanup. */
export function installInput(): () => void {
  const offs = [installKeyboard(), installGamepad()];
  return () => offs.forEach((off) => off());
}
