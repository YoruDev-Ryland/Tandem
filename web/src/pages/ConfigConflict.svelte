<script lang="ts">
  import { getConfigs, getConfigVersions, getDiff, getGroups, resolveConfigConflict } from "../lib/api/endpoints";
  import { resource } from "../lib/api/resource.svelte";
  import type { ConfigVersionSummary, DeviceRef, FileDiff } from "../lib/api/types";
  import { nameList } from "../lib/deviceStates";
  import { emulatorName } from "../lib/emulators";
  import { formatSize, formatWhen } from "../lib/format";
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import Button from "../lib/ui/Button.svelte";
  import Diff from "../lib/ui/Diff.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import Facts from "../lib/ui/Facts.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import { flash } from "../lib/ui/flash.svelte";

  const { emu, bundle: bundleId } = router.params as { emu: string; bundle: string };
  const back = `/configs/${encodeURIComponent(emu)}`;
  const emuName = emulatorName(emu);

  const configs = resource(getConfigs, ["configs"]);
  const groups = resource(getGroups, ["configs"]);
  const versions = resource(() => getConfigVersions(bundleId), ["configs"]);

  /** The group or the device on its own that owns this config. */
  const owner = $derived.by<{ name: string; kind: "group" | "solo"; members: DeviceRef[]; headVersionId: string | null; openConflict: boolean } | undefined>(() => {
    const row = configs.data?.emulators.find((e) => e.emulatorId === emu);
    if (!row) return undefined;
    const solo = row.solo.find((s) => s.bundleId === bundleId);
    if (solo) return { name: solo.device.name, kind: "solo", members: [solo.device], headVersionId: solo.headVersionId, openConflict: solo.openConflict };
    const groupId = groups.data?.items.find((g) => g.bundleId === bundleId)?.id;
    const g = row.groups.find((x) => x.id === groupId);
    return g ? { name: g.name, kind: "group", members: g.members, headVersionId: g.headVersionId, openConflict: g.openConflict } : undefined;
  });

  /** The current version first, then each version that arrived on top of something else and waits for a choice. */
  const sides = $derived.by<ConfigVersionSummary[]>(() => {
    const list = versions.data ?? [];
    const head = list.find((v) => v.id === owner?.headVersionId);
    const open = list.filter((v) => v.conflictReason !== null && !v.resolved && v.id !== head?.id);
    return head && open.length > 0 ? [head, ...open] : open.length > 1 ? open : [];
  });

  const who = (v: ConfigVersionSummary) =>
    v.source === "WebEdit" ? "The browser edit" : v.source === "Copy" ? "The copied config" : (v.deviceName ?? "A device");
  const whose = (v: ConfigVersionSummary) =>
    v.source === "WebEdit" ? "the browser edit" : v.source === "Copy" ? "the copied config" : `${v.deviceName ?? "the device"}'s`;
  const label = (v: ConfigVersionSummary) => `v${v.seq}`;

  let col = $state(0);
  const focus = $derived(sides[Math.min(col, Math.max(0, sides.length - 1))]);
  /** The diff compares the current version with the focused other one (or the first other, while the current is focused). */
  const other = $derived(focus && sides[0] && focus.id !== sides[0].id ? focus : sides[1]);

  let diff = $state<{ key: string; files: FileDiff[] } | undefined>();
  let diffError = $state<unknown>(undefined);
  let shownPath = $state("");
  const diffKey = $derived(sides[0] && other ? `${sides[0].id}|${other.id}` : "");
  $effect(() => {
    const k = diffKey;
    if (!k) return;
    const [a, b] = k.split("|") as [string, string];
    let live = true;
    diffError = undefined;
    getDiff(a, b).then(
      (r) => {
        if (live) diff = { key: k, files: r.files };
      },
      (e: unknown) => {
        if (live) diffError = e;
      },
    );
    return () => (live = false);
  });
  const changed = $derived(diff && diff.key === diffKey ? diff.files.filter((f) => f.status !== "Same") : undefined);

  let busy = $state(false);
  let failure = $state<unknown>(undefined);
  async function keep(v: ConfigVersionSummary) {
    if (busy) return;
    busy = true;
    failure = undefined;
    try {
      await resolveConfigConflict(bundleId, { choice: "KeepVersion", keepVersionId: v.id, variantName: null });
      const to = owner ? (owner.kind === "group" ? `every device in ${owner.name}` : owner.name) : "the devices";
      flash.say(`Kept ${whose(v)} ${emuName} config · ${to} gets it`);
      configs.reload();
      router.go(back);
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
        else router.go(back);
        return true;
      }
      if (sides.length === 0) return false;
      if (a === "left" || a === "right") {
        col = Math.max(0, Math.min(sides.length - 1, col + (a === "left" ? -1 : 1)));
        return true;
      }
      if (a === "a" && focus) {
        void keep(focus);
        return true;
      }
      return false;
    }),
  );

  const loaded = $derived(!!configs.data && !!versions.data && !!groups.data);
  const reaches = $derived(owner ? (owner.kind === "group" ? `Every device in ${owner.name} (${nameList(owner.members.map((m) => m.name))})` : owner.name) : "The devices");
</script>

<div class="page">
  <section>
    <p class="crumb"><a href={back} onclick={(e) => { e.preventDefault(); router.go(back); }}>Configs</a> {" / "}{emuName}{#if owner}{" / "}{owner.name}{/if}</p>
    {#if (configs.error && !configs.data) || (versions.error && !versions.data)}
      <h1>{emuName} config</h1>
      <ErrorLine error={configs.error ?? versions.error} />
    {:else if !loaded}
      <h1 class="fault">{emuName} config</h1>
      <p class="loading" aria-busy="true">— — —</p>
    {:else if sides.length < 2}
      <h1>{emuName} config</h1>
      <Empty text="Nothing to decide for this config." />
      <a class="link" href={back} onclick={(e) => { e.preventDefault(); router.go(back); }}>Back to Configs</a>
    {:else}
      <h1 class="fault">Two versions of the {emuName} config</h1>
      <p class="sub">
        {nameList(sides.map(who))} changed it before syncing. Until you keep one, changes to this config are on hold.
      </p>

      <div class="sides">
        {#each sides as v, j (v.id)}
          {@const on = j === Math.min(col, sides.length - 1)}
          <div class="side" onfocusin={() => (col = j)}>
            <h2>{who(v)}<small class="mono">{label(v)}{v.id === owner?.headVersionId ? " · current" : ""}</small></h2>
            <Facts
              items={[
                ["Changed", formatWhen(v.receivedUtc)],
                ["Size", formatSize(v.totalSize), true],
              ]}
            />
            <div class="actions">
              <span class="cursor" class:on>
                <Button variant={on ? "primary" : "secondary"} glyph={on ? "a" : undefined} disabled={busy} onclick={() => keep(v)}>Keep {whose(v)}</Button>
              </span>
            </div>
          </div>
        {/each}
      </div>

      <div class="what">
        <h2>What differs {#if other}<small>{label(sides[0]!)} → {label(other)}</small>{/if}</h2>
        {#if diffError}
          <ErrorLine error={diffError} />
        {:else if !changed}
          <p class="loading" aria-busy="true">— — —</p>
        {:else if changed.length === 0}
          <Empty text="The files are the same; only the order they arrived in differs. Keep either." />
        {:else}
          <Diff files={changed} bind:selected={shownPath} />
        {/if}
      </div>

      {#if failure}<div class="fail"><ErrorLine error={failure} /></div>{/if}
      <p class="d">{reaches} gets the one you keep. Where {emuName} is open, it waits until it closes.</p>
      <p class="d">Nothing has been overwritten. The other version stays in history either way.</p>
    {/if}
  </section>

  <HintBar
    hints={[
      { glyph: "a", label: "Keep this one" },
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
  .crumb {
    margin: 0 0 6px;
    color: var(--ink3);
    font-size: 14px;
  }
  .crumb a,
  .link {
    color: var(--ink2);
  }
  h1 {
    margin: 0 0 8px;
    font: 600 48px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.02em;
    color: var(--ink);
  }
  h1.fault {
    color: var(--fault);
  }
  h2 {
    margin: 0 0 14px;
    font: 600 24px/1.1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  h2 small {
    margin-left: 12px;
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    letter-spacing: 0;
    color: var(--ink3);
  }
  .sub {
    max-width: 70ch;
    margin: 0 0 28px;
    color: var(--ink2);
    font-size: 15px;
  }
  .sides {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
    gap: 0 40px;
  }
  .side + .side {
    padding-left: 40px;
    border-left: 1px solid var(--rule);
  }
  .actions {
    display: flex;
    align-items: center;
    gap: 20px;
    margin-top: 22px;
  }
  .cursor.on {
    display: inline-flex;
    outline: 2px solid var(--ink);
    outline-offset: 3px;
    border-radius: 3px;
  }
  .what {
    margin-top: 36px;
    padding-top: 20px;
    border-top: 1px solid var(--rule);
  }
  .d {
    margin: 14px 0 0;
    color: var(--ink3);
    font-size: 14px;
  }
  .fail {
    margin-top: 16px;
  }
  .loading {
    margin: 0;
    color: var(--ink3);
  }
  .link {
    display: inline-block;
    margin-top: 6px;
  }
  @media (max-width: 900px) {
    .sides {
      grid-template-columns: minmax(0, 1fr);
      gap: 24px 0;
    }
    .side + .side {
      padding: 24px 0 0;
      border-left: 0;
      border-top: 1px solid var(--rule);
    }
  }
</style>
