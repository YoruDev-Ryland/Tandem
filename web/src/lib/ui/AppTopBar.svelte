<script lang="ts">
  import { getDevices, getGames } from "../api/endpoints";
  import { resource } from "../api/resource.svelte";
  import { activeTab, PAGE_TABS } from "../nav";
  import { router } from "../router.svelte";
  import { waitingDevices } from "../state/waiting";
  import TopBar from "./TopBar.svelte";

  // The signed-in shell's top bar: page tabs plus live device status.
  const devices = resource(getDevices, ["devices"]);
  // The bar is mounted on every signed-in route, so it owns the waiting state instead of depending on a page.
  const games = resource(() => getGames(), ["saves", "conflicts", "devices"]);
</script>

<TopBar
  tabs={[...PAGE_TABS]}
  active={activeTab(router.path)}
  devices={Array.isArray(devices.data) ? devices.data : []}
  waitingDeviceIds={Array.isArray(games.data) ? waitingDevices(games.data) : []}
/>
