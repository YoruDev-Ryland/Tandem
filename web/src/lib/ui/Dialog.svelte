<script lang="ts">
  import { tick, type Snippet } from "svelte";
  import { onAction } from "../input/actions";

  interface Props {
    open: boolean;
    title: string;
    onclose?: () => void;
    /** Room for an editor: up to 960 px wide instead of 520. */
    wide?: boolean;
    /** Esc and B do not close it (a request that must finish is in flight). */
    locked?: boolean;
    children: Snippet;
  }
  let { open = $bindable(false), title, onclose, wide = false, locked = false, children }: Props = $props();

  let el: HTMLDialogElement;
  /** Children render only while shown, so their action handlers stack above the dialog's. */
  let shown = $state(false);
  let opener: HTMLElement | null = null;
  let offAction: (() => void) | null = null;

  function show() {
    opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    // Modal: B closes it, and nothing reaches the page underneath while it is open.
    offAction = onAction((a) => {
      if (a === "b" && !locked) open = false;
      // Gamepad A presses the focused control (keyboard Enter does this natively).
      if (a === "a" && document.activeElement instanceof HTMLElement && document.activeElement !== el && el.contains(document.activeElement)) {
        document.activeElement.click();
      }
      return true;
    });
    el.showModal();
    shown = true;
    // The contents render after showModal, so move focus into them once they exist.
    void tick().then(() => {
      if (shown) el.querySelector<HTMLElement>("[autofocus], input, select, textarea, button")?.focus();
    });
  }

  function hide() {
    offAction?.();
    offAction = null;
    shown = false;
    if (el.open) el.close();
    opener?.focus();
    opener = null;
    onclose?.();
  }

  $effect(() => {
    if (open && !shown) show();
    else if (!open && shown) hide();
  });
  $effect(() => () => {
    offAction?.();
  });

  // Esc closes natively too; keep `open` in step.
  function closed() {
    if (open && !locked) open = false;
  }
</script>

<dialog bind:this={el} class:wide aria-label={title} onclose={closed} oncancel={(e) => { e.preventDefault(); if (!locked) open = false; }}>
  {#if shown}
    <h2>{title}</h2>
    {@render children()}
  {/if}
</dialog>

<style>
  dialog {
    width: min(520px, calc(100vw - 32px));
    max-height: calc(100vh - 64px);
    padding: 28px 32px;
    border: 1px solid var(--rule2);
    border-radius: 3px;
    background: var(--bg);
    color: var(--ink);
  }
  dialog.wide {
    width: min(960px, calc(100vw - 32px));
  }
  dialog:focus-visible {
    outline: none;
  }
  dialog::backdrop {
    background: color-mix(in srgb, var(--bg) 72%, transparent);
  }
  dialog[open],
  dialog[open]::backdrop {
    animation: fade 180ms cubic-bezier(0.23, 1, 0.32, 1);
  }
  @keyframes fade {
    from {
      opacity: 0;
    }
  }
  h2 {
    margin: 0 0 16px;
    font: 600 24px/1.1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
  }
</style>
