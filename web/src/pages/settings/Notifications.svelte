<script lang="ts">
  import { onMount } from "svelte";
  import { getNotifications, putNotifications, testNotifications } from "../../lib/api/endpoints";
  import type { NotifyEvents, NotifyKind, NotifySettingsDto, NotifyTestResult } from "../../lib/api/types";
  import Button from "../../lib/ui/Button.svelte";
  import ErrorLine from "../../lib/ui/ErrorLine.svelte";
  import Field from "../../lib/ui/Field.svelte";
  import { settingsError } from "./settings-util";

  const MAX_TARGETS = 10;
  const KINDS = [
    { value: "Ntfy", label: "ntfy" },
    { value: "Discord", label: "Discord" },
    { value: "Webhook", label: "Webhook" },
  ];
  const EVENTS: [keyof NotifyEvents, string][] = [
    ["conflicts", "Conflicts"],
    ["failedSyncs", "Failed syncs"],
    ["shizukuOff", "Shizuku turned off"],
    ["backupFailed", "Backup failed"],
  ];

  interface Target {
    id: number;
    kind: NotifyKind;
    /** The real URL. For Discord and Webhook targets it is never shown, only its host. */
    url: string;
    /** Write-only: starts empty and is sent only when typed. */
    token: string;
    tokenSet: boolean;
    removeToken: boolean;
    editingUrl: boolean;
    original: string;
  }

  let nextId = 0;
  let loaded = $state(false);
  let loadError = $state<unknown>(undefined);
  let targets = $state<Target[]>([]);
  let events = $state<NotifyEvents>({ conflicts: true, failedSyncs: true, shizukuOff: true, backupFailed: true });
  let baseline = $state("");
  let busy = $state(false);
  let error = $state<string | null>(null);
  let done = $state(false);
  let testing = $state(false);
  let results = $state<NotifyTestResult[] | null>(null);
  let testError = $state<string | null>(null);

  const secretUrl = (k: NotifyKind) => k !== "Ntfy";
  /** "discord.com/…": the host only, since the rest of a webhook address is a credential. */
  function maskUrl(url: string): string {
    try {
      return `${new URL(url).host}/…`;
    } catch {
      return "Saved address";
    }
  }

  function apply(s: NotifySettingsDto) {
    targets = s.targets.map((t) => ({
      id: nextId++, kind: t.kind, url: t.url, token: "", tokenSet: !!t.tokenSet, removeToken: false, editingUrl: false, original: t.url,
    }));
    events = { ...s.events };
    baseline = snapshot();
  }

  const payload = (): NotifySettingsDto => ({
    targets: targets.map((t) => ({
      kind: t.kind,
      url: t.url.trim(),
      // null keeps the stored token, "" clears it, text replaces it.
      token: t.token !== "" ? t.token : t.removeToken ? "" : null,
    })),
    events: { ...events },
  });
  const snapshot = () => JSON.stringify(payload());

  onMount(() => {
    getNotifications().then(
      (s) => {
        apply(s);
        loaded = true;
      },
      (e) => (loadError = e),
    );
  });

  const dirty = $derived(loaded && snapshot() !== baseline);
  const urlProblem = (t: Target) => (t.url.trim() === "" ? "Enter the address to send to." : !/^https?:\/\//i.test(t.url.trim()) ? "The address must start with http:// or https://." : null);
  const valid = $derived(targets.every((t) => urlProblem(t) === null));

  function add() {
    targets.push({ id: nextId++, kind: "Ntfy", url: "", token: "", tokenSet: false, removeToken: false, editingUrl: true, original: "" });
    done = false;
  }
  function edit(t: Target) {
    t.url = "";
    t.editingUrl = true;
  }
  function keepUrl(t: Target) {
    t.url = t.original;
    t.editingUrl = false;
  }

  async function submit(e: SubmitEvent) {
    e.preventDefault();
    if (!dirty || !valid || busy) return;
    busy = true;
    error = null;
    done = false;
    try {
      apply(await putNotifications(payload()));
      results = null;
      done = true;
    } catch (err) {
      error = settingsError(err);
    } finally {
      busy = false;
    }
  }

  async function sendTest() {
    testing = true;
    testError = null;
    results = null;
    try {
      results = await testNotifications();
    } catch (err) {
      testError = settingsError(err);
    } finally {
      testing = false;
    }
  }

  const kindLabel = (k: NotifyKind) => KINDS.find((x) => x.value === k)!.label;
  const resultText = (r: NotifyTestResult) => (r.ok ? `Sent${r.status ? ` (${r.status})` : ""}` : `Failed${r.status ? ` (${r.status})` : ""}${r.error ? `: ${r.error}` : ""}`);
</script>

<div class="sec wide">
  <h2>Notifications</h2>
  {#if loadError}
    <ErrorLine error={loadError} />
  {:else if !loaded}
    <p class="note" aria-busy="true">— — —</p>
  {:else}
    <form onsubmit={submit}>
      {#if targets.length === 0}<p class="note">No destinations yet. Add one to hear about conflicts and problems.</p>{/if}
      {#each targets as t, i (t.id)}
        <fieldset class="target">
          <legend>Destination {i + 1}</legend>
          <div class="grid">
            <Field label="Kind" options={KINDS} bind:value={t.kind} />
            {#if secretUrl(t.kind) && !t.editingUrl}
              <div class="masked">
                <span class="lbl">URL</span>
                <span class="mono">{maskUrl(t.url)}</span>
              </div>
              <Button onclick={() => edit(t)}>Edit URL</Button>
            {:else}
              <Field label="URL" type="url" mono bind:value={t.url} placeholder="https://" oninput={() => (done = false)} />
              {#if t.original && secretUrl(t.kind)}<Button onclick={() => keepUrl(t)}>Keep current URL</Button>{/if}
            {/if}
          </div>
          {#if t.kind === "Ntfy"}
            <div class="grid token">
              <Field label="Token" type="password" autocomplete="off" placeholder="Leave empty to keep it" bind:value={t.token} disabled={t.removeToken} />
              <span class="status ink2">{t.token !== "" ? "New token" : t.tokenSet && !t.removeToken ? "Token set" : "No token"}</span>
              {#if t.tokenSet && !t.removeToken}
                <Button onclick={() => { t.removeToken = true; t.token = ""; }}>Remove token</Button>
              {:else if t.removeToken}
                <Button onclick={() => (t.removeToken = false)}>Keep token</Button>
              {/if}
            </div>
          {/if}
          {#if urlProblem(t) && (t.editingUrl || t.url !== "")}<p class="fault">{urlProblem(t)}</p>{/if}
          <div class="actions"><Button onclick={() => { targets.splice(i, 1); done = false; }}>Remove destination</Button></div>
        </fieldset>
      {/each}
      <div class="actions"><Button onclick={add} disabled={targets.length >= MAX_TARGETS}>Add destination</Button></div>

      <fieldset>
        <legend>Tell me about</legend>
        {#each EVENTS as [key, label] (key)}
          <label class="check"><input type="checkbox" bind:checked={events[key]} onchange={() => (done = false)} />{label}</label>
        {/each}
      </fieldset>

      {#if error}<p class="fault" role="alert">{error}</p>{/if}
      {#if done}<p class="done" role="status">Saved.</p>{/if}
      <div class="actions">
        <Button type="submit" variant="primary" disabled={!dirty || !valid || busy}>Save</Button>
        <Button onclick={sendTest} disabled={testing || dirty || targets.length === 0}>Send a test</Button>
        {#if dirty}<span class="note">Save your changes to send a test.</span>{/if}
        {#if testing}<span class="note">Sending to each destination. This can take a few seconds.</span>{/if}
      </div>
    </form>

    {#if testError}<p class="fault" role="alert">{testError}</p>{/if}
    {#if results}
      <ul class="rows" aria-label="Test results">
        {#each results as r, i (i)}
          <li><span class="kind">{kindLabel(r.kind)}</span><span class={r.ok ? "ink2" : "fault"}>{resultText(r)}</span></li>
        {/each}
      </ul>
    {/if}
  {/if}
</div>

<style>
  .target {
    padding-bottom: 14px;
    border-bottom: 1px solid var(--rule);
    gap: 12px;
  }
  .grid {
    display: grid;
    grid-template-columns: 140px minmax(0, 1fr) auto;
    gap: 12px;
    align-items: end;
  }
  .grid.token {
    grid-template-columns: minmax(0, 1fr) 100px auto;
  }
  .masked {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .masked .mono {
    height: 40px;
    display: flex;
    align-items: center;
  }
  .lbl {
    color: var(--ink2);
    font-size: 14px;
  }
  .status {
    height: 40px;
    display: flex;
    align-items: center;
    font-size: 14px;
  }
  .kind {
    width: 100px;
  }
</style>
