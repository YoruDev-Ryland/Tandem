import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { connectEvents } from "../../src/lib/api/events";

export class FakeEventSource {
  static instances: FakeEventSource[] = [];
  readyState = 0;
  closed = false;
  private listeners = new Map<string, ((e: Event) => void)[]>();
  constructor(public url: string) { FakeEventSource.instances.push(this); }
  addEventListener(type: string, fn: (e: Event) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }
  close() { this.closed = true; this.readyState = 2; }
  emit(type: string, data?: unknown) {
    const e = data === undefined ? new Event(type) : Object.assign(new Event(type), { data: JSON.stringify(data) });
    for (const fn of this.listeners.get(type) ?? []) fn(e);
  }
}

describe("connectEvents", () => {
  beforeEach(() => { FakeEventSource.instances = []; vi.stubGlobal("EventSource", FakeEventSource); });
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("reports changed topics and ignores ping and unknown topics", () => {
    const onTopic = vi.fn();
    const stop = connectEvents(onTopic, vi.fn());
    const es = FakeEventSource.instances[0];
    expect(es.url).toBe("/v1/events");
    es.emit("open");
    es.emit("changed", { type: "changed", topic: "saves" });
    es.emit("ping", {});
    es.emit("changed", { type: "changed", topic: "nope" });
    expect(onTopic).toHaveBeenCalledOnce();
    expect(onTopic).toHaveBeenCalledWith("saves");
    stop();
  });

  it("calls onReconnect once after an error then open, not on the first open", () => {
    const onReconnect = vi.fn();
    const stop = connectEvents(vi.fn(), onReconnect);
    const es = FakeEventSource.instances[0];
    es.emit("open");
    expect(onReconnect).not.toHaveBeenCalled();
    es.emit("error");
    es.emit("open");
    expect(onReconnect).toHaveBeenCalledOnce();
    es.emit("open");
    expect(onReconnect).toHaveBeenCalledOnce();
    stop();
  });

  it("shares one connection and closes it after the last unsubscribe", () => {
    const a = connectEvents(vi.fn(), vi.fn());
    const b = connectEvents(vi.fn(), vi.fn());
    expect(FakeEventSource.instances).toHaveLength(1);
    a();
    expect(FakeEventSource.instances[0].closed).toBe(false);
    b();
    expect(FakeEventSource.instances[0].closed).toBe(true);
  });

  it("reopens after the browser gives up on the connection", () => {
    vi.useFakeTimers();
    const onReconnect = vi.fn();
    const stop = connectEvents(vi.fn(), onReconnect);
    const first = FakeEventSource.instances[0];
    first.readyState = 2;
    first.emit("error");
    vi.advanceTimersByTime(3000);
    expect(FakeEventSource.instances).toHaveLength(2);
    FakeEventSource.instances[1].emit("open");
    expect(onReconnect).toHaveBeenCalledOnce();
    stop();
  });
});
