<script lang="ts">
  import { onMount } from "svelte";
  import { getBackup, getBackupHistory, putBackup, runBackup } from "../../lib/api/endpoints";
  import type { BackupRunInfo, BackupSettingsDto, BackupSettingsResponse, BackupTargetKind } from "../../lib/api/types";
  import { formatSize, formatWhen } from "../../lib/format";
  import Button from "../../lib/ui/Button.svelte";
  import ErrorLine from "../../lib/ui/ErrorLine.svelte";
  import Field from "../../lib/ui/Field.svelte";
  import { settingsError } from "./settings-util";

  interface Form {
    enabled: boolean;
    kind: BackupTargetKind;
    folderPath: string;
    endpoint: string;
    bucket: string;
    prefix: string;
    accessKey: string;
    region: string;
    secretKey: string;
    hour: string;
    keep: string;
    encrypt: boolean;
    passphrase: string;
    clearPassphrase: boolean;
  }

  let loaded = $state(false);
  let loadError = $state<unknown>(undefined);
  let form = $state<Form>(blank());
  let baseline = $state("");
  let passphraseSet = $state(false);
  let running = $state(false);
  let history = $state<BackupRunInfo[] | null>(null);
  let historyError = $state<unknown>(undefined);
  let busy = $state(false);
  let error = $state<string | null>(null);
  let done = $state(false);
  let runError = $state<string | null>(null);
  let started = $state(false);

  function blank(): Form {
    return {
      enabled: false, kind: "Folder", folderPath: "", endpoint: "", bucket: "", prefix: "", accessKey: "", region: "", secretKey: "",
      hour: "3", keep: "7", encrypt: false, passphrase: "", clearPassphrase: false,
    };
  }

  function apply(b: BackupSettingsResponse) {
    form = {
      enabled: b.enabled, kind: b.kind, folderPath: b.folderPath ?? "", endpoint: b.s3?.endpoint ?? "", bucket: b.s3?.bucket ?? "",
      prefix: b.s3?.prefix ?? "", accessKey: b.s3?.accessKey ?? "", region: b.s3?.region ?? "", secretKey: "",
      hour: String(b.hourUtc), keep: String(b.keep), encrypt: b.encrypt, passphrase: "", clearPassphrase: false,
    };
    passphraseSet = b.passphraseSet;
    baseline = JSON.stringify(form);
  }
  async function loadHistory() {
    try {
      history = await getBackupHistory();
      historyError = undefined;
    } catch (e) {
      historyError = e;
    }
  }
  onMount(() => {
    getBackup().then(
      (b) => {
        apply(b);
        running = b.running ?? false;
        loaded = true;
      },
      (e) => (loadError = e),
    );
    void loadHistory();
  });

  const intIn = (v: string, lo: number, hi: number) => /^\d+$/.test(v) && Number(v) >= lo && Number(v) <= hi;
  const hourOk = $derived(intIn(form.hour, 0, 23));
  const keepOk = $derived(intIn(form.keep, 1, 1000));
  const willHavePassphrase = $derived(form.passphrase !== "" || (passphraseSet && !form.clearPassphrase));
  const encryptProblem = $derived(form.encrypt && !willHavePassphrase);
  const folderProblem = $derived(form.kind === "Folder" && form.folderPath.trim() !== "" && !form.folderPath.trim().startsWith("/"));
  const missing = $derived(
    form.enabled && (form.kind === "Folder" ? form.folderPath.trim() === "" : form.bucket.trim() === "" || form.accessKey.trim() === ""),
  );
  const valid = $derived(hourOk && keepOk && !encryptProblem && !folderProblem && !missing);
  const dirty = $derived(loaded && JSON.stringify(form) !== baseline);

  function toDto(): BackupSettingsDto {
    return {
      enabled: form.enabled,
      kind: form.kind,
      folderPath: form.kind === "Folder" ? form.folderPath.trim() || null : null,
      // A Folder target still sends any stored S3 block: the server would otherwise discard it and its secret key.
      s3:
        form.kind === "S3" || form.bucket || form.accessKey || form.endpoint || form.prefix || form.region || form.secretKey
          ? {
              endpoint: form.endpoint.trim() || null,
              bucket: form.bucket.trim(),
              prefix: form.prefix.trim() || null,
              accessKey: form.accessKey.trim(),
              // null keeps the stored key.
              secretKey: form.secretKey !== "" ? form.secretKey : null,
              region: form.region.trim() || null,
            }
          : null,
      hourUtc: Number(form.hour),
      keep: Number(form.keep),
      encrypt: form.encrypt,
      // null keeps the stored passphrase, "" clears it.
      passphrase: form.passphrase !== "" ? form.passphrase : form.clearPassphrase ? "" : null,
    };
  }

  async function submit(e: SubmitEvent) {
    e.preventDefault();
    if (!dirty || !valid || busy) return;
    busy = true;
    error = null;
    done = false;
    try {
      apply(await putBackup(toDto()));
      done = true;
    } catch (err) {
      error = settingsError(err);
    } finally {
      busy = false;
    }
  }

  async function runNow() {
    runError = null;
    started = false;
    try {
      await runBackup();
      started = true;
      running = true;
    } catch (err) {
      runError = settingsError(err);
      return;
    }
    await loadHistory();
  }
</script>

<div class="sec wide">
  <h2>Backup</h2>
  {#if loadError}
    <ErrorLine error={loadError} />
  {:else if !loaded}
    <p class="note" aria-busy="true">— — —</p>
  {:else}
    <form onsubmit={submit}>
      <label class="check"><input type="checkbox" bind:checked={form.enabled} onchange={() => (done = false)} />Back up every day</label>
      <Field label="Target" options={[{ value: "Folder", label: "Folder" }, { value: "S3", label: "S3" }]} bind:value={form.kind} />

      {#if form.kind === "Folder"}
        <Field label="Folder path" mono placeholder="/mnt/nas/emusync" bind:value={form.folderPath} />
        {#if folderProblem}<p class="fault">Use an absolute path that starts with /.</p>{/if}
      {:else}
        <div class="two">
          <Field label="Endpoint" mono placeholder="Leave empty for Amazon S3" bind:value={form.endpoint} />
          <Field label="Region" mono placeholder="us-east-1" bind:value={form.region} />
          <Field label="Bucket" mono bind:value={form.bucket} />
          <Field label="Prefix" mono bind:value={form.prefix} />
          <Field label="Access key" mono autocomplete="off" bind:value={form.accessKey} />
          <Field label="Secret key" type="password" autocomplete="off" placeholder="Leave empty to keep the saved key" bind:value={form.secretKey} />
        </div>
      {/if}

      <div class="two">
        <Field label="Hour (UTC)" type="number" min={0} max={23} step={1} mono bind:value={form.hour} />
        <Field label="Keep" type="number" min={1} max={1000} step={1} mono bind:value={form.keep} />
      </div>
      {#if !hourOk}<p class="fault">Enter a whole hour from 0 to 23.</p>{/if}
      {#if !keepOk}<p class="fault">Enter a whole number from 1 to 1000.</p>{/if}

      <label class="check"><input type="checkbox" bind:checked={form.encrypt} />Encrypt backups with a passphrase</label>
      <Field label="New passphrase" type="password" autocomplete="new-password" placeholder="Leave empty to keep it" bind:value={form.passphrase} disabled={form.clearPassphrase} />
      <div class="actions">
        <span class="ink2">{form.passphrase !== "" ? "New passphrase" : passphraseSet && !form.clearPassphrase ? "Passphrase set" : "No passphrase"}</span>
        {#if passphraseSet && !form.clearPassphrase}
          <Button onclick={() => { form.clearPassphrase = true; form.passphrase = ""; }}>Remove passphrase</Button>
        {:else if form.clearPassphrase}
          <Button onclick={() => (form.clearPassphrase = false)}>Keep passphrase</Button>
        {/if}
      </div>
      <p class="note">If you lose the passphrase, that backup can't be restored.</p>
      {#if encryptProblem}<p class="fault">Set a passphrase to encrypt backups.</p>{/if}
      {#if missing}<p class="fault">Fill in the target before turning backups on.</p>{/if}

      {#if error}<p class="fault" role="alert">{error}</p>{/if}
      {#if done}<p class="done" role="status">Saved.</p>{/if}
      <div class="actions"><Button type="submit" variant="primary" disabled={!dirty || !valid || busy}>Save</Button></div>
    </form>

    <h3>Backups</h3>
    <div class="actions">
      <Button onclick={runNow}>Run now</Button>
      {#if started}<span class="note">Backup started.</span>
      {:else if running}<span class="note">A backup is running. It appears below when it finishes.</span>{/if}
    </div>
    {#if runError}<p class="fault" role="alert">{runError}</p>{/if}
    {#if historyError}
      <ErrorLine error={historyError} />
    {:else if history && history.length === 0}
      <p class="note">No backups yet.</p>
    {:else if history}
      <ul class="rows" aria-label="Backup history">
        {#each history as r (r.utc)}
          <li>
            <span class="mono when">{formatWhen(r.utc)}</span>
            {#if r.ok}
              <span class="mono">{formatSize(r.bytes)}</span>
            {:else}
              <span class="fault">{r.error ?? "Failed"}</span>
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
  {/if}
</div>

<style>
  .two {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }
  .when {
    min-width: 180px;
  }
</style>
