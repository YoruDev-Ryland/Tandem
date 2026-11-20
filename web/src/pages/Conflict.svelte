<script lang="ts">
  import { getConflicts, resolveConflict } from "../lib/api/endpoints";
  import { downloadUrl, gamePath } from "../lib/api/paths";
  import { resource } from "../lib/api/resource.svelte";
  import type { ConflictRow, ConflictSide } from "../lib/api/types";
  import { formatSize, formatWhen } from "../lib/format";
  import { onAction } from "../lib/input/actions";
  import { gamepad } from "../lib/input/gamepad.svelte";
  import { router } from "../lib/router.svelte";
  import Button from "../lib/ui/Button.svelte";
  import Dialog from "../lib/ui/Dialog.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import Facts from "../lib/ui/Facts.svelte";
  import Field from "../lib/ui/Field.svelte";
  import Glyph from "../lib/ui/Glyph.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";

  const { emu, key } = router.params as { emu: string; key: string };
  const back = gamePath(emu, key);

  const conflicts = resource(getConflicts, ["conflicts", "saves"]);

  // The game's save first, then its state slots.
  const items = $derived(
    (conflicts.data ?? [])
      .filter((r) => r.emulatorId === emu && r.gameKey === key)
      .sort((a, b) => (a.kind === b.kind ? (a.slot ?? "").localeCompare(b.slot ?? "", undefined, { numeric: true }) : a.kind === "Save" ? -1 : 1)),
  );
  const gameName = $derived(items[0]?.displayName ?? key);

  const name = (s: ConflictSide) => s.deviceName ?? "Unknown device";
  const joinNames = (names: string[]) => (names.length < 2 ? (names[0] ?? "") : `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`);
  const sub = (r: ConflictRow) =>
    r.reason === "FirstSync"
      ? `${joinNames(r.sides.map(name))} each had a save before they first synced.`
      : "Both devices changed this save before syncing.";
  const heading = (r: ConflictRow) => (r.kind === "State" ? `Slot ${r.slot ?? ""}`.trim() : "Save");
  const possessives = (sides: ConflictSide[]) => joinNames(sides.map((x) => `${name(x)}'s`));
  const files = (n: number) => `${n} ${n === 1 ? "file" : "files"}`;

  /** The cursor: which item, and which of its sides. */
  let sec = $state(0);
  let col = $state(0);
  const current = $derived(items[Math.min(sec, items.length - 1)]);
  const focusSide = $derived(current?.sides[Math.min(col, current.sides.length - 1)]);

  // The name for the side that is not kept; edits stick per item, otherwise it follows the cursor.
  let typed = $state<Record<string, string>>({});
  const otherOf = (r: ConflictRow, s: ConflictSide | undefined) => r.sides.find((x) => x.versionId !== s?.versionId);
  const defaultName = (r: ConflictRow, s: ConflictSide | undefined) => {
    const o = otherOf(r, s);
    return o ? `From ${name(o)}` : "Other";
  };
  const nameFor = (r: ConflictRow) => typed[r.itemId] ?? defaultName(r, r.itemId === current?.itemId ? focusSide : r.sides[0]);

  let busy = $state(false);
  let failure = $state<unknown>(undefined);

  async function send(itemId: string, req: Parameters<typeof resolveConflict>[1]) {
    if (busy) return;
    busy = true;
    failure = undefined;
    try {
      await resolveConflict(itemId, req);
      conflicts.reload();
      router.go(back);
    } catch (e) {
      failure = e;
    } finally {
      busy = false;
    }
  }

  const keep = (r: ConflictRow, s: ConflictSide) => send(r.itemId, { choice: "KeepVersion", keepVersionId: s.versionId, variantName: null });

  /** Chosen when the dialog opens; a live reload must not change what Keep both acts on. */
  let target = $state<{ itemId: string; versionId: string; keepName: string; others: string; variantName: string | null } | undefined>();
  let confirming = $state(false);
  function openBoth() {
    const r = current;
    const s = focusSide;
    if (!r || !s || busy) return;
    const others = r.sides.filter((x) => x.versionId !== s.versionId);
    // With more than two saves the server labels each one "From {device}"; one typed name would stick to all of them.
    const variantName = r.sides.length > 2 ? null : nameFor(r).trim() || defaultName(r, s);
    target = { itemId: r.itemId, versionId: s.versionId, keepName: name(s), others: possessives(others), variantName };
    confirming = true;
  }
  function confirmBoth() {
    const t = target;
    if (!t) return;
    confirming = false;
    void send(t.itemId, { choice: "KeepBoth", keepVersionId: t.versionId, variantName: t.variantName });
  }

  let page: HTMLElement | undefined = $state();
  function downloadBoth() {
    const id = current?.itemId;
    if (!id) return;
    page?.querySelectorAll<HTMLAnchorElement>(`[data-item="${CSS.escape(id)}"] a.dl`).forEach((a) => a.click());
  }

  $effect(() =>
    onAction((a) => {
      if (a === "b") {
        if (router.canGoBack) router.back();
        else router.go(back);
        return true;
      }
      if (!current) return false;
      if (a === "left" || a === "right") {
        col = Math.max(0, Math.min(current.sides.length - 1, col + (a === "left" ? -1 : 1)));
        return true;
      }
      if (a === "up" || a === "down") {
        if (items.length < 2) return false;
        sec = Math.max(0, Math.min(items.length - 1, sec + (a === "up" ? -1 : 1)));
        return true;
      }
      if (a === "a" && focusSide) {
        void keep(current, focusSide);
        return true;
      }
      if (a === "x") {
        openBoth();
        return true;
      }
      if (a === "y") {
        downloadBoth();
        return true;
      }
      return false;
    }),
  );

  const several = $derived(items.length > 1);
</script>

<div class="page" bind:this={page}>
  <section>
    {#if conflicts.error && !conflicts.data}
      <ErrorLine error={conflicts.error} />
    {:else if !conflicts.data}
      <h1 class="fault">{key}</h1>
      <p class="loading" aria-busy="true">— — —</p>
    {:else if items.length === 0}
      <h1>{key}</h1>
      <Empty text="Nothing to decide for this game." />
      <a class="link" href={back} onclick={(e) => { e.preventDefault(); router.go(back); }}>Back to the game</a>
    {:else}
      <p class="crumb"><a href="/games" onclick={(e) => { e.preventDefault(); router.go("/games"); }}>Games</a> / <a href={back} onclick={(e) => { e.preventDefault(); router.go(back); }}>{gameName}</a></p>
      <h1 class="fault">Two different saves</h1>

      {#each items as r, i (r.itemId)}
        <div class="item" data-item={r.itemId}>
          {#if several}<h2 class="item-title">{heading(r)}</h2>{/if}
          <p class="sub">{sub(r)}</p>

          <div class="sides">
            {#each r.sides as s, j (s.versionId)}
              {@const on = i === Math.min(sec, items.length - 1) && j === Math.min(col, r.sides.length - 1)}
              <div class="side" onfocusin={() => { sec = i; col = j; }}>
                <h2>{name(s)}</h2>
                <Facts
                  items={[
                    ["Received", formatWhen(s.receivedUtc)],
                    ["Size", `${formatSize(s.totalSize)} · ${files(s.fileCount)}`, true],
                    ["Fingerprint", s.manifestHash.slice(0, 8), true],
                  ]}
                />
                <div class="actions">
                  <span class="cursor" class:on>
                    <Button variant={on ? "primary" : "secondary"} glyph="a" disabled={busy} onclick={() => keep(r, s)}>Keep {name(s)}'s</Button>
                  </span>
                  <a class="dl" href={downloadUrl(s.versionId)} download>Download</a>
                </div>
              </div>
            {/each}
          </div>

          <div class="both">
            <div class="grow">
              <div class="b">Keep both</div>
              <div class="m">The devices get the one you keep. The other stays on the server as a named copy you can restore later.</div>
            </div>
            {#if r.sides.length > 2}
              <p class="m">The others are kept as named copies.</p>
            {:else}
            <Field
              inline
              label="Name the other"
              bind:value={() => nameFor(r), (v) => (typed[r.itemId] = v)}
              onfocus={() => (sec = i)}
            />
            {/if}
            <Button
              glyph="x"
              disabled={busy}
              onclick={() => { sec = i; openBoth(); }}
            >Keep both</Button>
          </div>
        </div>
      {/each}

      {#if failure}<div class="fail"><ErrorLine error={failure} /></div>{/if}
      <p class="d">Nothing has been overwritten.</p>
      <p class="d">Both saves stay in history either way.</p>
    {/if}
  </section>

  <Dialog bind:open={confirming} title="Keep both saves?">
    {#if target}
      <p class="m">
        The devices get {target.keepName}'s save.
        {#if target.variantName}{target.others} stays on the server as “{target.variantName}”{:else}{target.others} stay on the server as named copies{/if}, and you can restore {target.variantName ? "it" : "them"} later.
      </p>
      <div class="row">
        <Button variant="primary" onclick={confirmBoth}>Keep both and continue</Button>
        <Button onclick={() => (confirming = false)}>Cancel</Button>
      </div>
    {/if}
  </Dialog>

  <HintBar
    hints={[
      { glyph: "a", label: "Keep this one" },
      { glyph: "x", label: "Keep both" },
      { glyph: "y", label: "Download both" },
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
    font: 600 40px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
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
  h2.item-title {
    margin: 28px 0 6px;
    color: var(--ink2);
  }
  .item + .item {
    margin-top: 28px;
    padding-top: 8px;
    border-top: 1px solid var(--rule);
  }
  .sub {
    margin: 0 0 20px;
    color: var(--ink2);
    font-size: 15px;
  }
  .sides {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
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
  .dl {
    color: var(--ink2);
    font-size: 14px;
  }
  .both {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 20px;
    margin-top: 36px;
    padding-top: 20px;
    border-top: 1px solid var(--rule);
  }
  .grow {
    flex: 1;
    min-width: 320px;
  }
  .b {
    font-size: 17px;
    font-weight: 600;
    color: var(--ink);
  }
  .m {
    margin: 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .d {
    margin: 14px 0 0;
    color: var(--ink3);
    font-size: 14px;
  }
  .fail {
    margin-top: 16px;
  }
  .row {
    display: flex;
    gap: 10px;
    margin-top: 20px;
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
