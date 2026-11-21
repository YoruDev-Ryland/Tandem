import { dispatch, type Action } from "./actions";

const KEYS: Record<string, Action> = {
  ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right",
  Enter: "a", Escape: "b",
  "[": "l1", "]": "r1", "{": "l2", "}": "r2",
  // The two secondary actions, so the X and Y hints work from a keyboard too (never while typing in a field).
  x: "x", X: "x", y: "y", Y: "y",
};

function isTextTarget(t: EventTarget | null): boolean {
  if (!(t instanceof HTMLElement)) return false;
  return t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable;
}

/** Buttons and links activate themselves on Enter; turning that into "a" would let a list or dialog swallow it. */
function isActivatable(t: EventTarget | null): boolean {
  return t instanceof HTMLElement && (t.tagName === "BUTTON" || t.tagName === "A" || t.tagName === "SUMMARY");
}

export function keyToAction(e: KeyboardEvent): Action | null {
  if (e.ctrlKey || e.altKey || e.metaKey) return null;
  const action = KEYS[e.key] ?? null;
  if (action && action !== "b" && isTextTarget(e.target)) return null;
  if (action === "a" && isActivatable(e.target)) return null;
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
