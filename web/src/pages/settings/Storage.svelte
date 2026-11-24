<script lang="ts">
  import { onMount } from "svelte";
  import { getStorage, getStorageView, setStorageView } from "../../lib/api/endpoints";
  import { resource } from "../../lib/api/resource.svelte";
  import type { StorageView } from "../../lib/api/types";
  import { onAction } from "../../lib/input/actions";
  import { gamepad } from "../../lib/input/gamepad.svelte";
  import { session } from "../../lib/session.svelte";
  import { cycleView, VIEW_LABEL, VIEWS } from "../../lib/storage/page";
  import Empty from "../../lib/ui/Empty.svelte";
  import ErrorLine from "../../lib/ui/ErrorLine.svelte";
  import Glyph from "../../lib/ui/Glyph.svelte";
  import BarsView from "./storage/BarsView.svelte";
  import BlocksView from "./storage/BlocksView.svelte";
  import MapView from "./storage/MapView.svelte";
  import StorageLower from "./storage/StorageLower.svelte";

  const usage = resource(getStorage, ["saves", "configs", "vault", "lost", "storage"]);
  const isAdmin = $derived(session.account?.isAdmin === true);

  // The account's saved view; until it arrives the page waits rather than flash the map first. Unreadable is the map.
  let view = $state<StorageView | null>(null);
  onMount(() => {
    getStorageView().then(
      (v) => (view ??= VIEWS.includes(v) ? v : "map"),
      () => (view ??= "map"),
    );
  });

  function choose(v: StorageView) {
    if (v === view) return;
    view = v;
    setStorageView(v).catch(() => {});
  }

  // L2/R2 cycle the view here, ahead of the Settings sections.
  $effect(() =>
    onAction((a) => {
      if ((a !== "l2" && a !== "r2") || !view || !usage.data || usage.data.totalBytes === 0) return false;
      choose(cycleView(view, a === "r2" ? 1 : -1));
      return true;
    }),
  );
</script>

<div class="sec wide storage">
  <div class="head">
    <h2>Storage</h2>
    {#if usage.data && usage.data.totalBytes > 0 && view}
      <div class="views" role="group" aria-label="View">
        {#if gamepad.connected}<Glyph action="l2" />{/if}
        <span class="seg">
          {#each VIEWS as v (v)}
            <button type="button" aria-pressed={v === view} onclick={() => choose(v)}>{VIEW_LABEL[v]}</button>
          {/each}
        </span>
        {#if gamepad.connected}<Glyph action="r2" />{/if}
      </div>
    {/if}
  </div>

  {#if usage.error && !usage.data}
    <ErrorLine error={usage.error} />
  {:else if !usage.data || !view}
    <p class="note" aria-busy="true">— — —</p>
  {:else if usage.data.totalBytes === 0}
    <Empty text="Nothing is stored yet." />
    {#if isAdmin && usage.data.server}<StorageLower usage={usage.data} {isAdmin} filesOnly />{/if}
  {:else}
    {@const u = usage.data}
    {#if view === "map"}<MapView usage={u} {isAdmin} />
    {:else if view === "bars"}<BarsView usage={u} {isAdmin} />
    {:else}<BlocksView usage={u} {isAdmin} />
    {/if}
    <StorageLower usage={u} {isAdmin} />
  {/if}
</div>

<style>
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;
  }
  .views {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .seg {
    display: inline-flex;
    border: 1.5px solid var(--rule2);
    border-radius: 3px;
  }
  .seg button {
    height: 30px;
    padding: 0 14px;
    border: 0;
    background: none;
    color: var(--ink2);
    font: 600 13px "Barlow", system-ui, sans-serif;
    cursor: pointer;
  }
  .seg button + button {
    border-left: 1.5px solid var(--rule2);
  }
  .seg button:hover {
    color: var(--ink);
  }
  .seg button[aria-pressed="true"] {
    background: var(--ink);
    color: var(--bg);
  }
  .seg button:focus-visible {
    outline: 2px solid var(--ink);
    outline-offset: 2px;
  }
  @media (prefers-reduced-motion: no-preference) {
    .seg button {
      transition:
        color 150ms cubic-bezier(0.23, 1, 0.32, 1),
        background-color 150ms cubic-bezier(0.23, 1, 0.32, 1);
    }
  }
</style>
