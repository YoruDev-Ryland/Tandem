<script lang="ts">
  import { editConfigFile, getConfigFileBytes } from "../api/endpoints";
  import { ApiError } from "../api/http";
  import { decodeConfig, encodeConfig, NotText, type EditableText } from "../configText";
  import { nameList } from "../deviceStates";
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
  /** Each file as loaded (or as last saved), and as edited, by path. */
  let original = $state<Record<string, EditableText>>({});
  let text = $state<Record<string, string>>({});
  /** The version the next save builds on: it moves on with every file saved. */
  let base = $state("");
  /** After a save that stopped part-way: what was saved and what wasn't. */
  let partial = $state("");
  let loadError = $state<unknown>(undefined);
  let saveError = $state<unknown>(undefined);
  let busy = $state(false);

  const stale = $derived(saveError instanceof ApiError && saveError.code === "stale_base");
  const edited = $derived(files.filter((p) => p in text && p in original && text[p] !== original[p]!.text));

  $effect(() => {
    if (open) {
      shown = path;
      original = {};
      text = {};
      base = baseVersionId;
      partial = "";
      loadError = undefined;
      saveError = undefined;
    }
  });

  $effect(() => {
    const p = shown;
    if (!open || !p || p in original) return;
    loadError = undefined;
    getConfigFileBytes(baseVersionId, p, device.id).then(
      (bytes) => {
        try {
          const file = decodeConfig(bytes);
          original[p] = file;
          text[p] = file.text;
        } catch (e) {
          // Refuse to edit: what the textarea showed would not be the file's bytes.
          if (shown === p) loadError = e;
        }
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
    partial = "";
    const saved: string[] = [];
    const pending = [...edited];
    try {
      // Each saved file makes a new version; the next file builds on it.
      for (const p of pending) {
        const value = text[p]!;
        const file = original[p]!;
        const r = await editConfigFile(bundleId, p, { baseVersionId: base, text: encodeConfig(value, file) }, device.id);
        base = r.versionId;
        original[p] = { ...file, text: value };
        saved.push(p);
      }
      open = false;
      onsaved();
    } catch (e) {
      saveError = e;
      if (saved.length > 0) {
        const left = pending.filter((p) => !saved.includes(p));
        partial = `Saved ${nameList(saved)}. ${nameList(left)} ${left.length === 1 ? "wasn't" : "weren't"} saved.`;
      }
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
      <p class="fault" role="alert">{loadError instanceof NotText ? loadError.message : describeConfigError(loadError)}</p>
    {:else if !(shown in text)}
      <p class="loading" aria-busy="true">— — —</p>
    {:else}
      <textarea aria-label={shown} spellcheck="false" autocomplete="off" wrap="off" bind:value={text[shown]}></textarea>
    {/if}
    <p class="note">Saving changes it on {appliesTo}. {emulatorName} must be closed there; until then it waits.</p>
    {#if partial}<p class="note ink">{partial}</p>{/if}
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
  .note.ink {
    color: var(--ink);
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
