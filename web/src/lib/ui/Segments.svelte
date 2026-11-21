<script lang="ts">
  /**
   * The segment row from Setup: one short segment per item, filled for each one done. Past `max` items the row keeps
   * `max` segments and fills them in proportion, so a long list doesn't run off the row.
   */
  let { done, total, max = 24, small = false }: { done: number; total: number; max?: number; small?: boolean } = $props();
  const n = $derived(Math.min(total, max));
  const filled = $derived(total <= max ? done : Math.round((done / Math.max(1, total)) * max));
</script>

<span class="row" class:small aria-hidden="true">
  {#each { length: n }, i (i)}
    <span class:on={i < filled}></span>
  {/each}
</span>

<style>
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 3px;
  }
  .row span {
    flex: none;
    width: 22px;
    height: 5px;
    background: var(--rule2);
  }
  .small span {
    width: 8px;
    height: 4px;
  }
  .row span.on {
    background: currentColor;
  }
</style>
