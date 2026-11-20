<script lang="ts">
  import { getDevices } from "../lib/api/endpoints";
  import { resource } from "../lib/api/resource.svelte";
  import type { DeviceSummary } from "../lib/api/types";
  import { deviceStatus } from "../lib/deviceStatus";
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import { relativeTime } from "../lib/time";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import List from "../lib/ui/List.svelte";

  const devices = resource(getDevices, ["devices"]);
  const rows = $derived(devices.data ?? []);

  let now = $state(Date.now());
  $effect(() => {
    const t = setInterval(() => (now = Date.now()), 15_000);
    return () => clearInterval(t);
  });

  const COLUMNS = "minmax(0,1fr) 140px 160px 200px";
  const HEADER = ["Device", "Platform", "Last seen", "Status"];

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
      <List {rows} columns={COLUMNS} header={HEADER} bind:index onOpen={open} key={(d) => d.id}>
        {#snippet row(d: DeviceSummary)}
          {@const s = deviceStatus(d, now)}
          <span class="name ellipsis">{d.name}</span>
          <span class="ink2">{d.platform}</span>
          <span class="ink3">{d.lastSeenUtc ? relativeTime(d.lastSeenUtc, now) : "never"}</span>
          <span class={s.tone}>{s.text}</span>
        {/snippet}
      </List>
    {/if}
  </section>

  <HintBar hints={[{ glyph: "a", label: "Open" }, { glyph: "x", label: "Pair a new device" }, { glyph: "b", label: "Back" }]} />
</div>

<style>
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
