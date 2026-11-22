<script lang="ts">
  import type { GameRow } from "../api/types";
  import type { CardCopy, RailRow } from "../shelf";
  import { spineColour, spineInk } from "../spines";
  import { bareKey, systemLabel } from "../systems";
  import ShelfCard from "./ShelfCard.svelte";
  import StatusRail from "./StatusRail.svelte";

  /**
   * One emulator's shelf: a spine per game, coloured by its system, with the game under the cursor pulled out as a
   * card; the shelf edge; the status rail under it. The page owns the cursor; this draws it and keeps it in view.
   */
  interface Props {
    games: GameRow[];
    index: number;
    rows: RailRow[];
    copy: CardCopy;
    art: string | null;
    onSelect: (i: number) => void;
    onOpen: (i: number) => void;
  }
  let { games, index, rows, copy, art, onSelect, onOpen }: Props = $props();

  let scroller: HTMLDivElement;

  const sys = (g: GameRow) => systemLabel(g.emulatorId, g.gameKey, g.core);
  const keyOf = (g: GameRow) => g.emulatorId + "/" + g.gameKey;

  function px(name: string): number {
    return parseFloat(getComputedStyle(scroller).getPropertyValue(name)) || 0;
  }

  // Keep the card in view with one spine of context each side, by arithmetic rather than layout (the card may still
  // be growing). The jump is instant: the shelf never animates its scroll.
  $effect(() => {
    const i = index;
    void games.length;
    if (!scroller || scroller.clientWidth === 0) return;
    const pitch = px("--spine-w") + px("--spine-gap");
    const left = i * pitch;
    const right = left + px("--card-w");
    const view = scroller.clientWidth;
    let x = scroller.scrollLeft;
    if (right + pitch - x > view) x = right + pitch - view;
    if (left - pitch < x) x = left - pitch;
    scroller.scrollLeft = Math.max(0, x);
  });

  // A mouse wheel scrolls the shelf sideways; a trackpad's own sideways swipe already does.
  $effect(() => {
    const el = scroller;
    const wheel = (e: WheelEvent) => {
      if (Math.abs(e.deltaY) <= Math.abs(e.deltaX) || el.scrollWidth <= el.clientWidth) return;
      el.scrollLeft += e.deltaY;
      e.preventDefault();
    };
    el.addEventListener("wheel", wheel, { passive: false });
    return () => el.removeEventListener("wheel", wheel);
  });

  function click(e: MouseEvent, i: number) {
    // detail 0 is a keyboard activation (Enter on a focused spine): it opens the cursor game.
    if (e.detail === 0 || i === index) onOpen(index);
    else onSelect(i);
  }
</script>

<div class="shelf" style:--rows={rows.length}>
  <ol class="labels" aria-hidden="true">
    {#each rows as r (r.deviceId)}<li title={r.name}>{r.label}</li>{/each}
  </ol>
  <div class="scroller" bind:this={scroller}>
    <div class="track">
      <ul class="games" aria-label="Games">
        {#each games as g, i (keyOf(g))}
          {@const s = sys(g)}
          {@const on = i === index}
          <li class="item" class:on style:--sp={spineColour(s)} style:--sp-ink={spineInk(s)}>
            <button
              type="button"
              class="hit"
              aria-current={on ? "true" : undefined}
              aria-label={on ? undefined : `${g.displayName ?? bareKey(g.emulatorId, g.gameKey)}${s ? `, ${s}` : ""}`}
              onclick={(e) => click(e, i)}
            >
              {#if on}
                <ShelfCard game={g} {copy} system={s} {art} />
              {:else}
                <span class="spine">
                  <i class="sy">{s ?? "—"}</i>
                  {#if g.displayName}
                    <span class="title">{g.displayName}</span>
                  {:else}
                    <span class="title key">{bareKey(g.emulatorId, g.gameKey)}</span>
                  {/if}
                </span>
              {/if}
            </button>
          </li>
        {/each}
      </ul>
      <div class="edge"></div>
      <StatusRail {games} {rows} {index} />
    </div>
  </div>
</div>

<style>
  .shelf {
    --spine-w: 34px;
    --spine-gap: 5px;
    --card-w: 262px;
    --shelf-h: clamp(260px, calc(100vh - 480px), 400px);
    --rail-gap: 2px;
    --mark-h: 20px;
    position: relative;
    display: grid;
    grid-template-columns: var(--gutter) minmax(0, 1fr);
  }
  @media (max-width: 600px) {
    .shelf {
      --card-w: 224px;
      --shelf-h: 470px;
      grid-template-columns: 46px minmax(0, 1fr);
    }
  }

  /* The device labels sit in the gutter, level with their rail rows, and stay put while the shelf scrolls. */
  .labels {
    grid-column: 1;
    align-self: start;
    margin: calc(var(--shelf-h) + 6px + 12px) 0 0;
    padding: 0 6px 0 0;
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: var(--rail-gap);
    font: 400 10px/var(--mark-h) "JetBrains Mono", ui-monospace, monospace;
    letter-spacing: 0.02em;
    color: var(--ink3);
    text-align: right;
    white-space: nowrap;
  }

  .scroller {
    grid-column: 2;
    overflow-x: auto;
    overflow-y: hidden;
    padding: 0 var(--gutter) 14px 0;
    scrollbar-width: thin;
  }
  .track {
    display: inline-flex;
    flex-direction: column;
    min-width: 100%;
  }
  .games {
    display: flex;
    align-items: flex-end;
    gap: var(--spine-gap);
    height: var(--shelf-h);
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .item {
    flex: none;
    width: var(--spine-w);
    height: calc(var(--shelf-h) - 30px);
  }
  .item.on {
    width: var(--card-w);
    height: var(--shelf-h);
  }
  @media (prefers-reduced-motion: no-preference) {
    .item {
      transition: width 120ms cubic-bezier(0.23, 1, 0.32, 1);
    }
  }
  .hit {
    display: block;
    width: 100%;
    height: 100%;
    padding: 0;
    border: 0;
    border-radius: 3px 3px 0 0;
    background: var(--sp);
    color: var(--sp-ink);
    font: inherit;
    cursor: pointer;
    overflow: hidden;
  }
  .on .hit {
    background: var(--raised);
    color: var(--ink);
    box-shadow: 0 0 0 1px var(--rule2);
  }
  .hit:focus-visible {
    outline-offset: 2px;
  }

  .spine {
    position: relative;
    display: flex;
    align-items: flex-end;
    justify-content: center;
    width: 100%;
    height: 100%;
    padding: 26px 0 10px;
  }
  .sy {
    position: absolute;
    top: 7px;
    left: 0;
    right: 0;
    text-align: center;
    font: 700 9px/1 "Barlow Semi Condensed", sans-serif;
    font-style: normal;
    letter-spacing: 0.06em;
    color: color-mix(in srgb, var(--sp-ink) 72%, var(--sp));
    overflow: hidden;
    white-space: nowrap;
  }
  /* The name reads bottom to top, cut with an ellipsis inside the spine. */
  .title {
    writing-mode: vertical-rl;
    transform: rotate(180deg);
    max-height: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font: 700 14px/1 "Barlow Semi Condensed", sans-serif;
  }
  .title.key {
    font: 400 12px/1 "JetBrains Mono", ui-monospace, monospace;
  }

  .edge {
    height: 6px;
    background: var(--ink);
  }
</style>
