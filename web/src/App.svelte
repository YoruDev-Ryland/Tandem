<script lang="ts">
  import { onMount } from "svelte";
  import { router } from "./lib/router.svelte";
  import { session } from "./lib/session.svelte";
  import SignIn from "./pages/SignIn.svelte";

  const titles: Record<string, string> = {
    home: "Home",
    games: "Games",
    game: "Game",
    "game-conflict": "Conflict",
    conflicts: "Conflicts",
    devices: "Devices",
    device: "Device",
    "device-setup": "Device setup",
    "device-pair": "Pair a device",
    configs: "Configs",
    "config-emulator": "Configs",
    vault: "Vault",
    activity: "Activity",
    settings: "Settings",
  };

  onMount(() => {
    void session.check();
  });

  const route = $derived(router.route);
  // Pages load once on mount, so a different path or param must remount the page.
  const key = $derived(router.path);
</script>

{#if route.name === "sign-in"}
  <SignIn />
{:else if session.checked && session.account}
  {#key key}
    <main>
      {#if route.name === "not-found"}
        <h1>Page not found</h1>
        <p class="empty">There is nothing at this address.</p>
      {:else}
        <h1>{titles[route.name]}</h1>
      {/if}
    </main>
  {/key}
{/if}

<style>
  main {
    padding: 0 40px;
  }
  h1 {
    margin: 0;
    height: 72px;
    display: flex;
    align-items: center;
    font-family: "Barlow Semi Condensed", sans-serif;
    font-weight: 600;
    font-size: 28px;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  .empty {
    margin: 0;
    color: var(--ink2);
  }
</style>
