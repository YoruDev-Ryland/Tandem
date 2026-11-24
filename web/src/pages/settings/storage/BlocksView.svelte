<script lang="ts">
  import { exportZipUrl, gamePath } from "../../../lib/api/paths";
  import type { StorageUsage } from "../../../lib/api/types";
  import { emulatorName } from "../../../lib/emulators";
  import { formatSize } from "../../../lib/format";
  import { onAction } from "../../../lib/input/actions";
  import { router } from "../../../lib/router.svelte";
  import { blockGroups, blockLayout } from "../../../lib/storage/blocks";
  import { tick } from "svelte";
  import { deepSpine, nothingFocused, tileTip, usedBytes } from "../../../lib/storage/page";
  import type { Tile } from "../../../lib/storage/tiles";
  import type { Tip } from "../../../lib/storage/page";
  import StorageTip from "./StorageTip.svelte";

  let { usage: u, isAdmin }: { usage: StorageUsage; isAdmin: boolean } = $props();

  const COLS = 20;

  const laid = $derived(blockGroups(u, isAdmin));
  const unit = $derived(laid.unit);
  const groupOf = $derived(new Map(laid.groups.map((g) => [g.tile.id, g])));
  const blocks = $derived(blockLayout(laid.groups.map((g) => ({ id: g.tile.id, bytes: g.tile.value, olderBytes: g.tile.olderBytes ?? 0 })), unit));
  const free = $derived((COLS - (blocks.length % COLS)) % COLS);
  const notched = $derived(blocks.filter((b) => b.notched).length);

  interface Key { id: string; label: string; kind: Tile["kind"]; colour?: string; emulatorId?: string; count: number }
  const keys = $derived.by(() => {
    const out = new Map<string, Key>();
    for (const b of blocks) {
      const g = groupOf.get(b.group)!;
      const k = out.get(g.key);
      if (k) k.count++;
      else {
        const t = g.tile;
        const emulator = t.kind === "game" || t.kind === "more";
        out.set(g.key, { id: g.key, label: emulator ? emulatorName(t.emulatorId!) : t.label, kind: emulator ? "emulator" : t.kind, colour: t.colour, emulatorId: emulator ? t.emulatorId : undefined, count: 1 });
      }
    }
    return [...out.values()];
  });

  // The cursor is on a group (one game, an emulator's small games, the rest, art, the database), not on a block.
  const order = $derived([...new Set(blocks.map((b) => b.group))]);
  const firstOf = $derived.by(() => {
    const m = new Map<string, number>();
    blocks.forEach((b, i) => {
      if (!m.has(b.group)) m.set(b.group, i);
    });
    return m;
  });
  // Blocks per group and each group's tip, worked out once per layout.
  const counts = $derived.by(() => {
    const m = new Map<string, number>();
    for (const b of blocks) m.set(b.group, (m.get(b.group) ?? 0) + 1);
    return m;
  });
  const countOf = (id: string) => counts.get(id) ?? 0;
  let cursorId = $state<string | null>(null);
  const cursor = $derived(cursorId && firstOf.has(cursorId) ? cursorId : (order[0] ?? null));
  const focusKey = $derived(cursor ? groupOf.get(cursor)?.key : undefined);

  // The card shows its cursor, and takes the arrows and A, only while someone is on it: focus inside, or keys or the
  // pointer lit it.
  let lit = $state(false);
  let focusIn = $state(false);
  const inside = () => !!grid?.contains(document.activeElement);
  const uid = $props.id();
  const tipId = `${uid}-tip`;

  let grid = $state<HTMLDivElement>();
  let tip = $state<{ id: string; x: number; y: number } | null>(null);
  let box = $state({ w: 600, h: 300 });

  function place(id: string, x?: number, y?: number) {
    if (!grid) return;
    const g = grid.getBoundingClientRect();
    box = { w: g.width || 600, h: g.height || 300 };
    if (x === undefined || y === undefined) {
      const r = (grid.children[firstOf.get(id) ?? 0] as HTMLElement | undefined)?.getBoundingClientRect();
      x = r ? r.left - g.left + r.width / 2 : 0;
      y = r ? r.top - g.top + r.height / 2 : 0;
    }
    tip = { id, x, y };
  }

  function pointerMove(e: PointerEvent) {
    const el = (e.target as HTMLElement).closest<HTMLElement>("[data-i]");
    if (!el || !grid) return;
    const b = blocks[Number(el.dataset.i)];
    if (!b) return;
    cursorId = b.group;
    lit = true;
    const g = grid.getBoundingClientRect();
    place(b.group, e.clientX - g.left, e.clientY - g.top);
  }

  function open(id: string) {
    const t = groupOf.get(id)?.tile;
    if (t?.kind === "game") router.go(gamePath(t.emulatorId!, t.gameKey!));
  }

  /** The group the arrow leads to: the next or previous group, or the first other group a row up or down. */
  function step(from: string, a: "up" | "down" | "left" | "right"): string | null {
    if (a === "left" || a === "right") return order[order.indexOf(from) + (a === "right" ? 1 : -1)] ?? null;
    const d = a === "down" ? COLS : -COLS;
    for (let i = firstOf.get(from)! + d; i >= 0 && i < blocks.length; i += d) if (blocks[i]!.group !== from) return blocks[i]!.group;
    return null;
  }

  async function focusCursor(force = false) {
    await tick();
    if (force || grid?.contains(document.activeElement)) grid?.querySelector<HTMLElement>("button[tabindex='0']")?.focus({ preventScroll: true });
  }

  $effect(() =>
    onAction((a) => {
      if (!cursor) return false;
      // As on the map: the arrows are the card's while focus is in it or no control has focus.
      if (a === "up" || a === "down" || a === "left" || a === "right") {
        if (!inside()) {
          if (!nothingFocused()) return false;
          lit = true;
          place(cursor);
          void focusCursor(true);
          return true;
        }
        lit = true;
        const to = step(cursor, a);
        if (to) {
          cursorId = to;
          void focusCursor();
        }
        place(to ?? cursor);
        return true;
      }
      if (a === "a") {
        if (!inside() && !(lit && nothingFocused())) return false;
        open(cursor);
        return true;
      }
      return false;
    }),
  );

  const blocksWord = (n: number) => `${n} ${n === 1 ? "block" : "blocks"}`;
  const tips = $derived.by(() => {
    const m = new Map<string, Tip>();
    for (const g of laid.groups) {
      const base = tileTip(g.tile, u);
      m.set(g.tile.id, { title: base.title, lines: [...base.lines, blocksWord(countOf(g.tile.id))] });
    }
    return m;
  });
  const tipShown = $derived(tip ? tips.get(tip.id) : undefined);

  // One click handler for the card: any block of a game opens it, as its first block (the group's button) does.
  function click(e: MouseEvent) {
    const el = (e.target as HTMLElement).closest<HTMLElement>("[data-i]");
    const b = el ? blocks[Number(el.dataset.i)] : undefined;
    if (!b) return;
    cursorId = b.group;
    open(b.group);
  }
  const heroNote = $derived(
    [formatSize(usedBytes(u, isAdmin)), `1 block = ${unit} MB`, ...(isAdmin && u.server?.diskFreeBytes != null ? [`${formatSize(u.server.diskFreeBytes)} free on the disk`] : [])].join(" · "),
  );
  const swatch = (k: { kind: Tile["kind"]; colour?: string }) => (k.colour ? `background: ${k.colour}` : "");
</script>

<div>
  <div class="kick">Used by Tandem</div>
  <div class="hero">
    <b>{blocks.length} {blocks.length === 1 ? "block" : "blocks"}</b>
    <span class="note">{heroNote}</span>
  </div>
</div>

<div class="side">
  <div class="wrap">
    <!-- The card's click is the pointer's way to any block; keys reach every group by its own button. -->
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
    <div
      class="card"
      class:lit={lit || focusIn}
      role="group"
      aria-label="Storage blocks"
      bind:this={grid}
      onpointermove={pointerMove}
      onclick={click}
      onpointerleave={() => {
        tip = null;
        lit = false;
      }}
      onfocusin={() => (focusIn = true)}
      onfocusout={(e) => (focusIn = !!grid?.contains(e.relatedTarget as Node | null))}
    >
      {#each blocks as b, i (i)}
        {@const g = groupOf.get(b.group)!}
        {#if firstOf.get(b.group) === i}
          <button
            type="button"
            data-i={i}
            class={g.tile.kind}
            class:notch={b.notched}
            class:on={b.group === cursor}
            class:deep={deepSpine(g.tile.colour)}
            class:act={g.tile.kind === "game"}
            style:background={g.tile.colour}
            tabindex={b.group === cursor ? 0 : -1}
            aria-label="{tips.get(b.group)?.title}, {blocksWord(countOf(b.group))}, {formatSize(g.tile.value)}"
            aria-describedby={tip && tip.id === b.group ? tipId : undefined}
          ></button>
        {:else}
          <i
            data-i={i}
            class={g.tile.kind}
            class:notch={b.notched}
            class:on={b.group === cursor}
            class:deep={deepSpine(g.tile.colour)}
            class:act={g.tile.kind === "game"}
            style:background={g.tile.colour}
            aria-hidden="true"
          ></i>
        {/if}
      {/each}
      {#each { length: free }, i (i)}<i class="free" aria-hidden="true"></i>{/each}
    </div>
    {#if tip && tipShown}
      <StorageTip id={tipId} tip={tipShown} x={tip.x} y={tip.y} boxW={box.w} boxH={box.h} />
    {/if}
  </div>
  <ul class="key" aria-label="Key">
    {#each keys as k (k.id)}
      <li class:focus={(lit || focusIn) && k.id === focusKey}>
        <i class={k.kind} style={swatch(k)}></i>
        <span class="name">{k.label}</span>
        <span class="mono">{k.count}</span>
        {#if k.emulatorId}
          <a class="link" href={exportZipUrl(k.emulatorId)} download aria-label="Export {k.label}">Export</a>
        {:else}<span></span>{/if}
      </li>
    {/each}
  </ul>
</div>
<p class="note">
  Hover or move the cursor onto a block to see whose it is.
  {#if notched > 0}Older versions kept by Retention are the blocks with a notch: <span class="mono strong">{notched}</span> of them.{/if}
</p>

<style>
  .kick {
    font: 500 11px "Barlow", system-ui, sans-serif;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--ink3);
  }
  .hero {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 6px 16px;
    margin-top: 4px;
  }
  .hero b {
    font: 600 46px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.02em;
    color: var(--ink);
  }
  .note {
    margin: 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .strong {
    color: var(--ink);
  }
  .side {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 260px;
    gap: 28px;
    align-items: start;
  }
  .wrap {
    position: relative;
    max-width: 600px;
  }
  .card {
    display: grid;
    grid-template-columns: repeat(20, 1fr);
    gap: 4px;
  }
  .card > i,
  .card > button {
    display: block;
    margin: 0;
    padding: 0;
    border: 0;
    aspect-ratio: 1;
    border-radius: 1px;
    box-shadow: inset 0 0 0 1px var(--rule2);
    font: inherit;
  }
  .card > .deep {
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--ink) 28%, transparent);
  }
  .card > .act {
    cursor: pointer;
  }
  .card > i.free {
    box-shadow: inset 0 0 0 1px var(--rule);
    background: none;
  }
  .card > .notch {
    clip-path: polygon(0 0, 70% 0, 100% 30%, 100% 100%, 0 100%);
  }
  /* The cursor's group is marked on its first block, as the mockup marks a block: an outline. */
  .card.lit > button.on,
  .card > button:focus-visible {
    outline: 2px solid var(--ink);
    outline-offset: 1px;
    clip-path: none;
  }
  .art {
    background: repeating-linear-gradient(135deg, var(--rule2) 0 2px, transparent 2px 6px);
  }
  .database {
    background: radial-gradient(var(--ink3) 1px, transparent 1.3px) 0 0 / 5px 5px;
  }
  .configs {
    background: var(--ink3);
  }
  .key {
    display: flex;
    flex-direction: column;
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .key li {
    display: grid;
    grid-template-columns: 14px minmax(0, 1fr) auto 44px;
    align-items: center;
    gap: 10px;
    margin-left: -10px;
    padding: 7px 0 7px 10px;
    border-bottom: 1px solid var(--rule);
    font-size: 14px;
    color: var(--ink);
  }
  .key li.focus {
    background: var(--raised);
  }
  .key i {
    width: 14px;
    height: 14px;
    border-radius: 1px;
    box-shadow: inset 0 0 0 1px var(--rule2);
  }
  .key .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .key .mono {
    color: var(--ink2);
    text-align: right;
  }
  .mono {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
  }
  .link {
    justify-self: end;
    color: var(--ink2);
    font-size: 13px;
    text-underline-offset: 3px;
  }
  .link:hover {
    color: var(--ink);
  }
  @media (max-width: 760px) {
    .side {
      grid-template-columns: minmax(0, 1fr);
    }
  }
  @media (prefers-reduced-motion: no-preference) {
    .key li {
      transition: background-color 150ms cubic-bezier(0.23, 1, 0.32, 1);
    }
    .link {
      transition: color 150ms cubic-bezier(0.23, 1, 0.32, 1);
    }
  }
</style>
