import { beforeEach, describe, expect, it } from "vitest";
import { matchRoute, router } from "../src/lib/router.svelte";
import { gamePath } from "../src/lib/api/paths";

describe("router", () => {
  beforeEach(() => router.go("/"));

  it("resolves game params from an encoded path", () => {
    expect(matchRoute("/games/nds/a%2Fb")).toEqual({ name: "game", params: { emu: "nds", key: "a/b" } });
    expect(matchRoute(gamePath("nds", "Pokémon/Black 2"))).toEqual({
      name: "game", params: { emu: "nds", key: "Pokémon/Black 2" },
    });
  });

  it("resolves the conflict route and the other pages", () => {
    expect(matchRoute("/games/nds/a/conflict")).toEqual({ name: "game-conflict", params: { emu: "nds", key: "a" } });
    expect(matchRoute("/devices/pair").name).toBe("device-pair");
    expect(matchRoute("/devices/d1/setup")).toEqual({ name: "device-setup", params: { id: "d1" } });
    expect(matchRoute("/devices/d1")).toEqual({ name: "device", params: { id: "d1" } });
    expect(matchRoute("/configs/retroarch")).toEqual({ name: "config-emulator", params: { emu: "retroarch" } });
    for (const p of ["/", "/games", "/conflicts", "/devices", "/configs", "/vault", "/activity", "/settings", "/sign-in"]) {
      expect(matchRoute(p).name).not.toBe("not-found");
    }
  });

  it("sends unknown routes to not-found", () => {
    expect(matchRoute("/nope").name).toBe("not-found");
    expect(matchRoute("/games/nds").name).toBe("not-found");
    expect(matchRoute("/devices/%E0%A4%A").name).toBe("not-found");
  });

  it("go pushes history and updates path and params", () => {
    const before = history.length;
    router.go("/games/nds/a%2Fb");
    expect(location.pathname).toBe("/games/nds/a%2Fb");
    expect(history.length).toBe(before + 1);
    expect(router.path).toBe("/games/nds/a%2Fb");
    expect(router.params).toEqual({ emu: "nds", key: "a/b" });
  });

  it("follows popstate when going back", async () => {
    router.go("/vault");
    router.go("/activity");
    router.back();
    await new Promise((r) => setTimeout(r, 20));
    expect(router.path).toBe("/vault");
  });
});
