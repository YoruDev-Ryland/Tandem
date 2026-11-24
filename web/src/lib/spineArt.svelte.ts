import { fetchArt, type ArtResult } from "./api/endpoints";

export type ArtKind = "cover" | "spine";
export interface ArtWant {
  emu: string;
  key: string;
  kind: ArtKind;
}

type Fetcher = (emu: string, key: string, opts: { kind?: ArtKind; signal?: AbortSignal }) => Promise<ArtResult>;

export const artId = (emu: string, key: string, kind: ArtKind) => `${kind}:${emu}/${key}`;

/**
 * The shelf's images: real spines and covers as blob URLs, fetched at most [limit] at a time, each asked for once per
 * page visit ("none" is remembered). A pending answer is asked again after [retryMs] while the image is still wanted,
 * up to MAX_ASKS times (the server fetches one image at a time, so a shelf's worth takes a while); a failed one is
 * asked again when next wanted. [release] frees every URL not in its keep set.
 */
export function createSpineArt(fetcher: Fetcher = fetchArt, limit = 4, retryMs = 3000) {
  const urls = $state<Record<string, string>>({});
  const missing = $state<Record<string, true>>({});
  const asked = new Set<string>();
  const asks = new Map<string, number>();
  const MAX_ASKS = 10;
  const queue: ArtWant[] = [];
  const stop = new AbortController();
  let running = 0;

  function pump() {
    while (running < limit && queue.length > 0) {
      const w = queue.shift()!;
      const id = artId(w.emu, w.key, w.kind);
      running++;
      fetcher(w.emu, w.key, { kind: w.kind, signal: stop.signal })
        .then((r) => {
          if (r.kind === "image") {
            if (stop.signal.aborted || !asked.has(id)) URL.revokeObjectURL(r.url);
            else urls[id] = r.url;
          } else if (r.kind === "none") missing[id] = true;
          else {
            // Pending: ask again in a while, as long as it is still wanted (release takes it out of asked).
            const n = (asks.get(id) ?? 0) + 1;
            asks.set(id, n);
            if (n < MAX_ASKS && !stop.signal.aborted)
              setTimeout(() => {
                if (!asked.has(id) || stop.signal.aborted) return;
                queue.push(w);
                pump();
              }, retryMs);
            else asked.delete(id);
          }
        })
        .catch(() => asked.delete(id))
        .finally(() => {
          running--;
          pump();
        });
    }
  }

  return {
    /** Queues the images not asked for yet, in order. */
    want(ids: ArtWant[]): void {
      for (const w of ids) {
        const id = artId(w.emu, w.key, w.kind);
        if (asked.has(id)) continue;
        asked.add(id);
        queue.push(w);
      }
      pump();
    },
    url(emu: string, key: string, kind: ArtKind): string | null {
      return urls[artId(emu, key, kind)] ?? null;
    },
    /** Whether the server said there is none of this. */
    missing(emu: string, key: string, kind: ArtKind): boolean {
      return missing[artId(emu, key, kind)] === true;
    },
    /** Frees every image not in [keep] (ids from artId); they are asked for again if wanted again. */
    release(keep: Set<string>): void {
      for (const id of Object.keys(urls)) {
        if (keep.has(id)) continue;
        URL.revokeObjectURL(urls[id]!);
        delete urls[id];
        asked.delete(id);
      }
      // Anything else not kept (asked, pending a retry, or in flight) is forgotten: a retry stops, and a late answer
      // is freed when it lands.
      for (const id of [...asked]) if (!keep.has(id)) asked.delete(id);
      for (let i = queue.length - 1; i >= 0; i--) {
        const w = queue[i]!;
        const id = artId(w.emu, w.key, w.kind);
        if (!keep.has(id)) {
          queue.splice(i, 1);
          asked.delete(id);
        }
      }
    },
    /** Stops everything and frees every image (the page is leaving). */
    destroy(): void {
      stop.abort();
      queue.length = 0;
      for (const id of Object.keys(urls)) {
        URL.revokeObjectURL(urls[id]!);
        delete urls[id];
      }
      asked.clear();
    },
  };
}

export type SpineArt = ReturnType<typeof createSpineArt>;
