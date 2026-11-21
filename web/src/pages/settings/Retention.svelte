<script lang="ts">
  import { onMount } from "svelte";
  import { getRetention, putRetention } from "../../lib/api/endpoints";
  import type { RetentionSettingsDto } from "../../lib/api/types";
  import Button from "../../lib/ui/Button.svelte";
  import ErrorLine from "../../lib/ui/ErrorLine.svelte";
  import Field from "../../lib/ui/Field.svelte";
  import { settingsError } from "./settings-util";

  const MIN = 1;
  const MAX = 1000;
  const FIELDS = [
    ["saves", "Save versions per game"],
    ["configs", "Config versions per emulator"],
    ["vault", "Vault versions per file"],
  ] as const;

  let loaded = $state(false);
  let loadError = $state<unknown>(undefined);
  const AGE_MAX = 3650;
  let saved = $state({ saves: "", configs: "", vault: "", age: "" });
  let form = $state({ saves: "", configs: "", vault: "", age: "" });
  let busy = $state(false);
  let error = $state<string | null>(null);
  let done = $state(false);

  onMount(() => {
    getRetention().then(
      (r) => {
        saved = fromDto(r);
        form = { ...saved };
        loaded = true;
      },
      (e) => (loadError = e),
    );
  });

  const fromDto = (r: RetentionSettingsDto) => ({
    saves: String(r.saveVersions), configs: String(r.configVersions), vault: String(r.vaultVersions), age: String(r.stateMaxAgeDays ?? 0),
  });
  const validAge = (v: string) => /^\d+$/.test(v) && Number(v) <= AGE_MAX;
  const valid = (v: string) => /^\d+$/.test(v) && Number(v) >= MIN && Number(v) <= MAX;
  const allValid = $derived(valid(form.saves) && valid(form.configs) && valid(form.vault) && validAge(form.age));
  const dirty = $derived(form.saves !== saved.saves || form.configs !== saved.configs || form.vault !== saved.vault || form.age !== saved.age);

  async function submit(e: SubmitEvent) {
    e.preventDefault();
    if (!allValid || !dirty || busy) return;
    busy = true;
    error = null;
    done = false;
    try {
      const r = await putRetention({ saveVersions: Number(form.saves), configVersions: Number(form.configs), vaultVersions: Number(form.vault), stateMaxAgeDays: Number(form.age) });
      saved = fromDto(r);
      form = { ...saved };
      done = true;
    } catch (err) {
      error = settingsError(err);
    } finally {
      busy = false;
    }
  }
</script>

<div class="sec">
  <h2>Retention</h2>
  {#if loadError}
    <ErrorLine error={loadError} />
  {:else if !loaded}
    <p class="note" aria-busy="true">— — —</p>
  {:else}
    <p class="note">Conflict versions and pinned versions are never removed.</p>
    <form onsubmit={submit}>
      {#each FIELDS as [key, label] (key)}
        <div>
          <Field {label} type="number" min={MIN} max={MAX} step={1} mono bind:value={form[key]} oninput={() => (done = false)} />
          {#if !valid(form[key])}<p class="fault">Enter a whole number from {MIN} to {MAX}.</p>{/if}
        </div>
      {/each}
      <div>
        <Field label="Drop older save states after (days)" type="number" min={0} max={AGE_MAX} step={1} mono bind:value={form.age} oninput={() => (done = false)} />
        {#if !validAge(form.age)}<p class="fault">Enter a whole number from 0 to {AGE_MAX}.</p>{:else}<p class="note">0, the default, keeps every state. Otherwise the newest state is always kept.</p>{/if}
      </div>
      {#if error}<p class="fault" role="alert">{error}</p>{/if}
      {#if done}<p class="done" role="status">Saved.</p>{/if}
      <div class="actions"><Button type="submit" variant="primary" disabled={!allValid || !dirty || busy}>Save</Button></div>
    </form>
  {/if}
</div>
