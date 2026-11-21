package dev.tandem.app.ui.state

/*
 * The short system name on a game's system mark (SWITCH, 3DS, GBA, GC, WII), the same logic as the web's
 * web/src/lib/systems.ts: fixed for single-system emulators, from the game id for Dolphin, from the key or the core for
 * RetroArch. Never the emulator's name: a game whose system can't be told gets no name, and the mark shows a dash.
 */

private val BY_EMULATOR = mapOf(
    "eden" to "SWITCH",
    "azahar" to "3DS",
    "melonds" to "DS",
    "ppsspp" to "PSP",
    "vita3k" to "VITA",
)

/** Older RetroArch keys name the system in a path segment of the game key. */
private val BY_SEGMENT = mapOf(
    "gba" to "GBA", "gbc" to "GBC", "gb" to "GB", "nes" to "NES", "snes" to "SNES", "nds" to "DS", "ds" to "DS",
    "n64" to "N64", "gc" to "GC", "gamecube" to "GC", "wii" to "WII", "md" to "MD", "genesis" to "MD",
    "psp" to "PSP", "psx" to "PSX", "ps1" to "PSX",
)

/** RetroArch cores, by the core name a save carries (lowercase, without "_libretro"). */
private val BY_CORE = listOf(
    Regex("^(mgba|vba[-_ ]?m|vbam|gpsp|meteor|beetle[-_ ]?gba|mednafen[-_ ]?gba)") to "GBA",
    Regex("^(gambatte|sameboy|gearboy|tgbdual)") to "GBC",
    Regex("^(melonds|desmume)") to "DS",
    Regex("^(snes9x|bsnes|mesen[-_ ]?s|beetle[-_ ]?supafaust)") to "SNES",
    Regex("^(fceumm|nestopia|quicknes|mesen)") to "NES",
    Regex("^(mupen64|parallel[-_ ]?n64)") to "N64",
    Regex("^(genesis[-_ ]?plus|picodrive|blastem)") to "MD",
    Regex("^(pcsx|swanstation|duckstation|beetle[-_ ]?psx|mednafen[-_ ]?psx)") to "PSX",
    Regex("^ppsspp") to "PSP",
    Regex("^dolphin") to "GC",
)

private val SIX = Regex("^[A-Z0-9]{6}$", RegexOption.IGNORE_CASE)
private val FOUR = Regex("^[A-Z0-9]{4}$", RegexOption.IGNORE_CASE)

/**
 * Dolphin keys are the disc's game id: a GameCube save is the 6-character id (GALE01), a Wii save the 4-character
 * title (RMCE). The first letter says the same for ids of other lengths (G and D GameCube, R and S Wii).
 */
private fun dolphinSystem(gameKey: String): String? {
    val parts = gameKey.split('/')
    for (seg in parts.dropLast(1)) BY_SEGMENT[seg.lowercase()]?.takeIf { it == "GC" || it == "WII" }?.let { return it }
    val id = parts.last()
    if (SIX.matches(id)) return "GC"   // memory-card saves (GCI) exist only for GameCube games
    if (FOUR.matches(id)) return "WII"
    return when (id.firstOrNull()?.uppercaseChar()) {
        'G', 'D' -> "GC"
        'R', 'S' -> "WII"
        else -> null
    }
}

private fun retroarchSystem(gameKey: String, core: String?): String? {
    // "retroarch/<name>" or "<system>/<name>": a known system segment before the name wins.
    for (seg in gameKey.split('/').dropLast(1)) BY_SEGMENT[seg.lowercase()]?.let { return it }
    val c = core.orEmpty().lowercase().replace(Regex("_libretro.*$"), "").trim()
    if (c.isNotEmpty()) for ((re, sys) in BY_CORE) if (re.containsMatchIn(c)) return sys
    return null
}

/** The system mark's text for a game, or null when it can't be told (the mark is then a dash). */
fun systemLabel(emulatorId: String, gameKey: String, core: String? = null): String? {
    BY_EMULATOR[emulatorId]?.let { return it }
    return when (emulatorId) {
        "dolphin" -> dolphinSystem(gameKey)
        "retroarch" -> retroarchSystem(gameKey, core)
        else -> if ('/' in gameKey) BY_SEGMENT[gameKey.substringBefore('/').lowercase()] else null
    }
}

/** "Pokemon - Emerald Version" for "retroarch/Pokemon - Emerald Version": a game key without the module's prefix. */
fun bareKey(emulatorId: String, gameKey: String): String =
    if (emulatorId == "retroarch" && gameKey.startsWith("retroarch/")) gameKey.removePrefix("retroarch/") else gameKey
