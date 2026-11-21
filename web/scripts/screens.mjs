// Captures every route at desktop and phone width, both themes.
// Usage: TANDEM_EMAIL=... TANDEM_PASSWORD=... node web/scripts/screens.mjs http://localhost:5100 [outDir]
import { chromium } from "playwright-core";
import { homedir } from "node:os";
import { mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const base = (process.argv[2] ?? "http://localhost:5100").replace(/\/$/, "");
const here = dirname(fileURLToPath(import.meta.url));
const out = process.argv[3] ?? join(here, "../../design/screens/web");
const email = process.env.TANDEM_EMAIL;
const password = process.env.TANDEM_PASSWORD;
if (!email || !password) throw new Error("Set TANDEM_EMAIL and TANDEM_PASSWORD.");
const executablePath = process.env.CHROMIUM ?? join(homedir(), ".cache/ms-playwright/chromium-1234/chrome-linux64/chrome");
mkdirSync(out, { recursive: true });

const headers = { "Tandem-Protocol": "1" };
const browser = await chromium.launch({ executablePath, args: ["--no-sandbox"] });

// Sign in once through the API, then reuse the session cookie.
const bootstrap = await browser.newContext({ baseURL: base });
const signIn = await bootstrap.request.post("/v1/web/session", { headers, data: { email, password } });
if (!signIn.ok()) throw new Error(`Sign-in failed: ${signIn.status()}`);
const state = await bootstrap.storageState();
const getJson = async (p) => (await bootstrap.request.get(p, { headers })).json();

// Real data picks the dynamic routes.
const games = await getJson("/v1/web/games");
const devices = await getJson("/v1/devices");
const conflicts = await getJson("/v1/web/conflicts");
const configs = await getJson("/v1/web/configs");
const gameUrl = (g) => `/games/${encodeURIComponent(g.emulatorId)}/${g.gameKey.split("/").map(encodeURIComponent).join("/")}`;
const game = games.find((g) => !g.openConflict && g.newestUtc) ?? games[0];
// A config bundle with an open conflict, if there is one.
const groups = (await getJson("/v1/configs/groups")).items ?? [];
const configConflict = (configs.emulators ?? []).flatMap((e) => [
  ...e.groups.filter((g) => g.openConflict).map((g) => ({ emu: e.emulatorId, bundle: groups.find((s) => s.id === g.id)?.bundleId })),
  ...e.solo.filter((s) => s.openConflict).map((s) => ({ emu: e.emulatorId, bundle: s.bundleId })),
]).find((c) => c.bundle);

const routes = [
  { name: "home", path: "/", wait: "h1, nav[aria-label=Sections]" },
  { name: "games", path: "/games", wait: "h1, nav[aria-label=Sections]" },
  game && { name: "game", path: gameUrl(game), wait: "h1, nav[aria-label=Sections]" },
  conflicts[0] && { name: "conflict", path: `${gameUrl(conflicts[0])}/conflict`, wait: "h1, nav[aria-label=Sections]" },
  { name: "conflicts", path: "/conflicts", wait: "h1, nav[aria-label=Sections]" },
  { name: "devices", path: "/devices", wait: "h1, nav[aria-label=Sections]" },
  devices[0] && { name: "device", path: `/devices/${devices[0].id}`, wait: "h1, nav[aria-label=Sections]" },
  devices[0] && { name: "device-setup", path: `/devices/${devices[0].id}/setup`, wait: "h1, nav[aria-label=Sections]" },
  { name: "device-pair", path: "/devices/pair", wait: "h1, nav[aria-label=Sections]" },
  { name: "configs", path: "/configs", wait: "h1, nav[aria-label=Sections]" },
  configs.emulators?.[0] && { name: "config-emulator", path: `/configs/${encodeURIComponent(configs.emulators[0].emulatorId)}`, wait: "h1, nav[aria-label=Sections]" },
  configConflict && { name: "config-conflict", path: `/configs/${encodeURIComponent(configConflict.emu)}/conflict/${encodeURIComponent(configConflict.bundle)}`, wait: "h1, nav[aria-label=Sections]" },
  { name: "vault", path: "/vault", wait: "h1, nav[aria-label=Sections]" },
  { name: "activity", path: "/activity", wait: "h1, nav[aria-label=Sections]" },
  { name: "settings", path: "/settings", wait: "h1, nav[aria-label=Sections]" },
].filter(Boolean);

const sizes = { desktop: { width: 1440, height: 900 }, phone: { width: 390, height: 844 } };
for (const theme of ["light", "dark"]) {
  for (const [size, viewport] of Object.entries(sizes)) {
    const ctx = await browser.newContext({ baseURL: base, viewport, storageState: state, reducedMotion: "reduce" });
    await ctx.addInitScript((t) => localStorage.setItem("tandem.theme", t), theme);
    const page = await ctx.newPage();
    for (const r of routes) {
      await page.goto(r.path);
      await page.waitForSelector(r.wait, { timeout: 15000 }).catch(async (e) => { await page.screenshot({ path: join(out, `FAILED-${r.name}.png`) }); throw new Error(`${r.name}: ${e.message}`); });
      // The event stream never goes idle; wait for the data placeholders to clear instead.
      await page.waitForFunction(() => !document.querySelector('[aria-busy="true"]')).catch(() => {});
      await page.waitForTimeout(600);
      // Phone shots are full height, capped so a 138-row list stays viewable.
      const height = size === "phone" ? Math.min(await page.evaluate(() => document.documentElement.scrollHeight), 2000) : viewport.height;
      await page.screenshot({ path: join(out, `${r.name}-${size}-${theme}.png`), clip: { x: 0, y: 0, width: viewport.width, height }, fullPage: size === "phone" });
      console.log(`${r.name} ${size} ${theme}`);
    }
    await ctx.close();
  }
}
// Sign-in page is captured signed out.
for (const theme of ["light", "dark"]) {
  for (const [size, viewport] of Object.entries(sizes)) {
    const ctx = await browser.newContext({ baseURL: base, viewport, reducedMotion: "reduce" });
    await ctx.addInitScript((t) => localStorage.setItem("tandem.theme", t), theme);
    const page = await ctx.newPage();
    await page.goto("/sign-in");
    await page.waitForSelector("h1");
    await page.waitForTimeout(300);
    await page.screenshot({ path: join(out, `sign-in-${size}-${theme}.png`) });
    await ctx.close();
  }
}
await browser.close();
