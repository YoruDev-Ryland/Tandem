import { afterEach, describe, expect, it, vi } from "vitest";
import { keyToAction, installKeyboard } from "../../src/lib/input/keyboard";
import { onAction, type Action } from "../../src/lib/input/actions";

function key(k: string, init: KeyboardEventInit = {}, target: Element = document.body) {
  const e = new KeyboardEvent("keydown", { key: k, bubbles: true, cancelable: true, ...init });
  target.dispatchEvent(e);
  return e;
}

describe("keyToAction", () => {
  it("maps the documented keys", () => {
    const pairs: [string, Action][] = [
      ["ArrowUp", "up"], ["ArrowDown", "down"], ["ArrowLeft", "left"], ["ArrowRight", "right"],
      ["Enter", "a"], ["Escape", "b"], ["[", "l1"], ["]", "r1"], ["{", "l2"], ["}", "r2"],
    ];
    for (const [k, a] of pairs) expect(keyToAction(new KeyboardEvent("keydown", { key: k }))).toBe(a);
    expect(keyToAction(new KeyboardEvent("keydown", { key: "q" }))).toBeNull();
  });

  it("ignores everything but Escape while typing in an input, textarea or select", () => {
    for (const tag of ["input", "textarea", "select"]) {
      const el = document.body.appendChild(document.createElement(tag));
      for (const k of ["[", "ArrowDown", "Enter"]) {
        const e = new KeyboardEvent("keydown", { key: k });
        Object.defineProperty(e, "target", { value: el });
        expect(keyToAction(e)).toBeNull();
      }
      const esc = new KeyboardEvent("keydown", { key: "Escape" });
      Object.defineProperty(esc, "target", { value: el });
      expect(keyToAction(esc)).toBe("b");
      el.remove();
    }
  });

  it("leaves Enter on a focused button or link to the browser, so the control activates itself", () => {
    for (const tag of ["button", "a"]) {
      const el = document.body.appendChild(document.createElement(tag));
      const enter = new KeyboardEvent("keydown", { key: "Enter" });
      Object.defineProperty(enter, "target", { value: el });
      expect(keyToAction(enter)).toBeNull();
      const down = new KeyboardEvent("keydown", { key: "ArrowDown" });
      Object.defineProperty(down, "target", { value: el });
      expect(keyToAction(down)).toBe("down");
      el.remove();
    }
  });

  it("ignores keys with Ctrl, Alt or Meta held", () => {
    for (const mod of ["ctrlKey", "altKey", "metaKey"])
      expect(keyToAction(new KeyboardEvent("keydown", { key: "ArrowLeft", [mod]: true }))).toBeNull();
  });
});

describe("installKeyboard", () => {
  const cleanups: (() => void)[] = [];
  afterEach(() => { cleanups.splice(0).forEach((c) => c()); });

  it("prevents default only when a handler consumes the action", () => {
    cleanups.push(installKeyboard());
    expect(key("ArrowDown").defaultPrevented).toBe(false);
    cleanups.push(onAction(() => true));
    expect(key("ArrowDown").defaultPrevented).toBe(true);
  });

  it("stops listening after cleanup", () => {
    const seen = vi.fn(() => true);
    cleanups.push(onAction(seen));
    installKeyboard()();
    key("ArrowDown");
    expect(seen).not.toHaveBeenCalled();
  });
});
