package dev.emusync.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.emusync.app.R

/** The bundled OFL faces (res/font). */
object EmuFonts {
    val Barlow = FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_bold, FontWeight.Bold),
    )

    val BarlowSemiCondensed = FontFamily(
        Font(R.font.barlow_semi_condensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_semi_condensed_bold, FontWeight.Bold),
    )

    /** google/fonts ships JetBrains Mono only as a variable font; pin its weight axis to 400. */
    val JetBrainsMono = FontFamily(
        Font(R.font.jetbrains_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    )
}

/**
 * The app's type roles (UI spec §4, app sizes). Styles carry face, size and weight only; colour comes from
 * [LocalEmu] at the call site, following the hierarchy primary = ink, secondary = ink2, metadata = ink3.
 */
object EmuType {
    val title = TextStyle(
        fontFamily = EmuFonts.BarlowSemiCondensed, fontWeight = FontWeight.SemiBold, fontSize = 26.sp,
        letterSpacing = (-0.01).em,
    )
    val section = TextStyle(fontFamily = EmuFonts.BarlowSemiCondensed, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val tab = TextStyle(fontFamily = EmuFonts.BarlowSemiCondensed, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val rowPrimary = TextStyle(fontFamily = EmuFonts.Barlow, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val body = TextStyle(fontFamily = EmuFonts.Barlow, fontWeight = FontWeight.Normal, fontSize = 14.sp)
    val meta = TextStyle(fontFamily = EmuFonts.Barlow, fontWeight = FontWeight.Normal, fontSize = 13.sp)

    /** Column headers. Compose has no text-transform, so callers pass the text uppercased. */
    val header = TextStyle(
        fontFamily = EmuFonts.Barlow, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.02.em,
    )

    /** Versions, sizes, hashes, paths and diffs, with tabular figures. */
    val mono = TextStyle(
        fontFamily = EmuFonts.JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, fontFeatureSettings = "tnum",
    )
}
