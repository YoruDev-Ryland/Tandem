<script lang="ts">
  import type { DeviceSummary } from "../api/types";
  import { PRODUCT_NAME } from "../brand";
  import { emulatorName } from "../emulators";
  import Tabs from "./Tabs.svelte";

  interface Props {
    tabs: { label: string; path: string }[];
    active: string;
    devices: DeviceSummary[];
    /** Devices that have a game waiting for an emulator to close; their "open" state shows in --wait. */
    waitingDeviceIds?: string[];
  }
  let { tabs, active, devices, waitingDeviceIds = [] }: Props = $props();

  const OFFLINE_AFTER_MS = 2 * 60_000;

  let now = $state(Date.now());
  $effect(() => {
    const t = setInterval(() => (now = Date.now()), 15_000);
    return () => clearInterval(t);
  });

  function status(d: DeviceSummary): { text: string; tone: "ink2" | "ink3" | "wait" | "fault" } {
    const seen = d.lastSeenUtc ? Date.parse(d.lastSeenUtc) : NaN;
    if (!(now - seen <= OFFLINE_AFTER_MS)) return { text: "offline", tone: "ink3" };
    if (d.shizukuOk === false) return { text: "Shizuku off", tone: "fault" };
    if (d.runningEmulators.length > 0) {
      const names = d.runningEmulators.map(emulatorName).join(", ");
      return { text: `${names} open`, tone: waitingDeviceIds.includes(d.id) ? "wait" : "ink2" };
    }
    return { text: "online", tone: "ink2" };
  }
</script>

<header>
  <span class="word">{PRODUCT_NAME}</span>
  <Tabs items={tabs} {active} level={1} />
  {#if devices.length}
    <ul class="devices" aria-label="Devices">
      {#each devices as d (d.id)}
        {@const s = status(d)}
        <li class="device"><b>{d.name}</b> <span class="state {s.tone}">{s.text}</span></li>
      {/each}
    </ul>
  {/if}
</header>

<style>
  header {
    flex: none;
    display: flex;
    align-items: center;
    gap: 28px;
    height: 72px;
    padding: 0 40px;
  }
  .word {
    flex: none;
    margin-right: 20px;
    font: 700 19px "Barlow Semi Condensed", sans-serif;
    letter-spacing: 0.01em;
    color: var(--ink);
  }
  .devices {
    display: flex;
    gap: 26px;
    margin: 0 0 0 auto;
    padding: 0;
    list-style: none;
    font: 500 14px "Barlow", system-ui, sans-serif;
    white-space: nowrap;
  }
  b {
    font-weight: 600;
    color: var(--ink);
  }
  .ink2 {
    color: var(--ink2);
  }
  .ink3 {
    color: var(--ink3);
  }
  .wait {
    color: var(--wait);
  }
  .fault {
    color: var(--fault);
  }
  @media (max-width: 1100px) {
    header {
      flex-wrap: wrap;
      height: auto;
      min-height: 72px;
      padding-block: 14px;
      row-gap: 10px;
    }
    .devices {
      flex-basis: 100%;
      margin-left: 0;
    }
  }
  @media (max-width: 640px) {
    header {
      padding-inline: 16px;
    }
  }
</style>
