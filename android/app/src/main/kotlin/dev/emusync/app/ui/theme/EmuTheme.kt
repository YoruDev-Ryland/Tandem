package dev.emusync.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

enum class ThemeMode { System, Light, Dark }

/**
 * Provides [EmuColors] through [LocalEmu]. Material 3 is plumbing only: its colour scheme and typography are
 * mapped from the console tokens so any Material internals stay neutral, but screens draw with ui/kit, not Material.
 */
@Composable
fun EmuTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (dark) EmuColors.Dark else EmuColors.Light
    CompositionLocalProvider(
        LocalEmu provides colors,
        LocalTextSelectionColors provides TextSelectionColors(handleColor = colors.ink, backgroundColor = colors.rule2),
    ) {
        MaterialTheme(colorScheme = colors.toMaterial(dark), typography = typography, content = content)
    }
}

private val typography = Typography(
    bodyLarge = EmuType.body,
    bodyMedium = EmuType.body,
    bodySmall = EmuType.meta,
    labelLarge = EmuType.body,
    titleLarge = EmuType.title,
)

private fun EmuColors.toMaterial(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = ink, onPrimary = bg, secondary = ink2, onSecondary = bg, tertiary = ink2, onTertiary = bg,
        background = bg, onBackground = ink, surface = bg, onSurface = ink, surfaceVariant = bg, onSurfaceVariant = ink2,
        surfaceTint = bg, outline = rule2, outlineVariant = rule, error = fault, onError = bg,
    )
}
