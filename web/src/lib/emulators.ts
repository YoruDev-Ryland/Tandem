const NAMES: Record<string, string> = {
  eden: "Eden",
  retroarch: "RetroArch",
  azahar: "Azahar",
  ppsspp: "PPSSPP",
  vita3k: "Vita3K",
  dolphin: "Dolphin",
  melonds: "melonDS",
};

/** Display name for an emulator id; unknown ids show as they are. */
export function emulatorName(id: string): string {
  return NAMES[id] ?? id;
}
