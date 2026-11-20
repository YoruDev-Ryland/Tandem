<script lang="ts">
  import { getGames } from "../lib/api/endpoints";
  import { gamePath } from "../lib/api/paths";
  import { resource } from "../lib/api/resource.svelte";
  import { EMULATOR_IDS, type GameRow } from "../lib/api/types";
  import { deviceStateCopy, differing, otherDevices } from "../lib/deviceStates";
  import { emulatorName } from "../lib/emulators";
  import { router } from "../lib/router.svelte";
  import { systemLabel } from "../lib/systems";
  import { relativeTime } from "../lib/time";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import Field from "../lib/ui/Field.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import List from "../lib/ui/List.svelte";
  import Tabs from "../lib/ui/Tabs.svelte";

  const games = resource(() => getGames(), ["saves", "conflicts", "devices"]);

  let query = $state("");
  let emulator = $state("all");
  let index = $state(0);

  const title = (g: GameRow) => g.displayName ?? g.gameKey;

  // Tabs follow the module order, and only for emulators that have games.
  const tabs = $derived.by(() => {
    const present = new Set((games.data ?? []).map((g) => g.emulatorId));
    return [
      { label: "All", path: "all" },
      ...EMULATOR_IDS.filter((e) => present.has(e)).map((e) => ({ label: emulatorName(e), path: e })),
    ];
  });

  const rows = $derived.by(() => {
    const q = query.trim().toLowerCase();
    return (games.data ?? [])
      .filter((g) => emulator === "all" || g.emulatorId === emulator)
      .filter((g) => !q || title(g).toLowerCase().includes(q) || g.gameKey.toLowerCase().includes(q))
      .sort((a, b) => {
        if (a.openConflict !== b.openConflict) return a.openConflict ? -1 : 1;
        return title(a).localeCompare(title(b));
      });
  });

  const COLUMNS = "72px minmax(0,1fr) 128px 130px 210px 96px";
  const HEADER = ["System", "Game", "Emulator", "Newest on", "Other devices", "Saved"];

  function open(g: GameRow) {
    router.go(gamePath(g.emulatorId, g.gameKey));
  }
</script>

<div class="page">
  <section>
    <h1>Games{#if games.data}<small>{rows.length} {rows.length === 1 ? "game" : "games"}</small>{/if}</h1>

    <div class="controls">
      <Tabs level={2} items={tabs} active={emulator} onSelect={(p) => { emulator = p; index = 0; }} />
      <div class="search">
        <Field type="search" label="Search games" bind:value={query} inline />
      </div>
    </div>

    {#if games.error && !games.data}
      <ErrorLine error={games.error} />
    {:else if !games.data}
      <p class="loading" aria-busy="true">— — —</p>
    {:else if games.data.length === 0}
      <Empty text="No games yet. Start the app on a handheld and they'll appear here." />
    {:else if rows.length === 0}
      <Empty text="No games match." />
    {:else}
      <List {rows} columns={COLUMNS} header={HEADER} headerEnd={[5]} phoneColumns="minmax(0,1fr) 84px 60px" phoneHide={[0, 2, 4]} minWidth="0px" bind:index onOpen={open} key={(g) => g.emulatorId + "/" + g.gameKey}>
        {#snippet row(g: GameRow)}
          {@const bad = g.openConflict}
          <span class="sys ink3 sm-hide">{systemLabel(g.emulatorId, g.gameKey)}</span>
          <span class="name ellipsis" class:fault={bad} title={title(g)}>{title(g)}</span>
          <span class="ink2 sm-hide">{emulatorName(g.emulatorId)}</span>
          {#if bad}
            <span class="strong fault">Two saves</span>
            <span class="fault sm-hide">{differing(g)}</span>
            <span class="end fault">choose</span>
          {:else}
            {#if g.newestOnDeviceName}
              <span class="strong">{g.newestOnDeviceName}</span>
            {:else}
              <span class="ink3">—</span>
            {/if}
            <span class="sm-hide">
              {#each otherDevices(g) as d, i (d.deviceId)}
                {@const c = deviceStateCopy(d, g.emulatorId)}{#if i > 0}<span class="ink2">;&nbsp;</span>{/if}<span class={c.tone || "ink2"}>{c.text}</span>
              {/each}
            </span>
            <span class="end ink3">{g.newestUtc ? relativeTime(g.newestUtc) : "—"}</span>
          {/if}
        {/snippet}
      </List>
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
    padding: 8px var(--gutter) 0;
  }
  h1 {
    margin: 0 0 18px;
    font: 600 40px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  h1 small {
    margin-left: 10px;
    font: 500 20px "Barlow", system-ui, sans-serif;
    letter-spacing: 0;
    color: var(--ink3);
  }
  .controls {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 24px;
    margin: 0 0 16px;
    padding: 0 16px;
  }
  .search {
    width: 280px;
    flex: none;
  }
  .sys {
    font: 600 13px "Barlow Semi Condensed", sans-serif;
  }
  .loading {
    color: var(--ink3);
    margin: 0;
    padding: 12px 16px;
  }
  .ellipsis {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
