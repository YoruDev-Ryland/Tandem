<script lang="ts">
  import { onAction } from "../input/actions";
  import { gamepad } from "../input/gamepad.svelte";
  import { router } from "../router.svelte";
  import type { TabMark } from "../attention";
  import Glyph from "./Glyph.svelte";

  interface Props {
    items: { label: string; path: string }[];
    /** The active item's path. */
    active: string;
    level: 1 | 2;
    /** Defaults to navigating to the item's path. */
    onSelect?: (path: string) => void;
    /** Tabs with something waiting on the user, keyed by path: a square in the item's colour and the count. */
    marks?: Record<string, TabMark>;
  }
  let { items, active, level, onSelect = (p) => router.go(p), marks = {} }: Props = $props();
  const WHAT = { fault: "needs you", wait: "waiting", ink: "to look at", quiet: "to look at" } as const;

  const prev = $derived(level === 1 ? "l1" : "l2");
  const next = $derived(level === 1 ? "r1" : "r2");

  $effect(() =>
    onAction((a) => {
      if (a !== prev && a !== next) return false;
      const i = items.findIndex((t) => t.path === active);
      const to = items[Math.max(0, Math.min(items.length - 1, i + (a === next ? 1 : -1)))];
      if (to && to.path !== active) onSelect(to.path);
      return true;
    }),
  );

  function click(e: MouseEvent, path: string) {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    if (path !== active) onSelect(path);
  }
</script>

<nav class="tabs level{level}" aria-label={level === 1 ? "Pages" : "Sections"}>
  {#if gamepad.connected}<Glyph action={prev} />{/if}
  {#each items as t (t.path)}
    <a href={t.path} aria-current={t.path === active ? "page" : undefined} onclick={(e) => click(e, t.path)}
      >{t.label}{#if marks[t.path]}{@const m = marks[t.path]!}<span class="mark {m.tone}" data-testid="tab-mark"
          ><span class="sq" aria-hidden="true"></span>{m.count}<span class="sr"> {WHAT[m.tone]}</span></span
        >{/if}</a
    >
  {/each}
  {#if gamepad.connected}<Glyph action={next} />{/if}
</nav>

<style>
  .tabs {
    display: flex;
    align-items: center;
    min-width: 0;
    overflow-x: auto;
    scrollbar-width: none;
    /* Room for the focus outline inside the scroll area. */
    padding: 6px 5px;
    margin: -6px -5px;
    font-family: "Barlow Semi Condensed", sans-serif;
    font-weight: 600;
  }
  .level1 {
    gap: 26px;
    font-size: 17px;
  }
  .level2 {
    gap: 24px;
    font-size: 15px;
  }
  a {
    flex: none;
    color: var(--ink3);
    text-decoration: none;
    white-space: nowrap;
    border-radius: 3px;
  }
  a:hover {
    color: var(--ink2);
  }
  a[aria-current="page"] {
    color: var(--ink);
  }
  .mark {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-left: 7px;
    font: 600 13px/1 "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
    vertical-align: 1px;
  }
  .sq {
    width: 7px;
    height: 7px;
    background: currentColor;
  }
  .mark.fault {
    color: var(--fault);
  }
  .mark.wait {
    color: var(--wait);
  }
  .mark.ink {
    color: var(--ink);
  }
  .mark.quiet {
    color: var(--ink3);
  }
  .mark.quiet .sq {
    background: none;
    border: 1.5px solid currentColor;
  }
  .sr {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  /* On a phone the page tabs wrap to a second line: nothing is hidden behind a scroll. */
  @media (max-width: 640px) {
    .level1 {
      flex-wrap: wrap;
      overflow: visible;
      gap: 6px 22px;
    }
  }
</style>
