import * as endpoints from "./api/endpoints";
import { ApiError, onUnauthorized } from "./api/http";
import type { AccountInfo, WebSessionRequest } from "./api/types";
import { router } from "./router.svelte";

let account = $state<AccountInfo | null>(null);
let checked = $state(false);
let returnTo: string | null = null;

// A 401 while on the sign-in page is a wrong password or pairing code: the page shows it in place.
onUnauthorized(() => {
  account = null;
  if (router.path === "/sign-in") return;
  returnTo = location.pathname + location.search;
  router.go("/sign-in");
});

export const session = {
  get account() { return account; },
  /** True once the first check has finished, signed in or not. */
  get checked() { return checked; },
  get returnTo() { return returnTo; },
  async check(): Promise<void> {
    try {
      account = await endpoints.getAccount();
    } catch (e) {
      account = null;
      if (!(e instanceof ApiError)) throw e;
    } finally {
      checked = true;
    }
  },
  async signIn(req: WebSessionRequest): Promise<void> {
    account = await endpoints.signIn(req);
    const to = returnTo ?? "/";
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
