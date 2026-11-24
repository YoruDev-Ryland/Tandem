import { spineBand, spineKind, spineRatio } from "../src/lib/spines";
import { visibleRange, windowOrder } from "../src/lib/shelf";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cardCopy, cursorIndex, railLabel, railLabels, railRows, shelfOrder, systemName } from "../src/lib/shelf";
import { SPINES, spineColour, spineInk, spineKey } from "../src/lib/spines";
import { fetchArt } from "../src/lib/api/endpoints";
import { AYN, ODIN, RP, THOR, dev, game } from "./fixtures/home";
import type { GameRow } from "../src/lib/api/types";

const named = (key: string, name: string | null, over: Partial<GameRow> = {}) =>
  game({ emulatorId: "dolphin", gameKey: key, displayName: name, ...over });

describe("shelf order", () => {
  it("puts conflicts first, then names case-insensitively, then unnamed games by key", () => {
    const rows = [named("b", "b"), named("a", "A conflict", { openConflict: true }), named("Z", null), named("c", "C"), named("A", null)];
    expect(shelfOrder(rows).map((g) => g.gameKey)).toEqual(["a", "b", "c", "A", "Z"]);
  });

  // The same order and fixture as the app's ShelfTest.order_names_by_code_point_ignoring_case.
  it("orders names by code point ignoring case, as the app does", () => {
    const rows = ["Zelda", "Ōkami", "okami", "Éclair", "eve", "Apple", "_Underscore"].map((n, i) => named(`k${i}`, n));
    expect(shelfOrder(rows).map((g) => g.displayName)).toEqual(["_Underscore", "Apple", "eve", "okami", "Zelda", "Éclair", "Ōkami"]);
  });

  it("does not change the list it is given", () => {
    const rows = [named("b", "b"), named("a", "a")];
    shelfOrder(rows);
    expect(rows.map((g) => g.gameKey)).toEqual(["b", "a"]);
  });
});

describe("rail", () => {
  // The same table as the app's ShelfTest.rail_label_drops_ayn_and_cuts_to_five.
  it.each([
    ["AYN Thor", "THOR"], ["ayn thor", "THOR"], ["ayn odin 3", "ODIN3"], ["Odin3", "ODIN3"], ["Odin 3", "ODIN3"],
    ["Steam Deck", "STEAM"], ["Odin3 (2)", "ODIN3·2"], ["Retroid Pocket 5 (3)", "RETRO·3"],
  ])("rail labels: the shared table: %s → %s", (name, label) => {
    expect(railLabel(name)).toBe(label);
  });

  it("numbers names that cut to the same label", () => {
    expect(railLabels(["Odin 3", "Odin3 Pro", "Odin3 (2)"])).toEqual(["ODIN3", "ODIN3·2", "ODIN3·3"]);
    expect(railLabels(["AYN Thor", "Odin3", "Odin3 (2)"])).toEqual(["THOR", "ODIN3", "ODIN3·2"]);
  });

  it("orders devices by name and keeps three rows", () => {
    const g = game({ emulatorId: "eden", gameKey: "k", devices: [dev(THOR, "Newest"), dev(ODIN, "HasIt"), dev(RP, "Behind"), dev(AYN, "Pending")] });
    const rows = railRows([g]);
    expect(rows.map((r) => r.name)).toEqual(["Ayn", "Odin 3", "Retroid"]);
    expect(rows.map((r) => r.label)).toEqual(["AYN", "ODIN3", "RETRO"]);
    expect(rows.map((r) => r.deviceId)).toEqual([AYN, ODIN, RP]);
  });

  it("tells two devices with the same name apart", () => {
    const g = game({
      emulatorId: "eden",
      gameKey: "k",
      devices: [{ deviceId: "x1", deviceName: "Odin3", state: "Newest" }, { deviceId: "x2", deviceName: "Odin3", state: "Behind" }],
    });
    const rows = railRows([g]);
    expect(rows.map((r) => r.name)).toEqual(["Odin3", "Odin3 (2)"]);
    expect(new Set(rows.map((r) => r.label)).size).toBe(2);
    expect(rows.map((r) => r.label)).toEqual(["ODIN3", "ODIN3·2"]);
  });
});

describe("card copy", () => {
  const now = Date.parse("2026-11-20T12:00:00Z");

  it("lists every device, beyond the three rail rows", () => {
    const g = game({ emulatorId: "eden", gameKey: "k", devices: [dev(THOR, "Newest"), dev(ODIN, "Behind"), dev(RP, "Waiting"), dev(AYN, "Pending")] });
    const c = cardCopy(g, "Eden", [g], now);
    expect(c.lines.map((l) => l.name)).toEqual(["Ayn", "Odin 3", "Retroid", "Thor"]);
    expect(c.lines.map((l) => l.presence)).toEqual(["coming", "older", "waiting", "newest"]);
    expect(c.lines.map((l) => l.words)).toEqual(["on its way", "older copy", "waiting for Eden to close", "newest"]);
  });

  it("dates the newest copy on its own device and says the rest plainly", () => {
    const g = game({ emulatorId: "eden", gameKey: "k", newestOnDeviceId: ODIN, newestOnDeviceName: "Odin 3", newestUtc: "2026-11-20T10:00:00Z", devices: [dev(ODIN, "Newest"), dev(THOR, "HasIt")] });
    const other = game({ emulatorId: "eden", gameKey: "o", devices: [dev(RP, "Newest")] });
    const c = cardCopy(g, "Eden", [g, other], now);
    expect(c.sentence).toBe("Newest on Odin 3 · 2 h ago");
    expect(c.tone).toBe("");
    expect(c.lines).toEqual([
      { name: "Odin 3", presence: "newest", words: "newest · 2 h ago", deviceId: ODIN },
      { name: "Retroid", presence: "absent", words: "not installed", deviceId: RP },
      { name: "Thor", presence: "newest", words: "newest", deviceId: THOR },
    ]);
  });

  it("says who it is waiting to reach, in wait", () => {
    const g = game({ emulatorId: "dolphin", gameKey: "GSXE69", displayName: "SSX 3", devices: [dev(ODIN, "Newest"), dev(THOR, "Waiting")] });
    const c = cardCopy(g, "Dolphin", [g], now);
    expect(c.sentence).toBe("Waiting to reach Thor");
    expect(c.tone).toBe("wait");
    expect(c.kicker).toBe("GameCube · Dolphin");
  });

  it("names both sides of a conflict, in fault", () => {
    const g = game({ emulatorId: "eden", gameKey: "k", openConflict: true, devices: [dev(ODIN, "Differs"), dev(THOR, "Differs")] });
    const c = cardCopy(g, "Eden", [g], now);
    expect(c.sentence).toBe("Conflict between Odin 3 and Thor");
    expect(c.tone).toBe("fault");
    expect(c.lines.map((l) => l.words)).toEqual(["conflict", "conflict"]);
  });

  it("says when there is no save yet", () => {
    const g = game({ emulatorId: "ppsspp", gameKey: "k", newestUtc: null, newestOnDeviceId: null, newestOnDeviceName: null, devices: [dev(ODIN, "HasIt")] });
    const c = cardCopy(g, "PPSSPP", [g], now);
    expect(c.sentence).toBe("No save yet");
    expect(c.lines[0]!.words).toBe("no save yet");
    expect(c.kicker).toBe("PSP · PPSSPP");
  });

  it("names systems with the app's table: only GC, PSX, VITA and MD are spelled out", () => {
    expect(["GC", "PSX", "VITA", "MD", "GBA", "DS", "SWITCH"].map(systemName)).toEqual(
      ["GameCube", "PS1", "PS Vita", "Mega Drive", "GBA", "DS", "SWITCH"]);
  });

  it("leaves the system out of the kicker when it cannot be told", () => {
    const g = game({ emulatorId: "retroarch", gameKey: "retroarch/Mystery" });
    expect(cardCopy(g, "RetroArch", [g], now).kicker).toBe("RetroArch");
    expect(systemName(null)).toBeNull();
  });
});

describe("cursor", () => {
  it("follows the remembered game when the list changes around it", () => {
    expect(cursorIndex({ key: "c", keys: ["a", "b", "c"] }, ["x", "a", "b", "c"])).toBe(3);
  });
  // The app's ShelfCursor rule: the nearest earlier game still there, not the next one.
  it("falls back to the nearest earlier game still there when the game is gone", () => {
    expect(cursorIndex({ key: "b", keys: ["a", "b", "c"] }, ["a", "c"])).toBe(0);
    expect(cursorIndex({ key: "c", keys: ["a", "b", "c", "d"] }, ["a", "d"])).toBe(0);
    expect(cursorIndex({ key: "c", keys: ["a", "b", "c", "d"] }, ["d", "b"])).toBe(1);
    expect(cursorIndex({ key: "a", keys: ["a", "b"] }, ["b"])).toBe(0);
    expect(cursorIndex({ key: "z", keys: [] }, ["a", "b", "c"])).toBe(0);
    expect(cursorIndex({ key: "a", keys: ["a"] }, [])).toBe(0);
    expect(cursorIndex(undefined, ["a"])).toBe(0);
  });
});

// ---- Spine palette ----

function lum(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  const ch = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * ch[0]! + 0.7152 * ch[1]! + 0.0722 * ch[2]!;
}
const contrast = (a: string, b: string) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
  return (x! + 0.05) / (y! + 0.05);
};
function hsl(hex: string): { h: number; s: number } {
  const n = parseInt(hex.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => v / 255) as [number, number, number];
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  if (d === 0) return { h: 0, s: 0 };
  const s = d / (1 - Math.abs(2 * l - 1));
  let h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  h *= 60;
  return { h: h < 0 ? h + 360 : h, s };
}

describe("spine palette", () => {
  it("matches the app", () => {
    expect(SPINES).toEqual({
      GC: ["#34343A", "#F4F2EA"],
      WII: ["#E4E2DA", "#2A2925"],
      GB: ["#5E6850", "#F4F2EA"],
      GBC: ["#2B5F80", "#F4F2EA"],
      GBA: ["#4C5FA0", "#F4F2EA"],
      DS: ["#D2CEC2", "#2A2925"],
      "3DS": ["#3C6E68", "#F4F2EA"],
      SWITCH: ["#44484E", "#F4F2EA"],
      NES: ["#A9A395", "#1B1A17"],
      SNES: ["#5B5F7A", "#F4F2EA"],
      N64: ["#2F5E4A", "#F4F2EA"],
      PS1: ["#9A9893", "#1B1A17"],
      PSP: ["#202226", "#F4F2EA"],
      VITA: ["#2D4A6B", "#F4F2EA"],
      unknown: ["#6B675E", "#F4F2EA"],
    });
  });

  it("maps the system labels onto the palette, with a neutral spine for the rest", () => {
    expect(spineKey("PSX")).toBe("PS1");
    expect(spineKey("3DS")).toBe("3DS");
    expect(spineKey("MD")).toBe("unknown");
    expect(spineKey(null)).toBe("unknown");
    expect(spineColour("GBA")).toBe("#4C5FA0");
    expect(spineInk("DS")).toBe("#2A2925");
    expect(spineColour(null)).toBe("#6B675E");
  });

  it("spine text meets AA", () => {
    for (const [sys, [bg, ink]] of Object.entries(SPINES)) expect(contrast(bg, ink), sys).toBeGreaterThanOrEqual(4.5);
  });

  it("no spine is near wait amber, problem red or purple", () => {
    // --wait and --fault, both themes (tokens.css).
    const near = [hsl("#f0a030").h, hsl("#9a5c00").h, hsl("#ff6b55").h, hsl("#c23824").h];
    for (const [sys, [bg]] of Object.entries(SPINES)) {
      const { h, s } = hsl(bg);
      if (s <= 0.25) continue;
      for (const n of near) expect(Math.min(Math.abs(h - n), 360 - Math.abs(h - n)), sys).toBeGreaterThan(20);
      expect(h >= 260 && h <= 300, sys).toBe(false);
    }
  });
});

// ---- fetchArt ----

const png = () => new Response(new Uint8Array([137, 80, 78, 71]), { status: 200, headers: { "Content-Type": "image/png" } });
const status = (code: number, error: string) =>
  new Response(JSON.stringify({ error }), { status: code, headers: { "Content-Type": "application/json", "Retry-After": "3" } });

describe("fetchArt", () => {
  const wait = vi.fn(async (_ms: number) => {});
  afterEach(() => { vi.unstubAllGlobals(); wait.mockClear(); });

  function stub(...answers: (Response | Error)[]) {
    const f = vi.fn(async (_url: string, _init?: RequestInit) => {
      const a = answers.shift();
      if (!a || a instanceof Error) throw a ?? new Error("no answer");
      return a;
    });
    vi.stubGlobal("fetch", f);
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: vi.fn(() => "blob:art") }));
    return f;
  }

  it("gives an image url on 200, asking with the protocol header and the game's encoded key", async () => {
    const f = stub(png());
    expect(await fetchArt("retroarch", "GBA/Pokémon - Emerald Version (USA).srm", { wait })).toEqual({ kind: "image", url: "blob:art" });
    expect(f.mock.calls[0]![0]).toBe("/v1/web/art/retroarch/GBA/Pok%C3%A9mon%20-%20Emerald%20Version%20(USA).srm");
    expect((f.mock.calls[0]![1]!.headers as Record<string, string>)["Tandem-Protocol"]).toBe("1");
  });

  it("asks once more after 3 s when the art is pending", async () => {
    const f = stub(status(202, "art_pending"), png());
    expect(await fetchArt("dolphin", "GSXE69", { wait })).toEqual({ kind: "image", url: "blob:art" });
    expect(f).toHaveBeenCalledTimes(2);
    expect(wait).toHaveBeenCalledWith(3000);
  });

  it("gives pending when the second answer is still pending", async () => {
    const f = stub(status(202, "art_pending"), status(202, "art_pending"));
    expect(await fetchArt("dolphin", "GSXE69", { wait })).toEqual({ kind: "pending" });
    expect(f).toHaveBeenCalledTimes(2);
  });

  it("gives none on 404", async () => {
    stub(status(404, "no_art"));
    expect(await fetchArt("dolphin", "GSXE69", { wait })).toEqual({ kind: "none" });
  });

  it("gives none on a network error, a 5xx or a body that is not an image", async () => {
    stub(new TypeError("Failed to fetch"));
    expect(await fetchArt("dolphin", "GSXE69", { wait })).toEqual({ kind: "none" });
    stub(new Response("oops", { status: 503 }));
    expect(await fetchArt("dolphin", "GSXE69", { wait })).toEqual({ kind: "none" });
    stub(new Response("<html>", { status: 200, headers: { "Content-Type": "text/html" } }));
    expect(await fetchArt("dolphin", "GSXE69", { wait })).toEqual({ kind: "none" });
  });

  it("gives none once its focus has gone", async () => {
    const c = new AbortController();
    const f = stub(status(202, "art_pending"), png());
    const p = fetchArt("dolphin", "GSXE69", { wait: async () => c.abort(), signal: c.signal });
    expect(await p).toEqual({ kind: "none" });
    expect(f).toHaveBeenCalledTimes(1);
  });
});

describe("case spines", () => {
  it("spine kind rules", () => {
    expect(spineKind("SWITCH", false, false, true)).toBe("plain");
    expect(spineKind("GC", true, true, true)).toBe("real");
    expect(spineKind("GC", true, false, true)).toBe("built");
    expect(spineKind("GBA", false, false, true)).toBe("built");
    expect(spineKind("GBA", false, false, false)).toBe("fallback");
    expect(spineKind(null, false, false, false)).toBe("fallback");
  });
  it("ratios and bands follow the table", () => {
    expect(spineRatio("GC")).toBe(0.074);
    expect(spineRatio("WII")).toBe(0.074);
    expect(spineRatio("3DS")).toBe(0.1);
    expect(spineRatio("SWITCH")).toBe(0.065);
    expect(spineRatio("PSP")).toBe(0.078);
    expect(spineRatio(null)).toBe(0.1);
    expect(spineBand("GBA")).toEqual({ colour: "#F4F2EA", name: "GAME BOY ADVANCE", ink: "#2A2925", height: 0.27 });
    expect(spineBand("SWITCH")?.height).toBe(0.2);
    expect(spineBand("PSX")?.name).toBe("PLAYSTATION");
    expect(spineBand(null)).toBeNull();
  });
});

describe("spine window", () => {
  it("orders the visible spines and a dozen either side by distance from the cursor", () => {
    expect(windowOrder(5, 4, 7, 20, 1)).toEqual([5, 4, 6, 3, 7, 8]);
    expect(windowOrder(0, 0, 2, 3, 12)).toEqual([0, 1, 2]);
    expect(windowOrder(9, 0, 9, 10, 12)).toEqual([9, 8, 7, 6, 5, 4, 3, 2, 1, 0]);
  });
  it("finds the spines in view from the widths and the scroll", () => {
    // Spines 10 px wide, 5 px apart; the card (index 2) is 100 px.
    const widths = [10, 10, 10, 10, 10, 10, 10, 10];
    expect(visibleRange(widths, 5, 100, 2, 0, 60)).toEqual([0, 2]);
    expect(visibleRange(widths, 5, 100, 2, 120, 40)).toEqual([2, 4]);
    expect(visibleRange(widths, 5, 100, 2, 0, 10_000)).toEqual([0, 7]);
    expect(visibleRange([], 5, 100, 0, 0, 100)).toEqual([0, -1]);
  });
});
