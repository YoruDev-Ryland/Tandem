<script lang="ts">
  import type { Action } from "../input/actions";

  /** A face button (a b x y) draws as a disc; shoulders and the D-pad draw as an outlined key. */
  let { action }: { action: Action } = $props();

  const LABELS: Record<Action, string> = {
    a: "A", b: "B", x: "X", y: "Y", l1: "L1", r1: "R1", l2: "L2", r2: "R2",
    up: "Up", down: "Down", left: "Left", right: "Right",
  };
  const face = $derived(action === "a" || action === "b" || action === "x" || action === "y");
</script>

<span class={face ? "disc" : "key"} aria-hidden="true">{LABELS[action]}</span>

<style>
  .disc {
    flex: none;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: var(--glyph);
    color: var(--ink);
    font: 700 11px/1 "Barlow", sans-serif;
  }
  .key {
    flex: none;
    display: inline-flex;
    align-items: center;
    height: 20px;
    padding: 0 6px;
    border: 1.5px solid var(--ink3);
    border-radius: 3px;
    color: var(--ink2);
    font: 700 11px/1 "Barlow", sans-serif;
  }
</style>
