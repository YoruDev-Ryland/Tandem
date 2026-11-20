<script lang="ts">
  import type { ComponentProps } from "svelte";
  import { getConfigs, getDevice, getSetup, keepOwn } from "../lib/api/endpoints";
  import { resource } from "../lib/api/resource.svelte";
  import type { DeviceRef, SetupStep, SetupStepKind } from "../lib/api/types";
  import { emulatorName } from "../lib/emulators";
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import { relativeTime } from "../lib/time";
  import ApplyDialog from "../lib/ui/ApplyDialog.svelte";
  import Button from "../lib/ui/Button.svelte";
  import Dialog from "../lib/ui/Dialog.svelte";
  import Check from "../lib/ui/Check.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import Field from "../lib/ui/Field.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import Ticks from "../lib/ui/Ticks.svelte";

  const uid = $props.id();
  const { id } = router.params as { id: string };
  const topics = ["devices", "configs", "saves"] as const;
  const checklist = resource(() => getSetup(id), [...topics]);
  const device = resource(() => getDevice(id), [...topics]);
  const configs = resource(getConfigs, ["configs"]);

  const steps = $derived(checklist.data?.steps ?? []);
  const done = $derived(steps.filter((s) => s.done).length);
  const summary = $derived(device.data?.summary);
  const name = $derived(summary?.name ?? "this device");

  // A device that has reported nothing yet has no history to rebuild from.
  const hasHistory = $derived.by(() => {
    const d = device.data;
    return !!d && (d.storageBytes > 0 || d.installs.some((i) => i.games > 0) || d.configs.length > 0);
  });
  const title = $derived(`Set up ${name}${hasHistory ? " again" : ""}`);

  const COLUMNS: { label: string; heading: string; kinds: { kind: SetupStepKind; heading: string }[] }[] = [
    { label: "Install", heading: "", kinds: [{ kind: "InstallEmulator", heading: "Emulators" }, { kind: "InstallCores", heading: "Cores" }, { kind: "GrantFolders", heading: "Folders" }] },
    { label: "On the device", heading: "", kinds: [{ kind: "PlaceVault", heading: "Vault" }, { kind: "RestoreSaves", heading: "Restore saves" }, { kind: "ChooseConfig", heading: "Configs" }] },
  ];
  const groupOf = (kind: SetupStepKind) => steps.filter((s) => s.kind === kind);

  /** Built from the kind and the emulator's name; the server's detail text rides alongside. */
  function label(s: SetupStep): string {
    const n = emulatorName(s.emulatorId);
    switch (s.kind) {
      case "InstallEmulator": return `Install ${n}`;
      case "InstallCores": return s.done ? `Cores for ${n}` : `Install ${n} cores`;
      case "GrantFolders": return `Grant folder access for ${n}`;
      case "PlaceVault": return `Place ${n} system files`;
      case "RestoreSaves": return `Restore ${n} saves`;
      case "ChooseConfig": return `Choose a ${n} config`;
    }
  }

  const nextConfig = $derived(steps.find((s) => s.kind === "ChooseConfig" && !s.done));

  let failure = $state<unknown>(undefined);
  let busy = $state(false);

  async function keep(emulatorId: string) {
    if (busy) return;
    busy = true;
    failure = undefined;
    try {
      await keepOwn(id, emulatorId);
      checklist.reload();
    } catch (e) {
      failure = e;
    } finally {
      busy = false;
    }
  }

  interface ConfigSource {
    key: string;
    versionId: string;
    /** "Handhelds" or "Ayn". */
    name: string;
    groupName: string | null;
    ownerName: string | null;
  }
  interface Picking {
    emulatorId: string;
    emulatorName: string;
    /** This device, frozen when the dialog opens. */
    me: DeviceRef;
    mine: boolean;
    sources: ConfigSource[];
    chosen: string;
  }
  /** Frozen when the dialog opens, so a live reload cannot change what is applied. */
  let picking = $state<Picking>();
  let picked = $state(false);
  let applyTarget = $state<(Omit<ComponentProps<typeof ApplyDialog>, "open" | "onapplied">) | undefined>();
  let applying = $state(false);

  /** Configs this device could take: groups it is not in, and other devices' own, for this emulator. */
  function sourcesFor(emulatorId: string): { sources: ConfigSource[]; mine: boolean } {
    const row = configs.data?.emulators.find((e) => e.emulatorId === emulatorId);
    if (!row) return { sources: [], mine: false };
    const sources: ConfigSource[] = [
      ...row.groups
        .filter((g) => g.headVersionId && !g.members.some((m) => m.id === id))
        .map((g) => ({ key: `g/${g.id}`, versionId: g.headVersionId!, name: g.name, groupName: g.name, ownerName: null })),
      ...row.solo
        .filter((x) => x.headVersionId && x.device.id !== id)
        .map((x) => ({ key: `d/${x.device.id}`, versionId: x.headVersionId!, name: x.device.name, groupName: null, ownerName: x.device.name })),
    ];
    return { sources, mine: row.solo.some((x) => x.device.id === id) };
  }
  const canApply = (emulatorId: string) => sourcesFor(emulatorId).sources.length > 0;
  function openApply(emulatorId: string) {
    const { sources, mine } = sourcesFor(emulatorId);
    if (sources.length === 0) return;
    picking = { emulatorId, emulatorName: emulatorName(emulatorId), me: { id, name }, mine, sources, chosen: sources[0]!.key };
    picked = true;
  }
  const pickTitle = $derived(picking ? `Use a ${picking.emulatorName} config on ${picking.me.name}` : "");
  function proceed() {
    const p = picking;
    const src = p?.sources.find((x) => x.key === p.chosen);
    if (!p || !src) return;
    applyTarget = {
      emulatorId: p.emulatorId,
      emulatorName: p.emulatorName,
      versionId: src.versionId,
      label: `${src.name} · latest version`,
      title: pickTitle,
      groupName: src.groupName,
      ownerName: src.ownerName,
      rollback: [],
      linkTargets: [p.me],
      copyTargets: p.mine ? [p.me] : [],
    };
    picked = false;
    applying = true;
  }

  $effect(() =>
    onAction((a) => {
      if (a === "b") {
        if (router.canGoBack) router.back();
        else router.go(`/devices/${encodeURIComponent(id)}`);
        return true;
      }
      if (nextConfig && a === "x") {
        void keep(nextConfig.emulatorId);
        return true;
      }
      if (nextConfig && a === "y" && canApply(nextConfig.emulatorId)) {
        openApply(nextConfig.emulatorId);
        return true;
      }
      return false;
    }),
  );

  const status = $derived(summary?.lastSeenUtc ? `${name} reported in ${relativeTime(summary.lastSeenUtc)}` : undefined);
  const hints = $derived([
    ...(nextConfig ? [{ glyph: "x" as const, label: "Keep own config" }, { glyph: "y" as const, label: "Use a config" }] : []),
    { glyph: "b" as const, label: "Back" },
  ]);
</script>

<div class="page">
  <section>
    <div class="head">
      <h1>{device.data ? title : "Set up"}</h1>
      {#if checklist.data}<Ticks {done} total={steps.length} />{/if}
    </div>

    {#if checklist.error && !checklist.data}
      <ErrorLine error={checklist.error} />
    {:else if !checklist.data}
      <p class="loading" aria-busy="true">— — —</p>
    {:else}
      <div class="split">
        {#each COLUMNS as col (col.label)}
          <section class="col" aria-label={col.label}>
            {#each col.kinds as k, i (k.kind)}
              {@const rows = groupOf(k.kind)}
              {#if rows.length > 0}
                <h2 class:gap={i > 0}>{k.heading} <small>{rows.filter((r) => r.done).length} of {rows.length}</small></h2>
                <ul>
                  {#each rows as s, n (k.kind + "/" + s.emulatorId + "/" + n)}
                    <li>
                      <Check done={s.done} />
                      <span class:ink2={s.done}>{label(s)}</span>
                      {#if k.kind === "ChooseConfig" && !s.done}
                        <span class="acts">
                          <Button disabled={busy} onclick={() => keep(s.emulatorId)}>Keep this device's own</Button>
                          <Button disabled={!canApply(s.emulatorId)} aria-describedby={canApply(s.emulatorId) ? undefined : `${uid}-${s.emulatorId}`} onclick={() => openApply(s.emulatorId)}>Use a config…</Button>
                        </span>
                        {#if !canApply(s.emulatorId)}
                          <span class="reason" id="{uid}-{s.emulatorId}">No other {emulatorName(s.emulatorId)} config to use yet.</span>
                        {/if}
                      {:else if s.detail}
                        <span class="detail" class:mono={k.kind === "GrantFolders"}>{s.detail}</span>
                      {:else}
                        <span></span>
                      {/if}
                    </li>
                  {/each}
                </ul>
              {/if}
            {/each}
          </section>
        {/each}
      </div>
      {#if failure}<div class="err"><ErrorLine error={failure} /></div>{/if}
      {#if nextConfig}<p class="note">A config chosen here waits until its emulator is closed on {name}.</p>{/if}
    {/if}
  </section>

  <Dialog bind:open={picked} title={pickTitle}>
    {#if picking}
      <form onsubmit={(e) => { e.preventDefault(); proceed(); }}>
        <Field label="Config" options={picking.sources.map((x) => ({ value: x.key, label: `${x.name} · latest version` }))} bind:value={picking.chosen} />
        <div class="actions">
          <Button variant="primary" type="submit">Continue</Button>
          <Button onclick={() => (picked = false)}>Cancel</Button>
        </div>
      </form>
    {/if}
  </Dialog>

  {#if applyTarget}
    <ApplyDialog
      bind:open={applying}
      {...applyTarget}
      onapplied={() => {
        checklist.reload();
        configs.reload();
      }}
    />
  {/if}

  <HintBar {status} {hints} />
</div>

<style>
  .page {
    display: flex;
    flex-direction: column;
    min-height: calc(100vh - 72px);
  }
  .page > section {
    flex: 1;
    padding: 8px 40px 24px;
  }
  .head {
    display: flex;
    justify-content: space-between;
    align-items: flex-end;
    margin-bottom: 28px;
  }
  h1 {
    margin: 0;
    font: 600 40px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  .split {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 40px;
  }
  .col + .col {
    padding-left: 40px;
    border-left: 1px solid var(--rule);
  }
  h2 {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    margin: 0 0 8px;
    font: 600 18px "Barlow Semi Condensed", sans-serif;
    color: var(--ink);
  }
  h2.gap {
    margin-top: 24px;
  }
  h2 small {
    font: 500 13px "Barlow", system-ui, sans-serif;
    color: var(--ink3);
  }
  ul {
    margin: 0;
    padding: 0;
    list-style: none;
  }
  li {
    display: grid;
    grid-template-columns: 22px minmax(0, 1fr) auto;
    align-items: center;
    column-gap: 14px;
    min-height: 44px;
    padding: 0 14px;
    border-top: 1px solid var(--rule);
    color: var(--ink);
  }
  .ink2 {
    color: var(--ink2);
  }
  .detail {
    color: var(--ink3);
    font-size: 14px;
  }
  .detail.mono {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
  }
  .reason {
    grid-column: 2 / -1;
    padding-bottom: 10px;
    color: var(--ink3);
    font-size: 14px;
  }
  .actions {
    display: flex;
    gap: 10px;
    margin-top: 22px;
  }
  .acts {
    display: flex;
    gap: 8px;
  }
  .note {
    margin: 20px 0 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .err {
    margin-top: 16px;
  }
  .loading {
    margin: 0;
    padding: 12px 16px;
    color: var(--ink3);
  }
</style>
