const BY_EMULATOR: Record<string, string> = {
  eden: "SWITCH",
  azahar: "3DS",
  melonds: "DS",
  ppsspp: "PSP",
  vita3k: "VITA",
};

// Older RetroArch keys (and tests) name the system in the first segment of the game key.
const BY_SEGMENT: Record<string, string> = {
  gba: "GBA", gbc: "GBC", gb: "GB", nes: "NES", snes: "SNES", nds: "DS", ds: "DS", n64: "N64",
  gc: "GC", gamecube: "GC", wii: "WII", md: "MD", genesis: "MD",
  psp: "PSP", psx: "PSX", ps1: "PSX",
};

// RetroArch cores, by the core folder / core name a save version carries (lowercase, without "_libretro").
const BY_CORE: [RegExp, string][] = [
  [/^(mgba|vba[-_ ]?m|vbam|gpsp|meteor|beetle[-_ ]?gba|mednafen[-_ ]?gba)/, "GBA"],
  [/^(gambatte|sameboy|gearboy|tgbdual)/, "GBC"],
  [/^(melonds|desmume)/, "DS"],
  [/^(snes9x|bsnes|mesen[-_ ]?s|beetle[-_ ]?supafaust)/, "SNES"],
  [/^(fceumm|nestopia|quicknes|mesen)/, "NES"],
  [/^(mupen64|parallel[-_ ]?n64)/, "N64"],
  [/^(genesis[-_ ]?plus|picodrive|blastem)/, "MD"],
  [/^(pcsx|swanstation|duckstation|beetle[-_ ]?psx|mednafen[-_ ]?psx)/, "PSX"],
  [/^ppsspp/, "PSP"],
  [/^dolphin/, "GC"],
];

/**
 * Dolphin keys are the disc's game id: a GameCube save is the 6-character id (GALE01), a Wii save the 4-character
 * title (RMCE). The first letter says the same thing for ids of other lengths.
 */
function dolphinSystem(gameKey: string): string | null {
  const parts = gameKey.split("/");
  for (const seg of parts.slice(0, -1)) {
    const hit = BY_SEGMENT[seg.toLowerCase()];
    if (hit === "GC" || hit === "WII") return hit;
  }
  const id = parts[parts.length - 1] ?? "";
  if (/^[A-Z0-9]{6}$/i.test(id)) return "GC"; // memory-card saves (GCI) exist only for GameCube games
  if (/^[A-Z0-9]{4}$/i.test(id)) return "WII";
  const first = id[0]?.toUpperCase();
  if (first === "G" || first === "D") return "GC";
  if (first === "R" || first === "S") return "WII";
  return null;
}

function retroarchSystem(gameKey: string, core?: string | null): string | null {
  const parts = gameKey.split("/");
  // "retroarch/<name>" or "<system>/<name>": a known system segment anywhere before the name wins.
  for (const seg of parts.slice(0, -1)) {
    const hit = BY_SEGMENT[seg.toLowerCase()];
    if (hit) return hit;
  }
  const c = (core ?? "").toLowerCase().replace(/_libretro.*$/, "").trim();
  if (c) for (const [re, sys] of BY_CORE) if (re.test(c)) return sys;
  return null;
}

/**
 * The short system name for a game's system mark: SWITCH, 3DS, GBA, GC, WII. Null when it can't be told (a RetroArch
 * save whose core isn't known): the mark then shows a dash, never the emulator's name.
 */
export function systemLabel(emulatorId: string, gameKey: string, core?: string | null, system?: string | null): string | null {
  // The server's answer (which also knows what a device reported) wins over what the key and core say here.
  if (system) return system;
  const fixed = BY_EMULATOR[emulatorId];
  if (fixed) return fixed;
  if (emulatorId === "dolphin") return dolphinSystem(gameKey);
  if (emulatorId === "retroarch") return retroarchSystem(gameKey, core);
  const seg = gameKey.includes("/") ? BY_SEGMENT[gameKey.split("/")[0]!.toLowerCase()] : undefined;
  return seg ?? null;
}

/** "Pokemon - Emerald Version" for "retroarch/Pokemon - Emerald Version": the game key without a module prefix. */
export function bareKey(emulatorId: string, gameKey: string): string {
  return emulatorId === "retroarch" && gameKey.startsWith("retroarch/") ? gameKey.slice("retroarch/".length) : gameKey;
}
