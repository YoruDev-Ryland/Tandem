import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const dir = join(__dirname, "../../src/lib/ui");

// Spec §7: with prefers-reduced-motion, movement is removed and colour/opacity fades stay.
describe("reduced motion", () => {
  it("never switches off fades, only movement", () => {
    for (const f of readdirSync(dir).filter((n) => n.endsWith(".svelte"))) {
      const src = readFileSync(join(dir, f), "utf8");
      for (const m of src.matchAll(/@media \(prefers-reduced-motion: reduce\)\s*\{([\s\S]*?)\n  \}/g)) {
        expect(m[1], f).not.toMatch(/(animation|transition)\s*:\s*none/);
      }
    }
  });
});
