package com.tvku.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvku.core.designsystem.theme.TvkuTheme

private val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

@Composable
fun TvkuPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = TvkuTheme.spacing.minTouchTarget + 4.dp),
        shape = TvkuTheme.shapes.pill,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = TvkuTheme.colors.accent,
            contentColor = TvkuTheme.colors.onAccent,
            disabledContainerColor = TvkuTheme.colors.surfaceHighlight,
            disabledContentColor = TvkuTheme.colors.textTertiary,
        ),
    ) { Text(text, style = TvkuTheme.typography.labelLarge) }
}

@Composable
fun TvkuSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = TvkuTheme.spacing.minTouchTarget + 4.dp),
        shape = TvkuTheme.shapes.pill,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, TvkuTheme.colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TvkuTheme.colors.textPrimary),
    ) { Text(text, style = TvkuTheme.typography.labelLarge) }
}

@Composable
fun TvkuTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = TvkuTheme.spacing.minTouchTarget),
        colors = ButtonDefaults.textButtonColors(contentColor = TvkuTheme.colors.textSecondary),
    ) { Text(text, style = TvkuTheme.typography.labelLarge) }
}
