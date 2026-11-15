package com.tvku.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalTvkuColors = staticCompositionLocalOf { DarkTvkuColors }
private val LocalTvkuSpacing = staticCompositionLocalOf { TvkuSpacing() }
private val LocalTvkuShapes = staticCompositionLocalOf { TvkuShapes() }
private val LocalTvkuMotion = staticCompositionLocalOf { TvkuMotion() }

/**
 * TVKu theme. Dark-first: the product is a television app and is designed
 * around a dark canvas. Material 3 components receive a matching scheme so
 * stock components look consistent with TVKu components.
 */
@Composable
fun TvkuTheme(content: @Composable () -> Unit) {
    val colors = DarkTvkuColors
    val shapes = TvkuShapes()
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        secondary = colors.textSecondary,
        background = colors.background,
        onBackground = colors.textPrimary,
        surface = colors.surface,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceElevated,
        onSurfaceVariant = colors.textSecondary,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surfaceElevated,
        surfaceContainerHighest = colors.surfaceHighlight,
        outline = colors.outline,
        outlineVariant = colors.outline,
        error = colors.error,
        scrim = colors.scrim,
    )
    CompositionLocalProvider(
        LocalTvkuColors provides colors,
        LocalTvkuSpacing provides TvkuSpacing(),
        LocalTvkuShapes provides shapes,
        LocalTvkuMotion provides TvkuMotion(),
    ) {
        MaterialTheme(colorScheme = scheme, typography = TvkuTypography, shapes = shapes.toMaterial(), content = content)
    }
}

/** Access TVKu tokens: `TvkuTheme.colors.accent`, `TvkuTheme.spacing.md`, ... */
object TvkuTheme {
    val colors: TvkuColors
        @Composable @ReadOnlyComposable get() = LocalTvkuColors.current
    val spacing: TvkuSpacing
        @Composable @ReadOnlyComposable get() = LocalTvkuSpacing.current
    val shapes: TvkuShapes
        @Composable @ReadOnlyComposable get() = LocalTvkuShapes.current
    val motion: TvkuMotion
        @Composable @ReadOnlyComposable get() = LocalTvkuMotion.current
    val typography
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography
}
