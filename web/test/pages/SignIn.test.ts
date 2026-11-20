import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import SignIn from "../../src/pages/SignIn.svelte";
import { router } from "../../src/lib/router.svelte";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("SignIn", () => {
  beforeEach(() => router.go("/sign-in"));
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("shows invalid_credentials in place, in --fault, without leaving the page", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(401, { code: "invalid_credentials", message: "no" })));
    render(SignIn);
    await fireEvent.input(screen.getByLabelText("Email"), { target: { value: "a@b.c" } });
    await fireEvent.input(screen.getByLabelText("Password"), { target: { value: "bad" } });
    await fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toMatch(/email or password/);
    expect(alert.classList.contains("error")).toBe(true);
    expect(router.path).toBe("/sign-in");
  });

  it("shows a pairing code failure in place", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(410, { code: "code_expired", message: "x" })));
    render(SignIn);
    await fireEvent.input(screen.getByLabelText("Code"), { target: { value: "abcd2345" } });
    await fireEvent.click(screen.getByRole("button", { name: "Sign in with code" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/expired/);
  });

  it("upper-cases the pairing code and limits it to 8 characters", async () => {
    render(SignIn);
    const input = screen.getByLabelText("Code") as HTMLInputElement;
    await fireEvent.input(input, { target: { value: "abcd2345xyz" } });
    expect(input.value).toBe("ABCD2345");
  });

  it("sends the pairing code and leaves for home on success", async () => {
    const fetchMock = vi.fn(async () => json(200, { id: "1", email: "a@b.c", isAdmin: false }));
    vi.stubGlobal("fetch", fetchMock);
    render(SignIn);
    await fireEvent.input(screen.getByLabelText("Code"), { target: { value: "abcd2345" } });
    await fireEvent.click(screen.getByRole("button", { name: "Sign in with code" }));
    await vi.waitFor(() => expect(router.path).toBe("/"));
    const init = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect(JSON.parse(init.body as string)).toEqual({ pairingCode: "ABCD2345" });
  });

  it("offers the pairing hint", () => {
    render(SignIn);
    expect(screen.getByText("Get a code from the app or another signed-in browser.")).toBeTruthy();
  });
});
