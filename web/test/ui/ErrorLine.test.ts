import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/svelte";
import ErrorLine from "../../src/lib/ui/ErrorLine.svelte";
import { errorText } from "../../src/lib/ui/errors";
import { ApiError } from "../../src/lib/api/http";

describe("ErrorLine", () => {
  afterEach(cleanup);

  it("maps too_large to the upload-limit sentence, in place and in --fault", () => {
    render(ErrorLine, { error: new ApiError(413, "too_large", "Blob exceeds the maximum allowed size.") });
    const alert = screen.getByRole("alert");
    expect(alert.textContent).toBe("That file is too large for the server's upload limit.");
    expect(alert.classList.contains("fault")).toBe(true);
  });

  it("names unknown codes", () => {
    expect(errorText("teapot")).toBe("Something went wrong (teapot). Try again.");
  });

  it("has copy for every listed code", () => {
    for (const code of ["conflict_open", "too_large", "rate_limited", "device_revoked", "invalid_code", "protocol_mismatch"]) {
      expect(errorText(code)).not.toMatch(/Something went wrong/);
    }
  });
});
