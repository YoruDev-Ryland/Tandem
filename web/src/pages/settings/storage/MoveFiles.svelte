<script lang="ts">
  import { onDestroy } from "svelte";
  import { cancelMove, checkMove, getMove, removeOldCopy, startMove } from "../../../lib/api/endpoints";
  import { ApiError } from "../../../lib/api/http";
  import type { MoveCheckResult, MoveStatus } from "../../../lib/api/types";
  import { formatSize } from "../../../lib/format";
  import { moveLine } from "../../../lib/storage/move";
  import Button from "../../../lib/ui/Button.svelte";
  import Dialog from "../../../lib/ui/Dialog.svelte";
  import { describeError } from "../../../lib/ui/errors";
  import Field from "../../../lib/ui/Field.svelte";

  interface Props {
    /** The move the storage data holds: the last or running one. */
    move: MoveStatus | null;
    /** Called when a move starts, finishes or its old copy goes, so the page's figures are read again. */
    onchanged?: () => void;
  }
  let { move, onchanged }: Props = $props();

  // The storage data's move, kept fresher by polling while one runs.
  let status = $state<MoveStatus | null>(null);
  $effect(() => {
    status = move;
  });
  const line = $derived(status ? moveLine(status) : null);
  const running = $derived(status?.phase === "Copying" || status?.phase === "Holding");

  let lineError = $state<string | null>(null);

  async function refresh(): Promise<void> {
    try {
      const next = await getMove();
      const was = status?.phase;
      status = next;
      if (next && next.phase !== was && (next.phase === "Done" || next.phase === "Failed")) onchanged?.();
    } catch {
      /* the next tick or the storage topic tries again */
    }
  }

  let timer: ReturnType<typeof setInterval> | undefined;
  $effect(() => {
    if (!running) return;
    timer = setInterval(() => void refresh(), 2000);
    return () => {
      clearInterval(timer);
      timer = undefined;
    };
  });
  onDestroy(() => clearInterval(timer));

  async function cancel() {
    lineError = null;
    try {
      await cancelMove();
      await refresh();
    } catch (e) {
      lineError = describeError(e);
    }
  }

  // ---- The dialog ----
  let open = $state(false);
  let path = $state("");
  let checked = $state<MoveCheckResult | null>(null);
  let checking = $state(false);
  let starting = $state(false);
  let dialogError = $state<string | null>(null);
  let mountEl = $state<HTMLElement>();
  let copyNote = $state<string | null>(null);

  const trimmed = $derived(path.trim());
  const canMove = $derived(checked !== null && checked.ok && checked.path === trimmed && !starting && !checking);

  function openDialog() {
    path = "";
    checked = null;
    dialogError = null;
    copyNote = null;
    open = true;
  }

  function edited() {
    checked = null;
    dialogError = null;
    copyNote = null;
  }

  async function check(e?: SubmitEvent) {
    e?.preventDefault();
    if (!trimmed || checking || starting) return;
    checking = true;
    dialogError = null;
    try {
      checked = await checkMove(trimmed);
    } catch (err) {
      checked = null;
      dialogError = describeError(err);
    } finally {
      checking = false;
    }
  }

  async function begin() {
    if (!canMove) return;
    starting = true;
    dialogError = null;
    try {
      await startMove(trimmed);
      open = false;
      await refresh();
      onchanged?.();
    } catch (err) {
      if (err instanceof ApiError && err.code === "bad_path") {
        // The server's own check disagrees: show what it found.
        try {
          checked = await checkMove(trimmed);
        } catch (again) {
          dialogError = describeError(again);
        }
      } else {
        dialogError = err instanceof ApiError && err.status === 409 ? err.message : describeError(err);
      }
    } finally {
      starting = false;
    }
  }

  async function copyMount() {
    const text = checked?.mountLine;
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      copyNote = "Copied.";
    } catch {
      // No clipboard here (plain http, or refused): select the line so it can be copied by hand.
      if (mountEl) {
        const range = document.createRange();
        range.selectNodeContents(mountEl);
        const sel = getSelection();
        sel?.removeAllRanges();
        sel?.addRange(range);
      }
      copyNote = "Selected. Copy it with your keyboard.";
    }
  }

  // ---- Removing the old copy ----
  let removeOpen = $state(false);
  let removing = $state(false);
  let removeError = $state<string | null>(null);

  function openRemove() {
    removeError = null;
    removeOpen = true;
  }

  async function removeOld() {
    if (removing) return;
    removing = true;
    removeError = null;
    try {
      await removeOldCopy();
      removeOpen = false;
      await refresh();
      onchanged?.();
    } catch (err) {
      removeError = err instanceof ApiError && (err.code === "files_unavailable" || err.code === "server_moving") ? err.message : describeError(err);
    } finally {
      removing = false;
    }
  }
</script>

<div class="state">
  {#if line && status}
    <p class={line.tone} role="status">{line.text}</p>
    {#if status.phase === "Done" && status.oldKept > 0}
      <p class="ink">{status.oldKept} files the new folder doesn't hold were kept in {status.oldLocation ?? status.from}.</p>
    {/if}
    {#if lineError}<p class="fault" role="alert">{lineError}</p>{/if}
  {/if}
  <div class="row">
    {#if status?.phase === "Copying"}
      <Button onclick={cancel}>Cancel</Button>
    {:else if status?.phase === "Done" && status.oldLocation}
      <Button onclick={openRemove}>Remove old copy</Button>
    {/if}
    {#if !running}<Button onclick={openDialog}>Move files…</Button>{/if}
  </div>
</div>

<Dialog bind:open locked={starting} title="Move files">
  <form class="sec" onsubmit={check}>
    <p class="note">Copy every stored file to a new folder on this server. Uploads pause briefly at the end, then the server switches over.</p>
    <Field label="New folder" mono placeholder="/mnt/storage/tandem" autocomplete="off" spellcheck={false} bind:value={path} oninput={edited} />
    {#if checked}
      <ul class="items">
        {#each checked.items as it (it.name)}
          <li class:bad={!it.ok}>
            <span class="mark" aria-hidden="true">{it.ok ? "✓" : "✕"}</span>
            <span class="what">
              <span class="name">{it.name}</span>
              {#if !it.ok && it.problem}<span class="problem">{it.problem}</span>{/if}
            </span>
            <span class="sr">{it.ok ? "Ok" : "Problem"}</span>
          </li>
        {/each}
      </ul>
      {#if checked.mountLine}
        <div class="mount">
          <code bind:this={mountEl}>{checked.mountLine}</code>
          <Button onclick={copyMount}>Copy</Button>
        </div>
        {#if copyNote}<p class="note" role="status">{copyNote}</p>{/if}
      {/if}
      {#if checked.ok}<p class="note">{formatSize(checked.bytesToCopy)} to copy</p>{/if}
    {/if}
    {#if dialogError}<p class="fault" role="alert">{dialogError}</p>{/if}
    <div class="actions">
      <Button type="submit" disabled={!trimmed || checking || starting}>Check</Button>
      <Button variant="primary" disabled={!canMove} onclick={begin}>Move</Button>
      <Button onclick={() => (open = false)} disabled={starting}>Cancel</Button>
    </div>
  </form>
</Dialog>

<Dialog bind:open={removeOpen} locked={removing} title="Remove old copy">
  <div class="sec">
    <p class="note">Delete the old copy in {status?.oldLocation}? The files in {status?.to} are kept.</p>
    {#if removeError}<p class="fault" role="alert">{removeError}</p>{/if}
    <div class="actions">
      <Button variant="primary" disabled={removing} onclick={removeOld}>Delete old copy</Button>
      <Button onclick={() => (removeOpen = false)} disabled={removing}>Cancel</Button>
    </div>
  </div>
</Dialog>

<style>
  .state {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .state p {
    margin: 0;
    font-size: 14px;
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }
  .wait {
    color: var(--wait);
  }
  .fault {
    color: var(--fault);
  }
  .ink {
    color: var(--ink);
  }
  .items {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .items li {
    display: flex;
    gap: 10px;
    font-size: 14px;
    color: var(--ink2);
  }
  .items li.bad {
    color: var(--ink);
  }
  .mark {
    flex: none;
    width: 14px;
    text-align: center;
    color: var(--ink2);
  }
  .bad .mark {
    color: var(--fault);
  }
  .what {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }
  .problem {
    color: var(--fault);
  }
  .sr {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
  }
  .mount {
    display: flex;
    align-items: flex-start;
    gap: 10px;
  }
  .mount code {
    flex: 1;
    min-width: 0;
    padding: 10px 12px;
    border: 1.5px solid var(--rule2);
    border-radius: 3px;
    color: var(--ink);
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    overflow-wrap: anywhere;
    user-select: all;
  }
</style>
