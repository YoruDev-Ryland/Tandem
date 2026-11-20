import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushSync } from "svelte";
import { resource } from "../../src/lib/api/resource.svelte";
import { ApiError } from "../../src/lib/api/http";
import { root } from "./root.svelte";

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  readyState = 1;
  private listeners = new Map<string, ((e: Event) => void)[]>();
  constructor(public url: string) { FakeEventSource.instances.push(this); }
  addEventListener(type: string, fn: (e: Event) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }
  close() {}
  emit(type: string, data?: unknown) {
    const e = data === undefined ? new Event(type) : Object.assign(new Event(type), { data: JSON.stringify(data) });
    for (const fn of this.listeners.get(type) ?? []) fn(e);
  }
}

const tick = () => new Promise((r) => setTimeout(r, 0));

describe("resource", () => {
  let cleanup: () => void;
  beforeEach(() => { FakeEventSource.instances = []; vi.stubGlobal("EventSource", FakeEventSource); });
  afterEach(() => { cleanup?.(); vi.unstubAllGlobals(); });

  function make<T>(load: () => Promise<T>) {
    let r!: ReturnType<typeof resource<T>>;
    cleanup = root(() => { r = resource(load, ["saves"]); });
    flushSync();
    return r;
  }

  it("loads on creation and exposes data", async () => {
    const r = make(async () => 42);
    expect(r.loading).toBe(true);
    await tick();
    expect(r.data).toBe(42);
    expect(r.loading).toBe(false);
    expect(r.error).toBeUndefined();
  });

  it("reloads once on a matching topic and not on an unrelated one", async () => {
    const load = vi.fn(async () => 1);
    make(load);
    await tick();
    expect(load).toHaveBeenCalledTimes(1);
    const es = FakeEventSource.instances[0];
    es.emit("changed", { type: "changed", topic: "activity" });
    expect(load).toHaveBeenCalledTimes(1);
    es.emit("changed", { type: "changed", topic: "saves" });
    expect(load).toHaveBeenCalledTimes(2);
  });

  it("reloads once on reconnect", async () => {
    const load = vi.fn(async () => 1);
    make(load);
    await tick();
    const es = FakeEventSource.instances[0];
    es.emit("open");
    es.emit("error");
    es.emit("open");
    expect(load).toHaveBeenCalledTimes(2);
  });

  it("keeps data and sets error when a reload fails", async () => {
    let n = 0;
    const r = make(async () => {
      if (n++ === 0) return "ok";
      throw new ApiError(500, "boom", "Failed.");
    });
    await tick();
    r.reload();
    await tick();
    expect(r.data).toBe("ok");
    expect(r.error?.code).toBe("boom");
    expect(r.loading).toBe(false);
  });

  it("ignores a stale response that finishes after a newer one", async () => {
    const resolvers: ((v: string) => void)[] = [];
    const r = make(() => new Promise<string>((res) => resolvers.push(res)));
    r.reload();
    resolvers[1]("new");
    await tick();
    resolvers[0]("old");
    await tick();
    expect(r.data).toBe("new");
  });
});
