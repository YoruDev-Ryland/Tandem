<script lang="ts">
  import { applyConfig } from "../api/endpoints";
  import type { ApplyMode, DeviceRef } from "../api/types";
  import { nameList } from "../deviceStates";
  import Button from "./Button.svelte";
  import Dialog from "./Dialog.svelte";
  import Empty from "./Empty.svelte";
  import Field from "./Field.svelte";
  import { describeConfigError } from "./errors";

  interface Props {
    open: boolean;
    emulatorId: string;
    emulatorName: string;
    /** Frozen by the page when the dialog opens. */
    versionId: string;
    /** "v7", for the title. */
    label: string;
    /** The group the version belongs to; null for a device on its own. */
    groupName: string | null;
    /** For a device on its own: the device, which a Linked apply groups with the target. */
    ownerName: string | null;
    /** Devices a Linked apply changes back, when the version is older than the current one. */
    rollback: string[];
    /** Devices that can follow this config (Linked), and devices on their own that can take a copy. */
    linkTargets: DeviceRef[];
    copyTargets: DeviceRef[];
    onapplied: (deviceName: string, mode: ApplyMode) => void;
  }
  let {
    open = $bindable(false),
    emulatorId,
    emulatorName,
    versionId,
    label,
    groupName,
    ownerName,
    rollback,
    linkTargets,
    copyTargets,
    onapplied,
  }: Props = $props();

  const uid = $props.id();
  let mode = $state<ApplyMode>("Linked");
  let chosen = $state("");
  let busy = $state(false);
  let error = $state<unknown>(undefined);

  const targets = $derived(mode === "Linked" ? linkTargets : copyTargets);
  const device = $derived(targets.find((d) => d.id === chosen) ?? targets[0]);
  const joins = $derived(groupName ?? `a new group with ${ownerName ?? "this device"}`);

  $effect(() => {
    if (open) {
      mode = "Linked";
      chosen = linkTargets[0]?.id ?? "";
      error = undefined;
    }
  });

  function pick(m: ApplyMode) {
    mode = m;
    error = undefined;
    const list = m === "Linked" ? linkTargets : copyTargets;
    if (!list.some((d) => d.id === chosen)) chosen = list[0]?.id ?? "";
  }

  async function go() {
    if (!device || busy) return;
    busy = true;
    error = undefined;
    try {
      await applyConfig({ targetDeviceId: device.id, emulatorId, sourceVersionId: versionId, mode });
      open = false;
      onapplied(device.name, mode);
    } catch (e) {
      error = e;
    } finally {
      busy = false;
    }
  }
</script>

<Dialog bind:open title={`Apply ${label} to a device…`}>
  <form onsubmit={(e) => { e.preventDefault(); void go(); }}>
    <fieldset>
      <legend>How</legend>
      <div class="mode">
        <input id="{uid}-linked" type="radio" name="mode" value="Linked" aria-describedby="{uid}-linked-d" checked={mode === "Linked"} onchange={() => pick("Linked")} />
        <span>
          <label class="strong" for="{uid}-linked">Linked</label>
          <span id="{uid}-linked-d" class="ink2">Joins {joins} and keeps getting its changes.</span>
        </span>
      </div>
      <div class="mode">
        <input id="{uid}-copy" type="radio" name="mode" value="Copy" aria-describedby="{uid}-copy-d" checked={mode === "Copy"} onchange={() => pick("Copy")} />
        <span>
          <label class="strong" for="{uid}-copy">Copy once</label>
          <span id="{uid}-copy-d" class="ink2">Starts from this version, then stays on its own.</span>
        </span>
      </div>
    </fieldset>

    {#if targets.length === 0}
      <Empty text={mode === "Linked" ? "Every device with this emulator already uses this config." : "No other device is on its own for this emulator."} />
    {:else}
      <Field label="Device" options={targets.map((d) => ({ value: d.id, label: d.name }))} bind:value={chosen} />
      {#if mode === "Linked" && rollback.length > 0}
        <p class="ink">This also changes {nameList(rollback)} back to this version.</p>
      {/if}
      {#if mode === "Copy"}
        <p>The device's current config is saved as a version first. {emulatorName} must be closed; until then it waits.</p>
      {:else}
        <p>{emulatorName} must be closed there; until then it waits.</p>
      {/if}
    {/if}
    {#if error}<p class="fault" role="alert">{describeConfigError(error)}</p>{/if}
    <div class="actions">
      <Button variant="primary" type="submit" disabled={busy || !device}>Apply</Button>
      <Button onclick={() => (open = false)}>Cancel</Button>
    </div>
  </form>
</Dialog>

<style>
  fieldset {
    margin: 0 0 18px;
    padding: 0;
    border: 0;
  }
  legend {
    margin-bottom: 8px;
    padding: 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .mode {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 8px 0;
  }
  .mode label {
    cursor: pointer;
  }
  .mode > span {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  input[type="radio"] {
    margin: 3px 0 0;
    accent-color: var(--ink);
  }
  input[type="radio"]:focus-visible {
    outline: 2px solid var(--ink);
    outline-offset: 3px;
  }
  .strong {
    font-weight: 600;
  }
  .ink2 {
    color: var(--ink2);
    font-size: 15px;
  }
  p {
    margin: 16px 0 0;
    color: var(--ink2);
    font-size: 15px;
  }
  p.ink {
    color: var(--ink);
  }
  p.fault {
    color: var(--fault);
  }
  .actions {
    display: flex;
    gap: 10px;
    margin-top: 22px;
  }
</style>
