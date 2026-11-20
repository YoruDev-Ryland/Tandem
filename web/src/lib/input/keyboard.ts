import { dispatch, type Action } from "./actions";

const KEYS: Record<string, Action> = {
  ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right",
  Enter: "a", Escape: "b",
  "[": "l1", "]": "r1", "{": "l2", "}": "r2",
};

function isTextTarget(t: EventTarget | null): boolean {
  if (!(t instanceof HTMLElement)) return false;
  return t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable;
}

export function keyToAction(e: KeyboardEvent): Action | null {
  if (e.ctrlKey || e.altKey || e.metaKey) return null;
  const action = KEYS[e.key] ?? null;
  if (action && action !== "b" && isTextTarget(e.target)) return null;
  return action;
}

/** Installs the one global keydown listener. Returns its cleanup. */
export function installKeyboard(): () => void {
  const onKeyDown = (e: KeyboardEvent) => {
    const action = keyToAction(e);
    if (action && dispatch(action)) e.preventDefault();
  };
  window.addEventListener("keydown", onKeyDown);
  return () => window.removeEventListener("keydown", onKeyDown);
}
