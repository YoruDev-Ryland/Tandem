<script lang="ts">
  import { changePassword, deleteAccount } from "../../lib/api/endpoints";
  import { ApiError } from "../../lib/api/http";
  import Dialog from "../../lib/ui/Dialog.svelte";
  import { session } from "../../lib/session.svelte";
  import Button from "../../lib/ui/Button.svelte";
  import Field from "../../lib/ui/Field.svelte";
  import { settingsError } from "./settings-util";

  let current = $state("");
  let next = $state("");
  let busy = $state(false);
  let error = $state<string | null>(null);
  let done = $state(false);
  let signOutError = $state<string | null>(null);

  const tooShort = $derived(next.length > 0 && next.length < 8);
  const ready = $derived(current.length > 0 && next.length >= 8 && !busy);

  async function submit(e: SubmitEvent) {
    e.preventDefault();
    if (!ready) return;
    busy = true;
    error = null;
    done = false;
    try {
      await changePassword({ current, new: next });
      current = "";
      next = "";
      done = true;
    } catch (err) {
      error = settingsError(err);
    } finally {
      busy = false;
    }
  }

  // Delete the account: the password confirms it.
  let deleteOpen = $state(false);
  let deletePassword = $state("");
  let deleting = $state(false);
  let deleteError = $state<string | null>(null);
  function openDelete() {
    deletePassword = "";
    deleteError = null;
    deleteOpen = true;
  }
  async function confirmDelete(e: SubmitEvent) {
    e.preventDefault();
    if (deleting || !deletePassword) return;
    deleting = true;
    deleteError = null;
    try {
      await deleteAccount(deletePassword);
      deleteOpen = false;
      // The server cleared the session cookie with the account.
      session.forget();
    } catch (err) {
      deleteError =
        err instanceof ApiError && err.code === "bad_password"
          ? "That password is not right."
          : err instanceof ApiError && err.code === "last_admin"
            ? "You are the only admin and other people have accounts here. Make someone else an admin first, or delete their accounts."
            : settingsError(err);
    } finally {
      deleting = false;
    }
  }

  async function signOut() {
    signOutError = null;
    try {
      await session.signOut();
    } catch (err) {
      signOutError = settingsError(err);
    }
  }
</script>

<div class="sec">
  <h2>Account</h2>
  <p class="note">Signed in as <span class="mono">{session.account?.email}</span></p>

  <form onsubmit={submit}>
    <Field label="Current password" type="password" autocomplete="current-password" bind:value={current} oninput={() => (done = false)} />
    <Field label="New password" type="password" autocomplete="new-password" bind:value={next} oninput={() => (done = false)} />
    {#if tooShort}<p class="fault">Use at least 8 characters.</p>{/if}
    {#if error}<p class="fault" role="alert">{error}</p>{/if}
    {#if done}<p class="done" role="status">Password changed. Your other browsers were signed out.</p>{/if}
    {#if busy}<p class="note">Changing your password. This can take a moment.</p>{/if}
    <div class="actions"><Button type="submit" variant="primary" disabled={!ready}>Change password</Button></div>
  </form>

  <div class="actions">
    <Button onclick={signOut}>Sign out</Button>
    {#if signOutError}<p class="fault" role="alert">{signOutError}</p>{/if}
  </div>

  <div class="delete">
    <h3>Delete my account</h3>
    <p class="note">Removes your account and everything stored for it on this server: saves, states, configs, the vault and devices. Files on your devices are not touched.</p>
    <div class="actions"><Button onclick={openDelete}>Delete my account…</Button></div>
  </div>
</div>

<Dialog bind:open={deleteOpen} locked={deleting} title="Delete your account?">
  <form class="sec" onsubmit={confirmDelete}>
    <p class="note">Everything stored for <span class="mono">{session.account?.email}</span> is removed from this server, and it can't be undone. Export your saves first from Settings › Storage if you want a copy.</p>
    <Field label="Password" type="password" autocomplete="current-password" bind:value={deletePassword} />
    {#if deleteError}<p class="fault" role="alert">{deleteError}</p>{/if}
    <div class="actions">
      <Button type="submit" variant="primary" disabled={deleting || !deletePassword}>Delete my account</Button>
      <Button onclick={() => (deleteOpen = false)} disabled={deleting}>Cancel</Button>
    </div>
  </form>
</Dialog>

<style>
  .delete {
    display: flex;
    flex-direction: column;
    gap: 12px;
    margin-top: 28px;
    padding-top: 20px;
    border-top: 1px solid var(--rule);
  }
</style>
