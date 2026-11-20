<script lang="ts">
  import { restore } from "../api/endpoints";
  import Button from "./Button.svelte";
  import Dialog from "./Dialog.svelte";
  import Empty from "./Empty.svelte";
  import ErrorLine from "./ErrorLine.svelte";
  import Field from "./Field.svelte";

  interface Props {
    open: boolean;
    itemId: string;
    versionId: string;
    /** "v3", for the title. */
    label: string;
    emulatorName: string;
    /** Only devices the game is installed on. */
    devices: { id: string; name: string }[];
    /** Called with the device name once the restore is queued. */
    onrestored: (deviceName: string) => void;
  }
  let { open = $bindable(false), itemId, versionId, label, emulatorName, devices, onrestored }: Props = $props();

  let chosen = $state("");
  let busy = $state(false);
  let error = $state<unknown>(undefined);

  const device = $derived(devices.find((d) => d.id === chosen) ?? devices[0]);

  $effect(() => {
    if (open) {
      chosen = devices[0]?.id ?? "";
      error = undefined;
    }
  });

  async function go() {
    if (!device || busy) return;
    busy = true;
    error = undefined;
    try {
      await restore(itemId, versionId, device.id);
      open = false;
      onrestored(device.name);
    } catch (e) {
      error = e;
    } finally {
      busy = false;
    }
  }
</script>

<Dialog bind:open title={`Restore ${label} to…`}>
  {#if devices.length === 0}
    <Empty text="This game isn't on any device yet." />
  {:else}
    <form onsubmit={(e) => { e.preventDefault(); void go(); }}>
      <Field label="Restore to" options={devices.map((d) => ({ value: d.id, label: d.name }))} bind:value={chosen} />
      <p>{device?.name}'s current save is kept as a new version first. If {emulatorName} is open there, it waits until it closes.</p>
      {#if error}<ErrorLine {error} />{/if}
      <div class="actions">
        <Button variant="primary" type="submit" disabled={busy}>Restore</Button>
        <Button onclick={() => (open = false)}>Cancel</Button>
      </div>
    </form>
  {/if}
</Dialog>

<style>
  p {
    margin: 16px 0;
    color: var(--ink2);
    font-size: 15px;
  }
  .actions {
    display: flex;
    gap: 10px;
    margin-top: 20px;
  }
</style>
