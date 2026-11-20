<script lang="ts">
  import type { HTMLInputAttributes } from "svelte/elements";

  interface Props extends Omit<HTMLInputAttributes, "value"> {
    label: string;
    value?: string;
    /** Given options render a native select instead of an input. */
    options?: { value: string; label: string }[];
    /** Label beside the control instead of above it. */
    inline?: boolean;
    mono?: boolean;
  }
  let { label, value = $bindable(""), options, inline = false, mono = false, ...rest }: Props = $props();
</script>

<label class:inline>
  <span class="label">{label}</span>
  {#if options}
    <select bind:value disabled={rest.disabled} class:mono>
      {#each options as o (o.value)}<option value={o.value}>{o.label}</option>{/each}
    </select>
  {:else}
    <input bind:value class:mono {...rest} />
  {/if}
</label>

<style>
  label {
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
  }
  label.inline {
    flex-direction: row;
    align-items: center;
    gap: 10px;
  }
  .label {
    color: var(--ink2);
    font-size: 14px;
  }
  input,
  select {
    height: 40px;
    min-width: 0;
    padding: 0 10px;
    border: 1.5px solid var(--rule2);
    border-radius: 3px;
    background: transparent;
    color: var(--ink);
    font: 500 14px "Barlow", system-ui, sans-serif;
  }
  input.mono,
  select.mono {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
  }
  input:hover,
  select:hover {
    border-color: var(--ink3);
  }
  input:focus-visible,
  select:focus-visible {
    outline: 2px solid var(--ink);
    outline-offset: 3px;
  }
  input::placeholder {
    color: var(--ink3);
  }
  select option {
    background: var(--bg);
    color: var(--ink);
  }
  input:disabled,
  select:disabled {
    color: var(--ink3);
  }
</style>
