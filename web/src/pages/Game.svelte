<script lang="ts">
  import { getDevices, getGame, pin, unpin } from "../lib/api/endpoints";
  import { downloadUrl, gamePath } from "../lib/api/paths";
  import { resource } from "../lib/api/resource.svelte";
  import { ApiError } from "../lib/api/http";
  import type { ItemKind, SaveItemDetail, VersionSummary } from "../lib/api/types";
  import { deviceStateCopy } from "../lib/deviceStates";
  import { emulatorName } from "../lib/emulators";
  import { formatSize, formatWhen } from "../lib/format";
  import { onAction } from "../lib/input/actions";
  import { gamepad } from "../lib/input/gamepad.svelte";
  import { router } from "../lib/router.svelte";
  import { systemLabel } from "../lib/systems";
  import { uploadFiles } from "../lib/upload";
  import Button from "../lib/ui/Button.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import Facts from "../lib/ui/Facts.svelte";
  import Glyph from "../lib/ui/Glyph.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import List from "../lib/ui/List.svelte";
  import RestoreDialog from "../lib/ui/RestoreDialog.svelte";

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
  const title = $derived(row?.displayName ?? key);
  const label = (v: VersionSummary) => `v${v.seq}`;
  const versionTitle = (f: { item: SaveItemDetail; v: VersionSummary }) =>
    `${f.item.kind === "State" ? `Slot ${f.item.slot} ` : ""}${label(f.v)} from ${f.v.deviceName ?? "an unknown device"}`;

  const installed = $derived.by(() => {
    const ids = new Set(row?.installedOn ?? []);
    return (devices.data ?? []).filter((d) => ids.has(d.id)).map((d) => ({ id: d.id, name: d.name }));
  });

  const COLUMNS = "72px minmax(0,1fr) 160px 90px 130px";
  const HEADER = ["Version", "From", "Saved", "Size", ""];

  function notes(v: VersionSummary): string {
    return [v.pinned && "pinned", v.isHead && "current", v.variant].filter(Boolean).join(", ");
  }

  function activate(l: Line) {
    if (l.kind === "slot") open[l.item.itemId] = !open[l.item.itemId];
    else restoring = true;
  }

  let restoring = $state(false);
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

  let download: HTMLAnchorElement | undefined = $state();
  let picker = $state<HTMLInputElement>()!;

  async function send(files: File[]) {
    if (files.length === 0 || busy) return;
    busy = true;
    failure = undefined;
    sent = "";
    uploaded = "";
    // The focused item decides where the file goes; a game with no save yet gets its first Save.
    const item = focus?.item;
    const kind: ItemKind = item?.kind ?? "Save";
    try {
      const r = await uploadFiles(emu, key, kind, item?.slot ?? "", item?.core ?? "", files);
      uploaded = r.status === "Unchanged" ? "That is already the newest save." : `Uploaded ${files.map((f) => f.name).join(", ")}.`;
      game.reload();
    } catch (e) {
      failure = e;
    } finally {
      busy = false;
    }
  }

  function pick() {
    const files = [...(picker.files ?? [])];
    picker.value = "";
    void send(files);
  }

  function drop(e: DragEvent) {
    if (!e.dataTransfer?.types.includes("Files")) return;
    e.preventDefault();
    void send([...e.dataTransfer.files]);
  }
  function over(e: DragEvent) {
    if (e.dataTransfer?.types.includes("Files")) e.preventDefault();
  }

  $effect(() =>
    onAction((a) => {
      if (a === "b") {
        router.go("/games");
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

<div class="page">
  <section>
    {#if game.error && !game.data}
      <ErrorLine error={game.error} />
    {:else if !row}
      <h1>{key}</h1>
      <p class="loading" aria-busy="true">— — —</p>
    {:else}
      <h1>{title}</h1>
      <p class="sub">
        <span>{systemLabel(emu, key)} · {emulatorName(emu)}</span>
        <span class="devices">
          {#each row.devices as d, i (d.deviceId)}
            {@const c = deviceStateCopy(d, emu)}{#if i > 0}<span class="ink2">;&nbsp;</span>{/if}<span class={c.tone || "ink2"}>{c.text}</span>
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
              bind:index
              onOpen={activate}
              key={(l) => (l.kind === "slot" ? "slot/" + l.item.itemId : l.item.itemId + "/" + l.v.id)}
            >
              {#snippet row(l: Line)}
                {#if l.kind === "slot"}
                  {@const h = head(l.item)}
                  <span class="name">Slot {l.item.slot}</span>
                  <span>{h?.deviceName ?? ""}</span>
                  <span class="ink2">{h ? formatWhen(h.receivedUtc) : ""}</span>
                  <span class="mono ink2">{l.item.versions.length} {l.item.versions.length === 1 ? "version" : "versions"}</span>
                  <span class="end ink3">{open[l.item.itemId] ? "hide" : "show"}</span>
                {:else}
                  <span class="name" class:nested={l.item.kind === "State"}>{label(l.v)}</span>
                  <span>{l.v.deviceName ?? ""}</span>
                  <span class="ink2">{formatWhen(l.v.receivedUtc)}</span>
                  <span class="mono ink2">{formatSize(l.v.totalSize)}</span>
                  <span class="end ink2">{notes(l.v)}{#if l.v.conflictReason === "FirstSync"}<span class="ink3">{notes(l.v) ? ", " : ""}first sync</span>{/if}</span>
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
                ["Size", `${formatSize(focused.v.totalSize)} · ${focused.v.fileCount} ${focused.v.fileCount === 1 ? "file" : "files"}`, true],
                ["Fingerprint", focused.v.manifestHash.slice(0, 8), true],
              ]}
            />
            <div class="buttons">
              <Button variant="primary" glyph="a" onclick={() => (restoring = true)}>Restore to…</Button>
              <a class="btn" href={downloadUrl(focused.v.id)} download bind:this={download}>
                {#if gamepad.connected}<Glyph action="y" />{/if}Download
              </a>
              <Button glyph="x" onclick={togglePin} disabled={busy}>{focused.v.pinned ? "Unpin" : "Pin"}</Button>
            </div>
            <p class="note">Restoring keeps the device's current save as a new version first. If {emulatorName(emu)} is open there, it waits until it closes.</p>
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
          <div class="upload">
            <Button onclick={() => picker.click()} disabled={busy}>Upload from this PC</Button>
            <input bind:this={picker} type="file" multiple hidden aria-label="Save file" onchange={pick} />
          </div>
        </aside>
      </div>
    {/if}
  </section>

  {#if focused}
    <RestoreDialog
      bind:open={restoring}
      itemId={focused.item.itemId}
      versionId={focused.v.id}
      label={label(focused.v)}
      emulatorName={emulatorName(emu)}
      devices={installed}
      onrestored={(name) => {
        failure = undefined;
        uploaded = "";
        sent = `Sent to ${name}. It goes in when ${emulatorName(emu)} is closed there.`;
        game.reload();
      }}
    />
  {/if}

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
    padding: 8px 40px 24px;
  }
  h1 {
    margin: 0 0 8px;
    font: 600 40px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  h2 {
    margin: 0 0 14px;
    font: 600 24px/1.1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  .sub {
    margin: 0 0 24px;
    color: var(--ink2);
    font-size: 15px;
  }
  .sub > span + span::before {
    content: " · ";
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
</style>
