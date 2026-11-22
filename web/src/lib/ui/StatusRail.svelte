<script lang="ts">
  import type { GameRow } from "../api/types";
  import { presence, presenceText } from "../deviceStates";
  import type { RailRow } from "../shelf";
  import PresenceMark from "./PresenceMark.svelte";

  /**
   * The status rail under the shelf edge: one row of presence marks per device, each mark directly under its game. A
   * column is as wide as the spine (or the pulled-out card) above it, so the two scroll together.
   */
  let { games, rows, index }: { games: GameRow[]; rows: RailRow[]; index: number } = $props();
</script>

<div class="rail" role="presentation">
  {#each games as g, i (g.emulatorId + "/" + g.gameKey)}
    <div class="col" class:wide={i === index}>
      {#each rows as r (r.deviceId)}
        {@const p = presence(g, r.deviceId)}
        <PresenceMark {p} label={`${g.displayName ?? g.gameKey}: ${presenceText(p, r.name, g.emulatorId)}`} />
      {/each}
    </div>
  {/each}
</div>

<style>
  .rail {
    display: flex;
    gap: var(--spine-gap);
    padding-top: 12px;
  }
  .col {
    flex: none;
    width: var(--spine-w);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--rail-gap);
  }
  .col.wide {
    width: var(--card-w);
  }
  @media (prefers-reduced-motion: no-preference) {
    .col {
      transition: width 120ms cubic-bezier(0.23, 1, 0.32, 1);
    }
  }
  /* A 34 px column has no room for the "wait" word: the amber outline and the label say it. */
  .col :global(.word) {
    display: none;
  }
</style>
