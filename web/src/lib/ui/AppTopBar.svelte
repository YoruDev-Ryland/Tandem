<script lang="ts">
  import { getDevices, getGames, getHome } from "../api/endpoints";
  import { attentionItems, tabMarks } from "../attention";
  import { resource } from "../api/resource.svelte";
  import { activeTab, PAGE_TABS } from "../nav";
  import { router } from "../router.svelte";
  import { waitingDevices } from "../state/waiting";
  import TopBar from "./TopBar.svelte";

  // The signed-in shell's top bar: page tabs plus live device status.
  const devices = resource(getDevices, ["devices"]);
  // The bar is mounted on every signed-in route, so it owns the waiting state instead of depending on a page.
  const games = resource(() => getGames(), ["saves", "conflicts", "devices"]);
  // What needs the user, marked on the tab where it gets dealt with.
  const home = resource(getHome, ["saves", "conflicts", "devices", "configs"]);
  let now = $state(Date.now());
  $effect(() => {
    const t = setInterval(() => (now = Date.now()), 60_000);
    return () => clearInterval(t);
  });
  const marks = $derived(tabMarks(attentionItems(home.data, Array.isArray(games.data) ? games.data : [], [], now)));
</script>

<TopBar
  tabs={[...PAGE_TABS]}
  active={activeTab(router.path)}
  devices={Array.isArray(devices.data) ? devices.data : []}
  waitingDeviceIds={Array.isArray(games.data) ? waitingDevices(games.data) : []}
  {marks}
/>
