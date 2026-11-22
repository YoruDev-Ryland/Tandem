<script lang="ts">
  import { untrack } from "svelte";
  import { fetchArt, getGames } from "../lib/api/endpoints";
  import { gamePath } from "../lib/api/paths";
  import { resource } from "../lib/api/resource.svelte";
  import { EMULATOR_IDS, type GameRow } from "../lib/api/types";
  import { onAction } from "../lib/input/actions";
  import { emulatorName } from "../lib/emulators";
  import { router } from "../lib/router.svelte";
  import { cardCopy, cursorIndex, railRows, shelfOrder, type ShelfMemory } from "../lib/shelf";
  import { bareKey } from "../lib/systems";
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
  let memory = $state<Record<string, ShelfMemory>>(
    params.get("e") && params.get("g") ? { [params.get("e")!]: { key: params.get("g")!, index: 0 } } : {},
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

  const index = $derived(cursorIndex(memory[emulator], shelf.map((g) => g.gameKey)));
  const focused = $derived(shelf[index] as GameRow | undefined);
  const rail = $derived(railRows(all));
  const copy = $derived(focused ? cardCopy(focused, emulatorName(emulator), all) : null);

  // The cursor follows its game when the list changes; a game that went away leaves it at the nearest place.
  $effect(() => {
    if (focused && (memory[emulator]?.key !== focused.gameKey || memory[emulator]?.index !== index)) {
      memory[emulator] = { key: focused.gameKey, index };
    }
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
    if (g) memory[emulator] = { key: g.gameKey, index: i };
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

  // Art for the focused game only, a moment after the cursor settles. Leaving the game aborts its fetch (and its one
  // retry) and frees the image.
  const focusId = $derived(focused ? `${focused.emulatorId}\n${focused.gameKey}` : null);
  let art = $state<{ id: string; url: string } | null>(null);
  $effect(() => {
    const id = focusId;
    if (!id) return;
    const [emu, key] = id.split("\n") as [string, string];
    const stop = new AbortController();
    let url: string | null = null;
    const t = setTimeout(async () => {
      const r = await fetchArt(emu, key, { signal: stop.signal });
      if (r.kind !== "image") return;
      if (stop.signal.aborted) return URL.revokeObjectURL(r.url);
      url = r.url;
      art = { id, url };
    }, 150);
    return () => {
      clearTimeout(t);
      stop.abort();
      if (url) URL.revokeObjectURL(url);
      art = null;
    };
  });
  const artUrl = $derived(art && art.id === focusId ? art.url : null);
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
        <Shelf games={shelf} {index} rows={rail} {copy} art={artUrl} onSelect={select} onOpen={open} />
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
