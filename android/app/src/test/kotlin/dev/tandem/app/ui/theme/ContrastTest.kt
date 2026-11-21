package dev.tandem.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG AA (4.5:1) for every text token in both themes, mirroring web/test/tokens.test.ts. */
class ContrastTest {
    private val themes = listOf("Dark" to EmuColors.Dark, "Light" to EmuColors.Light)

    private fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Opaque mix: [pa] of [a], the rest [b] (CSS color-mix(in srgb, a pa, b)). */
    private fun mix(a: Color, b: Color, pa: Float) = Color(
        red = a.red * pa + b.red * (1 - pa),
        green = a.green * pa + b.green * (1 - pa),
        blue = a.blue * pa + b.blue * (1 - pa),
    )

    private fun assertAa(label: String, fg: Color, bg: Color) {
        val ratio = contrast(fg, bg)
        assertTrue("$label is %.2f:1, below 4.5:1".format(ratio), ratio >= 4.5)
    }

    @Test fun textTokensOnBgReachAa() {
        for ((name, c) in themes) {
            assertAa("$name ink", c.ink, c.bg)
            assertAa("$name ink2", c.ink2, c.bg)
            assertAa("$name ink3", c.ink3, c.bg)
            assertAa("$name wait", c.wait, c.bg)
            assertAa("$name fault", c.fault, c.bg)
        }
    }

    @Test fun focusInkOnFocusBgReachesAa() {
        for ((name, c) in themes) assertAa("$name focusInk", c.focusInk, c.focusBg)
    }

    @Test fun secondaryTextOnCursorRowReachesAa() {
        for ((name, c) in themes) assertAa("$name focus secondary", mix(c.focusInk, c.focusBg, 0.62f), c.focusBg)
    }

    @Test fun tokensHoldTheSpecValues() {
        val d = EmuColors.Dark
        assertEquals(Color(0xFF121212), d.bg)
        assertEquals(Color(0xFFF2F2F0), d.ink)
        assertEquals(Color(0xFFA3A3A0), d.ink2)
        assertEquals(Color(0xFF7E7E7B), d.ink3)
        assertEquals(Color(0xFFF0A030), d.wait)
        assertEquals(Color(0xFFFF6B55), d.fault)
        assertEquals(Color(0xFF2A2A2A), d.glyph)
        assertEquals(Color.White.copy(alpha = 0.07f), d.rule)
        assertEquals(Color.White.copy(alpha = 0.14f), d.rule2)
        val l = EmuColors.Light
        assertEquals(Color(0xFFEDEDEA), l.bg)
        assertEquals(Color(0xFF141414), l.ink)
        assertEquals(Color(0xFF55554F), l.ink2)
        assertEquals(Color(0xFF6B6B66), l.ink3)
        assertEquals(Color(0xFF9A5C00), l.wait)
        assertEquals(Color(0xFFC23824), l.fault)
        assertEquals(Color(0xFFD6D6D1), l.glyph)
        assertEquals(Color.Black.copy(alpha = 0.09f), l.rule)
        assertEquals(Color.Black.copy(alpha = 0.18f), l.rule2)
        for (c in listOf(d, l)) {
            assertEquals(c.ink, c.focusBg)
            assertEquals(c.bg, c.focusInk)
        }
    }
}
