import { afterEach, describe, expect, it, vi } from "vitest";
import { hashFiles, uploadMissingBlobs } from "../src/lib/blobs";

const ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
const file = (text: string, path: string) => ({ file: new File([text], path.split("/").pop()!), path });

afterEach(() => vi.unstubAllGlobals());

describe("hashFiles", () => {
  it("hashes with lowercase hex SHA-256, keeping relative paths and sizes", async () => {
    const seen: number[] = [];
    const out = await hashFiles([file("abc", "keys/prod.keys")], (n) => seen.push(n));
    expect(out).toEqual([{ path: "keys/prod.keys", hash: ABC, size: 3 }]);
    expect(seen).toEqual([1]);
  });
});

describe("uploadMissingBlobs", () => {
  it("PUTs only the hashes the server reports missing", async () => {
    const files = [file("abc", "a.bin"), file("other", "b.bin")];
    const entries = await hashFiles(files);
    const calls: { method: string; url: string; body?: unknown }[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ method: init?.method ?? "GET", url, body: init?.body });
      if (url === "/v1/blobs/missing") {
        return new Response(JSON.stringify({ missing: [entries[1].hash] }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return new Response(null, { status: 204 });
    }));
    const progress: [number, number][] = [];
    await uploadMissingBlobs(entries, files, (n, m) => progress.push([n, m]));
    const puts = calls.filter((c) => c.method === "PUT");
    expect(puts.map((c) => c.url)).toEqual([`/v1/blobs/${entries[1].hash}`]);
    expect(progress).toEqual([[1, 1]]);
  });

  it("sends nothing when every blob is already there", async () => {
    const files = [file("abc", "a.bin")];
    const entries = await hashFiles(files);
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ missing: [] }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    await uploadMissingBlobs(entries, files);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
