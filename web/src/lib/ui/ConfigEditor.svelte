<script lang="ts">
  import { editConfigFile, getConfigFile } from "../api/endpoints";
  import { ApiError } from "../api/http";
  import Button from "./Button.svelte";
  import Dialog from "./Dialog.svelte";
  import Field from "./Field.svelte";
  import { describeConfigError } from "./errors";

  interface Props {
    open: boolean;
    /** Frozen by the page when the editor opens. */
    bundleId: string;
    /** The version being edited: the edit's base. */
    baseVersionId: string;
    /** "v7", for the title. */
    label: string;
    /** Whose view of the files is shown: folder paths are rewritten for this device. */
    device: { id: string; name: string };
    /** Every file in the version, and the one to show first. */
    files: string[];
    path: string;
    /** "Odin 3 and Thor": every device the saved change goes to. */
    appliesTo: string;
    emulatorName: string;
    onsaved: () => void;
    /** Closes the editor and reloads the page's config, after someone else's change. */
    onreload: () => void;
  }
  let { open = $bindable(false), bundleId, baseVersionId, label, device, files, path, appliesTo, emulatorName, onsaved, onreload }: Props = $props();

  let shown = $state("");
  /** Text as loaded, and as edited, by path. */
  let original = $state<Record<string, string>>({});
  let text = $state<Record<string, string>>({});
  let loadError = $state<unknown>(undefined);
  let saveError = $state<unknown>(undefined);
  let busy = $state(false);

  const stale = $derived(saveError instanceof ApiError && saveError.code === "stale_base");
  const edited = $derived(files.filter((p) => p in text && text[p] !== original[p]));

  $effect(() => {
    if (open) {
      shown = path;
      original = {};
      text = {};
      loadError = undefined;
      saveError = undefined;
    }
  });

  $effect(() => {
    const p = shown;
    if (!open || !p || p in original) return;
    loadError = undefined;
    getConfigFile(baseVersionId, p, device.id).then(
      (t) => {
        original[p] = t;
        text[p] = t;
      },
      (e: unknown) => {
        if (shown === p) loadError = e;
      },
    );
  });

  async function save() {
    if (busy || edited.length === 0) return;
    busy = true;
    saveError = undefined;
    // Each saved file makes a new version; the next file builds on it.
    let base = baseVersionId;
    try {
      for (const p of edited) {
        const r = await editConfigFile(bundleId, p, { baseVersionId: base, text: text[p]! }, device.id);
        base = r.versionId;
      }
      open = false;
      onsaved();
    } catch (e) {
      saveError = e;
    } finally {
      busy = false;
    }
  }
</script>

<Dialog bind:open title={`Edit ${label}`} wide>
  <form onsubmit={(e) => { e.preventDefault(); void save(); }}>
    {#if files.length > 1}
      <div class="file">
        <Field label="File" mono options={files.map((f) => ({ value: f, label: f }))} bind:value={shown} />
      </div>
    {/if}
    <p class="note">Folder paths are shown as they are on {device.name}.</p>
    {#if loadError}
      <p class="fault" role="alert">{describeConfigError(loadError)}</p>
    {:else if !(shown in text)}
      <p class="loading" aria-busy="true">— — —</p>
    {:else}
      <textarea aria-label={shown} spellcheck="false" autocomplete="off" wrap="off" bind:value={text[shown]}></textarea>
    {/if}
    <p class="note">Saving changes it on {appliesTo}. {emulatorName} must be closed there; until then it waits.</p>
    {#if saveError}
      <p class="fault" role="alert">{describeConfigError(saveError)}</p>
    {/if}
    <div class="actions">
      {#if stale}
        <Button variant="primary" onclick={() => { open = false; onreload(); }}>Reload</Button>
      {:else}
        <Button variant="primary" type="submit" disabled={busy || edited.length === 0}>Save</Button>
      {/if}
      <Button onclick={() => (open = false)}>Cancel</Button>
    </div>
  </form>
</Dialog>

<style>
  .file {
    max-width: 480px;
  }
  .note {
    margin: 12px 0;
    color: var(--ink2);
    font-size: 14px;
  }
  textarea {
    display: block;
    box-sizing: border-box;
    width: 100%;
    height: min(52vh, 480px);
    padding: 10px 12px;
    border: 1.5px solid var(--rule2);
    border-radius: 3px;
    background: transparent;
    color: var(--ink);
    font: 400 13px/1.7 "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
    resize: vertical;
    tab-size: 4;
  }
  textarea:focus-visible {
    outline: 2px solid var(--ink);
    outline-offset: 3px;
  }
  .fault {
    margin: 12px 0 0;
    color: var(--fault);
    font-size: 15px;
  }
  .loading {
    margin: 0;
    color: var(--ink3);
  }
  .actions {
    display: flex;
    gap: 10px;
    margin-top: 20px;
  }
</style>
