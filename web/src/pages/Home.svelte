<script lang="ts">
  import { getGames, getHome } from "../lib/api/endpoints";
  import { gamePath } from "../lib/api/paths";
  import { resource } from "../lib/api/resource.svelte";
  import type { GameDeviceState, GameRow } from "../lib/api/types";
  import { emulatorName } from "../lib/emulators";
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import { updateWaiting } from "../lib/state/waiting.svelte";
  import { systemLabel } from "../lib/systems";
  import { relativeTime } from "../lib/time";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import List from "../lib/ui/List.svelte";

  const games = resource(() => getGames(), ["saves", "conflicts", "devices"]);
  const home = resource(getHome, ["saves", "conflicts", "devices"]);

  // Conflicts pin first, then newest save first; games with no save yet go last.
  const rows = $derived(
    [...(games.data ?? [])].sort((a, b) => {
      if (a.openConflict !== b.openConflict) return a.openConflict ? -1 : 1;
      return (b.newestUtc ? Date.parse(b.newestUtc) : -Infinity) - (a.newestUtc ? Date.parse(a.newestUtc) : -Infinity);
    }),
  );

  $effect(() => {
    if (games.data) updateWaiting(games.data);
  });

  const COLUMNS = "72px minmax(0,1fr) 128px 130px 210px 96px";
  const HEADER = ["System", "Game", "Emulator", "Newest on", "Other devices", "Saved"];

  const reviews = $derived(home.data?.attention.firstSyncReviews ?? 0);

  const synced = $derived.by(() => {
    const seen = (home.data?.devices ?? [])
      .map((d) => (d.lastSeenUtc ? Date.parse(d.lastSeenUtc) : NaN))
      .filter((t) => !Number.isNaN(t));
    return seen.length ? `Synced ${relativeTime(new Date(Math.max(...seen)).toISOString())}` : undefined;
  });

  let index = $state(0);

  function open(g: GameRow) {
    router.go(gamePath(g.emulatorId, g.gameKey));
  }

  // X on the focused game goes to its page, where Restore lives.
  $effect(() =>
    onAction((a) => {
      const g = rows[index];
      if (a !== "x" || !g) return false;
      open(g);
      return true;
    }),
  );

  function differing(g: GameRow): string {
    const names = g.devices.filter((d) => d.state === "Differs").map((d) => d.deviceName);
    const list = names.length >= 2 ? names : g.devices.map((d) => d.deviceName);
    return list.length > 1 ? `${list.slice(0, -1).join(", ")} and ${list[list.length - 1]} differ` : `${list[0] ?? "Saves"} differ`;
  }

  function others(g: GameRow): GameDeviceState[] {
    return g.devices.filter((d) => d.state !== "Newest" && d.deviceId !== g.newestOnDeviceId);
  }

  function copy(d: GameDeviceState, g: GameRow): { text: string; tone: string } {
    switch (d.state) {
      case "Pending": return { text: `${d.deviceName}, on its way`, tone: "" };
      case "Waiting": return { text: `${d.deviceName}, until ${emulatorName(g.emulatorId)} closes`, tone: "wait" };
      case "Behind": return { text: `${d.deviceName}, older save`, tone: "ink2" };
      case "NotInstalled": return { text: `not on ${d.deviceName}`, tone: "ink3" };
      case "Differs": return { text: `${d.deviceName} differs`, tone: "fault" };
      default: return { text: d.deviceName, tone: "" };
    }
  }

  const title = (g: GameRow) => g.displayName ?? g.gameKey;
</script>

<div class="page">
<section>
  <h1>Newest saves{#if games.data}<small>{rows.length} {rows.length === 1 ? "game" : "games"}</small>{/if}</h1>

  {#if reviews > 0}
    <a class="review fault" href="/conflicts" onclick={(e) => { e.preventDefault(); router.go("/conflicts"); }}>Review first sync ({reviews})</a>
  {/if}

  {#if games.error && !games.data}
    <ErrorLine error={games.error} />
  {:else if games.data && rows.length === 0}
    <Empty text="No saves yet. Start the app on a handheld and they'll appear here." />
  {:else if !games.data}
    <p class="loading ink3" aria-busy="true">— — —</p>
  {:else}
    <List {rows} columns={COLUMNS} header={HEADER} headerEnd={[5]} bind:index onOpen={open} key={(g) => g.emulatorId + "/" + g.gameKey}>
      {#snippet row(g: GameRow)}
        {@const bad = g.openConflict}
        <span class="sys ink3">{systemLabel(g.emulatorId, g.gameKey)}</span>
        <span class="name ellipsis" class:fault={bad} title={title(g)}>{title(g)}</span>
        <span class="ink2">{emulatorName(g.emulatorId)}</span>
        {#if bad}
          <span class="strong fault">Two saves</span>
          <span class="fault">{differing(g)}</span>
          <span class="end fault">choose</span>
        {:else}
          {#if g.newestOnDeviceName}
            <span class="strong">{g.newestOnDeviceName}</span>
          {:else}
            <span class="ink3">—</span>
          {/if}
          <span class="others">
            {#each others(g) as d, i (d.deviceId)}
              {@const c = copy(d, g)}{#if i > 0}; {/if}<span class={c.tone}>{c.text}</span>
            {/each}
          </span>
          <span class="end ink3">{g.newestUtc ? relativeTime(g.newestUtc) : "—"}</span>
        {/if}
      {/snippet}
    </List>
  {/if}
</section>

<HintBar status={synced} hints={[{ glyph: "a", label: "Open" }, { glyph: "x", label: "Restore" }, { glyph: "b", label: "Back" }]} />
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
    padding: 8px 40px 0;
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
  .sys {
    font: 600 13px "Barlow Semi Condensed", sans-serif;
  }
  .review {
    display: block;
    padding: 0 16px 14px;
    font-weight: 600;
    text-decoration: none;
  }
  .review:hover {
    text-decoration: underline;
  }
  .loading {
    margin: 0;
    padding: 12px 16px;
  }
  .ellipsis {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .others {
    color: var(--ink2);
  }
</style>
