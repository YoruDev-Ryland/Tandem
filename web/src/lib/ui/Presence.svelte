<script lang="ts" module>
  /** The width of one device column: 72 px, narrowed to 36 px on a phone (--presence-col, set in base.css). */
  const COL = "var(--presence-col, 72px)";
  /** The CSS width of the whole matrix for `n` devices, for a grid-template-columns entry. */
  export const presenceWidth = (n: number) => `calc(${Math.max(1, n)} * ${COL} + ${Math.max(0, n - 1) * 4}px)`;
</script>

<script lang="ts">
  import type { GameRow } from "../api/types";
  import { presence, presenceText, PRESENCE_LEGEND, type MatrixColumn } from "../deviceStates";
  import PresenceMark from "./PresenceMark.svelte";

  /** The matrix cells for one row, or with `head`, the device names over them. */
  let { columns, row, head = false }: { columns: MatrixColumn[]; row?: GameRow; head?: boolean } = $props();
</script>

<span class="matrix" class:head style:grid-template-columns={`repeat(${columns.length}, ${COL})`} title={head ? PRESENCE_LEGEND : undefined}>
  {#each columns as c (c.id)}
    {#if head || !row}
      <span class="dev">{c.name}</span>
    {:else}
      {@const p = presence(row, c.id)}
      <PresenceMark {p} label={presenceText(p, c.name, row.emulatorId)} />
    {/if}
  {/each}
</span>

<style>
  .matrix {
    display: grid;
    column-gap: 4px;
    align-items: center;
  }
  .dev {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .head {
    cursor: help;
  }
</style>
