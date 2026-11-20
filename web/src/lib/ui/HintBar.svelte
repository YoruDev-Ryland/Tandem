<script lang="ts">
  import { dispatch, type Action } from "../input/actions";
  import { gamepad } from "../input/gamepad.svelte";
  import Glyph from "./Glyph.svelte";

  let { status, hints }: { status?: string; hints: { glyph: Action; label: string }[] } = $props();

  // Keyboard equivalents shown when no gamepad is connected. X and Y have none: the page's buttons carry them.
  const KEYS: Partial<Record<Action, string>> = { a: "Enter", b: "Esc", l1: "[", r1: "]", l2: "{", r2: "}" };

  const shown = $derived(gamepad.connected ? hints : hints.filter((h) => KEYS[h.glyph]));
</script>

<footer>
  {#if status}<span class="status">{status}</span>{/if}
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
  footer {
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
      padding: 0 16px;
    }
    .hints {
      display: none;
    }
  }
</style>
