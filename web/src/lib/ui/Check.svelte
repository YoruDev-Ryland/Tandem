<script lang="ts">
  let { done }: { done: boolean } = $props();
</script>

{#if done}
  <svg class="check" role="img" aria-label="Done" viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="2">
    <path d="M3.5 9.5l3.5 3.5 7.5-8" />
  </svg>
{:else}
  <span class="todo" role="img" aria-label="Not done"></span>
{/if}

<style>
  .check {
    display: block;
    width: 18px;
    height: 18px;
    flex: none;
  }
  .todo {
    display: block;
    width: 16px;
    height: 16px;
    margin: 1px;
    border: 1.5px solid var(--ink3);
    border-radius: 50%;
    flex: none;
  }
</style>
