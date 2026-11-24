<script lang="ts">
  import { tipPlace, type Tip } from "../../../lib/storage/page";

  /** A tip beside the point (x, y) of a box boxW by boxH, flipped so it stays inside. */
  let { id, tip, x, y, boxW, boxH }: { id: string; tip: Tip; x: number; y: number; boxW: number; boxH: number } = $props();
  const at = $derived(tipPlace(x, y, boxW, boxH));
</script>

<div {id} class="tip" class:fx={at.flipX} class:fy={at.flipY} role="tooltip" style:left="{at.left}px" style:top="{at.top}px">
  <b>{tip.title}</b>
  {#each tip.lines as l, i (i)}<small>{l}</small>{/each}
</div>

<style>
  .tip {
    position: absolute;
    z-index: 2;
    display: flex;
    flex-direction: column;
    gap: 1px;
    max-width: 340px;
    padding: 8px 10px;
    border-radius: 3px;
    border: 1px solid var(--rule2);
    background: var(--raised);
    color: var(--ink);
    font: 400 13px/1.35 "Barlow", system-ui, sans-serif;
    pointer-events: none;
  }
  .fx {
    transform: translateX(-100%);
  }
  .fy {
    transform: translateY(-100%);
  }
  .fx.fy {
    transform: translate(-100%, -100%);
  }
  b {
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  small {
    color: var(--ink2);
    font-size: 13px;
    white-space: nowrap;
  }
</style>
