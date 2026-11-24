<script lang="ts">
  import { exportZipUrl } from "../../../lib/api/paths";
  import type { StorageUsage } from "../../../lib/api/types";
  import { emulatorName } from "../../../lib/emulators";
  import { formatSize } from "../../../lib/format";
  import { spineColour } from "../../../lib/spines";
  import { diskRuler, kindRows, SYSTEMS, usedBytes, type KindRow } from "../../../lib/storage/page";
  import { EMULATOR_SYSTEM } from "../../../lib/storage/tiles";
  import { bareKey } from "../../../lib/systems";

  let { usage: u, isAdmin }: { usage: StorageUsage; isAdmin: boolean } = $props();

  const TOP = 10;
  let all = $state(false);

  const kinds = $derived(kindRows(u, isAdmin));
  const kindTotal = $derived(kinds.reduce((s, k) => s + k.bytes, 0));
  const FILL: Record<KindRow["id"], string> = {
    saves: "background: var(--ink)",
    states: "background: var(--ink2)",
    configs: "background: var(--ink3)",
    vault: "box-shadow: inset 0 0 0 1px var(--rule2)",
    lost: "box-shadow: inset 0 0 0 1px var(--rule2)",
    art: "",
    database: "",
  };

  const server = $derived(isAdmin ? u.server : null);
  const ruler = $derived(server ? diskRuler(server) : null);
  const pct = (f: number) => `${(f * 100).toFixed(2)}%`;
  // Tandem's label sits centred on its mark, or against it near either end; past 70 % the kept-free label moves below.
  const markAnchor = $derived(!ruler ? "mid" : ruler.tandem < 0.12 ? "start" : ruler.tandem > 0.7 ? "end" : "mid");

  const emulators = $derived(u.byEmulator.filter((r) => r.emulatorId && r.bytes > 0).sort((a, b) => b.bytes - a.bytes));
  const emulatorMax = $derived(Math.max(1, ...emulators.map((r) => r.bytes)));
  const games = $derived([...u.games].filter((g) => g.bytes > 0).sort((a, b) => b.bytes - a.bytes));
  const gameMax = $derived(Math.max(1, ...games.map((g) => g.bytes)));
  const shown = $derived(all ? games : games.slice(0, TOP));
  const versions = (n: number) => `${n} ${n === 1 ? "version" : "versions"}`;
  const share = (n: number, of: number) => `${Math.min(100, (n / of) * 100)}%`;
</script>

<div>
  <div class="kick">Used by Tandem</div>
  <div class="hero">
    <b>{formatSize(usedBytes(u, isAdmin))}</b>
    {#if server?.diskTotalBytes != null && server.diskFreeBytes != null}
      <span class="note">on a {formatSize(server.diskTotalBytes)} disk, {formatSize(server.diskFreeBytes)} free</span>
    {/if}
  </div>
</div>

<div class="cap" aria-hidden="true">
  {#each kinds.filter((k) => k.bytes > 0) as k (k.id)}
    <i class={k.id} style="width: {share(k.bytes, kindTotal)}; {FILL[k.id]}"></i>
  {/each}
</div>
<ul class="legend" aria-label="By kind">
  {#each kinds as k (k.id)}
    <li><i class={k.id} style={FILL[k.id]}></i>{k.label} <span class="mono">{formatSize(k.bytes)}</span></li>
  {/each}
</ul>

{#if ruler && server}
  <div class="disk">
    <span class="lab {markAnchor}" style:left={pct(ruler.tandem)}>Tandem {formatSize(ruler.tandemBytes)}</span>
    {#if ruler.tandem <= 0.7}<span class="lab" style:right="0">{formatSize(server.minFreeBytes)} kept free</span>{/if}
    <div class="track" aria-hidden="true">
      <div class="others" style:width={pct(ruler.others)}></div>
      <div class="ours" style:left={pct(ruler.others)} style:width={pct(ruler.tandem - ruler.others)}></div>
      <div class="mark" style:left={pct(ruler.tandem)}></div>
      <div class="floor" style:left={pct(ruler.floor)}></div>
    </div>
    <div class="scale">
      <span>Other data {formatSize(ruler.otherBytes)}</span>
      <span>{#if ruler.tandem > 0.7}{formatSize(server.minFreeBytes)} kept free · {/if}Disk {formatSize(server.diskTotalBytes!)}</span>
    </div>
  </div>
{/if}

<div class="hr"></div>

<h3>By emulator</h3>
<ul class="bars" aria-label="By emulator">
  {#each emulators as r (r.emulatorId)}
    {@const system = EMULATOR_SYSTEM[r.emulatorId] ?? null}
    <li>
      <span class="who">{emulatorName(r.emulatorId)}{#if SYSTEMS[r.emulatorId]}<small>{SYSTEMS[r.emulatorId]}</small>{/if}</span>
      <span class="bar"><i style:width={share(r.bytes, emulatorMax)} style:background={spineColour(system)}></i></span>
      <span class="mono">{formatSize(r.bytes)}</span>
      <a class="link" href={exportZipUrl(r.emulatorId)} download aria-label="Export {emulatorName(r.emulatorId)}">Export</a>
    </li>
  {/each}
</ul>

<h3>Largest games</h3>
<ul class="bars games" aria-label="Largest games">
  {#each shown as g (`${g.emulatorId}/${g.gameKey}`)}
    <li>
      <span class="who">
        <span class="name">{g.displayName ?? bareKey(g.emulatorId, g.gameKey)}</span>
        <small>{emulatorName(g.emulatorId)} · {versions(g.versions)}{#if g.olderBytes > 0}, <span class="old">{formatSize(g.olderBytes)} older</span>{/if}</small>
      </span>
      <span class="bar">
        <i style:width={share(g.currentBytes, gameMax)} style:background="var(--ink)"></i>
        {#if g.olderBytes > 0}<i class="hatch" style:width={share(g.olderBytes, gameMax)}></i>{/if}
      </span>
      <span class="mono">{formatSize(g.bytes)}</span>
    </li>
  {/each}
</ul>
<div class="more">
  <p class="note">Solid is the current version; hatched is older versions kept by Retention.</p>
  {#if games.length > TOP}
    <button type="button" class="link" onclick={() => (all = !all)}>{all ? `Show the top ${TOP}` : `Show all ${games.length}`}</button>
  {/if}
</div>

<style>
  .kick {
    font: 500 11px "Barlow", system-ui, sans-serif;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--ink3);
  }
  .hero {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 6px 16px;
    margin-top: 4px;
  }
  .hero b {
    font: 600 46px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.02em;
    color: var(--ink);
  }
  .note {
    margin: 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .hatch,
  .art {
    background: repeating-linear-gradient(135deg, var(--rule2) 0 2px, transparent 2px 6px);
  }
  .database {
    background: radial-gradient(var(--ink3) 1px, transparent 1.3px) 0 0 / 5px 5px;
  }
  .cap {
    display: flex;
    gap: 2px;
    height: 18px;
    border-radius: 2px;
    overflow: hidden;
  }
  .cap i {
    display: block;
    min-width: 2px;
    height: 100%;
  }
  .legend {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 22px;
    margin: -6px 0 0;
    padding: 0;
    list-style: none;
    color: var(--ink2);
    font-size: 14px;
  }
  .legend li {
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }
  .legend i {
    display: inline-block;
    width: 12px;
    height: 12px;
    border-radius: 1px;
  }
  .legend .mono {
    color: var(--ink);
  }
  .disk {
    position: relative;
    padding-top: 22px;
  }
  .disk .lab {
    position: absolute;
    top: 0;
    color: var(--ink2);
    font-size: 12px;
    white-space: nowrap;
  }
  .lab.mid {
    transform: translateX(-50%);
  }
  .lab.end {
    transform: translateX(-100%);
  }
  .track {
    position: relative;
    height: 6px;
    border-radius: 1px;
    background: var(--rule);
  }
  .others,
  .ours {
    position: absolute;
    top: 0;
    bottom: 0;
  }
  .others {
    left: 0;
    background: var(--ink3);
    opacity: 0.55;
  }
  .ours {
    background: var(--ink);
  }
  .mark {
    position: absolute;
    top: -5px;
    width: 2px;
    height: 16px;
    margin-left: -1px;
    background: var(--ink);
  }
  .floor {
    position: absolute;
    top: -4px;
    width: 1px;
    height: 14px;
    background: var(--wait);
  }
  .scale {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    margin-top: 7px;
    color: var(--ink3);
    font-size: 12px;
  }
  .hr {
    height: 1px;
    background: var(--rule);
  }
  .bars {
    display: flex;
    flex-direction: column;
    margin: -6px 0 0;
    padding: 0;
    list-style: none;
  }
  .bars li {
    display: grid;
    grid-template-columns: 170px minmax(0, 1fr) 82px 56px;
    align-items: center;
    gap: 14px;
    padding: 9px 0;
    border-bottom: 1px solid var(--rule);
  }
  .bars li:last-child {
    border-bottom: none;
  }
  .games li {
    grid-template-columns: minmax(0, 1fr) 260px 82px;
  }
  .who {
    display: flex;
    flex-direction: column;
    min-width: 0;
    line-height: 1.25;
    color: var(--ink);
    font-size: 15px;
  }
  .who small {
    color: var(--ink3);
    font-size: 12px;
  }
  .who .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .old {
    color: var(--ink2);
  }
  .bar {
    display: flex;
    gap: 1px;
    height: 10px;
  }
  .bar i {
    display: block;
    min-width: 2px;
    height: 100%;
    box-shadow: inset 0 0 0 1px var(--rule2);
  }
  .bars .mono {
    text-align: right;
    color: var(--ink);
  }
  .link {
    justify-self: end;
    padding: 0;
    border: 0;
    background: none;
    color: var(--ink2);
    font: 400 14px "Barlow", system-ui, sans-serif;
    text-decoration: underline;
    text-underline-offset: 3px;
    cursor: pointer;
  }
  .link:hover {
    color: var(--ink);
  }
  .more {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 8px 16px;
    margin-top: -8px;
  }
  @media (max-width: 640px) {
    .bars li {
      grid-template-columns: 110px minmax(0, 1fr) 72px 48px;
      gap: 10px;
    }
    .games li {
      grid-template-columns: minmax(0, 1fr) 90px 72px;
    }
  }
  @media (prefers-reduced-motion: no-preference) {
    .link {
      transition: color 150ms cubic-bezier(0.23, 1, 0.32, 1);
    }
  }
</style>
