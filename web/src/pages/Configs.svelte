<script lang="ts">
  import { tick } from "svelte";
  import { getConfigFile, getConfigs, getConfigVersions, getDevice, getDiff, getGroups } from "../lib/api/endpoints";
  import { resource } from "../lib/api/resource.svelte";
  import { EMULATOR_IDS, type ApplyMode, type ConfigVersionSummary, type DeviceRef, type FileDiff } from "../lib/api/types";
  import { nameList } from "../lib/deviceStates";
  import { emulatorName } from "../lib/emulators";
  import { onAction } from "../lib/input/actions";
  import { gamepad } from "../lib/input/gamepad.svelte";
  import { router } from "../lib/router.svelte";
  import { relativeTime } from "../lib/time";
  import ApplyDialog from "../lib/ui/ApplyDialog.svelte";
  import Button from "../lib/ui/Button.svelte";
  import ConfigEditor from "../lib/ui/ConfigEditor.svelte";
  import Diff from "../lib/ui/Diff.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import GroupDialog, { type GroupTarget } from "../lib/ui/GroupDialog.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import List from "../lib/ui/List.svelte";
  import Tabs from "../lib/ui/Tabs.svelte";
  import { describeConfigError } from "../lib/ui/errors";

  const configs = resource(getConfigs, ["configs"]);
  // The overview names groups; their bundle (for history and edits) comes from the group list.
  const groupList = resource(getGroups, ["configs"]);

  /** A group or a device on its own: one config with its own history. */
  interface Bundle {
    key: string;
    kind: "group" | "solo";
    groupId: string | null;
    name: string;
    members: DeviceRef[];
    bundleId: string | undefined;
    headVersionId: string | null;
    openConflict: boolean;
  }

  const routeEmu = router.params.emu;
  let picked = $state<string | undefined>(routeEmu && (EMULATOR_IDS as readonly string[]).includes(routeEmu) ? routeEmu : undefined);
  const rowFor = (e: string) => configs.data?.emulators.find((r) => r.emulatorId === e);
  const firstWithConfigs = $derived(EMULATOR_IDS.find((e) => {
    const r = rowFor(e);
    return !!r && r.groups.length + r.solo.length > 0;
  }));
  const emu = $derived(picked ?? firstWithConfigs ?? EMULATOR_IDS[0]!);
  const emuRow = $derived(rowFor(emu));
  const emuName = $derived(emulatorName(emu));
  const tabs = EMULATOR_IDS.map((e) => ({ label: emulatorName(e), path: `/configs/${e}` }));

  const bundles = $derived.by<Bundle[]>(() => {
    const r = emuRow;
    if (!r) return [];
    const out: Bundle[] = [
      ...r.groups.map((g) => ({
        key: "g/" + g.id,
        kind: "group" as const,
        groupId: g.id,
        name: g.name,
        members: g.members,
        bundleId: groupList.data?.items.find((s) => s.id === g.id)?.bundleId,
        headVersionId: g.headVersionId,
        openConflict: g.openConflict,
      })),
      ...r.solo.map((s) => ({
        key: "s/" + s.device.id,
        kind: "solo" as const,
        groupId: null,
        name: s.device.name,
        members: [s.device],
        bundleId: s.bundleId,
        headVersionId: s.headVersionId,
        openConflict: s.openConflict,
      })),
    ];
    // Problem rows pin to the top; the sort is stable, so groups stay before devices on their own.
    return out.sort((a, b) => Number(b.openConflict) - Number(a.openConflict));
  });

  let selectedKey = $state<string | undefined>();
  const selected = $derived(bundles.find((b) => b.key === selectedKey) ?? bundles[0]);

  // ---- History of the shown config ----
  let versions = $state<{ bundleId: string; list: ConfigVersionSummary[] } | undefined>();
  let versionsError = $state<unknown>(undefined);
  let versionsSeq = 0;
  $effect(() => {
    const id = selected?.bundleId;
    void configs.data; // the overview reloads on every config change; the history follows it
    if (!id) return;
    const mine = ++versionsSeq;
    getConfigVersions(id).then(
      (list) => {
        if (mine !== versionsSeq) return;
        versions = { bundleId: id, list };
        versionsError = undefined;
      },
      (e: unknown) => {
        if (mine === versionsSeq) versionsError = e;
      },
    );
  });
  const history = $derived(versions && versions.bundleId === selected?.bundleId ? versions.list : undefined);

  type Line = { kind: "bundle"; b: Bundle } | { kind: "version"; b: Bundle; v: ConfigVersionSummary };
  const lines = $derived.by<Line[]>(() => {
    const out: Line[] = [];
    for (const b of bundles) {
      out.push({ kind: "bundle", b });
      if (b.key === selected?.key && history) for (const v of history) out.push({ kind: "version", b, v });
    }
    return out;
  });

  // ---- What is compared ----
  /** The version shown (the "to" side); the config's current version until one is chosen with A. */
  let toId = $state<string | undefined>();
  const to = $derived(history ? (history.find((v) => v.id === toId) ?? history.find((v) => v.id === selected?.headVersionId) ?? history[0]) : undefined);
  const before = $derived(history && to ? history[history.indexOf(to) + 1] : undefined);
  const isHead = $derived(!!to && to.id === selected?.headVersionId);

  /** "v:<id>" compares with a version, "d:<deviceId>" with what that device has now; unset means the version before. */
  let compareChoice = $state<string | undefined>();
  const compareKey = $derived(compareChoice ?? (before ? "v:" + before.id : ""));

  const label = (v: ConfigVersionSummary) => `v${v.seq}`;
  function sourceText(v: ConfigVersionSummary): string {
    switch (v.source) {
      case "WebEdit":
        return "edited in browser";
      case "Copy":
        return "copied from another device";
      case "Join":
        return "joined group";
      default:
        return v.deviceName ?? "a device";
    }
  }

  const compareOptions = $derived.by(() => {
    const opts: { value: string; label: string }[] = [];
    if (!before && !compareChoice) opts.push({ value: "", label: "Nothing earlier" });
    for (const v of history ?? []) if (v !== to) opts.push({ value: "v:" + v.id, label: `${label(v)} · ${sourceText(v)}` });
    for (const m of selected?.members ?? []) opts.push({ value: "d:" + m.id, label: `${m.name}'s current files` });
    return opts;
  });

  /** The version each device last took, by "<emulator>/<device>"; null when it has taken none. */
  let applied = $state<Record<string, string | null>>({});
  let appliedError = $state<unknown>(undefined);
  $effect(() => {
    const key = compareKey;
    const e = emu;
    void configs.data;
    if (!key.startsWith("d:")) return;
    const id = key.slice(2);
    appliedError = undefined;
    getDevice(id).then(
      (d) => {
        applied[`${e}/${id}`] = d.configs.find((c) => c.emulatorId === e)?.appliedVersionId ?? null;
      },
      (err: unknown) => {
        if (compareKey === key) appliedError = err;
      },
    );
  });

  const compareDevice = $derived(compareKey.startsWith("d:") ? selected?.members.find((m) => m.id === compareKey.slice(2)) : undefined);
  type Request = { a: string; b: string; device: string; from: string } | { none: string };
  const request = $derived.by<Request | undefined>(() => {
    if (!to) return undefined;
    if (compareKey.startsWith("v:")) {
      const from = history?.find((v) => v.id === compareKey.slice(2));
      return { a: compareKey.slice(2), b: to.id, device: "", from: from ? label(from) : "" };
    }
    if (compareDevice) {
      const a = applied[`${emu}/${compareDevice.id}`];
      if (a === undefined) return undefined;
      if (a === null) return { none: `${compareDevice.name} hasn't taken a version of this config yet.` };
      return { a, b: to.id, device: compareDevice.id, from: `${compareDevice.name}'s current files` };
    }
    // The first version: the file list still comes from a diff of the version with itself.
    return { a: to.id, b: to.id, device: "", from: "" };
  });
  const requestKey = $derived(request && "a" in request ? `${request.a}|${request.b}|${request.device}` : "");

  let diffState = $state<{ key: string; files: FileDiff[] } | undefined>();
  let diffError = $state<unknown>(undefined);
  let diffSeq = 0;
  $effect(() => {
    const key = requestKey;
    if (!key) return;
    const [a, b, device] = key.split("|") as [string, string, string];
    const mine = ++diffSeq;
    diffError = undefined;
    getDiff(a, b, device || undefined).then(
      (r) => {
        if (mine === diffSeq) diffState = { key, files: r.files };
      },
      (e: unknown) => {
        if (mine === diffSeq) diffError = e;
      },
    );
  });
  const files = $derived(diffState && diffState.key === requestKey ? diffState.files : undefined);
  const changed = $derived((files ?? []).filter((f) => f.status !== "Same"));
  let shownPath = $state("");
  const fileIndex = $derived(Math.max(0, changed.findIndex((f) => f.path === shownPath)));
  const shownFile = $derived(changed[fileIndex]);

  // ---- Focus: the left pane (configs and history) or the diff ----
  let index = $state(0);
  let pane = $state<"left" | "diff">("left");
  let left: HTMLDivElement | undefined = $state();
  let scroller: HTMLDivElement | undefined = $state();
  const focus = $derived(lines[index]);

  function toDiff(): boolean {
    if (!files) return false;
    pane = "diff";
    scroller?.focus({ preventScroll: true });
    return true;
  }
  function toLeft() {
    pane = "left";
    void tick().then(() => left?.querySelector<HTMLElement>('[aria-current="true"]')?.focus({ preventScroll: true }));
  }

  function resetCompare() {
    toId = undefined;
    compareChoice = undefined;
    shownPath = "";
  }
  function choose(b: Bundle) {
    selectedKey = b.key;
    resetCompare();
  }
  function switchEmulator(path: string) {
    picked = path.slice("/configs/".length);
    selectedKey = undefined;
    resetCompare();
    index = 0;
    pane = "left";
  }

  function activate(l: Line) {
    if (l.kind === "bundle") {
      if (l.b.key === selected?.key) openGroup(l.b);
      else choose(l.b);
    } else {
      toId = l.v.id;
      compareChoice = undefined;
      shownPath = "";
    }
  }

  // ---- Dialogs: their targets are frozen when they open ----
  let message = $state("");

  let editTarget = $state<{ bundleId: string; baseVersionId: string; label: string; device: DeviceRef; files: string[]; path: string; appliesTo: string } | undefined>();
  let editing = $state(false);
  const editable = $derived(isHead && !selected?.openConflict && !!selected?.bundleId && !!files && (selected?.members.length ?? 0) > 0);
  function openEdit() {
    const b = selected;
    if (!editable || !b?.bundleId || !to || !files) return;
    const paths = files.filter((f) => f.status !== "Removed").map((f) => f.path);
    editTarget = {
      bundleId: b.bundleId,
      baseVersionId: to.id,
      label: label(to),
      device: b.members[0]!,
      files: paths,
      path: shownFile?.path ?? paths[0] ?? "",
      appliesTo: nameList(b.members.map((m) => m.name)),
    };
    message = "";
    editing = true;
  }

  let applyTarget = $state<{ versionId: string; label: string; groupName: string | null; ownerName: string | null; rollback: string[]; linkTargets: DeviceRef[]; copyTargets: DeviceRef[] } | undefined>();
  let applying = $state(false);
  function openApply() {
    const b = selected;
    const r = emuRow;
    if (!b || !to || !r) return;
    const inThis = (d: DeviceRef) => b.members.some((m) => m.id === d.id);
    const everyone = [...r.groups.flatMap((g) => g.members), ...r.solo.map((s) => s.device)];
    applyTarget = {
      versionId: to.id,
      label: label(to),
      groupName: b.kind === "group" ? b.name : null,
      ownerName: b.kind === "solo" ? b.name : null,
      // Linked makes the group carry this version, so an older one changes everyone already following it.
      rollback: isHead ? [] : b.members.map((m) => m.name),
      linkTargets: everyone.filter((d) => !inThis(d)),
      copyTargets: r.solo.map((s) => s.device).filter((d) => !inThis(d)),
    };
    message = "";
    applying = true;
  }
  function onApplied(name: string, mode: ApplyMode) {
    const group = applyTarget?.groupName;
    message = mode === "Linked"
      ? `${name} now follows ${group ?? "the new group"}. ${emuName} must be closed there; until then it waits.`
      : `Copied ${applyTarget?.label ?? "it"} to ${name}. ${emuName} must be closed there; until then it waits.`;
    reloadAll();
  }

  let groupKey = $state<GroupTarget | undefined>();
  let grouping = $state(false);
  function openGroup(b: Bundle) {
    groupKey = b.kind === "group" ? { kind: "group", id: b.groupId!, name: b.name, members: b.members } : { kind: "solo", device: b.members[0]! };
    message = "";
    grouping = true;
  }
  /** The frozen target, with the live member list while the group still exists. */
  const groupTarget = $derived.by<GroupTarget | undefined>(() => {
    const g = groupKey;
    if (g?.kind !== "group") return g;
    const live = emuRow?.groups.find((x) => x.id === g.id);
    return live ? { kind: "group", id: g.id, name: live.name, members: live.members } : g;
  });

  function reloadAll() {
    configs.reload();
    groupList.reload();
  }

  let downloadError = $state<unknown>(undefined);
  async function download() {
    const path = shownFile?.path ?? files?.find((f) => f.status !== "Removed")?.path;
    if (!to || !path) return;
    downloadError = undefined;
    try {
      const text = await getConfigFile(to.id, path, selected?.members[0]?.id);
      const url = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
      const a = document.createElement("a");
      a.href = url;
      a.download = path.split("/").pop() ?? path;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      downloadError = e;
    }
  }

  $effect(() =>
    onAction((a) => {
      if (pane === "diff") {
        if (a === "up" || a === "down") {
          scroller?.scrollBy?.({ top: a === "down" ? 120 : -120 });
          return true;
        }
        if (a === "left") {
          if (fileIndex > 0) shownPath = changed[fileIndex - 1]!.path;
          else toLeft();
          return true;
        }
        if (a === "right") {
          if (fileIndex < changed.length - 1) shownPath = changed[fileIndex + 1]!.path;
          return true;
        }
        if (a === "b") {
          toLeft();
          return true;
        }
        if (a === "a") return true;
      } else {
        if (a === "right") return toDiff();
        if (a === "b") {
          if (router.canGoBack) router.back();
          else router.go("/");
          return true;
        }
      }
      if (a === "x" && to) {
        openApply();
        return true;
      }
      if (a === "y" && editable) {
        openEdit();
        return true;
      }
      return false;
    }),
  );

  const aLabel = $derived(
    pane === "diff" ? undefined : focus?.kind === "version" ? "Compare" : focus && focus.b.key === selected?.key ? (focus.b.kind === "group" ? "Change group" : "Make a group") : "Show history",
  );
  const hints = $derived([
    ...(aLabel ? [{ glyph: "a" as const, label: aLabel }] : []),
    ...(editable ? [{ glyph: "y" as const, label: "Edit" }] : []),
    ...(to ? [{ glyph: "x" as const, label: "Apply" }] : []),
    { glyph: "b" as const, label: "Back" },
  ]);
  const COLUMNS = "56px minmax(0,1fr) auto";
</script>

<div class="page">
  <section>
    <div class="tabs">
      <Tabs level={2} items={tabs} active={`/configs/${emu}`} onSelect={switchEmulator} />
    </div>

    {#if configs.error && !configs.data}
      <ErrorLine error={configs.error} />
    {:else if !configs.data}
      <p class="loading" aria-busy="true">— — —</p>
    {:else if bundles.length === 0}
      <Empty text={`No ${emuName} configs yet.`} />
    {:else}
      <div class="split">
        <div bind:this={left} class="left" onfocusin={() => (pane = "left")}>
          <List
            rows={lines}
            columns={COLUMNS}
            size="detail"
            bind:index
            active={pane === "left"}
            onOpen={activate}
            key={(l) => (l.kind === "bundle" ? l.b.key : l.b.key + "/" + l.v.id)}
          >
            {#snippet row(l: Line)}
              {#if l.kind === "bundle"}
                {@const b = l.b}
                <span class="bundle">
                  <span class="name" class:fault={b.openConflict}>{b.name}</span>
                  {#if b.openConflict}
                    <span class="fault">Two versions — choose</span>
                  {:else if b.kind === "group"}
                    <span class="ink2">{nameList(b.members.map((m) => m.name))}</span>
                    <span class="ink3">Folder paths stay per device.</span>
                  {:else}
                    <span class="ink3">On its own: backed up, never changed by other devices.</span>
                  {/if}
                </span>
              {:else}
                <span class="strong nested">{label(l.v)}</span>
                <span>{sourceText(l.v)}{#if l.v.id === l.b.headVersionId}<span class="ink3">, current</span>{/if}{#if l.v.pinned}<span class="ink3">, pinned</span>{/if}</span>
                <span class="end ink2">{relativeTime(l.v.receivedUtc)}</span>
              {/if}
            {/snippet}
          </List>
          {#if versionsError}<div class="line"><ErrorLine error={versionsError} /></div>{/if}
        </div>

        <div class="right">
          {#if !to}
            {#if !versionsError}<p class="loading" aria-busy="true">— — —</p>{/if}
          {:else}
            <div class="head">
              <h2>
                {request && "from" in request && request.from ? `${request.from} → ${label(to)}` : label(to)}
                {#if files && request && "from" in request && request.from}<small>{changed.length === 0 ? "no changes" : `${changed.length} ${changed.length === 1 ? "file" : "files"} changed`}</small>{/if}
              </h2>
              <label class="compare">
                <span>Compare with</span>
                <select bind:value={() => compareKey, (v) => { compareChoice = v || undefined; shownPath = ""; }}>
                  {#each compareOptions as o (o.value)}<option value={o.value}>{o.label}</option>{/each}
                </select>
              </label>
            </div>

            <div
              bind:this={scroller}
              class="scroller"
              class:active={pane === "diff"}
              tabindex="-1"
              role="region"
              aria-label="Changes"
              onfocus={() => (pane = "diff")}
            >
              {#if request && "none" in request}
                <Empty text={request.none} />
              {:else if diffError || appliedError}
                <ErrorLine error={diffError ?? appliedError} />
              {:else if !files}
                <p class="loading" aria-busy="true">— — —</p>
              {:else if request && "from" in request && !request.from}
                <Empty text={`${label(to)} is the first version of this config.`} />
              {:else if changed.length === 0}
                <Empty text="No changes between these versions." />
              {:else}
                <Diff files={changed} bind:selected={shownPath} />
              {/if}
            </div>

            <div class="buttons">
              {#if editable}<Button glyph="y" onclick={openEdit}>Edit {label(to)}</Button>{/if}
              <Button glyph="x" onclick={openApply}>Apply to a device…</Button>
              <Button onclick={download} disabled={!files}>Download</Button>
              {#if selected}
                <Button onclick={() => openGroup(selected)}>{selected.kind === "group" ? "Change group…" : "Make a group…"}</Button>
              {/if}
            </div>
            {#if !isHead}<p class="note">Only the current version can be edited. Apply this one to go back to it.</p>{/if}
            {#if message}<p class="note ink">{message}</p>{/if}
            {#if downloadError}<p class="note fault" role="alert">{describeConfigError(downloadError)}</p>{/if}
          {/if}
        </div>
      </div>
    {/if}
  </section>

  {#if editTarget}
    <ConfigEditor
      bind:open={editing}
      {...editTarget}
      emulatorName={emuName}
      onsaved={() => {
        message = `Saved. It goes to ${editTarget?.appliesTo ?? "the device"}; ${emuName} must be closed there, until then it waits.`;
        resetCompare();
        reloadAll();
      }}
      onreload={() => {
        resetCompare();
        reloadAll();
      }}
    />
  {/if}

  {#if applyTarget}
    <ApplyDialog bind:open={applying} emulatorId={emu} emulatorName={emuName} {...applyTarget} onapplied={onApplied} />
  {/if}

  {#if groupTarget}
    <GroupDialog
      bind:open={grouping}
      emulatorId={emu}
      emulatorName={emuName}
      target={groupTarget}
      groups={(emuRow?.groups ?? []).map((g) => ({ id: g.id, name: g.name }))}
      solo={(emuRow?.solo ?? []).map((s) => s.device)}
      onchanged={(m) => {
        message = m;
        reloadAll();
      }}
    />
  {/if}

  <HintBar status={gamepad.connected ? "L2 / R2 switch emulator" : "{ and } switch emulator"} {hints} />
</div>

<style>
  .page {
    display: flex;
    flex-direction: column;
    min-height: calc(100vh - 72px);
  }
  section {
    flex: 1;
    padding: 2px 40px 24px;
  }
  .tabs {
    margin: 0 0 18px;
  }
  .split {
    display: grid;
    grid-template-columns: 540px minmax(0, 1fr);
    column-gap: 48px;
  }
  .right {
    min-width: 0;
    padding-left: 40px;
    border-left: 1px solid var(--rule2);
  }
  .head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 24px;
    margin-bottom: 12px;
  }
  h2 {
    margin: 0;
    font: 600 24px/1.1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
    white-space: nowrap;
  }
  h2 small {
    margin-left: 12px;
    font: 500 15px "Barlow", system-ui, sans-serif;
    letter-spacing: 0;
    color: var(--ink3);
  }
  .compare {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--ink2);
    font-size: 14px;
  }
  .compare select {
    height: 36px;
    max-width: 260px;
    padding: 0 8px;
    border: 1.5px solid var(--rule2);
    border-radius: 3px;
    background: transparent;
    color: var(--ink);
    font: 500 14px "Barlow", system-ui, sans-serif;
  }
  .compare select:hover {
    border-color: var(--ink3);
  }
  .compare select:focus-visible {
    outline: 2px solid var(--ink);
    outline-offset: 3px;
  }
  .compare option {
    background: var(--bg);
    color: var(--ink);
  }
  .scroller {
    max-height: calc(100vh - 72px - 56px - 230px);
    min-height: 120px;
    overflow-y: auto;
    border-radius: 3px;
  }
  .scroller:focus {
    outline: none;
  }
  .scroller.active {
    outline: 2px solid var(--ink);
    outline-offset: 4px;
  }
  .buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 20px;
  }
  .bundle {
    grid-column: 1 / -1;
    display: flex;
    align-items: baseline;
    gap: 14px;
  }
  .bundle > span {
    min-width: 0;
    flex: none;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .bundle > span:last-child {
    flex: 0 1 auto;
  }
  .nested {
    padding-left: 12px;
  }
  .note {
    margin: 16px 0 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .note.ink {
    color: var(--ink);
    font-size: 15px;
  }
  .note.fault {
    color: var(--fault);
  }
  .line {
    margin-top: 12px;
  }
  .loading {
    margin: 0;
    color: var(--ink3);
  }
</style>
