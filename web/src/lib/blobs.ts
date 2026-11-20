import { api, ApiError } from "./api/http";
import type { ManifestEntry } from "./api/types";
import type { UploadFile } from "./upload";

/** The server accepts at most this many hashes in one /v1/blobs/missing request. */
const MISSING_BATCH = 1000;

function readBuffer(file: File): Promise<ArrayBuffer> {
  if (typeof file.arrayBuffer === "function") return file.arrayBuffer();
  return new Promise((ok, no) => {
    const r = new FileReader();
    r.onload = () => ok(r.result as ArrayBuffer);
    r.onerror = () => no(r.error);
    r.readAsArrayBuffer(file);
  });
}

const hex = (buf: ArrayBuffer) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");

/**
 * SHA-256 of each file, lowercase hex, one file at a time so only one file is in memory.
 * `onProgress(done, total)` runs after each file.
 */
export async function hashFiles(files: UploadFile[], onProgress?: (done: number, total: number) => void): Promise<ManifestEntry[]> {
  const out: ManifestEntry[] = [];
  for (const f of files) {
    const hash = hex(await crypto.subtle.digest("SHA-256", await readBuffer(f.file)));
    out.push({ path: f.path, hash, size: f.file.size });
    onProgress?.(out.length, files.length);
  }
  return out;
}

async function put(hash: string, body: Blob): Promise<void> {
  let res: Response;
  try {
    res = await fetch(`/v1/blobs/${hash}`, {
      method: "PUT",
      headers: { "EmuSync-Protocol": "1", "Content-Type": "application/octet-stream" },
      body,
      credentials: "same-origin",
    });
  } catch {
    throw new ApiError(0, "network", "Cannot reach the server.");
  }
  if (res.ok) return;
  let code = res.status === 413 ? "too_large" : "error";
  try {
    const err = (await res.json()) as { code?: unknown };
    if (typeof err.code === "string") code = err.code;
  } catch {
    /* not an ErrorResponse body */
  }
  throw new ApiError(res.status, code, res.statusText || `Upload failed (${res.status}).`);
}

/**
 * Sends only the blobs the server does not have. `onProgress(done, total)` counts the blobs being sent.
 * Files with the same content are sent once.
 */
export async function uploadMissingBlobs(
  entries: ManifestEntry[],
  files: UploadFile[],
  onProgress?: (done: number, total: number) => void,
): Promise<void> {
  const byPath = new Map(files.map((f) => [f.path, f.file]));
  const byHash = new Map<string, Blob>();
  for (const e of entries) {
    const f = byPath.get(e.path);
    if (f && !byHash.has(e.hash)) byHash.set(e.hash, f);
  }
  const hashes = [...byHash.keys()];
  const missing: string[] = [];
  for (let i = 0; i < hashes.length; i += MISSING_BATCH) {
    const r = await api<{ missing: string[] }>("POST", "/v1/blobs/missing", { hashes: hashes.slice(i, i + MISSING_BATCH) });
    missing.push(...r.missing);
  }
  let done = 0;
  for (const h of missing) {
    await put(h, byHash.get(h)!);
    onProgress?.(++done, missing.length);
  }
}
