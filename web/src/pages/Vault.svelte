<script lang="ts">
  import { getDevices, getVault, sendVault, vaultUpload } from "../lib/api/endpoints";
  import { resource } from "../lib/api/resource.svelte";
  import { EMULATOR_IDS, type VaultItemDto } from "../lib/api/types";
  import { hashFiles, uploadMissingBlobs } from "../lib/blobs";
  import { emulatorName } from "../lib/emulators";
  import { formatSize } from "../lib/format";
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import { fromPicker, type UploadFile } from "../lib/upload";
  import Button from "../lib/ui/Button.svelte";
  import Dialog from "../lib/ui/Dialog.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import Field from "../lib/ui/Field.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import List from "../lib/ui/List.svelte";
  import Tabs from "../lib/ui/Tabs.svelte";
  import LostFound from "./vault/LostFound.svelte";

  // Two sections: the system files sent to devices, and Lost & found (the saves of deleted games).
  const SECTIONS = [
    { id: "files", label: "System files", path: "/vault" },
    { id: "lost", label: "Lost & found", path: "/vault?s=lost" },
  ];
  let section = $state(new URLSearchParams(router.search).get("s") === "lost" ? "lost" : "files");
  const current = $derived(SECTIONS.find((s) => s.id === section) ?? SECTIONS[0]);
  function select(path: string) {
    section = new URL(path, location.origin).searchParams.get("s") === "lost" ? "lost" : "files";
    router.go(path, { replace: true });
  }
  let lostCount = $state<number | null>(null);

  const vault = resource(getVault, ["vault", "devices"]);
  const devices = resource(getDevices, ["devices"]);

  const CATEGORIES = [
    { value: "bios", label: "BIOS" },
    { value: "keys", label: "Keys" },
    { value: "firmware", label: "Firmware" },
    { value: "sysdata", label: "Azahar sysdata" },
    { value: "system", label: "RetroArch system" },
  ];
  const categoryLabel = (c: string) => CATEGORIES.find((x) => x.value === c)?.label ?? c;
  const rank = (c: string) => {
    const i = CATEGORIES.findIndex((x) => x.value === c);
    return i < 0 ? CATEGORIES.length : i;
  };

  const items = $derived(Array.isArray(vault.data) ? vault.data : []);
  const rows = $derived(
    [...items].sort(
      (a, b) =>
        rank(a.category) - rank(b.category) ||
        (a.emulatorId ?? "").localeCompare(b.emulatorId ?? "") ||
        a.name.localeCompare(b.name),
    ),
  );
  const group = (v: VaultItemDto) => v.category + "/" + (v.emulatorId ?? "");
  const groupLabel = (v: VaultItemDto) => {
    const c = categoryLabel(v.category);
    const e = v.emulatorId ? emulatorName(v.emulatorId) : "";
    return e && !c.includes(e) ? `${c} · ${e}` : c;
  };

  const COLUMNS = "220px minmax(0,1fr) 100px minmax(0,1.2fr)";
  const HEADER = ["Kind", "Name", "Size", "On devices"];

  let index = $state(0);
  let status = $state("");
  let failure = $state<unknown>(undefined);

  // ---- Send ----
  /** The item chosen when the dialog opened; a live reload must not change what Send acts on. */
  let sendTarget = $state<{ id: string; name: string } | undefined>();
  let sending = $state(false);
  let sendBusy = $state(false);
  let sendError = $state<unknown>(undefined);

  function openSend(v: VaultItemDto | undefined = rows[index]) {
    if (!v) return;
    sendTarget = { id: v.id, name: v.name };
    sendError = undefined;
    sending = true;
  }
  /** Read live, so a device that just got it shows as having it. */
  const holder = (deviceId: string) => items.find((v) => v.id === sendTarget?.id)?.holders.find((h) => h.deviceId === deviceId);

  async function send(deviceId: string, deviceName: string) {
    if (!sendTarget || sendBusy) return;
    sendBusy = true;
    sendError = undefined;
    try {
      await sendVault(sendTarget.id, deviceId);
      sending = false;
      failure = undefined;
      status = `Sent to ${deviceName}. It goes in next time Tandem syncs there.`;
      vault.reload();
    } catch (e) {
      sendError = e;
    } finally {
      sendBusy = false;
    }
  }

  // ---- Upload ----
  let uploading = $state(false);
  let category = $state("bios");
  let emulator = $state("");
  let name = $state("");
  let picked = $state<UploadFile[]>([]);
  let progress = $state("");
  let upError = $state<unknown>(undefined);
  let upBusy = $state(false);
  /** Set when the browser can't hash files (crypto.subtle exists only over HTTPS or on localhost). */
  let insecure = $state(false);
  let filePicker = $state<HTMLInputElement>()!;
  let folderPicker = $state<HTMLInputElement>()!;

  function openUpload() {
    category = "bios";
    emulator = "";
    name = "";
    picked = [];
    progress = "";
    upError = undefined;
    insecure = false;
    uploading = true;
  }

  function pick(input: HTMLInputElement) {
    const list = [...(input.files ?? [])];
    input.value = "";
    if (list.length === 0) return;
    const rel = (list[0] as File & { webkitRelativePath?: string }).webkitRelativePath;
    picked = fromPicker(list, true);
    // A folder is named for itself; a single file for its own name.
    name = rel ? rel.split("/")[0] : list.length === 1 ? list[0].name : "";
  }

  const emulatorId = $derived(emulator || null);
  const replaces = $derived(items.find((v) => v.category === category && v.emulatorId === emulatorId && v.name === name.trim()));
  const pickedSize = $derived(picked.reduce((n, f) => n + f.file.size, 0));
  const canUpload = $derived(picked.length > 0 && name.trim() !== "" && !upBusy);

  async function upload() {
    if (!canUpload) return;
    insecure = !globalThis.crypto?.subtle;
    if (insecure) return;
    upBusy = true;
    upError = undefined;
    const files = picked;
    const finalName = name.trim();
    try {
      progress = `Hashing 0 of ${files.length}…`;
      const manifest = await hashFiles(files, (n, t) => (progress = `Hashing ${n} of ${t}…`));
      progress = "Uploading…";
      await uploadMissingBlobs(manifest, files, (n, m) => (progress = `Uploading ${n} of ${m}…`));
      const r = await vaultUpload({ category, emulatorId, name: finalName, manifest });
      uploading = false;
      failure = undefined;
      status = r.unchanged ? `${finalName} is already up to date.` : `Uploaded ${finalName}.`;
      vault.reload();
    } catch (e) {
      upError = e;
    } finally {
      upBusy = false;
      progress = "";
    }
  }

  $effect(() =>
    onAction((a) => {
      if (a === "x" && section === "files") {
        openUpload();
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
    <h1>Vault{#if section === "files" && vault.data}<small>{rows.length}</small>{:else if section === "lost" && lostCount !== null}<small>{lostCount}</small>{/if}</h1>
    <div class="tabs">
      <Tabs level={2} items={SECTIONS.map((s) => ({ label: s.label, path: s.path }))} active={current.path} onSelect={select} />
    </div>

    {#if section === "lost"}
      <LostFound bind:count={lostCount} />
    {:else if vault.error && !vault.data}
      <ErrorLine error={vault.error} />
    {:else if !vault.data}
      <p class="loading" aria-busy="true">— — —</p>
    {:else if rows.length === 0}
      <Empty text="Nothing in the vault yet. Upload BIOS, keys or firmware from this PC." />
    {:else}
      <List {rows} columns={COLUMNS} header={HEADER} bind:index onOpen={(v) => openSend(v)} key={(v) => v.id}>
        {#snippet row(v: VaultItemDto, _focused: boolean)}
          {@const i = rows.indexOf(v)}
          <span class="ink2">{i === 0 || group(rows[i - 1]) !== group(v) ? groupLabel(v) : ""}</span>
          <span class="name ellipsis" title={v.name}>{v.name}</span>
          <span class="mono ink2">{formatSize(v.totalSize)}</span>
          <span class="holders">
            {#each v.holders as h, j (h.deviceId)}{#if j > 0}<span class="ink2">;&nbsp;</span>{/if}{#if h.current}<span>{h.deviceName}</span>{:else}<span class="ink2">{h.deviceName}, older</span>{/if}{/each}
          </span>
        {/snippet}
      </List>
    {/if}

    {#if section === "files"}
      {#if status}<p class="ink2 line">{status}</p>{/if}
      {#if failure}<div class="line"><ErrorLine error={failure} /></div>{/if}
      <div class="actions">
        <Button glyph="x" onclick={openUpload}>Upload from this PC…</Button>
      </div>
    {/if}
  </section>

  <Dialog bind:open={sending} title={sendTarget ? `Send ${sendTarget.name}` : "Send"}>
    {#if (devices.data ?? []).length === 0}
      <Empty text="No devices yet. Pair a handheld first." />
    {:else}
      <ul class="devices">
        {#each devices.data ?? [] as d (d.id)}
          {@const has = holder(d.id)?.current === true}
          <li>
            <button type="button" disabled={has || sendBusy} onclick={() => send(d.id, d.name)}>
              <span>{d.name}</span>
              <span class="ink2">{has ? "has it" : holder(d.id) ? "has an older one" : ""}</span>
            </button>
          </li>
        {/each}
      </ul>
      <p class="note">It goes in next time Tandem syncs on that device.</p>
    {/if}
    {#if sendError}<ErrorLine error={sendError} />{/if}
    <div class="actions"><Button onclick={() => (sending = false)}>Cancel</Button></div>
  </Dialog>

  <Dialog bind:open={uploading} title="Upload to the vault">
    <form onsubmit={(e) => { e.preventDefault(); void upload(); }}>
      <div class="fields">
        <Field label="Kind" options={CATEGORIES} bind:value={category} disabled={upBusy} />
        <Field
          label="Emulator (optional)"
          options={[{ value: "", label: "Any" }, ...EMULATOR_IDS.map((id) => ({ value: id, label: emulatorName(id) }))]}
          bind:value={emulator}
          disabled={upBusy}
        />
        <Field label="Name" bind:value={name} maxlength={200} disabled={upBusy} />
      </div>
      <div class="actions pick">
        <Button onclick={() => filePicker.click()} disabled={upBusy}>Choose files</Button>
        <Button onclick={() => folderPicker.click()} disabled={upBusy}>Choose a folder</Button>
      </div>
      <input bind:this={filePicker} type="file" multiple hidden aria-label="Files" onchange={() => pick(filePicker)} />
      <input bind:this={folderPicker} type="file" hidden aria-label="Folder" onchange={() => pick(folderPicker)} {...{ webkitdirectory: true }} />
      {#if picked.length > 0}
        <p class="note">{picked.length} {picked.length === 1 ? "file" : "files"}, {formatSize(pickedSize)}</p>
      {/if}
      {#if replaces}<p class="note">This replaces {replaces.name}. The old version stays in history.</p>{/if}
      {#if progress}<p class="note" aria-live="polite">{progress}</p>{/if}
      {#if insecure}
        <p class="fault" role="alert">Vault uploads need the web UI over HTTPS (or on this machine as localhost). Saves upload fine either way.</p>
      {:else if upError}<ErrorLine error={upError} />{/if}
      <div class="actions">
        <Button variant="primary" type="submit" disabled={!canUpload}>Upload</Button>
        <Button onclick={() => (uploading = false)}>Cancel</Button>
      </div>
    </form>
  </Dialog>

  <HintBar
    hints={section === "lost"
      ? [...(lostCount ? [{ glyph: "a" as const, label: "Restore or download…" }] : []), { glyph: "b", label: "Back" }]
      : [
          ...(rows.length > 0 ? [{ glyph: "a" as const, label: "Send to a device…" }] : []),
          { glyph: "x", label: "Upload…" },
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
    margin: 0 0 18px;
    font: 600 48px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.02em;
    color: var(--ink);
  }
  .tabs {
    margin-bottom: 22px;
  }
  h1 small {
    margin-left: 14px;
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
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
  .mono {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
  }
  .holders {
    min-width: 0;
  }
  .line {
    margin: 16px 0 0;
    font-size: 15px;
  }
  .actions {
    display: flex;
    gap: 10px;
    margin-top: 20px;
  }
  .actions.pick {
    margin-top: 16px;
  }
  .fields {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .note {
    margin: 14px 0 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .fault {
    margin: 14px 0 0;
    color: var(--fault);
    font-size: 15px;
  }
  .devices {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .devices button {
    display: flex;
    justify-content: space-between;
    align-items: center;
    width: 100%;
    height: 44px;
    padding: 0 14px;
    border: 1.5px solid var(--rule2);
    border-radius: 3px;
    background: transparent;
    color: var(--ink);
    font: 600 15px "Barlow", system-ui, sans-serif;
    cursor: pointer;
  }
  .devices button:hover:not(:disabled) {
    border-color: var(--ink3);
  }
  .devices button:focus-visible {
    outline: 2px solid var(--ink);
    outline-offset: 3px;
  }
  .devices button:disabled {
    cursor: default;
    color: var(--ink3);
  }
  .devices .ink2 {
    font-weight: 500;
    font-size: 14px;
  }
</style>
