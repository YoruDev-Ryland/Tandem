<script lang="ts">
  import { onAction } from "../lib/input/actions";
  import { router } from "../lib/router.svelte";
  import { session } from "../lib/session.svelte";
  import HintBar from "../lib/ui/HintBar.svelte";
  import Tabs from "../lib/ui/Tabs.svelte";
  import Account from "./settings/Account.svelte";
  import Appearance from "./settings/Appearance.svelte";
  import Backup from "./settings/Backup.svelte";
  import Notifications from "./settings/Notifications.svelte";
  import Retention from "./settings/Retention.svelte";
  import Storage from "./settings/Storage.svelte";
  import Users from "./settings/Users.svelte";
  import "./settings/settings.css";

  const SECTIONS = [
    { id: "account", label: "Account", admin: false },
    { id: "appearance", label: "Appearance", admin: false },
    { id: "retention", label: "Retention", admin: false },
    { id: "storage", label: "Storage", admin: false },
    { id: "notifications", label: "Notifications", admin: false },
    { id: "users", label: "Users", admin: true },
    { id: "backup", label: "Backup", admin: true },
  ];

  const isAdmin = $derived(session.account?.isAdmin === true);
  const visible = $derived(SECTIONS.filter((s) => !s.admin || isAdmin));
  const tabPath = (id: string) => `/settings?s=${id}`;
  const requested = new URLSearchParams(router.search).get("s");
  let section = $state(requested ?? "account");
  // A non-admin who lands on an admin section (a stale link) gets Account.
  const current = $derived(visible.find((s) => s.id === section)?.id ?? "account");

  function select(path: string) {
    section = new URL(path, location.origin).searchParams.get("s") ?? "account";
    router.go(path, { replace: true });
  }

  $effect(() =>
    onAction((a) => {
      if (a !== "b") return false;
      if (router.canGoBack) router.back();
      else router.go("/");
      return true;
    }),
  );
</script>

<div class="page">
  <section>
    <h1>Settings</h1>
    <div class="tabs">
      <Tabs level={2} items={visible.map((s) => ({ label: s.label, path: tabPath(s.id) }))} active={tabPath(current)} onSelect={select} />
    </div>
    {#key current}
      {#if current === "account"}<Account />
      {:else if current === "appearance"}<Appearance />
      {:else if current === "retention"}<Retention />
      {:else if current === "storage"}<Storage />
      {:else if current === "notifications"}<Notifications />
      {:else if current === "users"}<Users />
      {:else if current === "backup"}<Backup />
      {/if}
    {/key}
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
    margin: 0 0 18px;
    font: 600 40px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  .tabs {
    margin-bottom: 28px;
  }
</style>
