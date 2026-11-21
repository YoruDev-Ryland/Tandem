<script lang="ts">
  import { systemLabel } from "../systems";

  /** The system mark: a fixed-width label, like the end label on a cartridge shelf. */
  let { emulatorId, gameKey, core = null }: { emulatorId: string; gameKey: string; core?: string | null } = $props();
  const label = $derived(systemLabel(emulatorId, gameKey, core));
</script>

{#if label}
  <span class="tag">{label}</span>
{:else}
  <span class="tag none" title="System not known" aria-label="System not known">—</span>
{/if}

<style>
  .tag {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    box-sizing: border-box;
    width: 48px;
    height: 20px;
    border: 1.5px solid var(--rule2);
    border-radius: 3px;
    color: var(--ink2);
    font: 600 11px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    font-variant-numeric: tabular-nums;
    overflow: hidden;
    white-space: nowrap;
  }
  .none {
    border-style: dashed;
    color: var(--ink3);
  }
  /* On the inverted cursor row the outline and ink follow the row. */
  :global(.row.focused) .tag {
    border-color: color-mix(in srgb, var(--focus-ink) 40%, var(--focus-bg));
    color: var(--focus-ink);
  }
</style>
