import { describe, expect, it } from "vitest";
import { downloadUrl, exportZipUrl, gamePath, parseGamePath } from "../../src/lib/api/paths";

describe("game paths", () => {
  it.each([
    ["nds", "Pokemon - Platinum Version (USA)"],
    ["retroarch", "a b#c?d"],
    ["ps", "ULUS-10041"],
    ["retroarch", "nds/Pokemon - Platinum Version (USA)"],
    ["retroarch", "100%/ünï/x%2Fy"],
  ])("round-trips %s %s", (emu, key) => {
    expect(parseGamePath(gamePath(emu, key))).toEqual({ emu, key });
  });

  it("keeps '/' as a separator and escapes the rest", () => {
    expect(gamePath("ps", "a b#c?d/e")).toBe("/games/ps/a%20b%23c%3Fd/e");
  });

  it("rejects other paths and bad escapes", () => {
    expect(parseGamePath("/devices/x")).toBeNull();
    expect(parseGamePath("/games/ps")).toBeNull();
    expect(parseGamePath("/games/ps/%E0%A4%A")).toBeNull();
  });

  it("builds download links", () => {
    expect(downloadUrl("abc")).toBe("/v1/saves/versions/abc/download");
    expect(exportZipUrl()).toBe("/v1/export.zip");
    expect(exportZipUrl("eden")).toBe("/v1/export.zip?emulator=eden");
  });
});
