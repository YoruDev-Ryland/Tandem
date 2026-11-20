<script lang="ts">
  /** [label, value, mono?] */
  let { items }: { items: [string, string, boolean?][] } = $props();
</script>

<dl>
  {#each items as [label, value, mono] (label)}
    <dt>{label}</dt>
    <dd class:mono>{value}</dd>
  {/each}
</dl>

<style>
  dl {
    display: grid;
    grid-template-columns: 150px minmax(0, 1fr);
    row-gap: 10px;
    column-gap: 16px;
    margin: 0;
    font-size: 15px;
    line-height: 1.35;
  }
  dt {
    color: var(--ink3);
  }
  dd {
    margin: 0;
    color: var(--ink);
    overflow-wrap: anywhere;
  }
</style>
