import { describe, expect, it } from "vitest";
import { waitingDevices } from "../src/lib/state/waiting";
import { systemLabel } from "../src/lib/systems";
import { relativeTime } from "../src/lib/time";
import { game, dev, THOR, ODIN, waiting } from "./fixtures/home";

describe("waitingDevices", () => {
  it("collects devices with a Waiting game, once each", () => {
    const other = game({ emulatorId: "eden", gameKey: "x", devices: [dev(THOR, "Waiting"), dev(ODIN, "HasIt")] });
    expect(waitingDevices([waiting, other])).toEqual([THOR]);
    expect(waitingDevices([])).toEqual([]);
  });
});

describe("systemLabel", () => {
  it("maps single-system emulators", () => {
    expect(systemLabel("eden", "x")).toBe("SWITCH");
    expect(systemLabel("azahar", "x")).toBe("3DS");
    expect(systemLabel("melonds", "x")).toBe("DS");
    expect(systemLabel("ppsspp", "x")).toBe("PSP");
    expect(systemLabel("vita3k", "x")).toBe("VITA");
  });
  it("derives multi-system labels from the key's first segment", () => {
    expect(systemLabel("retroarch", "gba/Emerald")).toBe("GBA");
    expect(systemLabel("retroarch", "gbc/Crystal")).toBe("GBC");
    expect(systemLabel("retroarch", "gb/Red")).toBe("GB");
    expect(systemLabel("retroarch", "nes/x")).toBe("NES");
    expect(systemLabel("retroarch", "snes/x")).toBe("SNES");
    expect(systemLabel("retroarch", "nds/x")).toBe("DS");
    expect(systemLabel("dolphin", "gc/x")).toBe("GC");
    expect(systemLabel("dolphin", "wii/x")).toBe("WII");
  });
  it("falls back to the emulator name", () => {
    expect(systemLabel("retroarch", "mystery/x")).toBe("RetroArch");
    expect(systemLabel("dolphin", "x")).toBe("Dolphin");
    expect(systemLabel("other", "x")).toBe("other");
  });
});

describe("relativeTime", () => {
  const now = Date.parse("2026-11-20T12:00:00Z");
  const ago = (ms: number) => new Date(now - ms).toISOString();
  it("reads naturally", () => {
    expect(relativeTime(ago(10_000), now)).toBe("just now");
    expect(relativeTime(ago(2 * 60_000), now)).toBe("2 min ago");
    expect(relativeTime(ago(2 * 3_600_000), now)).toBe("2 h ago");
    expect(relativeTime(ago(30 * 3_600_000), now)).toBe("yesterday");
    expect(relativeTime(ago(2 * 86_400_000), now)).toBe("2 days ago");
    expect(relativeTime(ago(10 * 86_400_000), now)).toBe("Nov 10");
  });
});
