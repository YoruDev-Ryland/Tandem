<script lang="ts">
  import { changePassword } from "../../lib/api/endpoints";
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
</div>
