import { emulatorName } from "./emulators";

const BY_EMULATOR: Record<string, string> = {
  eden: "SWITCH",
  azahar: "3DS",
  melonds: "DS",
  ppsspp: "PSP",
  vita3k: "VITA",
};

// Multi-system emulators (RetroArch, Dolphin) name the system in the first segment of the game key.
const BY_SEGMENT: Record<string, string> = {
  gba: "GBA", gbc: "GBC", gb: "GB", nes: "NES", snes: "SNES", nds: "DS", ds: "DS",
  gc: "GC", gamecube: "GC", wii: "WII",
  psp: "PSP", psx: "PSX",
};

/** The short system name shown in the SYSTEM column; unknown systems show the emulator's name. */
export function systemLabel(emulatorId: string, gameKey: string): string {
  const fixed = BY_EMULATOR[emulatorId];
  if (fixed) return fixed;
  const seg = gameKey.split("/")[0]!.toLowerCase();
  return (gameKey.includes("/") ? BY_SEGMENT[seg] : undefined) ?? emulatorName(emulatorId);
}
