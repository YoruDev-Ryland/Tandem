import { untrack } from "svelte";
import { ApiError } from "./http";
import { connectEvents } from "./events";
import type { Topic } from "./types";

export interface Resource<T> {
  readonly data: T | undefined;
  readonly error: ApiError | undefined;
  /** True while a fetch is in flight. `data` keeps the previous value during a reload. */
  readonly loading: boolean;
  reload(): void;
}

/**
 * Loads `load()` now and again whenever one of `topics` fires or the event stream reconnects.
 * Must be called during component initialisation (or inside an effect root): it subscribes in an effect.
 */
export function resource<T>(load: () => Promise<T>, topics: Topic[]): Resource<T> {
  let data = $state<T | undefined>(undefined);
  let error = $state<ApiError | undefined>(undefined);
  let loading = $state(true);
  let seq = 0;

  function reload(): void {
    const mine = ++seq;
    loading = true;
    load().then(
      (value) => {
        if (mine !== seq) return; // a newer load superseded this one
        data = value;
        error = undefined;
        loading = false;
      },
      (e: unknown) => {
        if (mine !== seq) return;
        error = e instanceof ApiError ? e : new ApiError(0, "error", e instanceof Error ? e.message : String(e));
        loading = false;
      },
    );
  }

  $effect(() => {
    untrack(reload);
    const stop = connectEvents(
      (t) => {
        if (topics.includes(t)) reload();
      },
      reload,
    );
    return () => {
      seq++; // drop in-flight results after teardown
      stop();
    };
  });

  return {
    get data() { return data; },
    get error() { return error; },
    get loading() { return loading; },
    reload,
  };
}
