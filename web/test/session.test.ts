import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { router } from "../src/lib/router.svelte";
import { safeReturnTo, session } from "../src/lib/session.svelte";
import { signIn as signInCall } from "../src/lib/api/endpoints";
import { ApiError } from "../src/lib/api/http";

const account = { id: "1", email: "a@b.c", isAdmin: false };
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("session", () => {
  beforeEach(() => router.go("/"));
  afterEach(() => vi.unstubAllGlobals());

  it("returns to the page that got a 401 after sign-in", async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === "/v1/web/session" && init?.method === "POST") return json(200, account);
      return json(401, { code: "unauthorized", message: "Sign in" });
    });
    vi.stubGlobal("fetch", fetchMock);

    router.go("/configs/retroarch");
    await session.check();
    expect(router.path).toBe("/sign-in");
    expect(session.account).toBeNull();

    await session.signIn({ email: "a@b.c", password: "pw" });
    expect(router.path).toBe("/configs/retroarch");
    expect(session.account).toEqual(account);
  });

  it("goes home after sign-in when nothing was remembered", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(200, account)));
    router.go("/sign-in");
    await session.signIn({ email: "a@b.c", password: "pw" });
    expect(router.path).toBe("/");
  });

  it("does not redirect or forget the return path when sign-in itself gets a 401", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(401, { code: "invalid_credentials", message: "no" })));
    router.go("/vault");
    await session.check(); // 401 -> /sign-in, returnTo=/vault
    expect(session.returnTo).toBe("/vault");

    const err = await signInCall({ email: "a", password: "b" }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe("invalid_credentials");
    expect(router.path).toBe("/sign-in");
    expect(session.returnTo).toBe("/vault");
  });

  it("signOut clears the account and goes to sign-in", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 204 })));
    await session.signOut();
    expect(session.account).toBeNull();
    expect(router.path).toBe("/sign-in");
  });

  it("signOut treats a 401 as success: the session is already gone", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(401, { code: "unauthorized", message: "no" })));
    router.go("/settings");
    await expect(session.signOut()).resolves.toBeUndefined();
    expect(session.account).toBeNull();
    expect(router.path).toBe("/sign-in");
  });

  it("signOut still fails on a network error", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new Error("offline"); }));
    await expect(session.signOut()).rejects.toBeInstanceOf(ApiError);
  });

  it("only accepts same-origin paths as a return target", () => {
    expect(safeReturnTo("/configs/retroarch?x=1")).toBe("/configs/retroarch?x=1");
    expect(safeReturnTo("//evil.com/x")).toBeNull();
    expect(safeReturnTo("https://x")).toBeNull();
    expect(safeReturnTo("/sign-in")).toBeNull();
    expect(safeReturnTo("/\\evil.com")).toBeNull();
  });

  it("records a failed check that was not a 401 and clears it on retry", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("offline"); }));
    router.go("/vault");
    await session.check();
    expect(session.error).toMatch(/reach the server/);
    expect(router.path).toBe("/vault");

    vi.stubGlobal("fetch", vi.fn(async () => json(200, account)));
    await session.check();
    expect(session.error).toBeNull();
    expect(session.account).toEqual(account);
  });
});
