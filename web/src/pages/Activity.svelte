<script lang="ts">
  import { untrack } from "svelte";
  import { getActivity, getDevices, getGames } from "../lib/api/endpoints";
  import { gamePath } from "../lib/api/paths";
  import { connectEvents } from "../lib/api/events";
  import { ApiError } from "../lib/api/http";
  import { resource } from "../lib/api/resource.svelte";
  import type { ActivityFilter, ActivityItem } from "../lib/api/types";
  import { emulatorName } from "../lib/emulators";
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import { relativeTime } from "../lib/time";
  import Button from "../lib/ui/Button.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import Field from "../lib/ui/Field.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import List from "../lib/ui/List.svelte";

  const LIMIT = 50;
  const EMULATOR_IDS = ["eden", "retroarch", "azahar", "ppsspp", "vita3k", "dolphin", "melonds"];

  // Filters live in the URL so other pages can link here.
  const query = $derived(new URLSearchParams(router.search));
  const device = $derived(query.get("device") ?? "");
  const emulator = $derived(query.get("emulator") ?? "");
  const game = $derived(query.get("game") ?? "");

  function setFilter(name: "device" | "emulator" | "game", value: string) {
    const q = new URLSearchParams(router.search);
    if (value) q.set(name, value);
    else q.delete(name);
    const s = q.toString();
    router.go(router.path + (s ? `?${s}` : ""), { replace: true });
  }

  const devices = resource(getDevices, ["devices"]);
  const games = resource(() => getGames(), ["saves"]);
  const gameName = $derived(
    (games.data ?? []).find((g) => g.gameKey === game && (!emulator || g.emulatorId === emulator))?.displayName ?? game,
  );

  // Emulators that have appeared so far, kept across filter changes so a filtered view can still switch back.
  let seen = $state<string[]>([]);
  const deviceOptions = $derived([
    { value: "", label: "All devices" },
    ...(devices.data ?? []).map((d) => ({ value: d.id, label: d.name })),
  ]);
  const emulatorOptions = $derived([
    { value: "", label: "All emulators" },
    ...EMULATOR_IDS.filter((e) => e === emulator || seen.includes(e)).map((e) => ({ value: e, label: emulatorName(e) })),
  ]);

  let items = $state<ActivityItem[]>([]);
  let nextBefore = $state<number | null>(null);
  let loaded = $state(false);
  let error = $state<ApiError | undefined>();
  let index = $state(0);
  let seq = 0;

  const filter = (): ActivityFilter => ({
    device: device || undefined,
    emulator: emulator || undefined,
    game: game || undefined,
    limit: LIMIT,
  });

  function note(page: ActivityItem[]) {
    const add = page.map((i) => i.emulatorId).filter((e): e is string => !!e && !seen.includes(e));
    if (add.length) seen = [...seen, ...new Set(add)];
  }
  const fail = (e: unknown) =>
    (error = e instanceof ApiError ? e : new ApiError(0, "error", e instanceof Error ? e.message : String(e)));

  // New filters start over from the newest page.
  $effect(() => {
    void [device, emulator, game];
    untrack(() => {
      const mine = ++seq;
      loaded = false;
      items = [];
      nextBefore = null;
      error = undefined;
      olderError = false;
      getActivity(filter()).then(
        (p) => {
          if (mine !== seq) return;
          items = p.items;
          nextBefore = p.nextBefore;
          error = undefined;
          loaded = true;
          index = 0;
          note(p.items);
        },
        (e) => {
          if (mine === seq) { fail(e); loaded = true; }
        },
      );
    });
  });

  // The activity topic carries no payload: fetch the newest page and add what is newer than the top row. Older
  // pages already loaded stay put, and the list is keyed by id, so the cursor stays on its item.
  function refresh() {
    const mine = seq;
    getActivity(filter()).then(
      (p) => {
        if (mine !== seq) return;
        note(p.items);
        const top = items[0]?.id ?? -Infinity;
        const fresh = p.items.filter((i) => i.id > top);
        if (fresh.length === 0) return;
        if (fresh.length === p.items.length && p.nextBefore !== null && items.length > 0) {
          // More arrived than one page holds: older rows would no longer join up, so start over.
          items = p.items;
          nextBefore = p.nextBefore;
          return;
        }
        items = [...fresh, ...items];
      },
      () => {},
    );
  }
  $effect(() => connectEvents((t) => { if (t === "activity") refresh(); }, refresh));

  let loadingOlder = $state(false);
  let olderError = $state(false);
  function older() {
    if (nextBefore === null || loadingOlder) return;
    const mine = seq;
    loadingOlder = true;
    olderError = false;
    getActivity({ ...filter(), before: nextBefore }).then(
      (p) => {
        if (mine !== seq) return;
        const have = new Set(items.map((i) => i.id));
        items = [...items, ...p.items.filter((i) => !have.has(i.id))];
        nextBefore = p.nextBefore;
        note(p.items);
      },
      () => { if (mine === seq) olderError = true; },
    ).finally(() => { loadingOlder = false; });
  }

  // The last row is the "Load older" button, so the cursor and A reach it.
  type Row = { kind: "item"; item: ActivityItem } | { kind: "older" };
  const rows = $derived<Row[]>([
    ...items.map((item) => ({ kind: "item" as const, item })),
    ...(nextBefore === null ? [] : [{ kind: "older" as const }]),
  ]);

  function open(r: Row) {
    if (r.kind === "older") return loadingOlder ? undefined : older();
    const { emulatorId, gameKey } = r.item;
    if (emulatorId && gameKey) router.go(gamePath(emulatorId, gameKey));
  }

  $effect(() =>
    onAction((a) => {
      if (a !== "b") return false;
      if (router.canGoBack) router.back();
      else router.go("/");
      return true;
    }),
  );

  const time = (iso: string) => {
    if (Date.now() - Date.parse(iso) < 24 * 3_600_000) return relativeTime(iso);
    return new Date(iso).toLocaleString("en-US", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  };
</script>

<div class="page">
<section>
  <h1>Activity</h1>

  <div class="filters">
    <Field label="Device" options={deviceOptions} bind:value={() => device, (v) => setFilter("device", v)} />
    <Field label="Emulator" options={emulatorOptions} bind:value={() => emulator, (v) => setFilter("emulator", v)} />
  </div>
  {#if game}
    <p class="only"><span>Only {gameName}</span> <Button onclick={() => setFilter("game", "")}>Show all</Button></p>
  {/if}

  {#if error && items.length === 0}
    <ErrorLine {error} />
  {:else if !loaded && items.length === 0}
    <p class="loading" aria-busy="true">— — —</p>
  {:else if items.length === 0}
    <Empty text="No activity matches these filters." />
  {:else}
    <List {rows} columns="140px 160px minmax(0,1fr)" header={["Time", "Device", "What happened"]} bind:index onOpen={open} minWidth="0px" phoneColumns="84px minmax(0,1fr)" phoneHide={[1]}
      key={(r) => (r.kind === "item" ? r.item.id : "older")} disabled={(r) => r.kind === "older" && loadingOlder}>
      {#snippet row(r: Row)}
        {#if r.kind === "item"}
          {@const bad = r.item.kind === "conflict_opened"}
          <span class="mono ink3">{time(r.item.utc)}</span>
          <span class="ink2 ellipsis sm-hide">{r.item.deviceName ?? "Server"}</span>
          <span class="ellipsis" class:fault={bad} title={r.item.message}>{r.item.message}</span>
        {:else}
          <span></span><span class="sm-hide"></span>
          <span class={loadingOlder ? "ink3" : "strong"}>{loadingOlder ? "Loading…" : "Load older"}</span>
        {/if}
      {/snippet}
    </List>
    {#if olderError}
      <p class="older-error" role="alert">Couldn't load older activity. Try again.</p>
    {:else if nextBefore === null}
      <p class="end-note">Nothing older</p>
    {/if}
  {/if}
</section>

<HintBar hints={[{ glyph: "a", label: "Open" }, { glyph: "b", label: "Back" }]} />
</div>

<style>
  .page {
    display: flex;
    flex-direction: column;
    min-height: calc(100vh - 72px);
  }
  section {
    flex: 1;
    padding: 8px var(--gutter) 0;
  }
  h1 {
    margin: 0 0 18px;
    font: 600 40px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  .filters {
    display: flex;
    gap: 20px;
    padding: 0 16px 14px;
  }
  .only {
    margin: 0;
    padding: 0 16px 14px;
    display: flex;
    align-items: center;
    gap: 12px;
    color: var(--ink2);
  }
  .mono {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
  }
  .older-error,
  .end-note {
    margin: 0;
    padding: 12px 16px;
  }
  .end-note {
    color: var(--ink3);
  }
  .older-error {
    color: var(--fault);
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
</style>
