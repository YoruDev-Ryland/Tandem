<script lang="ts">
  import type { Presence } from "../deviceStates";

  /** One cell of the device presence matrix: a drawn 12 px mark, single 1.5 px stroke. */
  let { p, label }: { p: Presence; label: string } = $props();
</script>

<span class="cell {p}" role="img" aria-label={label} title={label}>
  {#if p === "absent"}
    <span class="dash" aria-hidden="true">—</span>
  {:else if p === "conflict"}
    <span class="two" aria-hidden="true">×2</span>
  {:else}
    <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
      {#if p === "newest"}
        <rect x="0.75" y="0.75" width="10.5" height="10.5" rx="1" fill="currentColor" stroke="currentColor" stroke-width="1.5" />
      {:else if p === "coming"}
        <rect x="0.75" y="0.75" width="10.5" height="10.5" rx="1" fill="none" stroke="currentColor" stroke-width="1.5" />
        <rect x="0.75" y="6" width="10.5" height="5.25" fill="currentColor" />
      {:else if p === "empty"}
        <rect x="0.75" y="0.75" width="10.5" height="10.5" rx="1" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="2.5 2" />
      {:else}
        <rect x="0.75" y="0.75" width="10.5" height="10.5" rx="1" fill="none" stroke="currentColor" stroke-width="1.5" />
      {/if}
    </svg>
    {#if p === "waiting"}<span class="word" aria-hidden="true">wait</span>{/if}
  {/if}
</span>

<style>
  .cell {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 20px;
    color: var(--ink);
    overflow: visible;
  }
  svg {
    flex: none;
    display: block;
  }
  .older {
    color: var(--ink2);
  }
  .empty,
  .absent {
    color: var(--ink3);
  }
  .waiting {
    color: var(--wait);
  }
  .conflict {
    color: var(--fault);
  }
  .dash {
    font-size: 14px;
  }
  .word,
  .two {
    font: 600 12px/1 "Barlow", system-ui, sans-serif;
    font-variant-numeric: tabular-nums;
  }
  /* On a phone the columns are 36 px: the amber outline says it alone. */
  @media (max-width: 640px) {
    .word {
      display: none;
    }
  }
  :global(.row.focused) .cell {
    color: var(--focus-ink);
  }
  :global(.row.focused) .empty,
  :global(.row.focused) .absent,
  :global(.row.focused) .older {
    color: color-mix(in srgb, var(--focus-ink) 62%, var(--focus-bg));
  }
</style>
