<script lang="ts">
  import { getDevices } from "../api/endpoints";
  import { resource } from "../api/resource.svelte";
  import { activeTab, PAGE_TABS } from "../nav";
  import { router } from "../router.svelte";
  import { waitingDeviceIds } from "../state/waiting.svelte";
  import TopBar from "./TopBar.svelte";

  // The signed-in shell's top bar: page tabs plus live device status.
  const devices = resource(getDevices, ["devices"]);
</script>

<TopBar
  tabs={[...PAGE_TABS]}
  active={activeTab(router.path)}
  devices={Array.isArray(devices.data) ? devices.data : []}
  waitingDeviceIds={waitingDeviceIds()}
/>
