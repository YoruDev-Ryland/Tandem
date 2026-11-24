<script lang="ts">
  // Dev-only page that renders every UI kit component. ?theme=light|dark forces a theme; ?pad=1 fakes a gamepad; ?dialog=1 opens the dialog.
  // ?storage=map|bars|blocks shows that Storage view with a real server's numbers instead (&user=1: as a non-admin).
  import { onMount } from "svelte";
  import { ApiError } from "../lib/api/http";
  import type { DeviceSummary, FileDiff } from "../lib/api/types";
  import { gamepad } from "../lib/input/gamepad.svelte";
  import { PAGE_TABS } from "../lib/nav";
  import TopBar from "../lib/ui/TopBar.svelte";
  import Tabs from "../lib/ui/Tabs.svelte";
  import List from "../lib/ui/List.svelte";
  import Button from "../lib/ui/Button.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import Facts from "../lib/ui/Facts.svelte";
  import Ticks from "../lib/ui/Ticks.svelte";
  import Check from "../lib/ui/Check.svelte";
  import Diff from "../lib/ui/Diff.svelte";
  import Dialog from "../lib/ui/Dialog.svelte";
  import Empty from "../lib/ui/Empty.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import Field from "../lib/ui/Field.svelte";
  import MapView from "./settings/storage/MapView.svelte";
  import BarsView from "./settings/storage/BarsView.svelte";
  import BlocksView from "./settings/storage/BlocksView.svelte";
  import StorageLower from "./settings/storage/StorageLower.svelte";
  import { storageUsage } from "./_storageFixture";
  import "./settings/settings.css";

  const params = new URLSearchParams(location.search);
  onMount(() => {
    const theme = params.get("theme");
    const root = document.documentElement;
    const before = root.dataset.theme;
    if (theme === "light" || theme === "dark") root.dataset.theme = theme;
    if (params.get("pad") === "1") gamepad.connected = true;
    if (params.get("dialog") === "1") dialogOpen = true;
    return () => {
      if (before) root.dataset.theme = before;
      else delete root.dataset.theme;
    };
  });

  const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();
  const devices: DeviceSummary[] = [
    { id: "odin", name: "Odin 3", platform: "android", lastSeenUtc: ago(0), shizukuOk: true, isCurrent: false, runningEmulators: [] },
    { id: "thor", name: "Thor", platform: "android", lastSeenUtc: ago(1), shizukuOk: true, isCurrent: false, runningEmulators: ["eden"] },
    { id: "rp5", name: "Retroid", platform: "android", lastSeenUtc: ago(0), shizukuOk: false, isCurrent: false, runningEmulators: [] },
    { id: "deck", name: "Deck", platform: "linux", lastSeenUtc: ago(90), shizukuOk: null, isCurrent: false, runningEmulators: [] },
  ];

  const emulators = [
    { label: "Eden", path: "eden" }, { label: "RetroArch", path: "retroarch" }, { label: "Azahar", path: "azahar" },
    { label: "PPSSPP", path: "ppsspp" }, { label: "Vita3K", path: "vita3k" }, { label: "Dolphin", path: "dolphin" },
    { label: "melonDS", path: "melonds" },
  ];
  let emulator = $state("retroarch");

  interface Game { sys: string; name: string; emu: string; newest: string; others: string; tone: "" | "wait" | "fault" | "ink3"; saved: string }
  const games: Game[] = [
    { sys: "SWITCH", name: "Pokémon Brilliant Diamond", emu: "Eden", newest: "Two saves", others: "Thor and Odin 3 differ", tone: "fault", saved: "choose" },
    { sys: "SWITCH", name: "Pokémon Sword", emu: "Eden", newest: "Odin 3", others: "Thor, until Eden closes", tone: "wait", saved: "2 h ago" },
    { sys: "DS", name: "Pokémon Platinum", emu: "melonDS", newest: "Odin 3", others: "Thor", tone: "", saved: "yesterday" },
    { sys: "GBA", name: "Pokémon Emerald", emu: "RetroArch", newest: "Odin 3", others: "not on Thor", tone: "ink3", saved: "3 days ago" },
    { sys: "SWITCH", name: "Xenoblade Chronicles X: Definitive Edition", emu: "Eden", newest: "Thor", others: "Odin 3", tone: "", saved: "Nov 18" },
  ];
  let gameIndex = $state(1);
  let opened = $state("");

  const steps = [
    { done: true, name: "RetroArch, Eden, Dolphin, PPSSPP", note: "found" },
    { done: false, name: "Vita3K", note: "Where to get it" },
    { done: false, name: "Gambatte", note: "for Pokémon Crystal" },
  ];

  const same = (from: number, n: number) => Array.from({ length: n }, (_, i) => ({ op: " " as const, text: `video_option_${from + i} = "false"` }));
  const files: FileDiff[] = [
    {
      path: "retroarch.cfg",
      status: "Changed",
      hunks: [
        {
          oldStart: 412, newStart: 412,
          lines: [
            { op: " ", text: 'video_scale_integer = "true"' },
            { op: "-", text: 'video_shader_enable = "false"' },
            { op: "+", text: 'video_shader_enable = "true"' },
            ...same(414, 8),
            { op: "-", text: 'input_menu_toggle_gamepad_combo = "2"' },
            { op: "+", text: 'input_menu_toggle_gamepad_combo = "4"' },
            { op: " ", text: 'input_overlay_enable = "false"' },
          ],
        },
      ],
    },
    { path: "config/mGBA/mGBA.opt", status: "Changed", hunks: [{ oldStart: 3, newStart: 3, lines: [{ op: "-", text: 'mgba_solar_sensor_level = "0"' }, { op: "+", text: 'mgba_solar_sensor_level = "5"' }] }] },
    { path: "config/remaps/mGBA/mGBA.rmp", status: "Added", hunks: [{ oldStart: 0, newStart: 1, lines: [{ op: "+", text: 'input_player1_btn_a = "8"' }] }] },
  ];
  let file = $state("retroarch.cfg");

  let dialogOpen = $state(false);
  let deviceName = $state("Odin 3");
  let otherName = $state("From Odin 3");
  let compare = $state("v6");

  const storageView = params.get("storage");
  const storageAdmin = params.get("user") !== "1";
  const storageData = storageAdmin ? storageUsage : { ...storageUsage, server: null };
</script>

{#if storageView}
  <main class="storage-kit">
    <div class="sec wide">
      <h2>Storage</h2>
      {#if storageView === "bars"}<BarsView usage={storageData} isAdmin={storageAdmin} />
      {:else if storageView === "blocks"}<BlocksView usage={storageData} isAdmin={storageAdmin} />
      {:else}<MapView usage={storageData} isAdmin={storageAdmin} />
      {/if}
      <StorageLower usage={storageData} isAdmin={storageAdmin} />
    </div>
  </main>
{:else}

<div class="page">
  <TopBar tabs={[...PAGE_TABS]} active="/configs" {devices} waitingDeviceIds={["thor"]} />

  <main>
    <div class="sub">
      <Tabs items={emulators} active={emulator} level={2} onSelect={(p) => (emulator = p)} />
    </div>

    <div class="split">
      <section>
        <h2>Newest saves <small>{opened ? `opened ${opened}` : "5 games"}</small></h2>
        <List
          rows={games}
          columns="64px minmax(0,1fr) 96px 92px 176px 84px"
          header={["System", "Game", "Emulator", "Newest on", "Other devices", "Saved"]}
          headerEnd={[5]}
          bind:index={gameIndex}
          onOpen={(g) => (opened = g.name)}
        >
          {#snippet row(g, focused)}
            <span class="ink3 sys">{g.sys}</span>
            <span class="name" class:fault={g.tone === "fault"}>{g.name}</span>
            <span class="ink2">{g.emu}</span>
            <span class="strong" class:fault={g.tone === "fault"}>{g.newest}</span>
            <span class={g.tone || "ink2"}>{focused && g.tone === "wait" ? `Waiting · ${g.others}` : g.others}</span>
            <span class="ink3 end">{g.saved}</span>
          {/snippet}
        </List>

        <h2 class="gap">Emulators <small>1 of 3</small></h2>
        <List rows={steps} columns="22px minmax(0,1fr) auto" size="detail" active={false} onOpen={() => {}}>
          {#snippet row(s)}
            <Check done={s.done} />
            <span class:strong={!s.done}>{s.name}</span>
            <span class="ink2">{s.note}</span>
          {/snippet}
        </List>

        <div class="cols gap">
          <div>
            <h2>Buttons</h2>
            <div class="buttons">
              <Button variant="primary" glyph="a" onclick={() => (dialogOpen = true)}>Rename…</Button>
              <Button glyph="y">Download</Button>
              <Button glyph="x">Pin</Button>
              <Button disabled>Restore</Button>
            </div>
          </div>
          <div>
            <h2>Fields</h2>
            <div class="fields">
              <Field label="Name the other" inline bind:value={otherName} />
              <Field
                label="Compare with"
                inline
                bind:value={compare}
                options={[{ value: "v6", label: "v6 · Thor" }, { value: "current", label: "Thor's current files" }]}
              />
            </div>
          </div>
        </div>

        <div class="cols gap">
          <div>
            <h2>Empty</h2>
            <Empty text="No games have synced yet." />
          </div>
          <div>
            <h2>Error</h2>
            <ErrorLine error={new ApiError(413, "too_large", "x")} />
          </div>
        </div>
      </section>

      <section class="pane">
        <div class="ticks"><Ticks done={9} total={17} /></div>
        <h2>v3 from Thor</h2>
        <Facts items={[["Saved", "Wed, Nov 19, 19:02"], ["Size", "512 KB · 1 file", true], ["Fingerprint", "c4e477bc", true]]} />
        <h2 class="gap">v6 → v7 <small>3 files changed</small></h2>
        <Diff {files} bind:selected={file} />
      </section>
    </div>
  </main>

  <HintBar
    status="Synced 2 min ago"
    hints={[{ glyph: "a", label: "Open" }, { glyph: "x", label: "Restore" }, { glyph: "y", label: "Download" }, { glyph: "b", label: "Back" }]}
  />
</div>

{/if}

<Dialog bind:open={dialogOpen} title="Rename device">
  <Field label="Name" bind:value={deviceName} />
  <div class="buttons dialog-buttons">
    <Button variant="primary" onclick={() => (dialogOpen = false)}>Save</Button>
    <Button onclick={() => (dialogOpen = false)}>Cancel</Button>
  </div>
</Dialog>

<style>
  .storage-kit {
    padding: 32px 40px;
  }
  .page {
    min-height: 100vh;
    display: flex;
    flex-direction: column;
  }
  main {
    flex: 1;
    padding: 4px 40px 32px;
  }
  .sub {
    margin: 2px 0 18px;
  }
  .split {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 480px;
    column-gap: 48px;
  }
  .pane {
    border-left: 1px solid var(--rule2);
    padding-left: 40px;
    min-width: 0;
  }
  h2 {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    margin: 0 0 8px;
    font: 600 18px "Barlow Semi Condensed", sans-serif;
  }
  h2 small {
    font: 500 13px "Barlow", sans-serif;
    color: var(--ink3);
  }
  .gap {
    margin-top: 28px;
  }
  .sys {
    font: 600 13px "Barlow Semi Condensed", sans-serif;
  }
  .ticks {
    margin-bottom: 24px;
  }
  .cols {
    display: grid;
    grid-template-columns: 1fr 1fr;
    column-gap: 48px;
  }
  .buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }
  .fields {
    display: flex;
    flex-direction: column;
    gap: 12px;
    align-items: flex-start;
  }
  .dialog-buttons {
    margin-top: 24px;
  }
</style>
