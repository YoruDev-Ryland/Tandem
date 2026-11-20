<script lang="ts">
  import type { Snippet } from "svelte";
  import type { HTMLButtonAttributes } from "svelte/elements";
  import { gamepad } from "../input/gamepad.svelte";
  import Glyph from "./Glyph.svelte";

  interface Props extends HTMLButtonAttributes {
    variant?: "primary" | "secondary";
    glyph?: "a" | "b" | "x" | "y";
    children: Snippet;
  }
  let { variant = "secondary", glyph, type = "button", children, ...rest }: Props = $props();
</script>

<button {type} class={variant} {...rest}>
  {#if glyph && gamepad.connected}<Glyph action={glyph} />{/if}
  {@render children()}
</button>

<style>
  button {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    height: 40px;
    padding: 0 16px;
    border-radius: 3px;
    border: 1.5px solid var(--rule2);
    background: transparent;
    color: var(--ink);
    font: 600 14px "Barlow", system-ui, sans-serif;
    white-space: nowrap;
    cursor: pointer;
    transition:
      border-color 150ms cubic-bezier(0.23, 1, 0.32, 1),
      background-color 150ms cubic-bezier(0.23, 1, 0.32, 1);
  }
  .secondary:hover:not(:disabled) {
    border-color: var(--ink3);
  }
  .primary {
    background: var(--ink);
    border-color: var(--ink);
    color: var(--bg);
  }
  .primary:hover:not(:disabled) {
    background: color-mix(in srgb, var(--ink) 86%, var(--bg));
  }
  /* The disc sits on the inverted fill, so it takes the page colours. */
  .primary :global(.disc) {
    background: var(--bg);
    color: var(--ink);
  }
  button:disabled {
    cursor: default;
    color: var(--ink3);
    border-color: var(--rule2);
    background: transparent;
  }
  @media (prefers-reduced-motion: reduce) {
    button {
      transition: none;
    }
  }
</style>
