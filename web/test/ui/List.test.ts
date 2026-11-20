import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import { createRawSnippet, flushSync, tick } from "svelte";
import ListHarness from "./ListHarness.svelte";
import Row from "../../src/lib/ui/Row.svelte";
import { dispatch } from "../../src/lib/input/actions";

const rows = [{ name: "Pokémon Sword" }, { name: "Pokémon Platinum" }, { name: "Xenoblade Chronicles X" }];
const current = () => screen.getAllByRole("button").find((b) => b.getAttribute("aria-current") === "true");

describe("List", () => {
  afterEach(cleanup);

  it("moves the cursor on down and opens the focused row on a", () => {
    const onOpen = vi.fn();
    render(ListHarness, { rows, onOpen });
    expect(current()?.textContent).toBe("Pokémon Sword");
    expect(dispatch("down")).toBe(true);
    flushSync();
    expect(current()?.textContent).toBe("Pokémon Platinum");
    dispatch("a");
    expect(onOpen).toHaveBeenCalledWith(rows[1]);
  });

  it("follows real pointer movement and opens on a mouse click of the focused row", async () => {
    const onOpen = vi.fn();
    render(ListHarness, { rows, onOpen });
    const third = screen.getByText("Xenoblade Chronicles X").closest("button")!;
    await fireEvent.pointerMove(third, { clientX: 10, clientY: 90 });
    expect(current()).toBe(third);
    await fireEvent.click(third, { detail: 1 });
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onOpen).toHaveBeenCalledWith(rows[2]);
  });

  it("ignores pointer events without movement, so Enter opens the highlighted row", async () => {
    const onOpen = vi.fn();
    render(ListHarness, { rows, onOpen });
    const [first, , third] = screen.getAllByRole("button");
    first!.focus();
    // Content scrolling under a resting pointer: enter and move events at the same coordinates.
    await fireEvent.pointerMove(third!, { clientX: 10, clientY: 90 });
    dispatch("up"); // keyboard takes the cursor back to row 0
    flushSync();
    await fireEvent.mouseEnter(third!);
    await fireEvent.pointerMove(third!, { clientX: 10, clientY: 90 });
    expect(current()?.textContent).toBe("Pokémon Platinum");
    // Enter on the focused row is a keyboard click (detail 0): it opens the highlighted row.
    await fireEvent.click(document.activeElement!, { detail: 0 });
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onOpen).toHaveBeenCalledWith(rows[1]);
  });

  it("moves DOM focus with the pointer while focus is inside the list", async () => {
    render(ListHarness, { rows, onOpen: vi.fn() });
    const [first, , third] = screen.getAllByRole("button");
    first!.focus();
    await fireEvent.pointerMove(third!, { clientX: 10, clientY: 90 });
    await tick();
    expect(document.activeElement).toBe(third);
  });

  it("stops handling actions once unmounted", () => {
    const onOpen = vi.fn();
    render(ListHarness, { rows, onOpen });
    cleanup();
    expect(dispatch("a")).toBe(false);
    expect(onOpen).not.toHaveBeenCalled();
  });
});

describe("Row", () => {
  afterEach(cleanup);
  const children = createRawSnippet(() => ({ render: () => "<span>v3</span>" }));

  it("gets aria-current=true only when focused", () => {
    const { rerender } = render(Row, { focused: true, columns: "1fr", children });
    expect(screen.getByRole("button").getAttribute("aria-current")).toBe("true");
    rerender({ focused: false, columns: "1fr", children });
    expect(screen.getByRole("button").hasAttribute("aria-current")).toBe(false);
  });

  it("keeps the cursor on the same row (by key) when rows are inserted above it", async () => {
    const key = (r: { name: string }) => r.name;
    const { rerender } = render(ListHarness, { rows, onOpen: vi.fn(), key });
    dispatch("down");
    flushSync();
    expect(current()?.textContent).toBe("Pokémon Platinum");
    await rerender({ rows: [{ name: "Brand New" }, ...rows], onOpen: vi.fn(), key });
    await tick();
    expect(current()?.textContent).toBe("Pokémon Platinum");
  });

  it("clamps when the focused row disappears", async () => {
    const key = (r: { name: string }) => r.name;
    const { rerender } = render(ListHarness, { rows, onOpen: vi.fn(), key });
    dispatch("down");
    dispatch("down");
    flushSync();
    await rerender({ rows: rows.slice(0, 2), onOpen: vi.fn(), key });
    await tick();
    expect(current()?.textContent).toBe("Pokémon Platinum");
  });
});
