export type Action = "up" | "down" | "left" | "right" | "a" | "b" | "x" | "y" | "l1" | "r1" | "l2" | "r2";

export type ActionHandler = (a: Action) => boolean;

const stack: ActionHandler[] = [];

/** Registers a handler on top of the stack. Returns its unsubscribe. */
export function onAction(handler: ActionHandler): () => void {
  stack.push(handler);
  return () => {
    const i = stack.lastIndexOf(handler);
    if (i >= 0) stack.splice(i, 1);
  };
}

/** Offers the action to handlers newest-first; true if one consumed it. */
export function dispatch(action: Action): boolean {
  for (let i = stack.length - 1; i >= 0; i--) {
    if (stack[i]!(action)) return true;
  }
  return false;
}
