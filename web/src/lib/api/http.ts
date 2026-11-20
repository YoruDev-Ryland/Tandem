import type { ErrorResponse } from "./types";

export class ApiError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

let unauthorizedHandler: () => void = () => {};

/** The session module registers what happens on a 401 (go to sign-in). Pass nothing to reset to a no-op. */
export function onUnauthorized(handler?: () => void): void {
  unauthorizedHandler = handler ?? (() => {});
}

async function send(method: string, path: string, body: unknown, accept: string): Promise<Response> {
  const headers: Record<string, string> = { "EmuSync-Protocol": "1", Accept: accept };
  let payload: BodyInit | undefined;
  if (body instanceof FormData) {
    payload = body; // the browser sets the multipart boundary
  } else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  let res: Response;
  try {
    res = await fetch(path, { method, headers, body: payload, credentials: "same-origin" });
  } catch {
    throw new ApiError(0, "network", "Cannot reach the server.");
  }
  if (res.ok) return res;

  let code = "error";
  let message = res.statusText || `Request failed (${res.status}).`;
  try {
    const err = (await res.json()) as Partial<ErrorResponse>;
    if (typeof err.code === "string") code = err.code;
    if (typeof err.message === "string") message = err.message;
  } catch {
    /* not an ErrorResponse body */
  }
  if (res.status === 401) {
    code = code === "error" ? "unauthorized" : code;
    unauthorizedHandler();
  }
  throw new ApiError(res.status, code, message);
}

/** JSON request. A FormData body is sent as multipart. 204 and empty bodies resolve to undefined. */
export async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await send(method, path, body, "application/json");
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** For endpoints that answer text/plain (config file contents). */
export async function apiText(method: string, path: string): Promise<string> {
  return (await send(method, path, undefined, "text/plain")).text();
}
