<script lang="ts">
  import { dispatch, type Action } from "../input/actions";
  import { gamepad } from "../input/gamepad.svelte";
  import Glyph from "./Glyph.svelte";
  import { flash } from "./flash.svelte";

  let { status, hints }: { status?: string; hints: { glyph: Action; label: string }[] } = $props();

  // Keyboard equivalents shown when no gamepad is connected.
  const KEYS: Partial<Record<Action, string>> = { a: "Enter", b: "Esc", x: "X", y: "Y", l1: "[", r1: "]", l2: "{", r2: "}" };

  const shown = $derived(gamepad.connected ? hints : hints.filter((h) => KEYS[h.glyph]));
</script>

<footer>
  {#if flash.text}
    {#key flash.seq}
      <span class="status done" role="status">
        <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true"><path d="M2 7.5 5.5 11 12 3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" pathLength="1" /></svg>
        {flash.text}
      </span>
    {/key}
  {:else if status}<span class="status">{status}</span>{/if}
  <div class="hints">
    {#each shown as h (h.glyph)}
      <button type="button" onclick={() => dispatch(h.glyph)}>
        {#if gamepad.connected}<Glyph action={h.glyph} />{:else}<kbd>{KEYS[h.glyph]}</kbd>{/if}
        {h.label}
      </button>
    {/each}
  </div>
</footer>

<style>
  /* Sticky: on a long list the hints and the status line stay in view. */
  footer {
    position: sticky;
    bottom: 0;
    z-index: 1;
    background: var(--bg);
    flex: none;
    display: flex;
    align-items: center;
    gap: 26px;
    height: 56px;
    padding: 0 var(--gutter);
    border-top: 1px solid var(--rule);
    font: 500 14px "Barlow", system-ui, sans-serif;
    color: var(--ink2);
  }
  .status {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--ink3);
  }
  .done {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    color: var(--ink);
  }
  .done svg {
    flex: none;
  }
  .done path {
    stroke-dasharray: 1;
    stroke-dashoffset: 0;
    animation: stroke 200ms cubic-bezier(0.23, 1, 0.32, 1) both;
  }
  @keyframes stroke {
    from {
      stroke-dashoffset: 1;
    }
  }
  .hints {
    display: flex;
    align-items: center;
    gap: 26px;
    margin-left: auto;
  }
  button {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0;
    border: 0;
    background: none;
    color: inherit;
    font: inherit;
    white-space: nowrap;
    cursor: pointer;
  }
  button:hover {
    color: var(--ink);
  }
  kbd {
    display: inline-flex;
    align-items: center;
    height: 20px;
    padding: 0 6px;
    border: 1.5px solid var(--rule2);
    border-radius: 3px;
    color: var(--ink2);
    font: 600 12px/1 "Barlow", system-ui, sans-serif;
  }
  @media (max-width: 640px) {
    footer {
      position: static;
      padding: 0 16px;
    }
    .hints {
      display: none;
    }
  }
</style>
