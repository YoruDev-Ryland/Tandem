import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, onUnauthorized } from "../../src/lib/api/http";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("api", () => {
  const fetchMock = vi.fn();
  beforeEach(() => { vi.stubGlobal("fetch", fetchMock); });
  afterEach(() => { fetchMock.mockReset(); onUnauthorized(); vi.unstubAllGlobals(); });

  it("sends the protocol header, credentials and a JSON body", async () => {
    fetchMock.mockResolvedValue(json(200, { ok: 1 }));
    const out = await api<{ ok: number }>("POST", "/v1/x", { a: 1 });
    expect(out).toEqual({ ok: 1 });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/v1/x");
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("same-origin");
    expect(init.headers["Tandem-Protocol"]).toBe("1");
    expect(init.headers["Content-Type"]).toBe("application/json");
    expect(init.body).toBe('{"a":1}');
  });

  it("leaves FormData bodies to the browser", async () => {
    fetchMock.mockResolvedValue(json(200, {}));
    await api("POST", "/v1/web/saves/upload", new FormData());
    expect(fetchMock.mock.calls[0][1].headers["Content-Type"]).toBeUndefined();
  });

  it("resolves 204 to undefined", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    expect(await api("DELETE", "/v1/x")).toBeUndefined();
  });

  it("maps ErrorResponse to ApiError", async () => {
    fetchMock.mockResolvedValue(json(409, { code: "conflict_open", message: "Resolve it first." }));
    const err = await api("POST", "/v1/x").catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 409, code: "conflict_open", message: "Resolve it first." });
  });

  it("falls back to a generic ApiError for non-JSON failures", async () => {
    fetchMock.mockResolvedValue(new Response("boom", { status: 502 }));
    const err = await api("GET", "/v1/x").catch((e) => e);
    expect(err).toMatchObject({ status: 502, code: "error" });
  });

  it("calls onUnauthorized on 401 and still throws", async () => {
    const hook = vi.fn();
    onUnauthorized(hook);
    fetchMock.mockResolvedValue(json(401, { code: "unauthorized", message: "Authentication is required." }));
    await expect(api("GET", "/v1/account")).rejects.toMatchObject({ status: 401, code: "unauthorized" });
    expect(hook).toHaveBeenCalledOnce();
  });

  it("reports a network failure as ApiError status 0", async () => {
    fetchMock.mockRejectedValue(new TypeError("failed"));
    await expect(api("GET", "/v1/x")).rejects.toMatchObject({ status: 0, code: "network" });
  });
});
