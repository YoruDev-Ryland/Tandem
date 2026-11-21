<script lang="ts">
  import { deleteGame, getDevices, getGame, pin, unpin } from "../lib/api/endpoints";
  import { downloadUrl, gamePath } from "../lib/api/paths";
  import { resource } from "../lib/api/resource.svelte";
  import { ApiError } from "../lib/api/http";
  import type { ItemKind, SaveItemDetail, VersionSummary } from "../lib/api/types";
  import { deviceLine, matrixColumns } from "../lib/deviceStates";
  import { emulatorName } from "../lib/emulators";
  import { formatSize, formatWhen } from "../lib/format";
  import { onAction } from "../lib/input/actions";
  import { gamepad } from "../lib/input/gamepad.svelte";
  import { router } from "../lib/router.svelte";
  import { bareKey } from "../lib/systems";
  import { captureDrop, collectDrop, fromPicker, UnreadableUpload, uploadFiles, type Dropped, type UploadFile } from "../lib/upload";
  import Button from "../lib/ui/Button.svelte";
  import Dialog from "../lib/ui/Dialog.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import Facts from "../lib/ui/Facts.svelte";
  import Glyph from "../lib/ui/Glyph.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import List from "../lib/ui/List.svelte";
  import RestoreDialog from "../lib/ui/RestoreDialog.svelte";
  import Presence, { presenceWidth } from "../lib/ui/Presence.svelte";
  import SystemTag from "../lib/ui/SystemTag.svelte";
  import { flash } from "../lib/ui/flash.svelte";

  const { emu, key } = router.params as { emu: string; key: string };

  const game = resource(() => getGame(emu, key), ["saves", "conflicts", "devices"]);
  const devices = resource(getDevices, ["devices"]);

  type Line =
    | { kind: "version"; item: SaveItemDetail; v: VersionSummary }
    | { kind: "slot"; item: SaveItemDetail };

  /** Slots whose versions are shown under the slot row. */
  let open = $state<Record<string, boolean>>({});

  const lines = $derived.by<Line[]>(() => {
    const items = game.data?.items ?? [];
    const out: Line[] = [];
    for (const item of items.filter((i) => i.kind === "Save")) {
      for (const v of item.versions) out.push({ kind: "version", item, v });
    }
    for (const item of items.filter((i) => i.kind === "State")) {
      out.push({ kind: "slot", item });
      if (open[item.itemId]) for (const v of item.versions) out.push({ kind: "version", item, v });
    }
    return out;
  });

  const head = (item: SaveItemDetail) => item.versions.find((v) => v.isHead) ?? item.versions[0];

  let index = $state(0);
  const focus = $derived(lines[index]);
  const focused = $derived.by(() => {
    if (!focus) return undefined;
    const v = focus.kind === "version" ? focus.v : head(focus.item);
    return v ? { item: focus.item, v } : undefined;
  });

  const row = $derived(game.data?.row);
  const title = $derived(row?.displayName ?? bareKey(emu, key));
  const named = $derived(!!row?.displayName);
  /** The device lane: one mark column per device, filled in on the current save's row. */
  const lane = $derived(row ? matrixColumns([row]) : []);
  const saveCore = $derived(game.data?.items.find((i) => i.kind === "Save")?.core ?? game.data?.items[0]?.core ?? null);
  const label = (v: VersionSummary) => `v${v.seq}`;
  const versionTitle = (f: { item: SaveItemDetail; v: VersionSummary }) =>
    `${f.item.kind === "State" ? `Slot ${f.item.slot} ` : ""}${label(f.v)} from ${f.v.deviceName ?? "an unknown device"}`;

  /** The device a restore is recorded against: one the game is installed on, else any of the account's. */
  const restoreDevice = $derived.by(() => {
    const ids = new Set(row?.installedOn ?? []);
    const list = devices.data ?? [];
    return (list.find((d) => ids.has(d.id)) ?? list[0])?.id;
  });

  const COLUMNS = $derived(`64px minmax(0,1fr) 150px 84px ${presenceWidth(lane.length)} 120px`);
  const HEADER = $derived(["Version", "From", "Saved", "Size", laneHead, ""]);

  /** "512 KB, unchanged since v2" or "516 KB, 4 KB more than v2": the size against the versions before it. */
  function sizeTrend(item: SaveItemDetail, v: VersionSummary): string {
    const older = item.versions.filter((x) => x.seq < v.seq).sort((a, b) => b.seq - a.seq);
    const size = formatSize(v.totalSize);
    if (older.length === 0) return size;
    let since = v;
    for (const o of older) {
      if (o.totalSize !== v.totalSize) break;
      since = o;
    }
    if (since !== v) return `${size}, unchanged since ${label(since)}`;
    const prev = older[0]!;
    const d = v.totalSize - prev.totalSize;
    return `${size}, ${formatSize(Math.abs(d))} ${d > 0 ? "more" : "less"} than ${label(prev)}`;
  }

  function notes(v: VersionSummary): string {
    return [v.pinned && "pinned", v.isHead && "current", v.variant].filter(Boolean).join(", ");
  }

  function activate(l: Line) {
    if (l.kind === "slot") open[l.item.itemId] = !open[l.item.itemId];
    else openRestore();
  }

  /** The version chosen when the dialog opened; a live reload must not change what Restore acts on. */
  let target = $state<{ itemId: string; versionId: string; label: string } | undefined>();
  let restoring = $state(false);
  function openRestore() {
    const f = focused;
    if (!f) return;
    target = { itemId: f.item.itemId, versionId: f.v.id, label: label(f.v) };
    restoring = true;
  }
  let problem = $state("");
  /** Files waiting for the user to confirm they are not the same shape as the focused version. */
  let pending = $state<{ files: UploadFile[]; item: SaveItemDetail | undefined; count: number } | undefined>();
  let confirming = $state(false);
  let sent = $state("");
  let failure = $state<unknown>(undefined);
  let uploaded = $state("");
  let busy = $state(false);

  async function togglePin() {
    const f = focused;
    if (!f || busy) return;
    busy = true;
    failure = undefined;
    sent = "";
    try {
      await (f.v.pinned ? unpin : pin)(f.item.itemId, f.v.id);
      game.reload();
    } catch (e) {
      failure = e;
    } finally {
      busy = false;
    }
  }

  // Delete the game's saves from the server.
  let deleting = $state(false);
  let deleteBusy = $state(false);
  let deleteError = $state<unknown>(undefined);
  /** Frozen when the dialog opens. */
  let deleteTarget = $state<{ title: string; versions: number; states: number; pinned: number } | undefined>();
  function openDelete() {
    const items = game.data?.items ?? [];
    deleteTarget = {
      title,
      versions: items.filter((i) => i.kind === "Save").reduce((n, i) => n + i.versions.length, 0),
      states: items.filter((i) => i.kind === "State").length,
      pinned: items.reduce((n, i) => n + i.versions.filter((v) => v.pinned).length, 0),
    };
    deleteError = undefined;
    deleting = true;
  }
  async function confirmDelete() {
    if (deleteBusy || !deleteTarget) return;
    deleteBusy = true;
    deleteError = undefined;
    try {
      await deleteGame(emu, key);
      deleting = false;
      flash.say(`Deleted ${deleteTarget.title} from the server.`);
      router.go("/games");
    } catch (e) {
      deleteError = e;
    } finally {
      deleteBusy = false;
    }
  }

  let download: HTMLAnchorElement | undefined = $state();
  let picker = $state<HTMLInputElement>()!;
  let folderPicker = $state<HTMLInputElement>()!;

  function offer(files: UploadFile[]) {
    if (files.length === 0 || busy) return;
    problem = "";
    const item = focus?.item;
    const count = focused?.v.fileCount;
    if (count !== undefined && count !== files.length) {
      pending = { files, item, count };
      confirming = true;
    } else void send(files, item);
  }

  async function send(files: UploadFile[], item: SaveItemDetail | undefined) {
    busy = true;
    failure = undefined;
    sent = "";
    uploaded = "";
    // The focused item decides where the file goes; a game with no save yet gets its first Save.
    const kind: ItemKind = item?.kind ?? "Save";
    try {
      const r = await uploadFiles(emu, key, kind, item?.slot ?? "", item?.core ?? "", files);
      uploaded = r.status === "Unchanged" ? "That is already the newest save." : `Uploaded ${files.map((f) => f.path).join(", ")}.`;
      game.reload();
    } catch (e) {
      failure = e;
    } finally {
      busy = false;
    }
  }

  function picked(input: HTMLInputElement) {
    const files = fromPicker(input.files ?? []);
    input.value = "";
    offer(files);
  }

  async function drop(e: DragEvent) {
    if (!e.dataTransfer?.types.includes("Files")) return;
    e.preventDefault();
    const dropped: Dropped = captureDrop(e.dataTransfer);
    try {
      offer(await collectDrop(dropped));
    } catch (err) {
      problem = err instanceof UnreadableUpload ? err.message : "Couldn't read what you dropped. Try Upload from this PC.";
    }
  }
  function over(e: DragEvent) {
    if (e.dataTransfer?.types.includes("Files")) e.preventDefault();
  }

  $effect(() =>
    onAction((a) => {
      if (a === "b") {
        // Back to wherever the user came from (Home or Games); a page opened directly goes to Games.
        if (router.canGoBack) router.back();
        else router.go("/games");
        return true;
      }
      if (a === "x" && focused) {
        void togglePin();
        return true;
      }
      if (a === "y" && focused) {
        download?.click();
        return true;
      }
      return false;
    }),
  );

  const conflictHref = $derived(gamePath(emu, key) + "/conflict");
  const isConflict = $derived(failure instanceof ApiError && failure.code === "conflict_open");
</script>

<svelte:window ondragover={over} ondrop={drop} />

{#snippet laneHead()}<Presence columns={lane} head />{/snippet}

<div class="page">
  <section>
    {#if game.error && !game.data}
      <ErrorLine error={game.error} />
    {:else if !row}
      <h1 class="mono-title">{bareKey(emu, key)}</h1>
      <p class="loading" aria-busy="true">— — —</p>
    {:else}
      {#if named}
        <h1>{title}</h1>
      {:else}
        <h1 class="mono-title">{title}<small>unknown title</small></h1>
      {/if}
      <p class="sub">
        <span class="tag"><SystemTag emulatorId={emu} gameKey={key} core={saveCore} /></span>
        <span class="emu">{emulatorName(emu)}</span>
        <span class="devices">
          {#each deviceLine(row) as c, i (i)}
            {#if i > 0}<span class="ink2">{" · "}</span>{/if}<span class={c.tone || "ink2"}>{c.text}</span>
          {/each}
        </span>
      </p>

      <div class="split">
        <div>
          <h2>Versions</h2>
          {#if lines.length === 0}
            <Empty text="No saves for this game yet. Upload one from this PC." />
          {:else}
            <List
              rows={lines}
              columns={COLUMNS}
              header={HEADER}
              size="detail"
              minWidth="0px"
              phoneColumns={`56px minmax(0,1fr) ${presenceWidth(lane.length)}`}
              phoneHide={[1, 3, 5]}
              bind:index
              onOpen={activate}
              key={(l) => (l.kind === "slot" ? "slot/" + l.item.itemId : l.item.itemId + "/" + l.v.id)}
            >
              {#snippet row(l: Line)}
                {#if l.kind === "slot"}
                  {@const h = head(l.item)}
                  <span class="name">Slot {l.item.slot}</span>
                  <span class="sm-hide">{h?.deviceName ?? ""}</span>
                  <span class="ink2">{h ? formatWhen(h.receivedUtc) : ""}</span>
                  <span class="mono ink2 sm-hide">{l.item.versions.length} {l.item.versions.length === 1 ? "version" : "versions"}</span>
                  <span></span>
                  <span class="end ink3 sm-hide">{open[l.item.itemId] ? "hide" : "show"}</span>
                {:else}
                  <span class="name" class:nested={l.item.kind === "State"}>{label(l.v)}</span>
                  <span class="sm-hide">{l.v.deviceName ?? ""}{#if l.v.emulatorVersion}<span class="mono ink3 ver">{l.v.emulatorVersion}</span>{/if}</span>
                  <span class="ink2">{formatWhen(l.v.receivedUtc)}</span>
                  <span class="mono ink2 sm-hide">{formatSize(l.v.totalSize)}</span>
                  {#if l.item.kind === "Save" && l.v.isHead && game.data}<Presence columns={lane} row={game.data.row} />{:else}<span></span>{/if}
                  <span class="end ink2 sm-hide">{notes(l.v)}{#if l.v.conflictReason === "FirstSync"}<span class="ink3">{notes(l.v) ? ", " : ""}first sync</span>{/if}</span>
                {/if}
              {/snippet}
            </List>
          {/if}
        </div>

        <aside>
          {#if focused}
            <h2>{versionTitle(focused)}</h2>
            <Facts
              items={[
                ["Saved", formatWhen(focused.v.receivedUtc)],
                ["Size", `${sizeTrend(focused.item, focused.v)} · ${focused.v.fileCount} ${focused.v.fileCount === 1 ? "file" : "files"}`, true],
                ...(focused.v.emulatorVersion ? [[emulatorName(emu), focused.v.emulatorVersion, true] as [string, string, boolean]] : []),
                ["Fingerprint", focused.v.manifestHash.slice(0, 8), true],
              ]}
            />
            <div class="buttons">
              <Button variant="primary" glyph="a" onclick={openRestore}>Restore this version</Button>
              <a class="btn" href={downloadUrl(focused.v.id)} download bind:this={download}>
                {#if gamepad.connected}<Glyph action="y" />{:else}<kbd aria-hidden="true">Y</kbd>{/if}Download
              </a>
              <Button glyph="x" onclick={togglePin} disabled={busy}>{focused.v.pinned ? "Unpin" : "Pin"}</Button>
            </div>
            <p class="note">Restoring makes {label(focused.v)} the current save again: every device with the game gets it. The save that is current now stays in history. Where {emulatorName(emu)} is open, it waits until it closes.</p>
          {/if}
          {#if sent}<p class="ink2 line">{sent}</p>{/if}
          {#if uploaded}<p class="ink2 line">{uploaded}</p>{/if}
          {#if failure}
            <div class="line">
              <ErrorLine error={failure} />
              {#if isConflict}
                <a class="link" href={conflictHref} onclick={(e) => { e.preventDefault(); router.go(conflictHref); }}>Open the conflict</a>
              {/if}
            </div>
          {/if}
          {#if problem}<p class="fault line" role="alert">{problem}</p>{/if}
          <div class="upload">
            <p class="note">Upload a save file or its folder, or drop it anywhere on this page.</p>
            <div class="buttons row">
              <Button onclick={() => picker.click()} disabled={busy}>Upload from this PC</Button>
              <Button onclick={() => folderPicker.click()} disabled={busy}>Upload a folder</Button>
            </div>
            <input bind:this={picker} type="file" multiple hidden aria-label="Save file" onchange={() => picked(picker)} />
            <input bind:this={folderPicker} type="file" hidden aria-label="Save folder" onchange={() => picked(folderPicker)} {...{ webkitdirectory: true }} />
          </div>
          {#if lines.length > 0}
            <div class="danger">
              <Button onclick={openDelete}>Delete from server…</Button>
            </div>
          {/if}
        </aside>
      </div>
    {/if}
  </section>

  <Dialog bind:open={confirming} title="Upload these files?">
    {#if pending}
      <p class="note">This save has {pending.count} {pending.count === 1 ? "file" : "files"}; you're uploading {pending.files.length}. Upload anyway?</p>
      <div class="buttons row">
        <Button variant="primary" onclick={() => { const p = pending!; confirming = false; void send(p.files, p.item); }}>Upload anyway</Button>
        <Button onclick={() => (confirming = false)}>Cancel</Button>
      </div>
    {/if}
  </Dialog>

  {#if target}
    <RestoreDialog
      bind:open={restoring}
      itemId={target.itemId}
      versionId={target.versionId}
      label={target.label}
      emulatorName={emulatorName(emu)}
      deviceId={restoreDevice}
      onrestored={() => {
        failure = undefined;
        uploaded = "";
        const l = target?.label ?? "It";
        sent = `${l} is the current save again. Every device with the game gets it; where ${emulatorName(emu)} is open, it waits until it closes.`;
        flash.say(`Restored ${l} · every device gets it`);
        game.reload();
      }}
    />
  {/if}

  <Dialog bind:open={deleting} locked={deleteBusy} title="Delete these saves from the server?">
    {#if deleteTarget}
      <p class="note first">
        This removes {deleteTarget.title}'s {deleteTarget.versions} save {deleteTarget.versions === 1 ? "version" : "versions"}{#if deleteTarget.states > 0}{" "}and {deleteTarget.states} save {deleteTarget.states === 1 ? "state" : "states"}{/if} from the server, with their history{#if deleteTarget.pinned > 0}, including {deleteTarget.pinned} pinned {deleteTarget.pinned === 1 ? "version" : "versions"}{/if}. It can't be undone.
      </p>
      <p class="note">Devices keep their files. Devices that still have this save upload it again within about 15 minutes, as a new save without this history.</p>
      {#if deleteError}<div class="line"><ErrorLine error={deleteError} /></div>{/if}
      <div class="buttons row dialog">
        <Button variant="primary" onclick={confirmDelete} disabled={deleteBusy}>Delete from server</Button>
        <Button onclick={() => (deleting = false)} autofocus>Cancel</Button>
      </div>
    {/if}
  </Dialog>

  <HintBar
    status="Upload a save from this PC: drop a file anywhere"
    hints={[
      { glyph: "a", label: "Restore" },
      { glyph: "y", label: "Download" },
      { glyph: "x", label: focused?.v.pinned ? "Unpin" : "Pin" },
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
    padding: 8px var(--gutter) 24px;
  }
  h1 {
    margin: 0 0 8px;
    font: 600 48px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.02em;
    color: var(--ink);
  }
  h2 {
    margin: 0 0 14px;
    font: 600 24px/1.1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  h1.mono-title {
    font: 400 30px/1.1 "JetBrains Mono", ui-monospace, monospace;
    letter-spacing: 0;
    color: var(--ink2);
    overflow-wrap: anywhere;
  }
  h1 small {
    margin-left: 14px;
    font: 500 15px "Barlow", system-ui, sans-serif;
    color: var(--ink3);
  }
  .sub {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px 14px;
    margin: 4px 0 28px;
    color: var(--ink2);
    font-size: 15px;
  }
  .tag {
    display: inline-flex;
  }
  .emu {
    color: var(--ink);
    font-weight: 500;
  }
  .ver {
    margin-left: 10px;
  }
  kbd {
    display: inline-flex;
    align-items: center;
    height: 18px;
    padding: 0 5px;
    border: 1.5px solid var(--rule2);
    border-radius: 3px;
    color: var(--ink2);
    font: 600 11px/1 "Barlow", system-ui, sans-serif;
  }
  .danger {
    margin-top: 28px;
    padding-top: 20px;
    border-top: 1px solid var(--rule);
  }
  .note.first {
    margin-top: 0;
    color: var(--ink);
  }
  .buttons.dialog {
    margin-top: 22px;
  }
  .devices .ink2 {
    color: var(--ink2);
  }
  .devices .wait {
    color: var(--wait);
  }
  .devices .fault {
    color: var(--fault);
  }
  .devices .ink3 {
    color: var(--ink3);
  }
  .split {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 460px;
    gap: 40px;
  }
  aside {
    padding-left: 40px;
    border-left: 1px solid var(--rule);
  }
  /* One horizontal action row: A Restore, Y Download, X Pin. */
  .buttons {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    margin-top: 22px;
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
  .note {
    margin: 20px 0 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .line {
    margin: 16px 0 0;
    font-size: 15px;
  }
  .link {
    display: inline-block;
    margin-top: 6px;
    color: var(--fault);
    font-weight: 600;
  }
  .upload {
    margin-top: 28px;
    padding-top: 20px;
    border-top: 1px solid var(--rule);
  }
  .upload .note {
    margin: 0 0 12px;
  }
  .buttons.row {
    flex-direction: row;
    margin-top: 0;
  }
  .fault {
    color: var(--fault);
  }
  .loading {
    margin: 0;
    color: var(--ink3);
  }
  .nested {
    padding-left: 12px;
  }
  .mono {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
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
