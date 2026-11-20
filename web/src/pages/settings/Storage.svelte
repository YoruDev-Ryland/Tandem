<script lang="ts">
  import { getStorage } from "../../lib/api/endpoints";
  import { resource } from "../../lib/api/resource.svelte";
  import type { StorageRow } from "../../lib/api/types";
  import { emulatorName } from "../../lib/emulators";
  import { formatSize } from "../../lib/format";
  import Empty from "../../lib/ui/Empty.svelte";
  import ErrorLine from "../../lib/ui/ErrorLine.svelte";

  const usage = resource(getStorage, ["saves", "configs", "vault"]);
  const label = (r: StorageRow) => r.displayName ?? (r.emulatorId ? emulatorName(r.emulatorId) : "Vault, no emulator");
</script>

<div class="sec wide">
  <h2>Storage</h2>
  {#if usage.error && !usage.data}
    <ErrorLine error={usage.error} />
  {:else if !usage.data}
    <p class="note" aria-busy="true">— — —</p>
  {:else}
    {@const u = usage.data}
    <p class="note">Used in total <span class="mono">{formatSize(u.totalBytes)}</span></p>
    {#if u.byEmulator.length === 0}
      <Empty text="Nothing is stored yet." />
    {:else}
      <h3>By emulator</h3>
      <ul class="rows">
        {#each u.byEmulator as r (r.emulatorId)}
          <li><span class="grow">{label(r)}</span><span class="mono">{formatSize(r.bytes)}</span></li>
        {/each}
      </ul>
      <h3>Largest games</h3>
      <ul class="rows">
        {#each u.topGames as r (`${r.emulatorId}/${r.gameKey}`)}
          <li>
            <span class="grow">{label(r)}</span>
            <span class="ink2">{emulatorName(r.emulatorId)}</span>
            <span class="mono">{formatSize(r.bytes)}</span>
          </li>
        {/each}
      </ul>
    {/if}
  {/if}
</div>

<style>
  .grow {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
