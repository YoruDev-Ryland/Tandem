import * as endpoints from "./api/endpoints";
import { ApiError, onUnauthorized } from "./api/http";
import type { AccountInfo, WebSessionRequest } from "./api/types";
import { router } from "./router.svelte";

let account = $state<AccountInfo | null>(null);
let checked = $state(false);
let error = $state<string | null>(null);
let returnTo: string | null = null;

/** A return target must be a same-origin path: not protocol-relative, not absolute, not the sign-in page. */
export function safeReturnTo(path: string | null): string | null {
  if (!path || !/^\/(?![/\\])/.test(path)) return null;
  return path === "/sign-in" || path.startsWith("/sign-in?") ? null : path;
}

// A 401 while on the sign-in page is a wrong password or pairing code: the page shows it in place.
onUnauthorized(() => {
  account = null;
  if (router.path === "/sign-in") return;
  returnTo = safeReturnTo(location.pathname + location.search);
  router.go("/sign-in");
});

export const session = {
  get account() { return account; },
  /** True once the first check has finished, signed in or not. */
  get checked() { return checked; },
  /** Set when the account check failed for a reason other than a 401 (for example, no network). */
  get error() { return error; },
  get returnTo() { return returnTo; },
  async check(): Promise<void> {
    try {
      account = await endpoints.getAccount();
      error = null;
    } catch (e) {
      account = null;
      if (!(e instanceof ApiError)) throw e;
      error = e.status === 401 ? null : "Can't reach the server. Check your connection and try again.";
    } finally {
      checked = true;
    }
  },
  async signIn(req: WebSessionRequest): Promise<void> {
    account = await endpoints.signIn(req);
    const to = safeReturnTo(returnTo) ?? "/";
    returnTo = null;
    router.go(to);
  },
  async signOut(): Promise<void> {
    await endpoints.signOut();
    account = null;
    returnTo = null;
    router.go("/sign-in");
  },
};
