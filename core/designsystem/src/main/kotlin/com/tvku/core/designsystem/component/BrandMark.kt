package com.tvku.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tvku.core.designsystem.theme.TvkuTheme

/** TVKu symbol: a rounded screen with a play glyph. Same geometry as the launcher icon. */
@Composable
fun TvkuSymbol(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val accent = TvkuTheme.colors.accent
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 48f
        drawRoundRect(
            color = accent,
            topLeft = Offset(4f * u, 9f * u),
            size = Size(40f * u, 28f * u),
            cornerRadius = CornerRadius(7f * u),
            style = Stroke(width = 4f * u),
        )
        drawLine(accent, Offset(17f * u, 42f * u), Offset(31f * u, 42f * u), strokeWidth = 4f * u)
        val play = Path().apply {
            moveTo(20f * u, 16.5f * u)
            lineTo(20f * u, 29.5f * u)
            lineTo(31f * u, 23f * u)
            close()
        }
        drawPath(play, accent)
    }
}

/** Symbol + wordmark ("TV" in primary text, "Ku" in accent). */
@Composable
fun TvkuLogo(modifier: Modifier = Modifier, symbolSize: Dp = 40.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvkuSymbol(size = symbolSize)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TvkuTheme.colors.textPrimary)) { append("TV") }
                withStyle(SpanStyle(color = TvkuTheme.colors.accent)) { append("Ku") }
            },
            style = TvkuTheme.typography.headlineLarge,
        )
    }
}
