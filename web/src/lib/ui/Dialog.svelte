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
      if (a === "b") {
        if (!locked) open = false;
      } else if (a === "up" || a === "down") {
        step(a === "down" ? 1 : -1);
      } else if (a === "left" || a === "right") {
        sideways(a === "right" ? 1 : -1);
      } else if (a === "a") {
        press();
      }
      return true;
    });
    el.showModal();
    shown = true;
    // The contents render after showModal, so move focus into them once they exist.
    void tick().then(() => {
      if (shown) (el.querySelector<HTMLElement>("[autofocus]") ?? stops()[0])?.focus();
    });
  }

  /** The focused control inside the dialog, if any. */
  function current(): HTMLElement | null {
    const f = document.activeElement;
    return f instanceof HTMLElement && f !== el && el.contains(f) ? f : null;
  }

  /** The dialog's controls in DOM order; a radio group is one stop, its checked radio. */
  function stops(): HTMLElement[] {
    const all = el.querySelectorAll<HTMLElement>("button, input, select, textarea, a[href]");
    return [...all].filter((c) => {
      if ((c as HTMLButtonElement).disabled || c.closest("[hidden]") || (c instanceof HTMLInputElement && c.type === "hidden")) return false;
      if (c instanceof HTMLInputElement && c.type === "radio" && c.name) {
        const group = radios(c);
        const stop = group.find((r) => r.checked) ?? group[0];
        return c === stop;
      }
      return true;
    });
  }

  function radios(r: HTMLInputElement): HTMLInputElement[] {
    return [...el.querySelectorAll<HTMLInputElement>('input[type="radio"]')].filter((o) => o.name === r.name && o.form === r.form && !o.disabled);
  }

  /** Up and down move through the controls, stopping at either end. */
  function step(by: 1 | -1) {
    const list = stops();
    if (list.length === 0) return;
    const f = current();
    const at = f ? list.indexOf(f) : -1;
    const next = at < 0 ? 0 : Math.min(Math.max(at + by, 0), list.length - 1);
    list[next]!.focus();
  }

  function changed(c: HTMLElement) {
    c.dispatchEvent(new Event("input", { bubbles: true }));
    c.dispatchEvent(new Event("change", { bubbles: true }));
  }

  /** Left and right step a select or radio group; on anything else they move like up and down. */
  function sideways(by: 1 | -1) {
    const f = current();
    if (f instanceof HTMLSelectElement) {
      const i = Math.min(Math.max(f.selectedIndex + by, 0), f.options.length - 1);
      if (i !== f.selectedIndex) {
        f.selectedIndex = i;
        changed(f);
      }
    } else if (f instanceof HTMLInputElement && f.type === "radio") {
      const group = radios(f);
      const i = Math.min(Math.max(group.indexOf(f) + by, 0), group.length - 1);
      const r = group[i]!;
      if (r !== f) {
        r.checked = true;
        r.focus();
        changed(r);
      }
    } else {
      step(by);
    }
  }

  /**
   * Gamepad A (keyboard Enter does this natively): a button or checkbox is pressed;
   * a field submits its form, as Enter would, or presses the dialog's primary button.
   */
  function press() {
    const f = current();
    if (!f) return;
    if (f instanceof HTMLButtonElement || f instanceof HTMLAnchorElement || (f instanceof HTMLInputElement && f.type === "checkbox")) {
      f.click();
      return;
    }
    if (f instanceof HTMLTextAreaElement) return;
    const form = (f as HTMLInputElement | HTMLSelectElement).form;
    if (form) {
      const submit = form.querySelector<HTMLButtonElement>('button[type="submit"], input[type="submit"]');
      if (submit?.disabled) return;
      form.requestSubmit(submit ?? undefined);
      return;
    }
    const primary = el.querySelector<HTMLButtonElement>("button.primary");
    if (primary && !primary.disabled) primary.click();
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
