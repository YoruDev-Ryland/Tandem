<script lang="ts" generics="T">
  import { tick, type Snippet } from "svelte";
  import { onAction } from "../input/actions";
  import { cursor } from "../input/cursor.svelte";
  import Row from "./Row.svelte";

  interface Props {
    rows: T[];
    /** CSS grid-template-columns shared by the header and every row. */
    columns: string;
    header?: string[];
    /** Header cells to right-align, by column index, to sit over `.end` cells. */
    headerEnd?: number[];
    onOpen: (row: T) => void;
    /** The cells of one row. */
    row: Snippet<[T, boolean]>;
    size?: "list" | "detail";
    /** The focused row index, for pages that show the focused item's detail. */
    index?: number;
    /** Only the active list on a page takes the cursor and the D-pad. */
    active?: boolean;
    /** Stable identity for each row; defaults to the row's position. */
    key?: (row: T, i: number) => unknown;
  }
  let {
    rows,
    columns,
    header,
    headerEnd = [],
    onOpen,
    row,
    size = "list",
    index = $bindable(0),
    active = true,
    key = (_r: T, i: number) => i,
  }: Props = $props();

  const c = cursor(() => rows.length);
  c.set(index);
  $effect(() => {
    index = c.index;
  });

  let list: HTMLDivElement;

  async function focusRow() {
    await tick();
    list.querySelector<HTMLElement>('[aria-current="true"]')?.focus({ preventScroll: true });
  }

  function open(i: number) {
    const r = rows[i];
    if (r !== undefined) onOpen(r);
  }

  $effect(() =>
    onAction((a) => {
      if (!active || rows.length === 0) return false;
      if (a === "up" || a === "down") {
        c.move(a === "down" ? 1 : -1);
        void focusRow();
        return true;
      }
      if (a === "a") {
        open(c.index);
        return true;
      }
      return false;
    }),
  );

  function click(i: number) {
    if (i === c.index) open(i);
    else c.set(i);
  }
</script>

<div class="list" bind:this={list}>
  {#if header}
    <div class="head {size}" style:grid-template-columns={columns} aria-hidden="true">
      {#each header as h, i (i)}<span class:end={headerEnd.includes(i)}>{h}</span>{/each}
    </div>
  {/if}
  {#each rows as r, i (key(r, i))}
    <Row
      focused={active && i === c.index}
      {columns}
      {size}
      onmouseenter={() => c.set(i)}
      onfocus={() => c.set(i)}
      onclick={() => click(i)}
    >
      {@render row(r, active && i === c.index)}
    </Row>
  {/each}
</div>

<style>
  .head {
    display: grid;
    align-items: end;
    font: 600 12px "Barlow", system-ui, sans-serif;
    text-transform: uppercase;
    letter-spacing: 0.02em;
    color: var(--ink3);
  }
  .head > span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .head > .end {
    text-align: right;
  }
  .head.list {
    padding: 0 16px 8px;
    column-gap: 20px;
  }
  .head.detail {
    height: 28px;
    padding: 0 14px;
    column-gap: 18px;
    align-items: center;
  }
</style>
