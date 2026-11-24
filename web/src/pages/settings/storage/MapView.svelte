<script lang="ts">
  import { tick } from "svelte";
  import { gamePath, exportZipUrl } from "../../../lib/api/paths";
  import type { StorageUsage } from "../../../lib/api/types";
  import { formatSize } from "../../../lib/format";
  import { onAction } from "../../../lib/input/actions";
  import { router } from "../../../lib/router.svelte";
  import { deepSpine, diskNote, gapLayout, labelLines, shade, tileTip, usedBytes } from "../../../lib/storage/page";
  import { gameTiles, nestedGameTiles, topTiles, type Tile } from "../../../lib/storage/tiles";
  import { neighbour, type Rect } from "../../../lib/storage/treemap";
  import StorageTip from "./StorageTip.svelte";

  let { usage: u, isAdmin }: { usage: StorageUsage; isAdmin: boolean } = $props();

  const H = 300;
  const GAP = 3;

  // The map's width; the settings column's widest until it is measured (and in tests, where nothing has a size).
  let box = $state<HTMLDivElement>();
  let width = $state(880);
  $effect(() => {
    const el = box;
    if (!el) return;
    const measure = () => {
      if (el.clientWidth > 0) width = el.clientWidth;
    };
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  });

  const top = $derived(topTiles(u, isAdmin));
  const mapTotal = $derived(top.reduce((s, t) => s + t.value, 0));
  let zoomId = $state<string | null>(null);
  // An emulator that went away (a live reload) zooms the map back out.
  const zoomed = $derived(zoomId ? top.find((t) => t.id === zoomId && t.kind === "emulator") : undefined);
  const tiles = $derived(zoomed ? gameTiles(u, zoomed.id) : top);
  const byId = $derived(new Map(tiles.map((t) => [t.id, t])));
  const rects = $derived(gapLayout(tiles, width, H, GAP));

  interface Inner { tile: Tile; rect: Rect; shade: string; head: boolean }
  // On the whole map each emulator's tile holds its games, the first one carrying the emulator's name.
  const inner = $derived.by(() => {
    const out = new Map<string, Inner[]>();
    if (zoomed) return out;
    for (const r of rects) {
      const t = byId.get(r.id)!;
      if (t.kind !== "emulator") continue;
      const games = nestedGameTiles(u, t.id, mapTotal);
      out.set(t.id, gapLayout(games, r.w, r.h, GAP).map((rect, k) => ({ tile: games.find((g) => g.id === rect.id)!, rect, shade: shade(t.colour!, t.ink!, k), head: k === 0 })));
    }
    return out;
  });
  // Everything a tip can be shown for: the tiles and, on the whole map, the games inside them.
  const tippable = $derived.by(() => {
    const m = new Map<string, Tile>(tiles.map((t) => [t.id, t]));
    for (const list of inner.values()) for (const i of list) m.set(i.tile.id, i.tile);
    return m;
  });

  let cursorId = $state<string | null>(null);
  // The cursor's outline shows once the keys or the pointer are on the map, not on a map nobody is using.
  let lit = $state(false);
  const uid = $props.id();
  const tipId = `${uid}-tip`;
  // The map takes the arrows and A only while someone is on it: focus inside, or keys or the pointer lit it.
  const engaged = () => lit || !!box?.contains(document.activeElement);
  const cursor = $derived(cursorId && byId.has(cursorId) ? cursorId : (rects[0]?.id ?? null));

  // The tip follows the pointer over a tile, or sits on the cursor's tile after a key moves it.
  let tip = $state<{ id: string; x: number; y: number } | null>(null);
  const tipTile = $derived(tip ? tippable.get(tip.id) : undefined);

  function keyTip(id: string) {
    const r = rects.find((x) => x.id === id);
    tip = r ? { id, x: r.x + r.w / 2, y: r.y + r.h / 2 } : null;
  }

  // Focus follows the cursor when it was on the map, including on a tile a zoom just took away.
  async function focusCursor(had = !!box?.contains(document.activeElement)) {
    await tick();
    if (had || box?.contains(document.activeElement)) box?.querySelector<HTMLElement>(".cur")?.focus({ preventScroll: true });
  }

  function zoomTo(id: string | null) {
    const had = !!box?.contains(document.activeElement);
    const from = zoomed?.id;
    zoomId = id;
    tip = null;
    cursorId = id ? null : (from ?? null);
    void focusCursor(had);
  }

  function activate(t: Tile) {
    if (t.kind === "emulator") zoomTo(t.id);
    else if (t.kind === "game") router.go(gamePath(t.emulatorId!, t.gameKey!));
  }

  function click(e: MouseEvent, t: Tile) {
    cursorId = t.id;
    activate(t);
    if (e.detail === 0) void focusCursor();
  }

  function pointerMove(e: PointerEvent) {
    const el = (e.target as HTMLElement).closest<HTMLElement>("[data-tip]");
    if (!el || !box) return;
    const b = box.getBoundingClientRect();
    tip = { id: el.dataset.tip!, x: e.clientX - b.left, y: e.clientY - b.top };
    lit = true;
    const owner = el.closest<HTMLElement>("[data-tile]")?.dataset.tile;
    if (owner && owner !== cursor) cursorId = owner;
  }

  $effect(() =>
    onAction((a) => {
      if (!cursor) return false;
      if (a === "b" && zoomed) {
        zoomTo(null);
        return true;
      }
      if (!engaged()) return false;
      if (a === "up" || a === "down" || a === "left" || a === "right") {
        lit = true;
        const next = neighbour(rects, cursor, a);
        if (next) {
          cursorId = next;
          keyTip(next);
          void focusCursor();
        } else keyTip(cursor);
        return true;
      }
      if (a === "a") {
        const t = byId.get(cursor);
        if (t) activate(t);
        return true;
      }
      return false;
    }),
  );

  // A narrow tile keeps the size and leaves the older part to the tip.
  const sizeLine = (t: Tile, w: number) =>
    formatSize(t.value) + (t.kind === "game" && (t.olderBytes ?? 0) > 0 && w >= 170 ? ` · ${formatSize(t.olderBytes!)} older` : "");
  const NOTES: Partial<Record<Tile["kind"], string>> = { art: "All accounts' covers and spines" };

  // A tile's label lines, most wanted first; a tile shows as many as fit, the size kept at the bottom.
  function labelParts(t: Tile, w: number, head: string | null): { k: "head" | "name" | "sub" | "size"; v: string }[] {
    const size = { k: "size" as const, v: sizeLine(t, w) };
    if (t.kind === "game" || t.kind === "more") {
      const name = { k: "name" as const, v: t.label };
      return head ? [{ k: "head", v: head }, name, size] : [name, size];
    }
    const note = NOTES[t.kind];
    return [{ k: "head", v: t.label }, size, ...(note ? [{ k: "sub" as const, v: note }] : [])];
  }
  const disk = $derived(diskNote(u));
</script>

{#snippet label(t: Tile, w: number, h: number, head: string | null)}
  {@const n = labelLines(w, h)}
  {#if n > 0}
    {@const shown = labelParts(t, w, head).slice(0, n)}
    <span class="lab" aria-hidden="true">
      <span class="lines">
        {#each shown.filter((p) => p.k !== "size") as p (p.k)}<span class={p.k}>{p.v}</span>{/each}
      </span>
      {#each shown.filter((p) => p.k === "size") as p (p.k)}<span class="size">{p.v}</span>{/each}
    </span>
  {/if}
{/snippet}

<div class="hero">
  <b>{formatSize(usedBytes(u, isAdmin))}</b><span class="note">{disk ? `used by Tandem · ${disk}` : "used by Tandem"}</span>
</div>

<div class="trail">
  {#if zoomed}
    <button type="button" class="crumb" onclick={() => zoomTo(null)}>All</button>
    <span class="sep" aria-hidden="true">›</span>
    <span class="here">{zoomed.label}</span>
    <span class="end">
      <span class="mono">{formatSize(zoomed.value)}</span>
      <a class="link" href={exportZipUrl(zoomed.id)} download aria-label="Export {zoomed.label}">Export</a>
    </span>
  {:else}
    <span class="here">All</span>
  {/if}
</div>

<div class="map" class:lit role="group" aria-label="Storage map" style:height="{H}px" bind:this={box} onpointermove={pointerMove} onpointerleave={() => {
    tip = null;
    lit = false;
  }}>
  {#each rects as r, i (r.id)}
    {@const t = byId.get(r.id)!}
    {@const kids = inner.get(r.id)}
    <button
      type="button"
      class="tile {t.kind}"
      class:deep={!kids && deepSpine(t.colour)}
      class:cur={r.id === cursor}
      class:act={t.kind === "emulator" || t.kind === "game"}
      tabindex={r.id === cursor ? 0 : -1}
      style:left="{r.x}px"
      style:top="{r.y}px"
      style:width="{r.w}px"
      style:height="{r.h}px"
      style:background={kids ? "transparent" : t.colour && zoomed ? shade(t.colour, t.ink!, i) : t.colour}
      style:color={t.ink}
      data-tile={t.id}
      data-tip={kids ? undefined : t.id}
      aria-label="{t.label}, {formatSize(t.value)}"
      aria-describedby={tip && tipTile && r.id === cursor ? tipId : undefined}
      onclick={(e) => click(e, t)}
    >
      {#if kids}
        {#each kids as k (k.tile.id)}
          <span
            class="tile inner"
            class:deep={deepSpine(t.colour)}
            data-tip={k.tile.id}
            style:left="{k.rect.x}px"
            style:top="{k.rect.y}px"
            style:width="{k.rect.w}px"
            style:height="{k.rect.h}px"
            style:background={k.shade}
          >
            {@render label(k.tile, k.rect.w, k.rect.h, k.head ? (k.rect.w >= 150 ? `${t.label} · ${formatSize(t.value)}` : t.label) : null)}
          </span>
        {/each}
      {:else}
        {@render label(t, r.w, r.h, null)}
      {/if}
    </button>
  {/each}
  {#if tip && tipTile}
    <StorageTip id={tipId} tip={tileTip(tipTile, u)} x={tip.x} y={tip.y} boxW={width} boxH={H} />
  {/if}
</div>

<p class="note">{zoomed ? "Hover a game for its versions; select it to open it." : "Hover a tile for its game and versions; select an emulator to see its games."}</p>

<style>
  .hero {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 6px 16px;
  }
  .hero b {
    font: 600 46px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.02em;
    color: var(--ink);
  }
  .trail {
    display: flex;
    align-items: baseline;
    gap: 8px;
    min-height: 24px;
    margin-bottom: -8px;
    font: 600 15px/1.2 "Barlow Semi Condensed", sans-serif;
  }
  .crumb {
    padding: 0;
    border: 0;
    background: none;
    color: var(--ink3);
    font: inherit;
    cursor: pointer;
    border-radius: 2px;
  }
  .crumb:hover {
    color: var(--ink2);
  }
  .crumb:focus-visible {
    outline: 2px solid var(--ink);
    outline-offset: 3px;
  }
  .sep {
    color: var(--ink3);
  }
  .here {
    color: var(--ink);
  }
  .end {
    display: flex;
    align-items: baseline;
    gap: 16px;
    margin-left: auto;
  }
  .end .mono {
    color: var(--ink2);
  }
  .link {
    color: var(--ink2);
    font: 400 14px "Barlow", system-ui, sans-serif;
    text-underline-offset: 3px;
  }
  .link:hover {
    color: var(--ink);
  }
  .map {
    position: relative;
    width: 100%;
  }
  .tile {
    position: absolute;
    display: block;
    margin: 0;
    padding: 0;
    border: 0;
    border-radius: 2px;
    overflow: hidden;
    text-align: left;
    font: 400 13px/1.2 "Barlow", system-ui, sans-serif;
    color: var(--ink);
    box-shadow: inset 0 0 0 1px var(--rule2);
    cursor: default;
  }
  .inner {
    color: inherit;
  }
  button.tile.emulator {
    box-shadow: none;
    overflow: visible;
  }
  .tile.act {
    cursor: pointer;
  }
  /* A spine as dark as the page (PSP, GameCube) needs a firmer edge to stand off it. */
  .tile.deep {
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--ink) 28%, transparent);
  }
  .lit .tile.cur {
    outline: 2px solid var(--ink);
    outline-offset: 1px;
    z-index: 1;
  }
  .tile:focus-visible {
    outline: 2px solid var(--ink);
    outline-offset: 1px;
  }
  .art {
    background: repeating-linear-gradient(135deg, var(--rule2) 0 2px, transparent 2px 6px);
  }
  .database {
    background: radial-gradient(var(--ink3) 0.9px, transparent 1.2px) 0 0 / 6px 6px;
  }
  /* Text over the dots sits on a plate of the page, or the dots would eat it. */
  .database .lines > span,
  .database .size {
    align-self: flex-start;
    box-sizing: border-box;
    max-width: calc(100% + 6px);
    margin: 0 -3px;
    padding: 0 3px;
    background: var(--bg);
    opacity: 1;
  }
  .database .lab {
    align-items: flex-start;
  }
  .database .head {
    letter-spacing: 0.06em;
  }
  .configs,
  .lost,
  .vault {
    background: var(--rule);
  }
  .lab {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    padding: 8px 9px;
    min-width: 0;
  }
  .lines {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }
  .lines > span,
  .size {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .head {
    font: 600 11px/1.45 "Barlow", system-ui, sans-serif;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    opacity: 0.8;
  }
  .name {
    font-weight: 600;
  }
  .note {
    margin: 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .sub {
    opacity: 0.85;
  }
  .size {
    font: 400 12px "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
    opacity: 0.85;
  }
  @media (prefers-reduced-motion: no-preference) {
    .crumb,
    .link {
      transition: color 150ms cubic-bezier(0.23, 1, 0.32, 1);
    }
  }
</style>
