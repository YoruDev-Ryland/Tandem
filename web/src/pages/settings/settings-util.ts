import { ApiError } from "../../lib/api/http";
import { describeError } from "../../lib/ui/errors";

/** Copy for a failed settings request. The server's own sentence is kept for a rejected value (it names the field). */
export function settingsError(e: unknown): string {
  return e instanceof ApiError && e.code === "bad_value" ? e.message : describeError(e);
}
