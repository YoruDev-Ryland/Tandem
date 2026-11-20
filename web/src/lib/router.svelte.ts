import { parseGamePath } from "./api/paths";

export type RouteName =
  | "home" | "games" | "game" | "game-conflict" | "conflicts"
  | "devices" | "device" | "device-setup" | "device-pair"
  | "configs" | "config-emulator" | "vault" | "activity" | "settings" | "sign-in" | "kit" | "not-found";

export interface Route { name: RouteName; params: Record<string, string> }

const fixed: Record<string, RouteName> = {
  "/": "home",
  "/games": "games",
  "/conflicts": "conflicts",
  "/devices": "devices",
  "/devices/pair": "device-pair",
  "/configs": "configs",
  "/vault": "vault",
  "/activity": "activity",
  "/settings": "settings",
  "/sign-in": "sign-in",
};

function decode(s: string): string | null {
  try { return decodeURIComponent(s); } catch { return null; }
}

export function matchRoute(rawPath: string): Route {
  const path = rawPath.length > 1 ? rawPath.replace(/\/+$/, "") : rawPath;
  const notFound: Route = { name: "not-found", params: {} };
  if (path in fixed) return { name: fixed[path], params: {} };
  // The UI kit page exists only in dev builds.
  if (import.meta.env.DEV && path === "/_kit") return { name: "kit", params: {} };

  if (path.startsWith("/games/")) {
    const conflict = path.endsWith("/conflict");
    const game = parseGamePath(conflict ? path.slice(0, -"/conflict".length) : path);
    if (!game) return notFound;
    return { name: conflict ? "game-conflict" : "game", params: { emu: game.emu, key: game.key } };
  }

  let m = /^\/devices\/([^/]+)(\/setup)?$/.exec(path);
  if (m) {
    const id = decode(m[1]);
    return id === null ? notFound : { name: m[2] ? "device-setup" : "device", params: { id } };
  }
  m = /^\/configs\/([^/]+)$/.exec(path);
  if (m) {
    const emu = decode(m[1]);
    return emu === null ? notFound : { name: "config-emulator", params: { emu } };
  }
  return notFound;
}

let path = $state(location.pathname);
// How many in-app navigations sit behind this entry; 0 on a page opened directly.
let depth = $state<number>((history.state as { i?: number } | null)?.i ?? 0);

function sync() {
  path = location.pathname;
  depth = (history.state as { i?: number } | null)?.i ?? 0;
}
addEventListener("popstate", sync);

export const router = {
  get path() { return path; },
  get route() { return matchRoute(path); },
  get params() { return matchRoute(path).params; },
  get canGoBack() { return depth > 0; },
  go(to: string): void {
    history.pushState({ i: depth + 1 }, "", to);
    sync();
  },
  back(): void {
    history.back();
  },
};
