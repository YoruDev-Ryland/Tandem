/**
 * The success line: after Keep, Restore, Apply or Delete, the hint bar says what happened for five seconds. It lives
 * outside any page so it survives the navigation that usually follows the action.
 */
export const FLASH_MS = 5000;

class Flash {
  text = $state("");
  /** Bumped on every say(), so the same sentence twice still restarts the tick. */
  seq = $state(0);
  private timer: ReturnType<typeof setTimeout> | undefined;

  say(text: string): void {
    clearTimeout(this.timer);
    this.text = text;
    this.seq++;
    this.timer = setTimeout(() => (this.text = ""), FLASH_MS);
  }

  clear(): void {
    clearTimeout(this.timer);
    this.text = "";
  }
}

export const flash = new Flash();

/**
 * Svelte action: when `value` changes after the first render (a live update from the event stream), the element's
 * ink fades from --ink3 to its own colour over 600 ms. Colour only, no movement, so it stays under reduced motion.
 */
export function pulse(node: HTMLElement, value: unknown) {
  let last = value;
  return {
    update(next: unknown) {
      if (next === last) return;
      last = next;
      node.classList.remove("pulse");
      void node.offsetWidth; // restart the animation
      node.classList.add("pulse");
    },
  };
}
