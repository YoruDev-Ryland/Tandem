<script lang="ts">
  import { restore } from "../api/endpoints";
  import Button from "./Button.svelte";
  import Dialog from "./Dialog.svelte";
  import Empty from "./Empty.svelte";
  import ErrorLine from "./ErrorLine.svelte";

  interface Props {
    open: boolean;
    itemId: string;
    versionId: string;
    /** "v3", for the title. */
    label: string;
    emulatorName: string;
    /**
     * The device the restore is recorded against in Activity. The server needs one of the account's devices, but a
     * restore is a rollback: it makes a new current save that reaches every device with the game, not just this one.
     */
    deviceId: string | undefined;
    /** Called once the new current save is made. */
    onrestored: () => void;
  }
  let { open = $bindable(false), itemId, versionId, label, emulatorName, deviceId, onrestored }: Props = $props();

  let busy = $state(false);
  let error = $state<unknown>(undefined);

  $effect(() => {
    if (open) error = undefined;
  });

  async function go() {
    if (!deviceId || busy) return;
    busy = true;
    error = undefined;
    try {
      await restore(itemId, versionId, deviceId);
      open = false;
      onrestored();
    } catch (e) {
      error = e;
    } finally {
      busy = false;
    }
  }
</script>

<Dialog bind:open title={`Restore ${label} — every device gets it`}>
  {#if !deviceId}
    <Empty text="Pair a device first: a restore goes to the devices that have this game." />
  {:else}
    <form onsubmit={(e) => { e.preventDefault(); void go(); }}>
      <p>{label} becomes the current save again, and every device that has this game gets it.</p>
      <p>Nothing is lost: the save that is current now is kept in history, and each device backs up its own copy before it takes {label}. Where {emulatorName} is open, the save waits until it closes.</p>
      {#if error}<ErrorLine {error} />{/if}
      <div class="actions">
        <Button variant="primary" type="submit" glyph="a" disabled={busy}>Restore {label}</Button>
        <Button onclick={() => (open = false)}>Cancel</Button>
      </div>
    </form>
  {/if}
</Dialog>

<style>
  p {
    margin: 0 0 12px;
    color: var(--ink2);
    font-size: 15px;
  }
  p:first-child {
    color: var(--ink);
  }
  .actions {
    display: flex;
    gap: 10px;
    margin-top: 20px;
  }
</style>
