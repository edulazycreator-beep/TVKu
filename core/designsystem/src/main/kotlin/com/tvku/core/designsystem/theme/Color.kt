package com.tvku.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Raw palette. Use [TvkuColors] tokens in UI code, not these values. */
internal object TvkuPalette {
    val Ink950 = Color(0xFF0B0D12)
    val Ink900 = Color(0xFF151922)
    val Ink850 = Color(0xFF1C212C)
    val Ink800 = Color(0xFF242A37)
    val Ink700 = Color(0xFF2E3544)
    val Ember500 = Color(0xFFFF6B35)
    val Ember400 = Color(0xFFFF8A5E)
    val Ember600 = Color(0xFFE85A26)
    val White = Color(0xFFFFFFFF)
    val Mist400 = Color(0xFFA5ADBA)
    val Mist600 = Color(0xFF838C9C)
    val Green500 = Color(0xFF32C878)
    val Amber500 = Color(0xFFF2B33D)
    val Red400 = Color(0xFFEF5350)
}

/**
 * Semantic colour tokens. Measured WCAG 2.1 contrast against [background]:
 * textPrimary 19.4:1, textSecondary 8.6:1, textTertiary 5.7:1, accent 6.9:1,
 * error 5.6:1; [onAccent] on accent 6.9:1. All text tokens pass AA (4.5:1) on
 * background, surface and surfaceElevated. White text is never placed on the
 * accent colour (2.8:1, fails AA).
 */
@Immutable
data class TvkuColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceHighlight: Color,
    val outline: Color,
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val scrim: Color,
)

internal val DarkTvkuColors = TvkuColors(
    background = TvkuPalette.Ink950,
    surface = TvkuPalette.Ink900,
    surfaceElevated = TvkuPalette.Ink850,
    surfaceHighlight = TvkuPalette.Ink800,
    outline = TvkuPalette.Ink700,
    accent = TvkuPalette.Ember500,
    accentPressed = TvkuPalette.Ember600,
    onAccent = TvkuPalette.Ink950,
    textPrimary = TvkuPalette.White,
    textSecondary = TvkuPalette.Mist400,
    textTertiary = TvkuPalette.Mist600,
    success = TvkuPalette.Green500,
    warning = TvkuPalette.Amber500,
    error = TvkuPalette.Red400,
    scrim = Color(0xCC0B0D12),
)
