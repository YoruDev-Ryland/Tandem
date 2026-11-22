import { readFileSync } from "node:fs";
import { resolve as pathResolve } from "node:path";
import { describe, expect, it } from "vitest";

const css = readFileSync(pathResolve(process.cwd(), "src/styles/tokens.css"), "utf8");

type Rgb = [number, number, number];
type Theme = Record<string, string>;

function block(selector: string): string {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) throw new Error(`no block for ${selector}`);
  return css.slice(css.indexOf("{", start) + 1, css.indexOf("}", start));
}

function parse(body: string): Theme {
  const out: Theme = {};
  for (const m of body.matchAll(/(--[\w-]+):\s*([^;]+);/g)) out[m[1]] = m[2].trim();
  return out;
}

const dark = parse(block(":root"));
const light = { ...dark, ...parse(block(':root[data-theme="light"]')) };

function resolve(theme: Theme, name: string): string {
  const v = theme[name];
  const ref = v.match(/^var\((--[\w-]+)\)$/);
  return ref ? resolve(theme, ref[1]) : v;
}

function rgba(value: string): [number, number, number, number] {
  const hex = value.match(/^#([0-9a-f]{6})$/i);
  if (hex) {
    const n = parseInt(hex[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 1];
  }
  const fn = value.match(/^rgba?\(([^)]+)\)$/);
  if (!fn) throw new Error(`cannot parse colour ${value}`);
  const p = fn[1].split(",").map(Number);
  return [p[0], p[1], p[2], p[3] ?? 1];
}

const colour = (theme: Theme, name: string): Rgb => {
  const [r, g, b] = rgba(resolve(theme, name));
  return [r, g, b];
};

const mix = (a: Rgb, b: Rgb, pa: number): Rgb => a.map((v, i) => v * pa + b[i] * (1 - pa)) as Rgb;

function luminance(c: Rgb): number {
  const [r, g, b] = c.map((v) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: Rgb, b: Rgb): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

function tint(theme: Theme, name: string): Rgb {
  const [r, g, b, a] = rgba(resolve(theme, name));
  return mix([r, g, b], colour(theme, "--bg"), a);
}

describe.each([
  ["dark", dark],
  ["light", light],
])("%s theme contrast (WCAG AA 4.5:1)", (_name, theme) => {
  it.each(["--ink", "--ink2", "--ink3", "--wait", "--fault"])("%s on --bg", (token) => {
    expect(contrast(colour(theme, token), colour(theme, "--bg"))).toBeGreaterThanOrEqual(4.5);
  });

  // The card keeps --ink3 for marks only (3:1 for graphics); its words are --ink2 at least.
  it.each(["--ink", "--ink2", "--wait", "--fault"])("%s on --raised (the shelf card)", (token) => {
    expect(contrast(colour(theme, token), colour(theme, "--raised"))).toBeGreaterThanOrEqual(4.5);
  });

  it("--focus-ink on --focus-bg", () => {
    expect(contrast(colour(theme, "--focus-ink"), colour(theme, "--focus-bg"))).toBeGreaterThanOrEqual(4.5);
  });

  it("secondary text on the cursor row (--focus-ink mixed 62% toward --focus-bg)", () => {
    const fbg = colour(theme, "--focus-bg");
    const secondary = mix(colour(theme, "--focus-ink"), fbg, 0.62);
    expect(contrast(secondary, fbg)).toBeGreaterThanOrEqual(4.5);
  });

  it.each([
    ["--diff-add-ink", "--diff-add"],
    ["--diff-del-ink", "--diff-del"],
  ])("%s on its %s tint", (ink, bg) => {
    expect(contrast(colour(theme, ink), tint(theme, bg))).toBeGreaterThanOrEqual(4.5);
  });

  it("keeps --ink3 dimmer than --ink2", () => {
    const bg = colour(theme, "--bg");
    expect(contrast(colour(theme, "--ink3"), bg)).toBeLessThan(contrast(colour(theme, "--ink2"), bg));
  });
});

describe("light theme via the system preference", () => {
  it("repeats the data-theme light values", () => {
    const media = css.slice(css.indexOf("@media (prefers-color-scheme: light)"));
    const open = media.indexOf("{", media.indexOf(":root:not"));
    const sys = parse(media.slice(open + 1, media.indexOf("}", open)));
    expect(Object.keys(sys).length).toBeGreaterThan(10);
    for (const [k, v] of Object.entries(sys)) expect(light[k]).toBe(v);
  });
});
