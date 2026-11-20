import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/svelte";
import Diff from "../../src/lib/ui/Diff.svelte";
import type { DiffLine, FileDiff } from "../../src/lib/api/types";

const same = (n: number, from: number): DiffLine[] =>
  Array.from({ length: n }, (_, i) => ({ op: " ", text: `line ${from + i}` }));

const file: FileDiff = {
  path: "retroarch.cfg",
  status: "Changed",
  hunks: [{
    oldStart: 1,
    newStart: 1,
    lines: [
      { op: "-", text: 'video_shader_enable = "false"' },
      { op: "+", text: 'video_shader_enable = "true"' },
      ...same(10, 2),
      { op: "-", text: 'input_menu_toggle_gamepad_combo = "2"' },
      { op: "+", text: 'input_menu_toggle_gamepad_combo = "4"' },
    ],
  }],
};

describe("Diff", () => {
  afterEach(cleanup);

  it("collapses a 10-line unchanged run, keeping one line of context each side", () => {
    render(Diff, { files: [file], selected: "retroarch.cfg" });
    expect(screen.getByText("· · · 8 unchanged lines")).toBeTruthy();
    expect(screen.getByText("line 2")).toBeTruthy();
    expect(screen.getByText("line 11")).toBeTruthy();
    expect(screen.queryByText("line 3")).toBeNull();
    expect(screen.queryByText("line 10")).toBeNull();
  });

  it("keeps short unchanged runs", () => {
    const short: FileDiff = { ...file, hunks: [{ oldStart: 1, newStart: 1, lines: [...same(3, 1), { op: "+", text: "x" }] }] };
    render(Diff, { files: [short], selected: "retroarch.cfg" });
    expect(screen.queryByText(/unchanged lines/)).toBeNull();
    expect(screen.getByText("line 1")).toBeTruthy();
  });
});
