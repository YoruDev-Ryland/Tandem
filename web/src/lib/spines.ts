// Spine colours: per-system identity on the Games shelf, the same values as the app's Spines.kt and the same in both
// themes. [spine, text]; every text colour meets WCAG AA on its spine, and none sits near wait-amber, problem-red or
// purple (test/shelf.test.ts pins all three).
export const SPINES: Record<string, readonly [string, string]> = {
  GC: ["#34343A", "#F4F2EA"],
  WII: ["#E4E2DA", "#2A2925"],
  GB: ["#5E6850", "#F4F2EA"],
  GBC: ["#2B5F80", "#F4F2EA"],
  GBA: ["#4C5FA0", "#F4F2EA"],
  DS: ["#D2CEC2", "#2A2925"],
  "3DS": ["#3C6E68", "#F4F2EA"],
  SWITCH: ["#44484E", "#F4F2EA"],
  NES: ["#A9A395", "#1B1A17"],
  SNES: ["#5B5F7A", "#F4F2EA"],
  N64: ["#2F5E4A", "#F4F2EA"],
  PS1: ["#9A9893", "#1B1A17"],
  PSP: ["#202226", "#F4F2EA"],
  VITA: ["#2D4A6B", "#F4F2EA"],
  unknown: ["#6B675E", "#F4F2EA"],
};

// systemLabel() says PSX where the palette says PS1.
const ALIAS: Record<string, string> = { PSX: "PS1" };

/** The palette key for a systemLabel() result; systems without a spine of their own get the neutral one. */
export function spineKey(system: string | null): string {
  if (!system) return "unknown";
  const k = ALIAS[system] ?? system;
  return k in SPINES ? k : "unknown";
}

export const spineColour = (system: string | null): string => SPINES[spineKey(system)]![0];
export const spineInk = (system: string | null): string => SPINES[spineKey(system)]![1];

/** A spine's width as a fraction of its height: the real case's proportions (the app's Spines.ratio). */
export function spineRatio(system: string | null): number {
  switch (system?.toUpperCase()) {
    case "GC":
    case "WII":
      return 0.074;
    case "SWITCH":
      return 0.065;
    case "PSP":
      return 0.078;
    default:
      return 0.1;
  }
}

export interface Band {
  colour: string;
  name: string;
  ink: string;
  /** The band's share of the spine's height. */
  height: number;
}

const CREAM = "#F4F2EA";
const INK = "#2A2925";
const BLACK = "#1B1A17";
export const SWITCH_RED = "#E60012";
const band = (colour: string, name: string, ink: string, height = 0.27): Band => ({ colour, name, ink, height });
const BANDS: Record<string, Band> = {
  GBA: band(CREAM, "GAME BOY ADVANCE", INK),
  GBC: band(CREAM, "GAME BOY COLOR", INK),
  GB: band(CREAM, "GAME BOY", INK),
  NES: band(BLACK, "NES", CREAM),
  SNES: band("#8E8C92", "SUPER NINTENDO", CREAM),
  N64: band(BLACK, "NINTENDO 64", CREAM),
  MD: band(BLACK, "GENESIS", CREAM),
  PSX: band(BLACK, "PLAYSTATION", CREAM),
  PSP: band("#141414", "PSP", CREAM),
  VITA: band("#2D4A6B", "PS VITA", CREAM),
  GC: band(BLACK, "GAMECUBE", CREAM),
  WII: band("#E4E2DA", "Wii", INK),
  DS: band(CREAM, "NINTENDO DS", INK),
  "3DS": band(CREAM, "NINTENDO 3DS", INK),
  SWITCH: band(SWITCH_RED, "NINTENDO SWITCH", "#FFFFFF", 0.2),
};

/** The console band at the top of a built, plain or fallback spine; null for an unknown system. */
export function spineBand(system: string | null): Band | null {
  const s = system?.toUpperCase();
  if (!s) return null;
  return BANDS[s === "PS1" ? "PSX" : s] ?? null;
}

export type SpineKind = "real" | "plain" | "built" | "fallback";

/** The first that applies: SWITCH is plain; a real spine image is real; a cover is built; else fallback. */
export function spineKind(system: string | null, hasSpine: boolean, spineImage: boolean, cover: boolean): SpineKind {
  if (system?.toUpperCase() === "SWITCH") return "plain";
  if (hasSpine && spineImage) return "real";
  if (cover) return "built";
  return "fallback";
}
