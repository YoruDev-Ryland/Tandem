<script lang="ts">
  import { getDevice, getDevices, getGames } from "../lib/api/endpoints";
  import { resource } from "../lib/api/resource.svelte";
  import type { DeviceSummary } from "../lib/api/types";
  import { deviceSaveCounts } from "../lib/deviceStates";
  import { deviceStatus } from "../lib/deviceStatus";
  import { formatSize } from "../lib/format";
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import { waitingDevices } from "../lib/state/waiting";
  import { relativeTime } from "../lib/time";
  import Button from "../lib/ui/Button.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import List from "../lib/ui/List.svelte";
  import Segments from "../lib/ui/Segments.svelte";

  const devices = resource(getDevices, ["devices"]);
  const games = resource(() => getGames(), ["saves", "conflicts", "devices"]);
  const rows = $derived(devices.data ?? []);
  const gameRows = $derived(games.data ?? []);
  const waiting = $derived(waitingDevices(gameRows));

  /** Saves stored per device, from each device's detail. */
  let storage = $state<Record<string, number>>({});
  $effect(() => {
    const ids = rows.map((d) => d.id);
    let live = true;
    for (const id of ids)
      getDevice(id).then(
        (d) => {
          if (live) storage[id] = d.storageBytes;
        },
        () => {},
      );
    return () => (live = false);
  });

  let now = $state(Date.now());
  $effect(() => {
    const t = setInterval(() => (now = Date.now()), 15_000);
    return () => clearInterval(t);
  });

  const platformName = (p: string) => (p ? p[0]!.toUpperCase() + p.slice(1) : "");
  const COLUMNS = "minmax(0,1fr) 170px 230px 110px 110px";
  const HEADER = ["Device", "Now", "Current saves", "Stored", "Last seen"];

  let index = $state(0);
  const open = (d: DeviceSummary) => router.go(`/devices/${encodeURIComponent(d.id)}`);
  const pair = () => router.go("/devices/pair");

  $effect(() =>
    onAction((a) => {
      if (a === "x") {
        pair();
        return true;
      }
      if (a === "b") {
        if (router.canGoBack) router.back();
        else router.go("/");
        return true;
      }
      return false;
    }),
  );
</script>

<div class="page">
  <section>
    <h1>Devices{#if devices.data}<small>{rows.length}</small>{/if}</h1>

    {#if devices.error && !devices.data}
      <ErrorLine error={devices.error} />
    {:else if !devices.data}
      <p class="loading" aria-busy="true">— — —</p>
    {:else if rows.length === 0}
      <Empty text="No devices yet. Pair a handheld to start syncing." />
    {:else}
      <List {rows} columns={COLUMNS} header={HEADER} headerEnd={[3, 4]} phoneColumns="minmax(0,1fr) 120px" phoneHide={[2, 3, 4]} minWidth="0px" bind:index onOpen={open} key={(d) => d.id}>
        {#snippet row(d: DeviceSummary)}
          {@const s = deviceStatus(d, now, waiting)}
          {@const c = deviceSaveCounts(gameRows, d.id)}
          <span class="name ellipsis">{d.name}<span class="plat">{platformName(d.platform)}</span></span>
          <span class={s.tone}>{s.text}</span>
          <span class="sm-hide"><span class="saves">
            {#if games.data && c.total > 0}
              <span class="count">{c.current}<span class="ink3">{" of "}{c.total}</span></span>
              <Segments done={c.current} total={c.total} max={16} small />
            {:else if games.data}
              <span class="ink3">no saves yet</span>
            {/if}
          </span></span>
          <span class="end mono ink2 sm-hide">{d.id in storage ? formatSize(storage[d.id]!) : ""}</span>
          <span class="end ink3 when sm-hide">{d.lastSeenUtc ? relativeTime(d.lastSeenUtc, now) : "never"}</span>
        {/snippet}
      </List>
    {/if}
    <div class="actions">
      <Button glyph="x" onclick={pair}>Pair a new device</Button>
    </div>
  </section>

  <HintBar hints={[{ glyph: "a", label: "Open" }, { glyph: "x", label: "Pair a new device" }, { glyph: "b", label: "Back" }]} />
</div>

<style>
  .actions {
    display: flex;
    gap: 10px;
    margin-top: 24px;
  }
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
  .plat {
    margin-left: 12px;
    font: 400 14px "Barlow", system-ui, sans-serif;
    color: var(--ink3);
    text-transform: capitalize;
  }
  :global(.row.focused) .plat {
    color: color-mix(in srgb, var(--focus-ink) 62%, var(--focus-bg));
  }
  .saves {
    display: flex;
    align-items: center;
    gap: 14px;
  }
  .count {
    min-width: 52px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
  .mono {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
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
