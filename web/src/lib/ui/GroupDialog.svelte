<script lang="ts" module>
  import type { DeviceRef } from "../api/types";

  export type GroupTarget =
    | { kind: "group"; id: string; name: string; members: DeviceRef[] }
    | { kind: "solo"; device: DeviceRef };
</script>

<script lang="ts">
  import { createGroup, deleteGroup, joinGroup, leaveGroup } from "../api/endpoints";
  import { nameList } from "../deviceStates";
  import Button from "./Button.svelte";
  import Dialog from "./Dialog.svelte";
  import Field from "./Field.svelte";
  import { describeConfigError } from "./errors";

  interface Props {
    open: boolean;
    emulatorId: string;
    emulatorName: string;
    /** The group or device the dialog opened on, kept current by the page. */
    target: GroupTarget;
    /** This emulator's groups, which a device on its own can join. */
    groups: { id: string; name: string }[];
    /** Devices on their own, which a group can add. */
    solo: DeviceRef[];
    /** Called after every change, with a line for the page. */
    onchanged: (message: string) => void;
  }
  let { open = $bindable(false), emulatorId, emulatorName, target, groups, solo, onchanged }: Props = $props();

  let name = $state("");
  let adding = $state("");
  let confirming = $state(false);
  let busy = $state(false);
  let error = $state<unknown>(undefined);

  const title = $derived(target.kind === "group" ? target.name : target.device.name);
  const addable = $derived(solo.find((d) => d.id === adding) ?? solo[0]);

  $effect(() => {
    if (open) {
      name = "";
      adding = "";
      confirming = false;
      error = undefined;
    }
  });

  async function run(work: () => Promise<string>, close: boolean) {
    if (busy) return;
    busy = true;
    error = undefined;
    try {
      const message = await work();
      if (close) open = false;
      onchanged(message);
    } catch (e) {
      error = e;
    } finally {
      busy = false;
    }
  }

  function make() {
    if (target.kind !== "solo" || !name.trim()) return;
    const seed = target.device;
    void run(async () => {
      await createGroup({ emulatorId, name: name.trim(), seedDeviceId: seed.id });
      return `Made ${name.trim()} from ${seed.name}'s settings.`;
    }, true);
  }

  function join(group: { id: string; name: string }, device: DeviceRef, close: boolean) {
    void run(async () => {
      await joinGroup(group.id, device.id);
      return `${device.name} joined ${group.name}. ${emulatorName} must be closed there; until then it waits.`;
    }, close);
  }

  function remove(group: { id: string; name: string }, device: DeviceRef) {
    void run(async () => {
      await leaveGroup(group.id, device.id);
      return `${device.name} left ${group.name} and is on its own.`;
    }, false);
  }

  function destroy() {
    if (target.kind !== "group") return;
    const group = { id: target.id, name: target.name, members: [...target.members] };
    void run(async () => {
      // Only an empty group can be deleted: every member goes back to being on its own first.
      for (const m of group.members) await leaveGroup(group.id, m.id);
      await deleteGroup(group.id);
      return `Deleted ${group.name}.`;
    }, true);
  }
</script>

<Dialog bind:open {title}>
  {#if target.kind === "group"}
    {@const group = target}
    {#if confirming}
      <p>Members keep their current files and go back to being on their own.</p>
      <div class="actions">
        <Button variant="primary" onclick={destroy} disabled={busy}>Delete {group.name}</Button>
        <Button onclick={() => (confirming = false)} autofocus>Keep the group</Button>
      </div>
    {:else}
      <p>{group.members.length > 0 ? `${nameList(group.members.map((m) => m.name))} share these settings.` : "No device follows this group."} Folder paths stay per device.</p>
      <ul>
        {#each group.members as m (m.id)}
          <li>
            <span>{m.name}</span>
            <Button aria-label={`Remove ${m.name}`} onclick={() => remove(group, m)} disabled={busy}>Remove</Button>
          </li>
        {/each}
      </ul>
      <p class="small">A device you remove keeps its current files and goes back to being on its own.</p>
      {#if solo.length > 0 && addable}
        <form class="row" onsubmit={(e) => { e.preventDefault(); join(group, addable, false); }}>
          <Field label="Add a device" options={solo.map((d) => ({ value: d.id, label: d.name }))} bind:value={adding} />
          <Button type="submit" disabled={busy}>Add</Button>
        </form>
        <p class="small">It takes the group's settings. Its current config is kept as a version first.</p>
      {/if}
      <div class="actions">
        <Button onclick={() => (confirming = true)} disabled={busy}>Delete group</Button>
        <Button onclick={() => (open = false)}>Close</Button>
      </div>
    {/if}
  {:else}
    {@const device = target.device}
    <p>On its own: backed up, never changed by other devices.</p>
    <form class="row" onsubmit={(e) => { e.preventDefault(); make(); }}>
      <Field label="Group name" bind:value={name} maxlength={64} />
      <Button type="submit" variant="primary" disabled={busy || !name.trim()}>Make a group</Button>
    </form>
    <p class="small">The group starts from {device.name}'s current settings. Add other devices to it afterwards.</p>
    {#if groups.length > 0}
      <div class="joins">
        {#each groups as g (g.id)}
          <Button onclick={() => join(g, device, true)} disabled={busy}>Join {g.name}</Button>
        {/each}
      </div>
      <p class="small">Joining takes the group's settings. {device.name}'s current config is kept as a version first.</p>
    {/if}
    <div class="actions">
      <Button onclick={() => (open = false)}>Close</Button>
    </div>
  {/if}
  {#if error}<p class="fault" role="alert">{describeConfigError(error)}</p>{/if}
</Dialog>

<style>
  p {
    margin: 0 0 16px;
    color: var(--ink2);
    font-size: 15px;
  }
  .small {
    margin: 10px 0 18px;
    font-size: 14px;
  }
  ul {
    margin: 0;
    padding: 0;
    list-style: none;
  }
  li {
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 52px;
    border-top: 1px solid var(--rule);
  }
  li:last-child {
    border-bottom: 1px solid var(--rule);
  }
  .row {
    display: flex;
    align-items: flex-end;
    gap: 10px;
  }
  .row > :global(label) {
    flex: 1;
  }
  .joins {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }
  .actions {
    display: flex;
    gap: 10px;
    margin-top: 22px;
  }
  .fault {
    margin: 16px 0 0;
    color: var(--fault);
  }
</style>
