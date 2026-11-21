<script lang="ts">
  import { getStorage } from "../../lib/api/endpoints";
  import { exportZipUrl } from "../../lib/api/paths";
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
    <div class="export">
      <a class="btn" href={exportZipUrl()} download>Export everything</a>
      <span class="note">A zip of every current save, state and config, in each emulator's own folders.</span>
    </div>
    {#if u.byEmulator.length === 0}
      <Empty text="Nothing is stored yet." />
    {:else}
      <h3>By emulator</h3>
      <ul class="rows">
        {#each u.byEmulator as r (r.emulatorId)}
          <li>
            <span class="grow">{label(r)}</span>
            <span class="mono">{formatSize(r.bytes)}</span>
            {#if r.emulatorId}<a class="link" href={exportZipUrl(r.emulatorId)} download aria-label="Export {label(r)}">Export</a>{/if}
          </li>
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
  .export {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px 16px;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    height: 40px;
    padding: 0 16px;
    border-radius: 3px;
    border: 1.5px solid var(--rule2);
    color: var(--ink);
    font: 600 14px "Barlow", system-ui, sans-serif;
    text-decoration: none;
    transition: border-color 150ms cubic-bezier(0.23, 1, 0.32, 1);
  }
  .btn:hover {
    border-color: var(--ink3);
  }
  .link {
    width: 56px;
    text-align: right;
    color: var(--ink2);
    font-size: 14px;
    text-underline-offset: 3px;
  }
  .link:hover {
    color: var(--ink);
  }
  .grow {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
