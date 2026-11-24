<script lang="ts">
  import type { GameRow } from "../api/types";
  import { visibleRange, wheelStep, type CardCopy, type RailRow } from "../shelf";
  import { spineBand, spineColour, spineInk, spineKind, spineRatio } from "../spines";
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
    /** A game's real spine and cover, as far as they have loaded. */
    images?: (g: GameRow) => { spine: string | null; cover: string | null };
    onSelect: (i: number) => void;
    onOpen: (i: number) => void;
    /** Told which spines are in view (first, last) whenever that changes. */
    onView?: (first: number, last: number) => void;
  }
  let { games, index, rows, copy, art, images = () => ({ spine: null, cover: null }), onSelect, onOpen, onView }: Props = $props();

  /** A spine's width: its case's ratio of the spine's height (the shelf less 30 px). */
  const widthOf = (g: GameRow) => `calc((var(--shelf-h) - 30px) * ${spineRatio(sys(g))})`;
  const widths = $derived(games.map(widthOf));
  let gamesEl: HTMLUListElement;

  let scroller: HTMLDivElement;

  const sys = (g: GameRow) => systemLabel(g.emulatorId, g.gameKey, g.core, g.system);
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
    // Spine widths follow their cases; work them out from the spine height (the shelf's, less 30 px).
    const gap = px("--spine-gap");
    const w = (j: number) => spineWidthPx(j);
    let left = 0;
    for (let j = 0; j < i; j++) left += w(j) + gap;
    const right = left + px("--card-w");
    const before = i > 0 ? w(i - 1) + gap : 0;
    const after = i < games.length - 1 ? w(i + 1) + gap : 0;
    const view = scroller.clientWidth;
    let x = scroller.scrollLeft;
    if (right + after - x > view) x = right + after - view;
    if (left - before < x) x = left - before;
    scroller.scrollLeft = Math.max(0, x);
    reportView();
  });

  /** A spine's width in px as laid out: its case ratio of the spine height, at least 12 px (the CSS floor). */
  function spineWidthPx(j: number): number {
    const spineH = Math.max(0, (gamesEl?.clientHeight ?? 0) - 30);
    return Math.max(12, spineH * spineRatio(sys(games[j]!)));
  }

  function reportView() {
    if (!onView || !scroller || scroller.clientWidth === 0) return;
    const [first, last] = visibleRange(games.map((_, j) => spineWidthPx(j)), px("--spine-gap"), px("--card-w"), index, scroller.scrollLeft, scroller.clientWidth);
    onView(first, last);
  }

  // Scrolling by hand (wheel, trackpad, scrollbar) changes what is in view too.
  $effect(() => {
    const el = scroller;
    let raf = 0;
    const scrolled = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(reportView);
    };
    el.addEventListener("scroll", scrolled, { passive: true });
    return () => {
      el.removeEventListener("scroll", scrolled);
      cancelAnimationFrame(raf);
    };
  });

  // A mouse wheel moves the cursor a game at a time, like the arrow keys, and the shelf follows it. A trackpad's
  // sideways swipe still scrolls the shelf freely.
  let wheelRest = 0;
  $effect(() => {
    const el = scroller;
    const wheel = (e: WheelEvent) => {
      if (Math.abs(e.deltaY) <= Math.abs(e.deltaX)) return;
      e.preventDefault();
      const r = wheelStep(wheelRest, e.deltaY, e.deltaMode);
      wheelRest = r.rest;
      // No wrap: at either end the cursor stays.
      const to = index + r.step;
      if (r.step !== 0 && to >= 0 && to < games.length) onSelect(to);
    };
    el.addEventListener("wheel", wheel, { passive: false });
    return () => el.removeEventListener("wheel", wheel);
  });

  function click(e: MouseEvent, i: number) {
    // detail 0 is a keyboard activation (Enter on a Tab-focused spine): it opens that spine's game, which may not be
    // the cursor's. A click selects a spine, and opens the card.
    if (e.detail === 0 || i === index) onOpen(i);
    else onSelect(i);
  }
</script>

<div class="shelf" style:--rows={rows.length}>
  <ol class="labels" aria-hidden="true">
    {#each rows as r (r.deviceId)}<li title={r.name}>{r.label}</li>{/each}
  </ol>
  <div class="scroller" bind:this={scroller}>
    <div class="track">
      <ul class="games" aria-label="Games" bind:this={gamesEl}>
        {#each games as g, i (keyOf(g))}
          {@const s = sys(g)}
          {@const on = i === index}
          {@const im = images(g)}
          {@const kind = spineKind(s, !!g.spine, !!im.spine, !!im.cover)}
          {@const band = kind === "real" ? null : spineBand(s)}
          <li class="item" class:on style:--sp={spineColour(s)} style:--sp-ink={spineInk(s)} style:--spine-w={widths[i]}>
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
                {#if kind === "real"}
                  <span class="spine real"><img src={im.spine} alt="" /></span>
                {:else}
                  <span class="spine {kind}" style:--title-top={band ? (band.height < 0.25 ? "23%" : "30%") : "6%"}>
                    {#if kind === "built"}
                      <i class="art" style:background-image={`url("${im.cover}")`}></i><i class="dim"></i>
                    {/if}
                    {#if band}
                      <b class="band" class:short={band.height < 0.25} style:background={band.colour} style:color={band.ink} style:height={`${band.height * 100}%`}><em>{band.name}</em></b>
                    {/if}
                    {#if g.displayName}
                      <span class="title">{g.displayName}</span>
                    {:else}
                      <span class="title key">{bareKey(g.emulatorId, g.gameKey)}</span>
                    {/if}
                  </span>
                {/if}
              {/if}
            </button>
          </li>
        {/each}
      </ul>
      <div class="edge"></div>
      <StatusRail {games} {rows} {index} {widths} />
    </div>
  </div>
</div>

<style>
  .shelf {
    --spine-w: 34px;
    --spine-gap: 5px;
    --card-w: 262px;
    /* Low floor, so on a short window the rail still clears the sticky hint bar. */
    --shelf-h: clamp(200px, calc(100vh - 480px), 400px);
    --label-w: 52px;
    --rail-gap: 2px;
    --mark-h: 20px;
    position: relative;
    display: grid;
    /* The labels' column starts at the page's left edge (the h1's), inside the content, not in the page margin. */
    grid-template-columns: var(--label-w) minmax(0, 1fr);
    padding-left: var(--gutter);
  }
  @media (max-width: 600px) {
    .shelf {
      --card-w: 224px;
      --shelf-h: 470px;
      --label-w: 50px;
    }
  }

  /* The device labels sit in their own column, level with their rail rows, and stay put while the shelf scrolls. */
  .labels {
    grid-column: 1;
    align-self: start;
    margin: calc(var(--shelf-h) + 6px + 12px) 0 0;
    padding: 0 8px 0 0;
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: var(--rail-gap);
    font: 400 10px/var(--mark-h) "JetBrains Mono", ui-monospace, monospace;
    letter-spacing: 0.02em;
    color: var(--ink3);
    text-align: left;
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
    min-width: 12px;
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

  /* A spine: the real case spine as scanned; Switch's plain red; built from the middle of the cover under a flat dark
     layer; or the system's colour. Built, plain and fallback spines carry the console band and a title reading top to
     bottom. Sizes follow the spine's height (container units). */
  .spine {
    position: relative;
    display: block;
    width: 100%;
    height: 100%;
    container-type: size;
    overflow: hidden;
    color: var(--sp-ink);
  }
  .spine.real img {
    display: block;
    width: 100%;
    height: 100%;
  }
  .spine.plain {
    background: #e60012;
    color: #fff;
  }
  .spine.built {
    color: #fff;
  }
  .art,
  .dim {
    position: absolute;
    inset: 0;
  }
  .art {
    background-size: auto 100%;
    background-position: 50% 0;
    background-repeat: no-repeat;
  }
  .dim {
    background: rgba(0, 0, 0, 0.42);
  }
  .band {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }
  .band em {
    writing-mode: vertical-rl;
    font: 700 2.2cqh/1 "Barlow Semi Condensed", sans-serif;
    font-style: normal;
    letter-spacing: 0.06em;
    white-space: nowrap;
  }
  .band.short em {
    font-size: 1.8cqh;
  }
  /* The name reads top to bottom, as on a printed case, cut with an ellipsis inside the spine. */
  .title {
    position: absolute;
    top: var(--title-top);
    bottom: 3%;
    left: 0;
    right: 0;
    margin: 0 auto;
    width: fit-content;
    writing-mode: vertical-rl;
    max-height: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font: 700 5cqh/1 "Barlow Semi Condensed", sans-serif;
  }
  .title.key {
    font: 400 4.2cqh/1 "JetBrains Mono", ui-monospace, monospace;
  }

  .edge {
    height: 6px;
    background: var(--shelf-edge);
  }
</style>
