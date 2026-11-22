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
