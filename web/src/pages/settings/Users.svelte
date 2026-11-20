<script lang="ts">
  import { onMount } from "svelte";
  import { createUser, deleteUser, getAdminSettings, getUsers, putAdminSettings } from "../../lib/api/endpoints";
  import type { CreatedUserResponse, UserInfo } from "../../lib/api/types";
  import { formatWhen } from "../../lib/format";
  import Button from "../../lib/ui/Button.svelte";
  import Dialog from "../../lib/ui/Dialog.svelte";
  import ErrorLine from "../../lib/ui/ErrorLine.svelte";
  import Field from "../../lib/ui/Field.svelte";
  import { settingsError } from "./settings-util";

  let users = $state<UserInfo[] | null>(null);
  let loadError = $state<unknown>(undefined);
  let allowRegistration = $state(false);
  let regError = $state<string | null>(null);

  async function load() {
    try {
      users = await getUsers();
      allowRegistration = (await getAdminSettings()).allowRegistration;
    } catch (e) {
      loadError = e;
    }
  }
  onMount(() => void load());

  async function toggleRegistration(e: Event & { currentTarget: HTMLInputElement }) {
    const want = e.currentTarget.checked;
    regError = null;
    try {
      allowRegistration = (await putAdminSettings({ allowRegistration: want })).allowRegistration;
    } catch (err) {
      allowRegistration = !want;
      regError = settingsError(err);
    }
  }

  // Create. The generated password lives only in `created`, which closing the dialog clears.
  let createOpen = $state(false);
  let email = $state("");
  let isAdmin = $state(false);
  let created = $state<CreatedUserResponse | null>(null);
  let createError = $state<string | null>(null);
  let creating = $state(false);
  let copied = $state(false);

  function openCreate() {
    email = "";
    isAdmin = false;
    created = null;
    copied = false;
    createError = null;
    createOpen = true;
  }
  async function submitCreate(e: SubmitEvent) {
    e.preventDefault();
    if (creating || !email.trim()) return;
    creating = true;
    createError = null;
    try {
      copied = false;
      created = await createUser({ email: email.trim(), isAdmin });
      users = await getUsers();
    } catch (err) {
      createError = settingsError(err);
    } finally {
      creating = false;
    }
  }
  const afterCreateClose = () => {
    created = null;
    email = "";
  };

  async function copy() {
    try {
      await navigator.clipboard.writeText(created!.password);
      copied = true;
    } catch {
      copied = false;
    }
  }

  // Delete. The target is frozen when the dialog opens.
  let deleteOpen = $state(false);
  let target = $state<UserInfo | null>(null);
  let deleteError = $state<string | null>(null);
  let deleting = $state(false);
  function askDelete(u: UserInfo) {
    target = u;
    deleteError = null;
    deleteOpen = true;
  }
  async function confirmDelete() {
    if (!target) return;
    deleting = true;
    deleteError = null;
    try {
      await deleteUser(target.id);
      deleteOpen = false;
      users = await getUsers();
    } catch (err) {
      deleteError = settingsError(err);
    } finally {
      deleting = false;
    }
  }
</script>

<div class="sec wide">
  <h2>Users</h2>
  {#if loadError}
    <ErrorLine error={loadError} />
  {:else if !users}
    <p class="note" aria-busy="true">— — —</p>
  {:else}
    <label class="check">
      <input type="checkbox" checked={allowRegistration} onchange={toggleRegistration} />
      Allow new accounts to register
    </label>
    {#if regError}<p class="fault" role="alert">{regError}</p>{/if}

    <ul class="rows">
      {#each users as u (u.id)}
        <li>
          <span class="who">{u.email}{#if u.isAdmin}<span class="ink2"> · admin</span>{/if}</span>
          <span class="ink3">{u.deviceCount} {u.deviceCount === 1 ? "device" : "devices"}</span>
          <span class="ink3 when">Since {formatWhen(u.createdUtc)}</span>
          <Button onclick={() => askDelete(u)} aria-label="Delete {u.email}">Delete</Button>
        </li>
      {/each}
    </ul>
    <div class="actions"><Button variant="primary" onclick={openCreate}>Add user</Button></div>
  {/if}
</div>

<Dialog bind:open={createOpen} locked={creating} title={created ? "User created" : "Add user"} onclose={afterCreateClose}>
  {#if created}
    <div class="sec">
      <p class="note">Password for <span class="mono">{created.email}</span></p>
      <p class="mono secret">{created.password}</p>
      <p class="note">Copy it now. It won't be shown again.</p>
      <div class="actions">
        <Button onclick={copy}>{copied ? "Copied" : "Copy"}</Button>
        <Button variant="primary" onclick={() => (createOpen = false)}>Done</Button>
      </div>
    </div>
  {:else}
    <form class="sec" onsubmit={submitCreate}>
      <Field label="Email" type="email" bind:value={email} />
      <label class="check"><input type="checkbox" bind:checked={isAdmin} />Make this user an admin</label>
      {#if createError}<p class="fault" role="alert">{createError}</p>{/if}
      <div class="actions">
        <Button type="submit" variant="primary" disabled={creating || !email.trim()}>Create</Button>
        <Button onclick={() => (createOpen = false)} disabled={creating}>Cancel</Button>
      </div>
    </form>
  {/if}
</Dialog>

<Dialog bind:open={deleteOpen} title="Delete user">
  <div class="sec">
    <p class="note">Delete <span class="mono">{target?.email}</span>? Their saves, configs and devices are removed from this server.</p>
    {#if deleteError}<p class="fault" role="alert">{deleteError}</p>{/if}
    <div class="actions">
      <Button variant="primary" onclick={confirmDelete} disabled={deleting}>Delete user</Button>
      <Button onclick={() => (deleteOpen = false)}>Cancel</Button>
    </div>
  </div>
</Dialog>

<style>
  .who {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .when {
    min-width: 170px;
  }
  .secret {
    margin: 0;
    padding: 12px;
    border: 1px solid var(--rule2);
    border-radius: 3px;
    color: var(--ink);
    user-select: all;
    overflow-wrap: anywhere;
  }
</style>
