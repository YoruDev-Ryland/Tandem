<script lang="ts">
  import { getConfigs, getDevice, getSetup, keepOwn } from "../lib/api/endpoints";
  import { resource } from "../lib/api/resource.svelte";
  import type { DeviceRef, SetupStep, SetupStepKind } from "../lib/api/types";
  import { emulatorName } from "../lib/emulators";
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import { relativeTime } from "../lib/time";
  import ApplyDialog from "../lib/ui/ApplyDialog.svelte";
  import Button from "../lib/ui/Button.svelte";
  import Check from "../lib/ui/Check.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import Ticks from "../lib/ui/Ticks.svelte";

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

  /** "Install eden" reads "Install Eden". */
  const label = (s: SetupStep) => s.label.replace(new RegExp(`\\b${s.emulatorId}\\b`), emulatorName(s.emulatorId));

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

  interface ApplyTarget {
    emulatorId: string;
    emulatorName: string;
    versionId: string;
    label: string;
    groupName: string | null;
    ownerName: string | null;
    rollback: string[];
    linkTargets: DeviceRef[];
    copyTargets: DeviceRef[];
  }
  /** Frozen when the dialog opens, so a live reload cannot change what Apply acts on. */
  let applyTarget = $state<ApplyTarget>();
  let applying = $state(false);

  /** The config this device can take: a group's, else another device's own. */
  function sourceFor(emulatorId: string): ApplyTarget | undefined {
    const row = configs.data?.emulators.find((e) => e.emulatorId === emulatorId);
    if (!row) return undefined;
    const me: DeviceRef = { id, name };
    const group = row.groups.find((g) => g.headVersionId);
    const solo = row.solo.find((s) => s.headVersionId && s.device.id !== id);
    const mine = row.solo.some((s) => s.device.id === id);
    const base = { emulatorId, emulatorName: emulatorName(emulatorId), rollback: [] as string[] };
    if (group) {
      const member = group.members.some((m) => m.id === id);
      return { ...base, versionId: group.headVersionId!, label: `${group.name} config`, groupName: group.name, ownerName: null, linkTargets: member ? [] : [me], copyTargets: mine ? [me] : [] };
    }
    if (solo) {
      return { ...base, versionId: solo.headVersionId!, label: `${solo.device.name}'s config`, groupName: null, ownerName: solo.device.name, linkTargets: [me], copyTargets: mine ? [me] : [] };
    }
    return undefined;
  }
  const canApply = (emulatorId: string) => sourceFor(emulatorId) !== undefined;
  function openApply(emulatorId: string) {
    const t = sourceFor(emulatorId);
    if (!t) return;
    applyTarget = t;
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
                  {#each rows as s (k.kind + "/" + s.emulatorId + "/" + s.label)}
                    <li>
                      <Check done={s.done} />
                      <span class:ink2={s.done}>{label(s)}</span>
                      {#if k.kind === "ChooseConfig" && !s.done}
                        <span class="acts">
                          <Button disabled={busy} onclick={() => keep(s.emulatorId)}>Keep this device's own</Button>
                          <Button disabled={!canApply(s.emulatorId)} onclick={() => openApply(s.emulatorId)}>Use a config…</Button>
                        </span>
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
