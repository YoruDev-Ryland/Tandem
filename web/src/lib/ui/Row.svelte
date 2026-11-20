<script lang="ts">
  import type { Snippet } from "svelte";

  interface Props {
    focused: boolean;
    /** CSS grid-template-columns for the cells. */
    columns: string;
    /** "list" rows are 46 px (page lists); "detail" rows are 44 px. */
    size?: "list" | "detail";
    onclick?: () => void;
    onmouseenter?: () => void;
    onfocus?: () => void;
    children: Snippet;
  }
  let { focused, columns, size = "list", onclick, onmouseenter, onfocus, children }: Props = $props();

  let el: HTMLButtonElement;
  $effect(() => {
    if (focused) el.scrollIntoView?.({ block: "nearest" });
  });
</script>

<!--
  Cells truncate with an ellipsis. They take these classes: .name (17 px/600), .strong (600), .ink2, .ink3, .wait, .fault, .end.
  On the cursor row the secondary inks mix toward the fill and status inks take --focus-ink: the words still carry them.
-->
<button
  bind:this={el}
  type="button"
  class="row {size}"
  class:focused
  style:grid-template-columns={columns}
  aria-current={focused ? "true" : undefined}
  tabindex={focused ? 0 : -1}
  {onclick}
  {onmouseenter}
  {onfocus}
>
  {@render children()}
</button>

<style>
  .row {
    display: grid;
    align-items: center;
    width: 100%;
    margin: 0;
    border: 0;
    border-top: 1px solid var(--rule);
    border-radius: 0;
    background: transparent;
    color: var(--ink);
    font: 400 15px/1.35 "Barlow", system-ui, sans-serif;
    text-align: left;
    cursor: pointer;
  }
  .list {
    height: 46px;
    padding: 0 16px;
    column-gap: 20px;
  }
  .detail {
    height: 44px;
    padding: 0 14px;
    column-gap: 18px;
  }
  /* Rows are a fixed height: cells never wrap. */
  .row > :global(*) {
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .row.focused {
    background: var(--focus-bg);
    color: var(--focus-ink);
    border-radius: 3px;
    border-top-color: transparent;
  }
  .row.focused + :global(.row) {
    border-top-color: transparent;
  }
  /* The inversion is the focus style for rows. */
  .row:focus-visible {
    outline: none;
  }

  .row :global(.name) {
    font-size: 17px;
    font-weight: 600;
  }
  .row :global(.strong) {
    font-weight: 600;
  }
  .row :global(.end) {
    text-align: right;
  }
  .row :global(.ink2) {
    color: var(--ink2);
  }
  .row :global(.ink3) {
    color: var(--ink3);
  }
  .row :global(.wait) {
    color: var(--wait);
  }
  .row :global(.fault) {
    color: var(--fault);
  }
  .row.focused :global(.ink2),
  .row.focused :global(.ink3) {
    color: color-mix(in srgb, var(--focus-ink) 62%, var(--focus-bg));
  }
  .row.focused :global(.wait),
  .row.focused :global(.fault) {
    color: var(--focus-ink);
  }
  .row.focused :global(.todo) {
    border-color: currentColor;
  }
</style>
