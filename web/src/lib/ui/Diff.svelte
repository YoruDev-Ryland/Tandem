<script lang="ts">
  import type { FileDiff } from "../api/types";
  import { diffRows } from "./diff";

  /** `selected` is the shown file's path; the file row above the diff switches it. */
  let { files, selected = $bindable() }: { files: FileDiff[]; selected: string } = $props();

  const file = $derived(files.find((f) => f.path === selected) ?? files[0]);
  const rows = $derived(file ? diffRows(file) : []);
  const MARK = { " ": "", "+": "+", "-": "−" } as const;
</script>

<div class="diff">
  {#if files.length > 1}
    <div class="files">
      {#each files as f (f.path)}
        <button type="button" aria-pressed={f.path === file?.path} onclick={() => (selected = f.path)}>{f.path}</button>
      {/each}
    </div>
  {/if}
  {#if file}
    <div class="body">
      {#each rows as r, i (i)}
        {#if r.kind === "gap"}
          <div class="line gap"><span class="ln"></span><span></span><span>· · · {r.count} unchanged lines</span></div>
        {:else}
          <div class="line" class:add={r.op === "+"} class:del={r.op === "-"}>
            <span class="ln">{r.n}</span><span class="op">{MARK[r.op]}</span><span class="text">{r.text}</span>
          </div>
        {/if}
      {:else}
        <p class="none">No line changes in this file.</p>
      {/each}
    </div>
  {/if}
</div>

<style>
  .diff {
    min-width: 0;
  }
  .files {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 22px;
    margin-bottom: 10px;
  }
  .files button {
    padding: 0;
    border: 0;
    border-radius: 3px;
    background: none;
    color: var(--ink2);
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    cursor: pointer;
  }
  .files button:hover,
  .files button[aria-pressed="true"] {
    color: var(--ink);
  }
  .body {
    overflow-x: auto;
    border-top: 1px solid var(--rule);
    font: 400 13px/1.9 "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
  }
  .line {
    display: grid;
    grid-template-columns: 44px 18px max-content;
    min-width: 100%;
    width: max-content;
    padding: 0 10px;
    color: var(--ink2);
  }
  .text {
    white-space: pre;
  }
  .ln {
    padding-right: 10px;
    text-align: right;
    color: var(--ink3);
  }
  .add {
    background: var(--diff-add);
    color: var(--diff-add-ink);
  }
  .del {
    background: var(--diff-del);
    color: var(--diff-del-ink);
  }
  .gap {
    color: var(--ink3);
  }
  .none {
    margin: 0;
    padding: 8px 10px;
    color: var(--ink2);
    font-family: "Barlow", system-ui, sans-serif;
    font-size: 15px;
  }
</style>
