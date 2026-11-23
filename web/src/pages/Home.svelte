<script lang="ts">
  import { getConfigs, getGames, getGroups, getHome } from "../lib/api/endpoints";
  import { downloadUrl, gamePath } from "../lib/api/paths";
  import { resource } from "../lib/api/resource.svelte";
  import type { ConfigOverview, GameRow, SyncGroupList } from "../lib/api/types";
  import { attentionItems, configConflictRefs } from "../lib/attention";
  import { differing, matrixColumns } from "../lib/deviceStates";
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import { relativeTime } from "../lib/time";
  import Arrow from "../lib/ui/Arrow.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import GameTitle from "../lib/ui/GameTitle.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import List from "../lib/ui/List.svelte";
  import Presence, { presenceWidth } from "../lib/ui/Presence.svelte";
  import SystemTag from "../lib/ui/SystemTag.svelte";
  import { pulse } from "../lib/ui/flash.svelte";

  /** How many games Recent shows. The whole catalogue lives on Games. */
  const RECENT = 12;

  const games = resource(() => getGames(), ["saves", "conflicts", "devices"]);
  const home = resource(getHome, ["saves", "conflicts", "devices", "configs"]);

  // Config conflicts are only a count on Home's payload; the bundles to link to come from the config overview.
  let configs = $state<{ overview: ConfigOverview; groups: SyncGroupList } | undefined>();
  const configConflicts = $derived(home.data?.attention.configConflicts ?? 0);
  $effect(() => {
    if (configConflicts === 0) {
      configs = undefined;
      return;
    }
    let live = true;
    Promise.all([getConfigs(), getGroups()]).then(
      ([overview, groups]) => {
        if (live) configs = { overview, groups };
      },
      () => {},
    );
    return () => (live = false);
  });

  let now = $state(Date.now());
  $effect(() => {
    const t = setInterval(() => (now = Date.now()), 30_000);
    return () => clearInterval(t);
  });

  const all = $derived(games.data ?? []);
  const withSave = $derived(all.filter((g) => g.newestUtc || g.openConflict));
  // Problems pin first, then newest save first.
  const rows = $derived(
    [...withSave]
      .sort((a, b) => {
        if (a.openConflict !== b.openConflict) return a.openConflict ? -1 : 1;
        return (b.newestUtc ? Date.parse(b.newestUtc) : 0) - (a.newestUtc ? Date.parse(a.newestUtc) : 0);
      })
      .slice(0, RECENT),
  );
  const columns = $derived(matrixColumns(all));

  const items = $derived(attentionItems(home.data, all, configConflictRefs(configs?.overview, configs?.groups), now));

  const synced = $derived.by(() => {
    const seen = (home.data?.devices ?? [])
      .map((d) => (d.lastSeenUtc ? Date.parse(d.lastSeenUtc) : NaN))
      .filter((t) => !Number.isNaN(t));
    // `now` keeps it ticking; a check-in that arrived after the last tick still reads from the real clock.
    return seen.length ? `Synced ${relativeTime(new Date(Math.max(...seen)).toISOString(), Math.max(now, Date.now()))}` : undefined;
  });

  const COLUMNS = $derived(`48px minmax(0,1fr) 128px ${presenceWidth(columns.length)} 96px`);
  const header = $derived(["System", "Game", "Newest on", matrixHead, "Saved"]);

  let index = $state(0);

  function open(g: GameRow) {
    router.go(gamePath(g.emulatorId, g.gameKey));
  }
  function go(e: MouseEvent, href: string) {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    router.go(href);
  }

  // Y downloads the focused game's save head, as the Game page does.
  function download(versionId: string) {
    const a = document.createElement("a");
    a.href = downloadUrl(versionId);
    a.download = "";
    a.click();
  }

  // X on the focused game goes to its page, where Restore lives.
  $effect(() =>
    onAction((a) => {
      const g = rows[index];
      if (!g) return false;
      if (a === "x") {
        open(g);
        return true;
      }
      if (a === "y" && g.headVersionId) {
        download(g.headVersionId);
        return true;
      }
      return false;
    }),
  );
  const focusedHead = $derived(rows[index]?.headVersionId ?? null);

  const loaded = $derived(!!games.data && !!home.data);
</script>

{#snippet matrixHead()}<Presence {columns} head />{/snippet}

<div class="page">
<section>
  <h1>Home</h1>
  {#if games.error && !games.data}
    <ErrorLine error={games.error} />
  {:else if !loaded}
    <p class="ink3" aria-busy="true">Loading…</p>
  {:else}
    {#if items.length > 0}
      <ul class="attention" aria-label="Needs attention">
        {#each items as it (it.key)}
          <li class={it.tone}>
            {#if it.href}
              <a href={it.href} onclick={(e) => go(e, it.href!)}><span class="text">{it.text}</span><span class="act">{it.action ?? "Open"}<Arrow /></span></a>
            {:else}
              <span class="text">{it.text}</span>
            {/if}
          </li>
        {/each}
      </ul>
    {/if}

    <h2>Recent</h2>
    {#if all.length === 0}
      <Empty text="No games yet. Start the app on a handheld and its saves appear here." />
    {:else if rows.length === 0}
      <Empty text="No saves yet. Games appear here once a handheld uploads a save." />
    {:else}
      <List {rows} columns={COLUMNS} {header} headerEnd={[4]} phoneColumns={`minmax(0,1fr) ${presenceWidth(columns.length)} 80px`} phoneHide={[0, 2]} minWidth="0px" bind:index onOpen={open} key={(g) => g.emulatorId + "/" + g.gameKey}>
        {#snippet row(g: GameRow)}
          {@const bad = g.openConflict}
          <span class="sm-hide"><SystemTag emulatorId={g.emulatorId} gameKey={g.gameKey} core={g.core} /></span>
          <GameTitle name={g.displayName} emulatorId={g.emulatorId} gameKey={g.gameKey} fault={bad} />
          {#if bad}
            <span class="newest fault sm-hide" title={differing(g)}>Two saves</span>
          {:else}
            <span class="newest sm-hide">{g.newestOnDeviceName ?? "—"}</span>
          {/if}
          <Presence {columns} row={g} />
          {#if bad}
            <span class="end fault">choose</span>
          {:else}
            <span class="end ink3 when" use:pulse={g.newestUtc}>{g.newestUtc ? relativeTime(g.newestUtc, now) : "—"}</span>
          {/if}
        {/snippet}
      </List>
    {/if}

    <p class="totals">
      <span>{withSave.length} {withSave.length === 1 ? "game" : "games"} with saves</span>
      <span class="ink3">{all.length - withSave.length} without</span>
      <a href="/games" onclick={(e) => go(e, "/games")}>All games<Arrow /></a>
    </p>
  {/if}
</section>

<HintBar
  status={synced}
  hints={[
    { glyph: "a", label: "Open" },
    ...(focusedHead ? [{ glyph: "y" as const, label: "Download" }] : []),
    { glyph: "x", label: "Restore" },
    { glyph: "b", label: "Back" },
  ]}
/>
</div>

<style>
  /* Fills the space under the 72 px top bar so the hint bar sits at the bottom edge. */
  .page {
    display: flex;
    flex-direction: column;
    min-height: calc(100vh - 72px);
  }
  section {
    flex: 1;
    padding: 16px var(--gutter) 32px;
  }
  h1 {
    margin: 0 0 18px;
    font: 600 48px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.02em;
    color: var(--ink);
  }
  .ink3 {
    color: var(--ink3);
  }
  .act {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
    white-space: nowrap;
    text-decoration: none;
    color: var(--ink);
  }
  a:hover .act {
    text-decoration: underline;
    text-underline-offset: 3px;
  }
  .attention {
    list-style: none;
    margin: 0;
    padding: 0;
    max-width: 960px;
  }
  .attention li {
    border-top: 1px solid var(--rule);
  }
  .attention li:last-child {
    border-bottom: 1px solid var(--rule);
  }
  .attention a,
  .attention li > .text {
    display: flex;
    align-items: center;
    gap: 24px;
    min-height: 44px;
    padding: 0 16px;
    color: inherit;
    text-decoration: none;
    border-radius: 3px;
  }
  .attention .text {
    flex: 1;
    min-width: 0;
  }
  .attention a:hover {
    background: color-mix(in srgb, var(--ink) 5%, transparent);
  }
  .attention .fault {
    color: var(--fault);
  }
  .attention .fault .text {
    font-weight: 600;
  }
  .attention .wait {
    color: var(--wait);
  }
  .attention .ink {
    color: var(--ink);
  }
  .attention .quiet {
    color: var(--ink2);
  }
  .attention .quiet .act {
    color: var(--ink2);
    font-weight: 500;
  }
  .attention .fault .act {
    color: var(--fault);
  }
  .attention .wait .act {
    color: var(--wait);
  }
  h2 {
    margin: 44px 0 14px;
    font: 600 18px/1 "Barlow Semi Condensed", sans-serif;
    color: var(--ink);
  }
  .newest {
    font-weight: 500;
  }
  .totals {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px 22px;
    margin: 0;
    padding: 16px 16px 0;
    border-top: 1px solid var(--rule);
    color: var(--ink2);
    font-size: 15px;
    font-variant-numeric: tabular-nums;
  }
  .totals .ink3 {
    color: var(--ink3);
  }
  .totals a {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    margin-left: auto;
    color: var(--ink);
    font-weight: 600;
    text-decoration: none;
  }
  .totals a:hover {
    text-decoration: underline;
    text-underline-offset: 3px;
  }
  @media (max-width: 640px) {
    h1 {
      font-size: 36px;
    }
    .attention a {
      flex-wrap: wrap;
      gap: 4px 16px;
      padding: 10px 0;
    }
    .totals {
      padding-inline: 0;
    }
  }
</style>
