package dev.tandem.app.modules.retroarch

import dev.tandem.app.ui.state.systemLabel

/**
 * A RetroArch game's system label (GBA, SNES, …, the labels Systems.kt and the server use), from what RetroArch's
 * playlists and the game's file say. Null when nothing tells.
 */
object RetroArchSystems {
    /** libretro database names, which RetroArch uses for system playlists ("Nintendo - Game Boy Advance.lpl") and db_name. */
    private val byDb = mapOf(
        "nintendo - game boy advance" to "GBA",
        "nintendo - game boy color" to "GBC",
        "nintendo - game boy" to "GB",
        "nintendo - nintendo entertainment system" to "NES",
        "nintendo - family computer disk system" to "NES",
        "nintendo - super nintendo entertainment system" to "SNES",
        "nintendo - nintendo 64" to "N64",
        "nintendo - nintendo ds" to "DS",
        "nintendo - gamecube" to "GC",
        "nintendo - wii" to "WII",
        "sega - mega drive - genesis" to "MD",
        "sony - playstation" to "PSX",
        "sony - playstation portable" to "PSP",
    )

    /** Extensions that name one system. Disc images (cue, chd, iso, pbp) and archives don't. */
    private val byExtension = mapOf(
        "gba" to "GBA", "gbc" to "GBC", "gb" to "GB", "nes" to "NES", "fds" to "NES", "sfc" to "SNES", "smc" to "SNES",
        "n64" to "N64", "z64" to "N64", "v64" to "N64", "nds" to "DS", "md" to "MD", "gen" to "MD", "smd" to "MD",
    )

    /** "Nintendo - Game Boy Advance" or "Nintendo - Game Boy Advance.lpl". */
    fun fromDb(name: String?): String? {
        val n = name?.trim()?.removeSuffix(".lpl")?.removeSuffix(".rdb")?.trim()?.lowercase() ?: return null
        return byDb[n]
    }

    /** "Nintendo - Game Boy Advance (mGBA)": the database part, else the core in brackets. */
    fun fromCoreName(coreName: String?): String? {
        val c = coreName?.trim()?.takeIf { it.isNotEmpty() && it != "DETECT" } ?: return null
        val open = c.lastIndexOf('(')
        if (open > 0) fromDb(c.substring(0, open))?.let { return it }
        val core = if (open >= 0) c.substring(open + 1).substringBefore(')') else c
        return systemLabel("retroarch", "retroarch/x", core)
    }

    /** "Golden Sun (USA).gba" → GBA. */
    fun fromExtension(fileName: String): String? = byExtension[fileName.substringAfterLast('.', "").lowercase()]
}
