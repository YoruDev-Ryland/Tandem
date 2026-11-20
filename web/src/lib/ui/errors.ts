import { ApiError } from "../api/http";

const TEXT: Record<string, string> = {
  conflict_open: "This game has two saves waiting for a choice. Resolve that first.",
  too_large: "That file is too large for the server's upload limit.",
  rate_limited: "Too many attempts. Wait a minute and try again.",
  device_revoked: "This device was removed from your account. Pair it again to keep syncing.",
  invalid_code: "That code is not valid. Check it and try again.",
  protocol_mismatch: "The server was updated. Reload the page to continue.",
};

/** Human copy for an API error code, with the next step. */
export function errorText(code: string): string {
  return TEXT[code] ?? `Something went wrong (${code}). Try again.`;
}

/** Copy for anything a request can throw: API errors by code, anything else as a lost connection. */
export function describeError(error: unknown): string {
  return error instanceof ApiError ? errorText(error.status === 413 ? "too_large" : error.code) : "Can't reach the server. Check your connection and try again.";
}
