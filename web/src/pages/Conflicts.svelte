<script lang="ts">
  import { bulkFirstSync, getConflicts } from "../lib/api/endpoints";
  import { gamePath } from "../lib/api/paths";
  import { resource } from "../lib/api/resource.svelte";
  import type { ConflictRow } from "../lib/api/types";
  import { emulatorName } from "../lib/emulators";
  import { onAction } from "../lib/input/actions";
  import { gamepad } from "../lib/input/gamepad.svelte";
  import { router } from "../lib/router.svelte";
  import Button from "../lib/ui/Button.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import List from "../lib/ui/List.svelte";

  const conflicts = resource(getConflicts, ["conflicts", "saves"]);
  const rows = $derived(conflicts.data ?? []);

  const COLUMNS = "minmax(0,1fr) 128px 120px 200px 220px";
  const HEADER = ["Game", "Emulator", "Item", "What differs", "Devices"];

  const title = (r: ConflictRow) => r.displayName ?? r.gameKey;
  const item = (r: ConflictRow) => (r.kind === "State" ? `Slot ${r.slot ?? ""}`.trim() : "Save");
  const names = (r: ConflictRow) => r.sides.map((s) => s.deviceName ?? "Unknown device").join(" and ");

  /** Devices with a side in a first-sync conflict, in the order they first appear, with their items. */
  const bulk = $derived.by(() => {
    const by = new Map<string, { deviceId: string; name: string; itemIds: string[] }>();
    for (const r of rows) {
      if (r.reason !== "FirstSync") continue;
      for (const s of r.sides) {
        if (!s.deviceId) continue;
        const d = by.get(s.deviceId) ?? { deviceId: s.deviceId, name: s.deviceName ?? "Unknown device", itemIds: [] };
        if (!d.itemIds.includes(r.itemId)) d.itemIds.push(r.itemId);
        by.set(s.deviceId, d);
      }
    }
    return [...by.values()];
  });
  const label = (name: string) => `Use ${name}'s for all first-sync games`;
  const pair = $derived(bulk.length === 2);

  let index = $state(0);
  let busy = $state(false);
  let failure = $state<unknown>(undefined);

  async function useDevice(d: { deviceId: string; itemIds: string[] } | undefined) {
    if (!d || busy) return;
    busy = true;
    failure = undefined;
    try {
      await bulkFirstSync(d.deviceId, "UseDevice", d.itemIds);
      conflicts.reload();
    } catch (e) {
      failure = e;
    } finally {
      busy = false;
    }
  }

  function open(r: ConflictRow) {
    router.go(gamePath(r.emulatorId, r.gameKey) + "/conflict");
  }

  $effect(() =>
    onAction((a) => {
      if (a === "b") {
        if (router.canGoBack) router.back();
        else router.go("/");
        return true;
      }
      if (pair && (a === "x" || a === "y")) {
        void useDevice(bulk[a === "x" ? 0 : 1]);
        return true;
      }
      return false;
    }),
  );
</script>

<div class="page">
  <section>
    <h1>Conflicts{#if conflicts.data && rows.length}<small>{rows.length}</small>{/if}</h1>

    {#if conflicts.error && !conflicts.data}
      <ErrorLine error={conflicts.error} />
    {:else if !conflicts.data}
      <p class="loading" aria-busy="true">— — —</p>
    {:else if rows.length === 0}
      <Empty text="Nothing to decide." />
    {:else}
      <List {rows} columns={COLUMNS} header={HEADER} bind:index onOpen={open} key={(r) => r.itemId}>
        {#snippet row(r: ConflictRow)}
          <span class="name ellipsis fault" title={title(r)}>{title(r)}</span>
          <span class="ink2">{emulatorName(r.emulatorId)}</span>
          <span>{item(r)}</span>
          <span class="fault">{r.reason === "FirstSync" ? "Before first sync" : "Changed on both"}</span>
          <span class="ink2 ellipsis">{names(r)}</span>
        {/snippet}
      </List>

      {#if bulk.length > 0 && !(pair && gamepad.connected)}
        <div class="bulk">
          {#each bulk as d (d.deviceId)}
            <Button disabled={busy} onclick={() => useDevice(d)}>{label(d.name)}</Button>
          {/each}
        </div>
      {/if}
      {#if failure}<div class="fail"><ErrorLine error={failure} /></div>{/if}
    {/if}
  </section>

  <HintBar
    status="Nothing has been overwritten"
    hints={[
      { glyph: "a", label: "Decide" },
      ...(pair ? [{ glyph: "x" as const, label: label(bulk[0]!.name) }, { glyph: "y" as const, label: label(bulk[1]!.name) }] : []),
      { glyph: "b", label: "Back" },
    ]}
  />
</div>

<style>
  .page {
    display: flex;
    flex-direction: column;
    min-height: calc(100vh - 72px);
  }
  section {
    flex: 1;
    padding: 8px 40px 24px;
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
  .bulk {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 24px;
    padding: 20px 16px 0;
    border-top: 1px solid var(--rule);
  }
  .fail {
    padding: 12px 16px 0;
  }
  .loading {
    margin: 0;
    padding: 12px 16px;
    color: var(--ink3);
  }
  .ellipsis {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
