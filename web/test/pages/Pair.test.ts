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
});
