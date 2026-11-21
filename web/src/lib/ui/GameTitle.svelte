<script lang="ts">
  import { bareKey } from "../systems";

  /**
   * A game's name. A game the server has no name for shows its id quietly in mono, marked "unknown title", so a raw
   * id never reads as a title.
   */
  let { name, emulatorId, gameKey, fault = false }: { name: string | null; emulatorId: string; gameKey: string; fault?: boolean } = $props();
  const id = $derived(bareKey(emulatorId, gameKey));
</script>

{#if name}
  <span class="name ellipsis" class:fault title={name}>{name}</span>
{:else}
  <span class="unknown ellipsis" class:fault title={`${id} · unknown title`}><span class="id">{id}</span><span class="what">unknown title</span></span>
{/if}

<style>
  .ellipsis {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .id {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    color: var(--ink3);
  }
  .what {
    margin-left: 10px;
    font-size: 14px;
    color: var(--ink3);
  }
  .fault .id,
  .fault .what {
    color: var(--fault);
  }
  :global(.row.focused) .id,
  :global(.row.focused) .what {
    color: color-mix(in srgb, var(--focus-ink) 62%, var(--focus-bg));
  }
</style>
