<script lang="ts">
  import QRCode from "qrcode";
  import { createPairingCode } from "../lib/api/endpoints";
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import Button from "../lib/ui/Button.svelte";
  import ErrorLine from "../lib/ui/ErrorLine.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";

  interface Shown { code: string; svg: string; expires: number }

  let shown = $state<Shown>();
  let failure = $state<unknown>(undefined);
  let now = $state(Date.now());
  let fetching = false;
  let alive = true;

  /** The library paints fixed colours; tag the two paints so the stylesheet can use the tokens. */
  function themed(svg: string): string {
    return svg.replace(/fill="[^"]*"/, 'class="bg"').replace(/stroke="[^"]*"/, 'class="fg"');
  }

  async function fresh() {
    if (fetching) return;
    fetching = true;
    try {
      const c = await createPairingCode();
      const svg = themed(await QRCode.toString(c.qrPayload, { type: "svg", margin: 2 }));
      if (!alive) return;
      shown = { code: c.code, svg, expires: Date.parse(c.expiresUtc) };
      failure = undefined;
    } catch (e) {
      if (alive) failure = e;
    } finally {
      fetching = false;
      if (alive) now = Date.now();
    }
  }

  $effect(() => {
    alive = true;
    void fresh();
    const t = setInterval(() => {
      now = Date.now();
      // A code that has run out is cleared and replaced once; a failed replacement waits for "Try again".
      if (shown && now >= shown.expires) {
        shown = undefined;
        void fresh();
      }
    }, 1000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  });

  const left = $derived(shown ? Math.max(0, Math.ceil((shown.expires - now) / 1000)) : 0);
  const clock = $derived(`${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`);

  $effect(() =>
    onAction((a) => {
      if (a !== "b") return false;
      if (router.canGoBack) router.back();
      else router.go("/devices");
      return true;
    }),
  );
</script>

<div class="page">
  <section>
    <h1>Pair a new device</h1>

    {#if failure && !shown}
      <ErrorLine error={failure} />
      <div class="retry"><Button onclick={fresh}>Try again</Button></div>
    {:else if !shown}
      <p class="loading" aria-busy="true">— — —</p>
    {:else}
      <div class="pair">
        <div class="qr" data-theme="light" role="img" aria-label="QR code for pairing">{@html shown.svg}</div>
        <div>
          <p class="code" aria-label="Pairing code">{shown.code}</p>
          <p class="expires">Expires in {clock}</p>
          <p class="how">Open Tandem on the device, choose Sign in with a code, and enter this code.</p>
          {#if failure}<ErrorLine error={failure} />{/if}
        </div>
      </div>
    {/if}
  </section>

  <HintBar hints={[{ glyph: "b", label: "Back" }]} />
</div>

<style>
  .page {
    display: flex;
    flex-direction: column;
    min-height: calc(100vh - 72px);
  }
  section {
    flex: 1;
    padding: 8px var(--gutter) 24px;
  }
  h1 {
    margin: 0 0 28px;
    font: 600 48px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.02em;
    color: var(--ink);
  }
  .pair {
    display: flex;
    align-items: center;
    gap: 48px;
  }
  .qr {
    flex: none;
    width: 280px;
    height: 280px;
    border: 1px solid var(--rule2);
    border-radius: 3px;
  }
  .qr :global(svg) {
    display: block;
    width: 100%;
    height: 100%;
  }
  .qr :global(.bg) {
    fill: var(--bg);
  }
  .qr :global(.fg) {
    stroke: var(--ink);
  }
  .code {
    margin: 0;
    font: 500 28px/1.2 "JetBrains Mono", ui-monospace, monospace;
    font-variant-numeric: tabular-nums;
    letter-spacing: 0.08em;
    color: var(--ink);
  }
  .expires {
    margin: 8px 0 0;
    color: var(--ink3);
    font-size: 15px;
    font-variant-numeric: tabular-nums;
  }
  .how {
    max-width: 420px;
    margin: 24px 0 0;
    color: var(--ink2);
    font-size: 15px;
  }
  .loading {
    margin: 0;
    padding: 12px 16px;
    color: var(--ink3);
  }
  .retry {
    margin-top: 14px;
  }
  @media (max-width: 640px) {
    .pair {
      flex-direction: column;
      align-items: flex-start;
      gap: 24px;
    }
    .qr {
      width: min(280px, 100%);
      height: auto;
      aspect-ratio: 1;
    }
    .code {
      font-size: 24px;
    }
  }
</style>
