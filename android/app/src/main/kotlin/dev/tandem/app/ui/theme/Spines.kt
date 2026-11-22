package dev.tandem.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The shelf's spine colours: one per system, the system's identity, and the same in both themes (the web's spines.ts
 * holds the same values). The text on each meets WCAG AA (4.5:1). No spine is purple or close to wait-amber or
 * problem-red, which keep their meanings. A system the palette doesn't know (MD, or none) gets the neutral spine.
 * The keys are the labels Systems.kt derives; its PSX is the PS1 spine.
 */
object Spines {
    private val LIGHT_TEXT = Color(0xFFF4F2EA)
    private val DARK_TEXT = Color(0xFF2A2925)
    private val DARKEST_TEXT = Color(0xFF1B1A17)

    private val palette: Map<String, Pair<Color, Color>> = mapOf(
        "GC" to (Color(0xFF34343A) to LIGHT_TEXT),
        "WII" to (Color(0xFFE4E2DA) to DARK_TEXT),
        "GB" to (Color(0xFF5E6850) to LIGHT_TEXT),
        "GBC" to (Color(0xFF2B5F80) to LIGHT_TEXT),
        "GBA" to (Color(0xFF4C5FA0) to LIGHT_TEXT),
        "DS" to (Color(0xFFD2CEC2) to DARK_TEXT),
        "3DS" to (Color(0xFF3C6E68) to LIGHT_TEXT),
        "SWITCH" to (Color(0xFF44484E) to LIGHT_TEXT),
        "NES" to (Color(0xFFA9A395) to DARKEST_TEXT),
        "SNES" to (Color(0xFF5B5F7A) to LIGHT_TEXT),
        "N64" to (Color(0xFF2F5E4A) to LIGHT_TEXT),
        "PS1" to (Color(0xFF9A9893) to DARKEST_TEXT),
        "PSP" to (Color(0xFF202226) to LIGHT_TEXT),
        "VITA" to (Color(0xFF2D4A6B) to LIGHT_TEXT),
    )
    private val UNKNOWN = Color(0xFF6B675E) to LIGHT_TEXT

    /** Systems.kt's labels that go by another name here. */
    private val ALIASES = mapOf("PSX" to "PS1")

    /** Every system with a spine of its own. */
    val systems: List<String> get() = palette.keys.toList()

    private fun entry(system: String?): Pair<Color, Color> {
        val s = system?.uppercase() ?: return UNKNOWN
        return palette[ALIASES[s] ?: s] ?: UNKNOWN
    }

    /** The spine's fill for [system] (a Systems.kt label; null when unknown). */
    fun colour(system: String?): Color = entry(system).first

    /** The text on that spine. */
    fun ink(system: String?): Color = entry(system).second

    /** Whether the spine is a light one (dark text): its system code is drawn in a muted dark, not a muted light. */
    fun isLight(system: String?): Boolean = entry(system).second != LIGHT_TEXT
}
