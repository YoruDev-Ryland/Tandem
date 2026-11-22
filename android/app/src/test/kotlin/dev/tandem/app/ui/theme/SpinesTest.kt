package dev.tandem.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class SpinesTest {
    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (x, y) = lum(a) to lum(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    /** Hue in degrees and saturation, as HSL has them. */
    private fun hsl(c: Color): Pair<Double, Double> {
        val r = c.red.toDouble(); val g = c.green.toDouble(); val b = c.blue.toDouble()
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val l = (mx + mn) / 2
        if (d == 0.0) return 0.0 to 0.0
        val s = d / (1 - abs(2 * l - 1))
        val h = when (mx) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        return (if (h < 0) h + 360 else h) to s
    }

    private fun near(a: Double, b: Double, by: Double): Boolean {
        val d = abs(a - b) % 360
        return min(d, 360 - d) <= by
    }

    private val systems = Spines.systems + listOf<String?>(null, "MD", "SOMETHING")

    @Test fun every_spine_text_meets_aa() {
        for (s in systems) {
            val ratio = contrast(Spines.colour(s), Spines.ink(s))
            assertTrue("$s: $ratio", ratio >= 4.5)
        }
    }

    @Test fun no_spine_near_amber_red_or_purple() {
        val avoid = listOf(EmuColors.Dark.wait, EmuColors.Light.wait, EmuColors.Dark.fault, EmuColors.Light.fault).map { hsl(it).first }
        for (s in systems) {
            val (h, sat) = hsl(Spines.colour(s))
            if (sat <= 0.25) continue
            for (a in avoid) assertTrue("$s hue $h is within 20° of $a", !near(h, a, 20.0))
            assertTrue("$s hue $h is purple", h < 260 || h > 300)
        }
    }

    @Test fun the_palette_is_the_shared_one() {
        // The web's spines.ts holds the same values; the controller keeps the two in step.
        val expected = mapOf(
            "GC" to 0xFF34343A, "WII" to 0xFFE4E2DA, "GB" to 0xFF5E6850, "GBC" to 0xFF2B5F80, "GBA" to 0xFF4C5FA0,
            "DS" to 0xFFD2CEC2, "3DS" to 0xFF3C6E68, "SWITCH" to 0xFF44484E, "NES" to 0xFFA9A395, "SNES" to 0xFF5B5F7A,
            "N64" to 0xFF2F5E4A, "PS1" to 0xFF9A9893, "PSP" to 0xFF202226, "VITA" to 0xFF2D4A6B,
        )
        for ((s, v) in expected) assertEquals(s, Color(v), Spines.colour(s))
        assertEquals(Color(0xFF6B675E), Spines.colour(null))
        assertEquals(Color(0xFF6B675E), Spines.colour("MD"))
        assertEquals(Color(0xFF2A2925), Spines.ink("DS"))
        assertEquals(Color(0xFF1B1A17), Spines.ink("NES"))
        assertEquals(Color(0xFFF4F2EA), Spines.ink("GC"))
        // The app's system labels map onto the palette: PSX is the PS1's spine.
        assertEquals(Spines.colour("PS1"), Spines.colour("PSX"))
    }
}
