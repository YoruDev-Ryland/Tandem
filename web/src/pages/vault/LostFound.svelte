<script lang="ts">
  import { deleteLost, getLost, restoreLost } from "../../lib/api/endpoints";
  import { gamePath, lostDownloadUrl } from "../../lib/api/paths";
  import { resource } from "../../lib/api/resource.svelte";
  import type { LostItemRow } from "../../lib/api/types";
  import { emulatorName } from "../../lib/emulators";
  import { formatSize, formatWhen } from "../../lib/format";
  import { router } from "../../lib/router.svelte";
  import { relativeTime } from "../../lib/time";
  import Button from "../../lib/ui/Button.svelte";
  import Dialog from "../../lib/ui/Dialog.svelte";
  import Empty from "../../lib/ui/Empty.svelte";
  import ErrorLine from "../../lib/ui/ErrorLine.svelte";
  import Facts from "../../lib/ui/Facts.svelte";
  import List from "../../lib/ui/List.svelte";

  /** How many items are listed (null while loading), for the page heading and hints. */
  let { count = $bindable(null) }: { count?: number | null } = $props();

  const lost = resource(getLost, ["lost"]);
  const rows = $derived(Array.isArray(lost.data) ? lost.data : []);
  $effect(() => {
    count = lost.data ? rows.length : null;
  });

  const COLUMNS = "minmax(0,1.4fr) 160px 150px 100px minmax(0,1fr)";
  const HEADER = ["Game", "Emulator", "What", "Size", "Lost"];
  const PHONE = "minmax(0,1fr) 120px";

  const title = (l: LostItemRow) => l.displayName || l.gameKey;
  const what = (l: LostItemRow) => (l.kind === "Save" ? "Save" : l.slot ? `State · slot ${l.slot}` : "State");
  const REASONS: Record<string, string> = { game_deleted: "game deleted" };
  const why = (l: LostItemRow) => REASONS[l.reason] ?? l.reason.replace(/_/g, " ");

  let index = $state(0);
  let status = $state("");
  /** Set after a restore that opened a conflict: where the owner settles it. */
  let conflictHref = $state<string | undefined>();

  /** The item chosen when the dialog opened; a live reload must not change what the buttons act on. */
  let target = $state<LostItemRow | undefined>();
  let open = $state(false);
  let busy = $state(false);
  let error = $state<unknown>(undefined);

  function openItem(l: LostItemRow) {
    target = l;
    error = undefined;
    open = true;
  }

  async function restore() {
    if (!target || busy) return;
    const t = target;
    busy = true;
    error = undefined;
    try {
      const r = await restoreLost(t.id);
      open = false;
      conflictHref = undefined;
      if (r.status === "Conflict") {
        status = `${title(t)} already has a newer save, so this one is back beside it as a conflict. Choose which to keep.`;
        conflictHref = gamePath(t.emulatorId, t.gameKey) + "/conflict";
      } else if (r.status === "Unchanged") {
        status = `${title(t)} already has this save. Nothing changed.`;
      } else {
        status = `${title(t)} is back. Every device with the game gets it next time Tandem syncs there.`;
      }
      lost.reload();
    } catch (e) {
      error = e;
    } finally {
      busy = false;
    }
  }

  // ---- Delete for good ----
  let confirming = $state(false);
  let deleteBusy = $state(false);
  let deleteError = $state<unknown>(undefined);

  function openDelete() {
    deleteError = undefined;
    open = false;
    confirming = true;
  }

  async function confirmDelete() {
    if (!target || deleteBusy) return;
    const t = target;
    deleteBusy = true;
    deleteError = undefined;
    try {
      await deleteLost(t.id);
      confirming = false;
      conflictHref = undefined;
      status = `Deleted ${title(t)} for good.`;
      lost.reload();
    } catch (e) {
      deleteError = e;
    } finally {
      deleteBusy = false;
    }
  }
</script>

{#if lost.error && !lost.data}
  <ErrorLine error={lost.error} />
{:else if !lost.data}
  <p class="loading" aria-busy="true">— — —</p>
{:else if rows.length === 0}
  <Empty text="Saves from games you delete land here, so nothing is lost by accident." />
{:else}
  <List {rows} columns={COLUMNS} header={HEADER} phoneColumns={PHONE} phoneHide={[1, 3, 4]} minWidth="0px" bind:index onOpen={openItem} key={(l) => l.id}>
    {#snippet row(l: LostItemRow, _focused: boolean)}
      <span class="name ellipsis" title={l.gameKey}>{title(l)}</span>
      <span class="ink2 sm-hide">{emulatorName(l.emulatorId)}</span>
      <span>{what(l)}</span>
      <span class="mono ink2 sm-hide">{formatSize(l.totalSize)}</span>
      <span class="ink2 sm-hide">{relativeTime(l.lostUtc)}, {why(l)}</span>
    {/snippet}
  </List>
{/if}

{#if status}
  <p class="ink2 line">
    {status}
    {#if conflictHref}
      {@const href = conflictHref}
      <a class="link" {href} onclick={(e) => { e.preventDefault(); router.go(href); }}>Open the conflict</a>
    {/if}
  </p>
{/if}

<Dialog bind:open locked={busy} title={target ? title(target) : "Lost & found"}>
  {#if target}
    <Facts
      items={[
        ["Emulator", emulatorName(target.emulatorId)],
        ["What", what(target)],
        ["Size", formatSize(target.totalSize), true],
        ["Lost", `${formatWhen(target.lostUtc)}, ${why(target)}`],
      ]}
    />
    <p class="note">Restoring puts it back as the game's newest save, and every device with the game gets it. If the game has a newer save by now, the two show up as a conflict for you to choose.</p>
    {#if error}<div class="line"><ErrorLine error={error} /></div>{/if}
    <div class="actions">
      <Button variant="primary" onclick={restore} disabled={busy}>Restore</Button>
      <a class="btn" href={lostDownloadUrl(target.id)} download>Download</a>
      <Button onclick={openDelete} disabled={busy}>Delete for good…</Button>
    </div>
  {/if}
</Dialog>

<Dialog bind:open={confirming} locked={deleteBusy} title={target ? `Delete ${title(target)} for good?` : "Delete for good?"}>
  <p class="note first">Its files leave the server and can't be brought back.</p>
  {#if deleteError}<div class="line"><ErrorLine error={deleteError} /></div>{/if}
  <div class="actions">
    <Button variant="primary" onclick={confirmDelete} disabled={deleteBusy}>Delete for good</Button>
    <Button onclick={() => (confirming = false)} autofocus>Cancel</Button>
  </div>
</Dialog>

<style>
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
  .mono {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
  }
  .line {
    margin: 16px 0 0;
    font-size: 15px;
  }
  .link {
    display: inline-block;
    margin-left: 8px;
    color: var(--fault);
    font-weight: 600;
  }
  .note {
    margin: 18px 0 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .note.first {
    margin-top: 0;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 20px;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    gap: 8px;
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
  .btn:focus-visible {
    outline: 2px solid var(--ink);
    outline-offset: 3px;
  }
</style>
