package dev.tandem.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The console colour tokens (UI spec §3). Names say what a colour means, not how it looks; every colour on screen
 * comes from here. The cursor is an inverted row, so [focusBg] is [ink] and [focusInk] is [bg].
 */
@Immutable
data class EmuColors(
    val bg: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val rule: Color,
    val rule2: Color,
    val focusBg: Color,
    val focusInk: Color,
    val wait: Color,
    val fault: Color,
    val glyph: Color,
    /** A surface raised off the background: the shelf's pulled-out card. */
    val raised: Color,
    /** The shelf's edge under the spines: ink in light; in dark a step down from ink, so it doesn't glare. */
    val shelfEdge: Color,
) {
    companion object {
        private val darkBg = Color(0xFF121212)
        private val darkInk = Color(0xFFF2F2F0)
        private val lightBg = Color(0xFFEDEDEA)
        private val lightInk = Color(0xFF141414)

        val Dark = EmuColors(
            bg = darkBg,
            ink = darkInk,
            ink2 = Color(0xFFA3A3A0),
            ink3 = Color(0xFF7E7E7B),
            rule = Color.White.copy(alpha = 0.07f),
            rule2 = Color.White.copy(alpha = 0.14f),
            focusBg = darkInk,
            focusInk = darkBg,
            wait = Color(0xFFF0A030),
            fault = Color(0xFFFF6B55),
            glyph = Color(0xFF2A2A2A),
            raised = Color(0xFF1C1C1B),
            shelfEdge = Color(0xFFA3A3A0),
        )

        val Light = EmuColors(
            bg = lightBg,
            ink = lightInk,
            ink2 = Color(0xFF55554F),
            ink3 = Color(0xFF6B6B66),
            rule = Color.Black.copy(alpha = 0.09f),
            rule2 = Color.Black.copy(alpha = 0.18f),
            focusBg = lightInk,
            focusInk = lightBg,
            wait = Color(0xFF9A5C00),
            fault = Color(0xFFC23824),
            glyph = Color(0xFFD6D6D1),
            raised = Color(0xFFF8F7F3),
            shelfEdge = lightInk,
        )
    }
}

val LocalEmu = staticCompositionLocalOf { EmuColors.Dark }
