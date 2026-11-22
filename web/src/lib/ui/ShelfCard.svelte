<script lang="ts">
  import type { GameRow } from "../api/types";
  import { presenceText } from "../deviceStates";
  import type { CardCopy } from "../shelf";
  import { spineColour, spineInk } from "../spines";
  import { bareKey } from "../systems";
  import PresenceMark from "./PresenceMark.svelte";

  /**
   * The game under the cursor, pulled out of the shelf: its cover, its name and state, and one line per device. A game
   * without art shows its system code on the system colour, never a stand-in picture.
   */
  let { game, copy, system, art }: { game: GameRow; copy: CardCopy; system: string | null; art: string | null } = $props();
  const id = $derived(bareKey(game.emulatorId, game.gameKey));
</script>

<div class="card">
  <div class="cover" style:--sp={spineColour(system)} style:--sp-ink={spineInk(system)}>
    {#if art}
      <img src={art} alt="" />
    {:else}
      <span class="code" data-testid="no-art">{system ?? "—"}</span>
    {/if}
  </div>
  <div class="text">
    <div class="kicker">{copy.kicker}</div>
    {#if game.displayName}
      <div class="title" title={game.displayName}>{game.displayName}</div>
    {:else}
      <div class="title unknown" title={`${id} · unknown title`}><span class="id">{id}</span><span class="what">unknown title</span></div>
    {/if}
    <p class="sentence {copy.tone}">{copy.sentence}</p>
  </div>
  <ul class="lines">
    {#each copy.lines as l (l.deviceId)}
      <li>
        <PresenceMark p={l.presence} label={presenceText(l.presence, l.name, game.emulatorId)} />
        <span class="name">{l.name}</span>
        <span class="words">{l.words}</span>
      </li>
    {/each}
  </ul>
</div>

<style>
  .card {
    box-sizing: border-box;
    width: var(--card-w);
    height: 100%;
    display: grid;
    grid-template-columns: 112px minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
    gap: 12px;
    padding: 12px;
    text-align: left;
    color: var(--ink);
  }
  .cover {
    width: 112px;
    height: 156px;
    border-radius: 2px;
    overflow: hidden;
    background: var(--sp);
    color: var(--sp-ink);
    display: flex;
    align-items: center;
    justify-content: center;
  }
  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .code {
    font: 700 30px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: 0.04em;
  }
  .text {
    min-width: 0;
  }
  .kicker {
    font: 700 10px/1.2 "Barlow Semi Condensed", sans-serif;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--ink2);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .title {
    margin: 5px 0 8px;
    font: 700 26px/1 "Barlow Semi Condensed", sans-serif;
    overflow-wrap: anywhere;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    overflow: hidden;
  }
  .title.unknown {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .id {
    font: 400 14px/1.2 "JetBrains Mono", ui-monospace, monospace;
    color: var(--ink2);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .what {
    font: 500 12.5px "Barlow", system-ui, sans-serif;
    color: var(--ink2);
  }
  .sentence {
    margin: 0;
    font: 500 12.5px/1.35 "Barlow", system-ui, sans-serif;
    color: var(--ink2);
  }
  .sentence.wait {
    color: var(--wait);
    font-weight: 600;
  }
  .sentence.fault {
    color: var(--fault);
    font-weight: 600;
  }
  .lines {
    grid-column: 1 / 3;
    /* The device lines rest on the shelf edge, just above the rail they repeat. */
    align-self: end;
    max-height: 100%;
    margin: 0;
    padding: 7px 0 0;
    list-style: none;
    border-top: 1px solid var(--rule2);
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-height: 0;
    overflow: hidden;
    font: 500 12.5px "Barlow", system-ui, sans-serif;
  }
  li {
    display: flex;
    align-items: center;
    gap: 7px;
    min-width: 0;
  }
  li :global(.word) {
    display: none;
  }
  .name {
    flex: none;
    max-width: 50%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .words {
    margin-left: auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font: 500 12px "Barlow", system-ui, sans-serif;
    font-variant-numeric: tabular-nums;
    color: var(--ink2);
  }
  /* Narrow windows: the cover stacks above the text. */
  @media (max-width: 600px) {
    .card {
      grid-template-columns: minmax(0, 1fr);
      grid-template-rows: auto auto minmax(0, 1fr);
    }
    .lines {
      grid-column: 1;
    }
  }
</style>
