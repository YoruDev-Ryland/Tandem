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

describe("hashFiles progress", () => {
  it("reports (done, total) after each file", async () => {
    const seen: [number, number][] = [];
    await hashFiles([file("abc", "a"), file("abd", "b")], (n, t) => seen.push([n, t]));
    expect(seen).toEqual([[1, 2], [2, 2]]);
  });
});

describe("uploadMissingBlobs", () => {
  it("retries after a failed PUT and sends only the one missing blob of two", async () => {
    const files = [file("abc", "a.bin"), file("other", "b.bin")];
    const entries = await hashFiles(files);
    let fail = true;
    const puts: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      if (url === "/v1/blobs/missing") return new Response(JSON.stringify({ missing: [entries[1].hash] }), { status: 200, headers: { "Content-Type": "application/json" } });
      puts.push(url);
      return fail ? new Response("{}", { status: 500 }) : new Response(null, { status: 204 });
    }));
    await expect(uploadMissingBlobs(entries, files)).rejects.toThrow();
    fail = false;
    await uploadMissingBlobs(entries, files);
    expect(puts).toEqual([`/v1/blobs/${entries[1].hash}`, `/v1/blobs/${entries[1].hash}`]);
  });

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

  it("batches more than 1000 hashes into several /missing requests", async () => {
    const entries = Array.from({ length: 2001 }, (_, i) => ({ path: `f${i}`, hash: i.toString(16).padStart(64, "0"), size: 1 }));
    const files = entries.map((e) => file("x", e.path));
    const sizes: number[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_u: string, init?: RequestInit) => {
      sizes.push(JSON.parse(init!.body as string).hashes.length);
      return new Response(JSON.stringify({ missing: [] }), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
    await uploadMissingBlobs(entries, files);
    expect(sizes).toEqual([1000, 1000, 1]);
  });
});
