<script lang="ts">
  import { exportZipUrl } from "../../../lib/api/paths";
  import type { StorageUsage } from "../../../lib/api/types";
  import { formatSize } from "../../../lib/format";
  import { router } from "../../../lib/router.svelte";
  import { kindRows, olderLine } from "../../../lib/storage/page";
  import MoveFiles from "./MoveFiles.svelte";

  interface Props {
    usage: StorageUsage;
    isAdmin: boolean;
    /** Only the admin's Files block: nothing of the account's own is stored yet. */
    filesOnly?: boolean;
    /** The page's figures are read again (a move started, finished, or its old copy went). */
    onchanged?: () => void;
  }
  let { usage: u, isAdmin, filesOnly = false, onchanged }: Props = $props();

  const kinds = $derived(kindRows(u, isAdmin));
  const older = $derived(olderLine(u));
  const server = $derived(isAdmin ? u.server : null);

  function retention(e: MouseEvent) {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    router.go("/settings?s=retention", { replace: true });
  }
</script>

<div class="lower" class:one={filesOnly}>
  {#if !filesOnly}
    <div>
      <h3>By kind</h3>
      <ul class="kv">
        {#each kinds as k (k.id)}
          <li>{k.label}<span class="mono">{formatSize(k.bytes)}</span></li>
        {/each}
      </ul>
    </div>
  {/if}
  {#if older || server}
    <div class="right">
      {#if older && !filesOnly}
        <div class="part">
          <h3>Retention</h3>
          <p class="callout">Older versions take <span class="mono strong">{older.size}</span>, most of it {older.game}. <a class="link" href="/settings?s=retention" onclick={retention}>Retention</a></p>
        </div>
      {/if}
      {#if server}
        <div class="part">
          <h3>Files</h3>
          <p class="callout">
            <span class="mono strong">{server.filesLocation}</span>
            <span>· {server.diskFreeBytes != null ? `${formatSize(server.diskFreeBytes)} free, ` : ""}{formatSize(server.minFreeBytes)} kept free</span>
          </p>
          {#if server.lowDisk}
            <p class="fault">The disk holding {server.lowDisk} is almost full. New saves are refused until there is room.</p>
          {/if}
          <MoveFiles move={server.move} {onchanged} />
        </div>
      {/if}
    </div>
  {/if}
</div>

{#if !filesOnly}
  <div class="export">
    <a class="btn" href={exportZipUrl()} download>Export everything</a>
    <span class="note">A zip of every current save, state and config, in each emulator's own folders.</span>
  </div>
{/if}

<style>
  .lower {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 28px;
    padding-top: 22px;
    border-top: 1px solid var(--rule);
  }
  .lower.one {
    grid-template-columns: 1fr;
  }
  h3 {
    margin: 0 0 8px;
    font: 600 17px/1.1 "Barlow Semi Condensed", sans-serif;
    color: var(--ink);
  }
  .kv {
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .kv li {
    display: flex;
    justify-content: space-between;
    padding: 8px 0;
    border-bottom: 1px solid var(--rule);
    color: var(--ink2);
    font-size: 14px;
  }
  .kv li:last-child {
    border-bottom: none;
  }
  .kv .mono {
    color: var(--ink);
  }
  .right {
    display: flex;
    flex-direction: column;
    gap: 22px;
  }
  .part {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .part h3 {
    margin: 0;
  }
  .callout {
    margin: 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .mono {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
  }
  .strong {
    color: var(--ink);
  }
  .fault {
    margin: 0;
    color: var(--fault);
    font-size: 14px;
  }
  .link {
    color: var(--ink2);
    text-underline-offset: 3px;
  }
  .link:hover {
    color: var(--ink);
  }
  .export {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px 16px;
  }
  .note {
    margin: 0;
    color: var(--ink2);
    font-size: 14px;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    height: 40px;
    padding: 0 16px;
    border-radius: 3px;
    border: 1.5px solid var(--rule2);
    color: var(--ink);
    font: 600 14px "Barlow", system-ui, sans-serif;
    text-decoration: none;
  }
  .btn:hover {
    border-color: var(--ink3);
  }
  @media (max-width: 640px) {
    .lower {
      grid-template-columns: 1fr;
    }
  }
  @media (prefers-reduced-motion: no-preference) {
    .btn {
      transition: border-color 150ms cubic-bezier(0.23, 1, 0.32, 1);
    }
    .link {
      transition: color 150ms cubic-bezier(0.23, 1, 0.32, 1);
    }
  }
</style>
