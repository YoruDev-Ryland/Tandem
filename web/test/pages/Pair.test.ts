import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/svelte";
import Pair from "../../src/pages/Pair.svelte";

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

describe("Pair", () => {
  beforeEach(() => vi.useFakeTimers({ toFake: ["setInterval", "clearInterval", "setTimeout", "clearTimeout", "Date"] }));
  afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); });

  function serve() {
    let n = 0;
    const f = vi.fn(async () => {
      n++;
      return json({ code: n === 1 ? "ABCD2345" : "WXYZ6789", qrPayload: `emusync://pair?code=${n}`, expiresUtc: new Date(Date.now() + 65_000).toISOString() });
    });
    vi.stubGlobal("fetch", f);
    return f;
  }

  it("renders the QR as an svg next to the code, with a countdown", async () => {
    serve();
    const { container } = render(Pair);
    await vi.advanceTimersByTimeAsync(0);
    expect(screen.getByText("ABCD2345")).toBeTruthy();
    expect(container.querySelector("svg")).toBeTruthy();
    expect(screen.getByText("Expires in 1:05")).toBeTruthy();
    await vi.advanceTimersByTimeAsync(5000);
    expect(screen.getByText("Expires in 1:00")).toBeTruthy();
    expect(container.innerHTML).not.toMatch(/#[0-9a-f]{3,8}"/i);
  });

  it("requests a new code when the countdown reaches zero", async () => {
    const f = serve();
    render(Pair);
    await vi.advanceTimersByTimeAsync(0);
    expect(f).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(66_000);
    expect(f).toHaveBeenCalledTimes(2);
    expect(screen.getByText("WXYZ6789")).toBeTruthy();
  });

  it("renders the QR on a light tile in either theme and drops the scan wording", async () => {
    serve();
    const { container } = render(Pair);
    await vi.advanceTimersByTimeAsync(0);
    expect(container.querySelector('[data-theme="light"] svg')).toBeTruthy();
    expect(screen.getByText("Open EmuSync on the device, choose Sign in with a code, and enter this code.")).toBeTruthy();
  });

  it("clears an expired code and does not retry every second when the refresh fails", async () => {
    let n = 0;
    const f = vi.fn(async () => {
      n++;
      if (n > 1) return new Response(JSON.stringify({ code: "boom" }), { status: 500, headers: { "Content-Type": "application/json" } });
      return json({ code: "ABCD2345", qrPayload: "emusync://x", expiresUtc: new Date(Date.now() + 3000).toISOString() });
    });
    vi.stubGlobal("fetch", f);
    render(Pair);
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(4000);
    expect(f).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(f).toHaveBeenCalledTimes(2);
    expect(screen.queryByText("ABCD2345")).toBeNull();
    expect(screen.getByRole("button", { name: "Try again" })).toBeTruthy();
  });

  it("stops fetching after unmount", async () => {
    const f = serve();
    const { unmount } = render(Pair);
    await vi.advanceTimersByTimeAsync(0);
    unmount();
    await vi.advanceTimersByTimeAsync(200_000);
    expect(f).toHaveBeenCalledTimes(1);
  });
});
