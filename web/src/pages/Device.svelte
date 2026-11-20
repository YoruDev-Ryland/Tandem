<script lang="ts">
  import { getDevice, renameDevice, revokeDevice } from "../lib/api/endpoints";
  import { resource } from "../lib/api/resource.svelte";
  import { deviceStatus } from "../lib/deviceStatus";
  import { emulatorName } from "../lib/emulators";
  import { formatSize, formatWhen } from "../lib/format";
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import Button from "../lib/ui/Button.svelte";
  import Dialog from "../lib/ui/Dialog.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import Facts from "../lib/ui/Facts.svelte";
  import Field from "../lib/ui/Field.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";

  const { id } = router.params as { id: string };
  const device = resource(() => getDevice(id), ["devices", "configs", "saves"]);

  const summary = $derived(device.data?.summary);
  const name = $derived(summary?.name ?? "Device");
  const status = $derived(summary ? deviceStatus(summary, Date.now()) : undefined);

  const facts = $derived.by<[string, string][]>(() => {
    const d = device.data;
    if (!d) return [];
    return [
      ["Platform", d.summary.platform],
      ["Last seen", d.summary.lastSeenUtc ? formatWhen(d.summary.lastSeenUtc) : "never"],
      ["Storage used", formatSize(d.storageBytes)],
      ["Pending changes", String(d.pendingDeliveries)],
    ];
  });

  // Rename and Revoke act on what was on screen when the dialog opened.
  let renaming = $state(false);
  let newName = $state("");
  let revoking = $state(false);
  let revokeName = $state("");
  let busy = $state(false);
  let failure = $state<unknown>(undefined);

  function openRename() {
    if (!summary) return;
    newName = summary.name;
    failure = undefined;
    renaming = true;
  }
  function openRevoke() {
    if (!summary) return;
    revokeName = summary.name;
    failure = undefined;
    revoking = true;
  }
  const setupAgain = () => router.go(`/devices/${encodeURIComponent(id)}/setup`);

  async function save() {
    const n = newName.trim();
    if (!n || busy) return;
    busy = true;
    failure = undefined;
    try {
      await renameDevice(id, n);
      renaming = false;
      device.reload();
    } catch (e) {
      failure = e;
    } finally {
      busy = false;
    }
  }
  async function revoke() {
    if (busy) return;
    busy = true;
    failure = undefined;
    try {
      await revokeDevice(id);
      revoking = false;
      router.go("/devices");
    } catch (e) {
      failure = e;
    } finally {
      busy = false;
    }
  }

  $effect(() =>
    onAction((a) => {
      if (a === "b") {
        if (router.canGoBack) router.back();
        else router.go("/devices");
        return true;
      }
      if (!summary) return false;
      if (a === "a") openRename();
      else if (a === "x") setupAgain();
      else if (a === "y") openRevoke();
      else return false;
      return true;
    }),
  );
</script>

<div class="page">
  <section>
    {#if device.error && !device.data}
      <h1>Device</h1>
      <ErrorLine error={device.error} />
    {:else if !device.data}
      <h1>Device</h1>
      <p class="loading" aria-busy="true">— — —</p>
    {:else}
      <h1>{name}</h1>
      {#if status}<p class="sub"><span class={status.tone}>{status.text}</span></p>{/if}

      <div class="split">
        <div>
          <h2>Emulators</h2>
          {#if device.data.emulators.length === 0}
            <Empty text="No emulators reported yet." />
          {:else}
            <ul class="rows">
              {#each device.data.emulators as e (e.emulatorId)}
                <li class="em">
                  <span class="name">{emulatorName(e.emulatorId)}</span>
                  <span class="pkg ink2 mono ellipsis" title={e.package}>{e.package}</span>
                  <span class="ink3 mono">{e.version ?? "—"}</span>
                  <span class="ink2 ellipsis">{e.cores?.length ? e.cores.join(", ") : ""}</span>
                </li>
              {/each}
            </ul>
          {/if}

          <h2 class="gap">Configs</h2>
          {#if device.data.configs.length === 0}
            <Empty text="No configs yet." />
          {:else}
            <ul class="rows">
              {#each device.data.configs as c (c.bundleId)}
                <li class="cf">
                  <span class="name">{emulatorName(c.emulatorId)}</span>
                  <span class="ink2 ellipsis">{c.groupName ?? "on its own"}</span>
                  {#if c.openConflict}
                    <span class="fault end">two versions</span>
                  {:else if c.upToDate}
                    <span class="ink3 end">up to date</span>
                  {:else}
                    <span class="wait end">behind</span>
                  {/if}
                </li>
              {/each}
            </ul>
          {/if}
        </div>

        <aside>
          <Facts items={facts} />
          <div class="buttons">
            <Button glyph="a" onclick={openRename}>Rename</Button>
            <Button glyph="x" onclick={setupAgain}>Set up again</Button>
            <Button glyph="y" onclick={openRevoke}>Revoke</Button>
          </div>
        </aside>
      </div>
    {/if}
  </section>

  <Dialog bind:open={renaming} title="Rename device">
    <form onsubmit={(e) => { e.preventDefault(); void save(); }}>
      <Field label="Name" bind:value={newName} />
      {#if failure}<div class="err"><ErrorLine error={failure} /></div>{/if}
      <div class="actions">
        <Button variant="primary" type="submit" disabled={busy || !newName.trim()}>Save</Button>
        <Button onclick={() => (renaming = false)}>Cancel</Button>
      </div>
    </form>
  </Dialog>

  <Dialog bind:open={revoking} title={`Revoke ${revokeName}?`}>
    <p class="note">{revokeName} stops syncing immediately. Its saves stay on the server.</p>
    {#if failure}<div class="err"><ErrorLine error={failure} /></div>{/if}
    <div class="actions">
      <Button variant="primary" disabled={busy} onclick={revoke}>Revoke</Button>
      <Button onclick={() => (revoking = false)} autofocus>Cancel</Button>
    </div>
  </Dialog>

  <HintBar
    hints={[{ glyph: "a", label: "Rename" }, { glyph: "x", label: "Set up again" }, { glyph: "y", label: "Revoke" }, { glyph: "b", label: "Back" }]}
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
    padding: 8px var(--gutter) 24px;
  }
  h1 {
    margin: 0 0 8px;
    font: 600 40px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  h2 {
    margin: 0 0 8px;
    font: 600 24px/1.1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  h2.gap {
    margin-top: 28px;
  }
  .sub {
    margin: 0 0 24px;
    font-size: 15px;
  }
  .split {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 380px;
    gap: 40px;
  }
  aside {
    padding-left: 40px;
    border-left: 1px solid var(--rule);
  }
  .buttons {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
    margin-top: 24px;
  }
  .rows {
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .rows li {
    display: grid;
    align-items: center;
    column-gap: 20px;
    height: 44px;
    padding: 0 16px;
    border-top: 1px solid var(--rule);
  }
  .rows li.em {
    grid-template-columns: 140px minmax(0, 1fr) 140px minmax(0, 1fr);
  }
  .rows li.cf {
    grid-template-columns: 140px minmax(0, 1fr) 140px;
  }
  @media (max-width: 640px) {
    .rows li.em {
      grid-template-columns: 110px 100px minmax(0, 1fr);
    }
    .rows li.em .pkg {
      display: none;
    }
    .rows li.cf {
      grid-template-columns: 110px minmax(0, 1fr) auto;
    }
  }
  .name {
    font: 600 17px "Barlow", system-ui, sans-serif;
  }
  .mono {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
  }
  .end {
    text-align: right;
  }
  .ink2 {
    color: var(--ink2);
  }
  .ink3 {
    color: var(--ink3);
  }
  .wait {
    color: var(--wait);
  }
  .fault {
    color: var(--fault);
  }
  .ellipsis {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .loading {
    color: var(--ink3);
    margin: 0;
    padding: 12px 16px;
  }
  .note {
    margin: 0;
    color: var(--ink2);
    font-size: 15px;
  }
  .err {
    margin-top: 14px;
  }
  .actions {
    display: flex;
    gap: 10px;
    margin-top: 22px;
  }
  @media (max-width: 900px) {
    .split {
      grid-template-columns: minmax(0, 1fr);
      gap: 24px;
    }
    aside {
      padding: 24px 0 0;
      border-left: 0;
      border-top: 1px solid var(--rule);
    }
  }
</style>
