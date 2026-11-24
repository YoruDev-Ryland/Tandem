import { describe, expect, it } from "vitest";
import { squarify, mergeSmall, neighbour } from "../src/lib/storage/treemap";
import { blockUnit, blockLayout, blockGroups } from "../src/lib/storage/blocks";
import { topTiles, gameTiles, nestedGameTiles } from "../src/lib/storage/tiles";
import { spineColour, spineInk } from "../src/lib/spines";
import type { StorageGame, StorageUsage } from "../src/lib/api/types";

// Binary, as formatSize shows sizes: a block of "1 MB" holds what the page calls 1.0 MB.
const MB = 1024 * 1024;
const GB = 1024 * MB;

describe("squarify", () => {
  const items = [{ id: "a", value: 60 }, { id: "b", value: 25 }, { id: "c", value: 10 }, { id: "d", value: 5 }, { id: "z", value: 0 }];
  const rects = squarify(items, 400, 300);

  it("drops zero values and sorts largest first", () => {
    expect(rects.map((r) => r.id)).toEqual(["a", "b", "c", "d"]);
  });

  it("gives each rect an area proportional to its value", () => {
    for (const r of rects) {
      const want = (items.find((i) => i.id === r.id)!.value / 100) * 400 * 300;
      expect(Math.abs(r.w * r.h - want)).toBeLessThanOrEqual(1);
    }
  });

  it("tiles the box without overlap", () => {
    const total = rects.reduce((s, r) => s + r.w * r.h, 0);
    expect(Math.abs(total - 400 * 300)).toBeLessThanOrEqual(1);
    for (const r of rects) {
      expect(r.x).toBeGreaterThanOrEqual(-1e-6);
      expect(r.y).toBeGreaterThanOrEqual(-1e-6);
      expect(r.x + r.w).toBeLessThanOrEqual(400 + 1e-6);
      expect(r.y + r.h).toBeLessThanOrEqual(300 + 1e-6);
    }
    for (let i = 0; i < rects.length; i++)
      for (let j = i + 1; j < rects.length; j++) {
        const a = rects[i]!, b = rects[j]!;
        const ox = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
        const oy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
        expect(ox > 1e-6 && oy > 1e-6).toBe(false);
      }
  });

  it("returns nothing for nothing", () => {
    expect(squarify([], 100, 100)).toEqual([]);
    expect(squarify([{ id: "a", value: 0 }], 100, 100)).toEqual([]);
  });
});

describe("mergeSmall", () => {
  it("folds items under 2 % into a rest", () => {
    const items = [{ value: 90 }, { value: 8 }, { value: 1 }, { value: 1 }];
    const { kept, rest } = mergeSmall(items);
    expect(kept.map((i) => i.value)).toEqual([90, 8]);
    expect(rest).toEqual({ count: 2, value: 2 });
  });

  it("has no rest when nothing is small", () => {
    expect(mergeSmall([{ value: 50 }, { value: 50 }]).rest).toBeNull();
  });

  it("keeps a lone small item: one more is no shorter than the item", () => {
    const { kept, rest } = mergeSmall([{ value: 99 }, { value: 1 }]);
    expect(kept.map((i) => i.value)).toEqual([99, 1]);
    expect(rest).toBeNull();
  });

  it("measures the share against a given total", () => {
    const { kept, rest } = mergeSmall([{ value: 10 }, { value: 5 }, { value: 5 }], 0.02, 1000);
    expect(kept).toEqual([]);
    expect(rest).toEqual({ count: 3, value: 20 });
  });
});

const game = (emulatorId: string, gameKey: string, bytes: number, over: Partial<StorageGame> = {}): StorageGame => ({
  emulatorId, gameKey, displayName: null, bytes, currentBytes: bytes, olderBytes: 0, versions: 1, kinds: ["Save"], ...over,
});

const usage = (over: Partial<StorageUsage> = {}): StorageUsage => ({
  totalBytes: 0, byEmulator: [], topGames: [],
  games: [game("eden", "k1", 60 * MB, { displayName: "Zelda", olderBytes: 10 * MB, versions: 3 }), game("eden", "k2", 30 * MB), game("dolphin", "k3", 10 * MB)],
  byKind: { saves: 100 * MB, states: 0, configs: 5 * MB, vault: 0, lostAndFound: 2 * MB },
  olderBytes: 10 * MB,
  server: { filesBytes: 0, diskTotalBytes: null, diskFreeBytes: null, minFreeBytes: 0, artBytes: 7 * MB, databaseBytes: 3 * MB,
    filesLocation: "/data", defaultLocation: true, move: null, lowDisk: null },
  ...over,
});

describe("topTiles", () => {
  it("lists emulators, configs and lost for an admin, plus art and database", () => {
    const ids = topTiles(usage(), true).map((t) => t.id);
    expect(ids).toEqual(expect.arrayContaining(["eden", "dolphin", "art", "database", "configs", "lost"]));
    expect(ids).not.toContain("vault");
  });

  it("leaves out art, database and lost for a non-admin, and zero kinds", () => {
    const ids = topTiles(usage({ server: null }), false).map((t) => t.id);
    expect(ids).toEqual(["eden", "dolphin", "configs"]);
  });

  it("colours emulator tiles from their console and labels them", () => {
    const eden = topTiles(usage(), false).find((t) => t.id === "eden")!;
    expect(eden).toMatchObject({ label: "Eden", kind: "emulator", value: 90 * MB, emulatorId: "eden" });
    expect(eden.colour).toBe(spineColour("SWITCH"));
    expect(eden.ink).toBe(spineInk("SWITCH"));
  });

  it("gives an emulator with no console the neutral colour", () => {
    const u = usage({ games: [game("retroarch", "retroarch/x", MB)] });
    expect(topTiles(u, false)[0]!.colour).toBe(spineColour(null));
  });
});

describe("gameTiles", () => {
  it("lists one emulator's games with name, older bytes and versions", () => {
    const tiles = gameTiles(usage(), "eden");
    expect(tiles.map((t) => t.label)).toEqual(["Zelda", "k2"]);
    expect(tiles[0]).toMatchObject({ kind: "game", gameKey: "k1", emulatorId: "eden", value: 60 * MB, olderBytes: 10 * MB, versions: 3 });
  });

  it("strips the retroarch prefix from unnamed games", () => {
    const u = usage({ games: [game("retroarch", "retroarch/Mario", MB)] });
    expect(gameTiles(u, "retroarch")[0]!.label).toBe("Mario");
  });

  it("merges games under 2 % into one more tile", () => {
    const games = [game("eden", "big", 1000), game("eden", "a", 5), game("eden", "b", 5)];
    const tiles = gameTiles(usage({ games }), "eden");
    expect(tiles.map((t) => t.kind)).toEqual(["game", "more"]);
    expect(tiles[1]).toMatchObject({ label: "2 more", value: 10 });
  });
});

describe("gameTiles folding", () => {
  it("keeps one small game rather than folding it alone", () => {
    const games = [game("eden", "big", 1000), game("eden", "a", 5)];
    expect(gameTiles(usage({ games }), "eden").map((t) => t.kind)).toEqual(["game", "game"]);
  });

  it("carries the current bytes and the count of older versions", () => {
    const g = game("eden", "k", 30 * MB, { currentBytes: 20 * MB, olderBytes: 10 * MB, versions: 5, kinds: ["Save", "State"] });
    expect(gameTiles(usage({ games: [g] }), "eden")[0]).toMatchObject({ currentBytes: 20 * MB, olderVersions: 3 });
  });
});

describe("nestedGameTiles", () => {
  // Eden is a sliver of a big map: its games are judged against the map, not against Eden.
  const games = [game("eden", "a", 50), game("eden", "b", 30), game("eden", "c", 20), game("melonds", "x", 10_000)];

  it("folds games under 2 % of the map's total, even when large within their emulator", () => {
    const tiles = nestedGameTiles(usage({ games }), "eden", 10_100);
    expect(tiles).toEqual([expect.objectContaining({ id: "eden/more", kind: "more", label: "3 more", value: 100 })]);
    // Against Eden alone none of them is small.
    expect(gameTiles(usage({ games }), "eden").map((t) => t.kind)).toEqual(["game", "game", "game"]);
  });

  it("keeps games that are big on the map", () => {
    const tiles = nestedGameTiles(usage({ games }), "melonds", 10_100);
    expect(tiles.map((t) => t.id)).toEqual(["melonds/x"]);
  });
});

describe("neighbour", () => {
  const rects = [
    { id: "a", x: 0, y: 0, w: 100, h: 100 },
    { id: "b", x: 100, y: 0, w: 100, h: 50 },
    { id: "c", x: 100, y: 50, w: 100, h: 50 },
    { id: "d", x: 200, y: 0, w: 50, h: 100 },
  ];
  it("picks the closest tile to the right", () => {
    expect(neighbour(rects, "a", "right")).toBe("b");
    expect(neighbour(rects, "c", "right")).toBe("d");
  });
  it("moves down and up", () => {
    expect(neighbour(rects, "b", "down")).toBe("c");
    expect(neighbour(rects, "c", "up")).toBe("b");
  });
  it("returns null at the edge or for an unknown id", () => {
    expect(neighbour(rects, "a", "left")).toBeNull();
    expect(neighbour(rects, "d", "right")).toBeNull();
    expect(neighbour(rects, "nope", "right")).toBeNull();
  });
});

describe("blockUnit", () => {
  it("is the smallest of 1, 10, 100, 1000 MB keeping used blocks at or under 200", () => {
    expect(blockUnit(116 * MB)).toBe(1);
    expect(blockUnit(200 * MB)).toBe(1);
    expect(blockUnit(201 * MB)).toBe(10);
    expect(blockUnit(5 * GB)).toBe(100);
    expect(blockUnit(150 * GB)).toBe(1000);
    expect(blockUnit(0)).toBe(1);
  });
});

describe("blockLayout", () => {
  it("makes blocks per group in order and notches those holding older versions", () => {
    const out = blockLayout([{ id: "eden", bytes: 5 * MB, olderBytes: 2 * MB }, { id: "dolphin", bytes: 2 * MB, olderBytes: 0 }], 1);
    expect(out.map((b) => b.group)).toEqual(["eden", "eden", "eden", "eden", "eden", "dolphin", "dolphin"]);
    expect(out.filter((b) => b.notched).length).toBe(2);
    expect(out.slice(5).some((b) => b.notched)).toBe(false);
  });

  it("gives a non-empty group at least one block and skips empty groups", () => {
    const out = blockLayout([{ id: "tiny", bytes: 10, olderBytes: 5 }, { id: "none", bytes: 0, olderBytes: 0 }], 1);
    expect(out).toEqual([{ group: "tiny", notched: true }]);
  });
});

describe("blockGroups", () => {
  const games = [
    game("eden", "zelda", 20 * MB, { displayName: "Zelda", olderBytes: 4 * MB }),
    game("eden", "s1", MB / 10), game("eden", "s2", MB / 10),
    game("melonds", "plat", 10 * MB),
  ];

  it("lays out games emulator by emulator, then the rest, art and the database", () => {
    const { groups, unit } = blockGroups(usage({ games }), true);
    expect(unit).toBe(1);
    expect(groups.map((g) => g.tile.id)).toEqual(["eden/zelda", "eden/more", "melonds/plat", "other", "art", "database"]);
    expect(groups.map((g) => g.key)).toEqual(["eden", "eden", "melonds", "other", "art", "database"]);
    expect(groups[1]!.tile).toMatchObject({ label: "2 more", value: MB / 5 });
    expect(groups[3]!.tile).toMatchObject({ label: "Configs, Lost & found", value: 7 * MB });
  });

  it("leaves out the admin-only groups for everyone else", () => {
    const { groups } = blockGroups(usage({ games, server: null, byKind: { saves: 0, states: 0, configs: MB, vault: 2 * MB, lostAndFound: MB } }), false);
    expect(groups.map((g) => g.key)).toEqual(["eden", "eden", "melonds", "other"]);
    expect(groups[3]!.tile).toMatchObject({ label: "Configs, Vault", value: 3 * MB });
  });

  it("sizes the block from the groups' bytes, not the total", () => {
    const big = [game("eden", "a", 150 * MB), game("melonds", "b", 100 * MB)];
    const { unit } = blockGroups(usage({ games: big, totalBytes: 10 * MB, byKind: { saves: 0, states: 0, configs: 0, vault: 0, lostAndFound: 0 }, server: null }), false);
    expect(unit).toBe(10);
  });
});
