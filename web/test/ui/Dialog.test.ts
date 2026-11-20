import { afterEach, beforeAll, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import { flushSync, tick } from "svelte";
import DialogHarness from "./DialogHarness.svelte";
import { dispatch } from "../../src/lib/input/actions";

// jsdom has no modal dialog support; model the open attribute and close event.
beforeAll(() => {
  const proto = HTMLDialogElement.prototype as HTMLDialogElement & Record<string, unknown>;
  proto.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
  proto.close ??= function (this: HTMLDialogElement) {
    if (!this.hasAttribute("open")) return;
    this.removeAttribute("open");
    this.dispatchEvent(new Event("close"));
  };
});

describe("Dialog", () => {
  afterEach(cleanup);

  it("closes on b and returns focus to its opener", async () => {
    render(DialogHarness);
    const opener = screen.getByRole("button", { name: "Rename" });
    opener.focus();
    await fireEvent.click(opener);
    const dialog = document.querySelector("dialog")!;
    expect(dialog.hasAttribute("open")).toBe(true);
    expect(screen.getByText("Inside")).toBeTruthy();
    expect(dispatch("b")).toBe(true);
    flushSync();
    expect(dialog.hasAttribute("open")).toBe(false);
    expect(document.activeElement).toBe(opener);
  });

  it("holds every other action while open so the page underneath stays put", async () => {
    render(DialogHarness);
    await fireEvent.click(screen.getByRole("button", { name: "Rename" }));
    expect(dispatch("down")).toBe(true);
    dispatch("b");
    flushSync();
    expect(dispatch("down")).toBe(false);
  });

  it("focuses its first control, which a presses", async () => {
    render(DialogHarness);
    await fireEvent.click(screen.getByRole("button", { name: "Rename" }));
    await tick();
    const done = screen.getByRole("button", { name: "Done" });
    expect(document.activeElement).toBe(done);
    dispatch("a");
    flushSync();
    expect(document.querySelector("dialog")!.hasAttribute("open")).toBe(false);
  });
});
