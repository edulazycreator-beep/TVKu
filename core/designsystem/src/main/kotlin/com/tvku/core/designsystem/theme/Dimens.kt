package com.tvku.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 4-dp based spacing scale. */
@Immutable
data class TvkuSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    val xxxl: Dp = 64.dp,
    /** Horizontal page margin on phones; tablets use [pageMarginWide]. */
    val pageMargin: Dp = 20.dp,
    val pageMarginWide: Dp = 40.dp,
    /** Minimum touch target (WCAG / Material). */
    val minTouchTarget: Dp = 48.dp,
)

@Immutable
data class TvkuShapes(
    val small: RoundedCornerShape = RoundedCornerShape(8.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(14.dp),
    val large: RoundedCornerShape = RoundedCornerShape(20.dp),
    val extraLarge: RoundedCornerShape = RoundedCornerShape(28.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50),
)

internal fun TvkuShapes.toMaterial() = Shapes(
    extraSmall = small, small = small, medium = medium, large = large, extraLarge = extraLarge,
)

/** Motion tokens: short, calm transitions; no bouncy springs. */
@Immutable
data class TvkuMotion(
    val durationShort: Int = 150,
    val durationMedium: Int = 260,
    val durationLong: Int = 420,
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0.2f, 1f),
)
