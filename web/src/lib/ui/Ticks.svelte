<script lang="ts">
  let { done, total }: { done: number; total: number } = $props();
</script>

<div class="ticks">
  <p><span class="count">{done}</span> of {total} done</p>
  <div class="row" aria-hidden="true">
    {#each { length: total }, i (i)}
      <span class:on={i < done}></span>
    {/each}
  </div>
</div>

<style>
  .ticks {
    display: inline-block;
  }
  p {
    margin: 0;
    color: var(--ink2);
    font-size: 15px;
  }
  .count {
    font: 600 28px/1 "Barlow Semi Condensed", sans-serif;
    color: var(--ink);
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 3px;
    margin-top: 10px;
  }
  .row span {
    flex: none;
    width: 22px;
    height: 5px;
    background: var(--rule2);
  }
  .row span.on {
    background: var(--ink);
  }
</style>
