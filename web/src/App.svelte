<script lang="ts">
  import { onMount } from "svelte";
  import { router } from "./lib/router.svelte";
  import { session } from "./lib/session.svelte";
  import { PRODUCT_NAME } from "./lib/brand";
  import { installInput } from "./lib/input";
  import SignIn from "./pages/SignIn.svelte";
  import Home from "./pages/Home.svelte";
  import Games from "./pages/Games.svelte";
  import Game from "./pages/Game.svelte";
  import Conflict from "./pages/Conflict.svelte";
  import Conflicts from "./pages/Conflicts.svelte";
  import Configs from "./pages/Configs.svelte";
  import ConfigConflict from "./pages/ConfigConflict.svelte";
  import Devices from "./pages/Devices.svelte";
  import Device from "./pages/Device.svelte";
  import Pair from "./pages/Pair.svelte";
  import Setup from "./pages/Setup.svelte";
  import Vault from "./pages/Vault.svelte";
  import Activity from "./pages/Activity.svelte";
  import Settings from "./pages/Settings.svelte";
  import AppTopBar from "./lib/ui/AppTopBar.svelte";

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
    "config-conflict": "Config conflict",
    vault: "Vault",
    activity: "Activity",
    settings: "Settings",
  };

  onMount(() => {
    if (router.route.name !== "kit") void session.check();
    return installInput();
  });

  const route = $derived(router.route);
  // Pages load once on mount, so a different path or param must remount the page.
  const key = $derived(router.path);
</script>

{#if import.meta.env.DEV && route.name === "kit"}
  {#await import("./pages/_Kit.svelte") then Kit}<Kit.default />{/await}
{:else if route.name === "sign-in"}
  <SignIn />
{:else if session.checked && session.error}
  <main>
    <h1>{PRODUCT_NAME}</h1>
    <p class="error" role="alert">{session.error}</p>
    <button type="button" onclick={() => session.check()}>Retry</button>
  </main>
{:else if session.checked && session.account}
  <AppTopBar />
  {#key key}
    {#if route.name === "home"}
      <Home />
    {:else if route.name === "games"}
      <Games />
    {:else if route.name === "game"}
      <Game />
    {:else if route.name === "game-conflict"}
      <Conflict />
    {:else if route.name === "conflicts"}
      <Conflicts />
    {:else if route.name === "configs" || route.name === "config-emulator"}
      <Configs />
    {:else if route.name === "config-conflict"}
      <ConfigConflict />
    {:else if route.name === "devices"}
      <Devices />
    {:else if route.name === "device"}
      <Device />
    {:else if route.name === "device-pair"}
      <Pair />
    {:else if route.name === "device-setup"}
      <Setup />
    {:else if route.name === "vault"}
      <Vault />
    {:else if route.name === "activity"}
      <Activity />
    {:else if route.name === "settings"}
      <Settings />
    {:else}
    <main>
      {#if route.name === "not-found"}
        <h1>Page not found</h1>
        <p class="empty">There is nothing at this address.</p>
      {:else}
        <h1>{titles[route.name]}</h1>
      {/if}
    </main>
    {/if}
  {/key}
{/if}

<style>
  main {
    padding: 0 var(--gutter);
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
  .error {
    margin: 0 0 16px;
    color: var(--fault);
  }
  button {
    font: 600 14px "Barlow", system-ui, sans-serif;
    height: 40px;
    padding: 0 18px;
    border-radius: 3px;
    border: 1.5px solid var(--rule2);
    background: transparent;
    color: var(--ink);
    cursor: pointer;
  }
  .empty {
    margin: 0;
    color: var(--ink2);
  }
</style>
