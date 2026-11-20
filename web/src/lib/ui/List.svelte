<script lang="ts" generics="T">
  import { tick, untrack, type Snippet } from "svelte";
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
    /** Rows that read as unavailable (aria-disabled). */
    disabled?: (row: T) => boolean;
    /** Narrowest width the table keeps on a phone before it scrolls sideways. */
    minWidth?: string;
    /** Phone (640px and narrower): a different column template, with the cells marked `sm-hide` left out. */
    phoneColumns?: string;
    /** Header cells (by index) that go with the hidden columns. */
    phoneHide?: number[];
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
    disabled = () => false,
    minWidth = "1040px",
    phoneColumns,
    phoneHide = [],
  }: Props = $props();

  const c = cursor(() => rows.length);
  c.set(index);
  $effect(() => {
    index = c.index;
  });

  let list: HTMLDivElement;

  // The cursor follows its row by key: rows that arrive or leave above it (a live reload) must not retarget it.
  let focusKey: unknown;
  let hasKey = false;
  function remember() {
    const r = rows[c.index];
    if (r === undefined) return;
    focusKey = key(r, c.index);
    hasKey = true;
  }
  function put(i: number) {
    c.set(i);
    remember();
  }
  $effect(() => {
    const rs = rows;
    untrack(() => {
      if (hasKey) {
        const i = rs.findIndex((r, j) => key(r, j) === focusKey);
        if (i >= 0) c.set(i);
      }
      remember();
    });
  });

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
        remember();
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

  // Only real pointer movement moves the cursor: browsers also fire pointer events when content scrolls under a
  // resting pointer (as scrollIntoView does on every up/down), which must not pull the cursor away from the keys.
  let lastX = NaN;
  let lastY = NaN;
  function pointerMove(e: PointerEvent, i: number) {
    if (e.clientX === lastX && e.clientY === lastY) return;
    lastX = e.clientX;
    lastY = e.clientY;
    if (i === c.index) return;
    put(i);
    // Keep DOM focus on the cursor row, so Enter (a native click) acts on the row that is shown.
    if (list.contains(document.activeElement)) void focusRow();
  }

  function click(e: MouseEvent, i: number) {
    // detail 0 is a keyboard activation (Enter on the focused row): it opens the cursor row.
    if (e.detail === 0 || i === c.index) open(c.index);
    else put(i);
  }
</script>

<div class="list" style:--list-min={minWidth} style:--cols={columns} style:--cols-phone={phoneColumns ?? columns} bind:this={list}>
  {#if header}
    <div class="head {size}" aria-hidden="true">
      {#each header as h, i (i)}<span class:end={headerEnd.includes(i)} class:sm-hide={phoneHide.includes(i)}>{h}</span>{/each}
    </div>
  {/if}
  {#each rows as r, i (key(r, i))}
    <Row
      focused={active && i === c.index}
      disabled={disabled(r)}
      columns="var(--cols)"
      {size}
      onpointermove={(e) => pointerMove(e, i)}
      onfocus={() => put(i)}
      onclick={(e) => click(e, i)}
    >
      {@render row(r, active && i === c.index)}
    </Row>
  {/each}
</div>

<style>
  .head {
    grid-template-columns: var(--cols);
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
  /* On a phone the table keeps its columns and scrolls sideways inside its own box. */
  @media (max-width: 640px) {
    .list :global(.sm-hide) {
      display: none;
    }
    .head,
    .list :global(.row) {
      grid-template-columns: var(--cols-phone) !important;
    }
    .list {
      overflow-x: auto;
    }
    .list > :global(*) {
      min-width: var(--list-min);
    }
  }
</style>
