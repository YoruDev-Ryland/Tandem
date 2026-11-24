<script lang="ts">
  import { untrack } from "svelte";
  import { getGames } from "../lib/api/endpoints";
  import { gamePath } from "../lib/api/paths";
  import { resource } from "../lib/api/resource.svelte";
  import { EMULATOR_IDS, type GameRow } from "../lib/api/types";
  import { onAction } from "../lib/input/actions";
  import { emulatorName } from "../lib/emulators";
  import { router } from "../lib/router.svelte";
  import { cardCopy, cursorIndex, railRows, shelfOrder, windowOrder, type ShelfMemory } from "../lib/shelf";
  import { artId, createSpineArt, type ArtWant } from "../lib/spineArt.svelte";
  import { bareKey, systemLabel } from "../lib/systems";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import Field from "../lib/ui/Field.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import Shelf from "../lib/ui/Shelf.svelte";
  import Tabs from "../lib/ui/Tabs.svelte";

  const games = resource(() => getGames(), ["saves", "conflicts", "devices"]);

  // The emulator and the game under the cursor live in the URL (replaced, not pushed), so Back from a game lands on
  // the same shelf and game.
  const params = new URLSearchParams(router.search);
  let chosen = $state<string | null>(params.get("e"));
  // Raw (not a deep proxy), so the keys stored are the shelf's own array and compare by identity; changed by reassigning.
  let memory = $state.raw<Record<string, ShelfMemory>>(
    params.get("e") && params.get("g") ? { [params.get("e")!]: { key: params.get("g")!, keys: [] } } : {},
  );
  let query = $state("");

  const all = $derived(games.data ?? []);
  const title = (g: GameRow) => g.displayName ?? bareKey(g.emulatorId, g.gameKey);

  // One shelf per emulator that has games, in module order.
  const emulators = $derived.by(() => {
    const present = new Set(all.map((g) => g.emulatorId));
    return EMULATOR_IDS.filter((e) => present.has(e)) as string[];
  });
  const emulator = $derived(chosen && emulators.includes(chosen) ? chosen : (emulators[0] ?? ""));
  const tabPath = (e: string) => `/games?e=${encodeURIComponent(e)}`;
  const tabs = $derived(emulators.map((e) => ({ label: emulatorName(e), path: tabPath(e) })));

  const q = $derived(query.trim().toLowerCase());
  const matches = (g: GameRow) => !q || title(g).toLowerCase().includes(q) || g.gameKey.toLowerCase().includes(q);
  const shelf = $derived(shelfOrder(all.filter((g) => g.emulatorId === emulator && matches(g))));
  // While searching: the other emulators' matches, as links to their shelves.
  const elsewhere = $derived.by(() => {
    if (!q) return [];
    return emulators
      .filter((e) => e !== emulator)
      .map((e) => ({ id: e, count: all.filter((g) => g.emulatorId === e && matches(g)).length }))
      .filter((x) => x.count > 0);
  });
  const elsewhereCount = $derived(elsewhere.reduce((n, x) => n + x.count, 0));

  const keys = $derived(shelf.map((g) => g.gameKey));
  const index = $derived(cursorIndex(memory[emulator], keys));
  const focused = $derived(shelf[index] as GameRow | undefined);
  const rail = $derived(railRows(all));
  const copy = $derived(focused ? cardCopy(focused, emulatorName(emulator), all) : null);

  // The cursor follows its game when the list changes; a game that went away leaves it on the nearest earlier one.
  $effect(() => {
    const m = memory[emulator];
    if (focused && (m?.key !== focused.gameKey || m.keys !== keys)) memory = { ...memory, [emulator]: { key: focused.gameKey, keys } };
  });

  $effect(() => {
    if (!emulator) return;
    const url = `/games?e=${encodeURIComponent(emulator)}${focused ? `&g=${encodeURIComponent(focused.gameKey)}` : ""}`;
    // untrack: router.go reads the router's own state, which must not re-run this after the page navigates away.
    untrack(() => {
      if (location.pathname === "/games" && location.search !== url.slice("/games".length)) router.go(url, { replace: true });
    });
  });

  function select(i: number) {
    const g = shelf[i];
    if (g) memory = { ...memory, [emulator]: { key: g.gameKey, keys } };
  }
  function open(i: number) {
    const g = shelf[i];
    if (g) router.go(gamePath(g.emulatorId, g.gameKey));
  }
  function switchTo(e: string) {
    chosen = e;
  }

  $effect(() =>
    onAction((a) => {
      if (a === "b") {
        if (router.canGoBack) router.back();
        else router.go("/");
        return true;
      }
      if (!games.data) return false;
      if (a === "left" || a === "right") {
        // No wrap: at either end the cursor stays.
        const to = index + (a === "right" ? 1 : -1);
        if (to >= 0 && to < shelf.length) select(to);
        return true;
      }
      if (a === "up" || a === "down") {
        const i = emulators.indexOf(emulator);
        const to = emulators[i + (a === "down" ? 1 : -1)];
        if (to) switchTo(to);
        return true;
      }
      if (a === "a" && focused) {
        open(index);
        return true;
      }
      return false;
    }),
  );

  // Spines and covers for the shelf: those in view and a dozen either side, nearest the cursor first, a moment after
  // the cursor settles. The focused game's cover is always wanted first, for its card; a Switch spine needs nothing; a
  // game with a real spine wants that, and its cover only once the server says it has no spine. Images outside the
  // window are freed; leaving the page frees the rest. Until the shelf has said what is in view (no layout yet), the
  // view is taken as 18 spines either side of the cursor.
  const spineArt = createSpineArt();
  $effect(() => () => spineArt.destroy());
  let view = $state<[number, number] | null>(null);
  const wants = $derived.by(() => {
    const out: ArtWant[] = [];
    if (focused) out.push({ emu: focused.emulatorId, key: focused.gameKey, kind: "cover" });
    const [first, last] = view ?? [index - 18, index + 18];
    for (const i of windowOrder(index, first, last, shelf.length)) {
      const g = shelf[i]!;
      if (systemLabel(g.emulatorId, g.gameKey, g.core, g.system) === "SWITCH") continue;
      if (g.spine && !spineArt.missing(g.emulatorId, g.gameKey, "spine")) out.push({ emu: g.emulatorId, key: g.gameKey, kind: "spine" });
      else if (g !== focused) out.push({ emu: g.emulatorId, key: g.gameKey, kind: "cover" });
    }
    return out;
  });
  $effect(() => {
    const w = wants;
    const t = setTimeout(() => {
      spineArt.release(new Set(w.map((x) => artId(x.emu, x.key, x.kind))));
      spineArt.want(w);
    }, 150);
    return () => clearTimeout(t);
  });
  const artUrl = $derived(focused ? spineArt.url(focused.emulatorId, focused.gameKey, "cover") : null);
  const images = (g: GameRow) => ({ spine: spineArt.url(g.emulatorId, g.gameKey, "spine"), cover: spineArt.url(g.emulatorId, g.gameKey, "cover") });
</script>

<div class="page">
  <section>
    <h1>Games{#if games.data && emulator}<small>{shelf.length} {shelf.length === 1 ? "game" : "games"}</small>{/if}</h1>

    {#if emulators.length}
      <div class="controls">
        <Tabs level={2} items={tabs} active={tabPath(emulator)} onSelect={(p) => switchTo(new URLSearchParams(p.split("?")[1]).get("e") ?? "")} />
        <div class="search">
          <Field type="search" label="Search games" bind:value={query} inline />
        </div>
      </div>
    {/if}

    {#if games.error && !games.data}
      <div class="pad"><ErrorLine error={games.error} /></div>
    {:else if !games.data}
      <p class="loading" aria-busy="true">— — —</p>
    {:else if all.length === 0}
      <div class="pad"><Empty text="No games yet. Start the app on a handheld and they'll appear here." /></div>
    {:else}
      {#if elsewhere.length}
        <p class="elsewhere">
          {elsewhereCount} {elsewhereCount === 1 ? "match" : "matches"} in other emulators:
          {#each elsewhere as x, i (x.id)}{#if i}<span class="sep"> · </span>{/if}<a href={tabPath(x.id)} onclick={(e) => { e.preventDefault(); switchTo(x.id); }}>{emulatorName(x.id)} <span class="n">{x.count}</span></a>{/each}
        </p>
      {/if}
      {#if focused && copy}
        <Shelf games={shelf} {index} rows={rail} {copy} art={artUrl} {images} onSelect={select} onOpen={open} onView={(f, l) => { if (!view || view[0] !== f || view[1] !== l) view = [f, l]; }} />
      {:else}
        <div class="pad"><Empty text={q ? `No games in ${emulatorName(emulator)} match.` : `No games for ${emulatorName(emulator)} yet.`} /></div>
      {/if}
    {/if}
  </section>

  <HintBar hints={[{ glyph: "a", label: "Open" }, { glyph: "b", label: "Back" }]} />
</div>

<style>
  .page {
    display: flex;
    flex-direction: column;
    min-height: calc(100vh - 72px);
  }
  section {
    flex: 1;
    padding: 8px 0 0;
  }
  h1 {
    margin: 0 var(--gutter) 18px;
    font: 600 48px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.02em;
    color: var(--ink);
  }
  h1 small {
    margin-left: 14px;
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
    letter-spacing: 0;
    color: var(--ink3);
  }
  .controls {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 24px;
    margin: 0 var(--gutter) 28px;
  }
  .search {
    width: 280px;
    flex: none;
  }
  .elsewhere {
    margin: -16px var(--gutter) 16px;
    font: 500 14px "Barlow", system-ui, sans-serif;
    color: var(--ink2);
  }
  .elsewhere a {
    color: var(--ink);
    text-decoration: underline;
    text-decoration-color: var(--rule2);
    text-underline-offset: 3px;
  }
  .elsewhere .n {
    font: 400 12px "JetBrains Mono", ui-monospace, monospace;
    color: var(--ink3);
  }
  .sep {
    color: var(--ink3);
  }
  .pad {
    padding: 0 var(--gutter);
  }
  .loading {
    color: var(--ink3);
    margin: 0;
    padding: 12px var(--gutter);
  }
  @media (max-width: 640px) {
    .controls {
      flex-direction: column;
      align-items: stretch;
      gap: 14px;
    }
    .search {
      width: auto;
    }
  }
</style>
