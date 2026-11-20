import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import { createRawSnippet, flushSync } from "svelte";
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

  it("follows the pointer and opens on a click of the focused row", async () => {
    const onOpen = vi.fn();
    render(ListHarness, { rows, onOpen });
    const third = screen.getByText("Xenoblade Chronicles X").closest("button")!;
    await fireEvent.mouseEnter(third);
    expect(current()).toBe(third);
    await fireEvent.click(third);
    expect(onOpen).toHaveBeenCalledWith(rows[2]);
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
});
