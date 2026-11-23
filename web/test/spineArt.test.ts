import { describe, expect, it, vi } from "vitest";
import { createSpineArt } from "../src/lib/spineArt.svelte";
import type { ArtResult } from "../src/lib/api/endpoints";

function deferredFetcher() {
  const waiting: (() => void)[] = [];
  let inFlight = 0;
  let peak = 0;
  const calls: string[] = [];
  const fetcher = vi.fn(async (emu: string, key: string, opts: { kind?: "cover" | "spine" } = {}): Promise<ArtResult> => {
    calls.push(`${opts.kind ?? "cover"}:${emu}/${key}`);
    inFlight++;
    peak = Math.max(peak, inFlight);
    await new Promise<void>((r) => waiting.push(r));
    inFlight--;
    return { kind: "image", url: `blob:${opts.kind ?? "cover"}-${key}` };
  });
  return { fetcher, calls, peak: () => peak, release: () => { while (waiting.length) waiting.shift()!(); } };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe("spine art", () => {
  it("asks each image once and at most four at a time", async () => {
    const f = deferredFetcher();
    const art = createSpineArt(f.fetcher, 4);
    const ids = Array.from({ length: 10 }, (_, i) => ({ emu: "retroarch", key: `g${i}`, kind: "cover" as const }));
    art.want(ids);
    art.want(ids);
    await flush();
    expect(f.peak()).toBe(4);
    for (let i = 0; i < 5; i++) { f.release(); await flush(); }
    expect(f.calls).toHaveLength(10);
    expect(f.peak()).toBeLessThanOrEqual(4);
    expect(art.url("retroarch", "g3", "cover")).toBe("blob:cover-g3");
    expect(art.url("retroarch", "g3", "spine")).toBeNull();
  });

  it("release revokes what is no longer kept", async () => {
    const f = deferredFetcher();
    const revoke = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    const art = createSpineArt(f.fetcher, 4);
    art.want([{ emu: "e", key: "a", kind: "cover" }, { emu: "e", key: "b", kind: "spine" }]);
    await flush(); f.release(); await flush();
    art.release(new Set(["cover:e/a"]));
    expect(revoke).toHaveBeenCalledWith("blob:spine-b");
    expect(revoke).not.toHaveBeenCalledWith("blob:cover-a");
    expect(art.url("e", "b", "spine")).toBeNull();
    revoke.mockRestore();
  });

  it("remembers a missing image so it is not asked again", async () => {
    const fetcher = vi.fn(async (): Promise<ArtResult> => ({ kind: "none" }));
    const art = createSpineArt(fetcher, 4);
    art.want([{ emu: "e", key: "a", kind: "spine" }]);
    await flush(); await flush();
    expect(art.missing("e", "a", "spine")).toBe(true);
    art.want([{ emu: "e", key: "a", kind: "spine" }]);
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
