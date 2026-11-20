import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import App from "../src/App.svelte";
import { router } from "../src/lib/router.svelte";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("App", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("shows a network failure in place with a retry instead of a blank page", async () => {
    router.go("/vault");
    let up = false;
    vi.stubGlobal("fetch", vi.fn(async () => {
      if (!up) throw new TypeError("offline");
      return json(200, { id: "1", email: "a@b.c", isAdmin: false });
    }));
    render(App);
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("Can't reach the server. Check your connection and try again.");
    up = true;
    await fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("heading", { name: "Vault" })).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
