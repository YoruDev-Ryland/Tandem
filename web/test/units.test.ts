import { describe, expect, it } from "vitest";
import { decodeConfig, NotText } from "../src/lib/configText";
import { attentionItems, heroLine, quietFor } from "../src/lib/attention";
import { deviceSaveCounts, matrixColumns, presence } from "../src/lib/deviceStates";
import { dev, game, home, noHead, ODIN, THOR, waiting } from "./fixtures/home";

const buf = (bytes: number[]) => new Uint8Array(bytes).buffer;

describe("decodeConfig (strict UTF-8)", () => {
  it("decodes UTF-8 and keeps the BOM and CRLF facts", () => {
    const t = decodeConfig(buf([0xef, 0xbb, 0xbf, 0x61, 0x0d, 0x0a, 0x62]));
    expect(t).toEqual({ text: "a\nb", bom: true, crlf: true });
  });
  it("refuses bytes that are not valid UTF-8 instead of replacing them", () => {
    expect(() => decodeConfig(buf([0x61, 0xff, 0xfe, 0x62]))).toThrow(NotText);
    expect(() => decodeConfig(buf([0xc3]))).toThrow(/can't be edited in the browser/);
  });
});

describe("presence", () => {
  it("one column per device, by name, repeated names numbered", () => {
    const a = game({ emulatorId: "eden", gameKey: "a", devices: [dev(THOR, "Newest"), dev(ODIN, "HasIt")] });
    const b = game({ emulatorId: "eden", gameKey: "b", devices: [{ deviceId: "d-two", deviceName: "Thor", state: "HasIt" }] });
    expect(matrixColumns([a, b])).toEqual([
      { id: ODIN, name: "Odin 3" },
      { id: THOR, name: "Thor" },
      { id: "d-two", name: "Thor (2)" },
    ]);
  });
  it("reads HasIt on a game with no save as installed, not as the newest", () => {
    expect(presence(noHead, ODIN)).toBe("empty");
    expect(presence(waiting, THOR)).toBe("waiting");
    expect(presence(waiting, "nobody")).toBe("absent");
  });
  it("counts a device's current saves among the games it has installed", () => {
    expect(deviceSaveCounts([waiting, noHead], ODIN)).toEqual({ current: 1, total: 1, newestHere: 1 });
    expect(deviceSaveCounts([waiting, noHead], THOR)).toEqual({ current: 0, total: 1, newestHere: 0 });
  });
});

describe("attention", () => {
  const now = Date.parse("2026-11-20T12:00:00Z");
  it("says how long a device has been quiet", () => {
    expect(quietFor(new Date(now - 3 * 86_400_000 - 5).toISOString(), now)).toBe("3 days");
    expect(quietFor(new Date(now - 30 * 3_600_000).toISOString(), now)).toBe("30 hours");
    expect(quietFor(null, now)).toBe("");
  });
  it("is calm with nothing to say, and the quiet backup note does not break the calm", () => {
    const h = home(0, { openConflicts: 0, backupNotConfigured: true });
    const items = attentionItems(h, [], [], now);
    expect(items.map((i) => i.tone)).toEqual(["quiet"]);
    expect(heroLine(items)).toEqual({ text: "Everything is where it should be.", tone: "ink" });
  });
  it("splits a single item's two sentences into the hero and the line under it", () => {
    const h = home(0, { openConflicts: 0, shizukuOffDevices: [THOR] });
    const hero = heroLine(attentionItems(h, [], [], now));
    expect(hero.text).toBe("Shizuku is off on Thor.");
    expect(hero.sub).toBe("Nothing syncs there until it is back on.");
    expect(hero.tone).toBe("fault");
    expect(hero.item?.href).toBe(`/devices/${THOR}`);
  });
  it("names failed deliveries and links to Activity", () => {
    const items = attentionItems(home(0, { openConflicts: 0, failedDeliveries24h: 3 }), [], [], now);
    expect(items[0]).toMatchObject({ text: "3 deliveries failed in the last day.", href: "/activity", tone: "fault" });
  });
});
