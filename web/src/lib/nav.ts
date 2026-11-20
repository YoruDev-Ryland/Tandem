/** The page tabs in the top bar, switched with L1 / R1 or [ and ]. */
export const PAGE_TABS = [
  { label: "Home", path: "/" },
  { label: "Games", path: "/games" },
  { label: "Devices", path: "/devices" },
  { label: "Configs", path: "/configs" },
  { label: "Vault", path: "/vault" },
  { label: "Activity", path: "/activity" },
  { label: "Settings", path: "/settings" },
] as const;

/** The tab a path belongs to. Pages outside the tabs (conflicts, not found) sit under Home. */
export function activeTab(path: string): string {
  const tab = PAGE_TABS.find((t) => t.path !== "/" && (path === t.path || path.startsWith(t.path + "/")));
  return tab?.path ?? "/";
}
