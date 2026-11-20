import { describe, expect, it } from "vitest";
import { emulatorName } from "../../src/lib/emulators";

describe("emulatorName", () => {
  it("maps ids to display names and passes unknown ids through", () => {
    expect(emulatorName("eden")).toBe("Eden");
    expect(emulatorName("melonds")).toBe("melonDS");
    expect(emulatorName("vita3k")).toBe("Vita3K");
    expect(emulatorName("mystery")).toBe("mystery");
  });
});
