package com.tvku.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tvku.core.designsystem.theme.TvkuTheme

/** Calm, honest empty / error state with an optional action. */
@Composable
fun TvkuEmptyState(
    icon: Painter,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.widthIn(max = 420.dp).padding(TvkuTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = TvkuTheme.colors.textTertiary, modifier = Modifier.padding(bottom = TvkuTheme.spacing.md).size(40.dp))
        Text(title, style = TvkuTheme.typography.titleLarge, color = TvkuTheme.colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(TvkuTheme.spacing.xs))
        Text(message, style = TvkuTheme.typography.bodyMedium, color = TvkuTheme.colors.textSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(TvkuTheme.spacing.lg))
            TvkuSecondaryButton(text = actionLabel, onClick = onAction)
        }
    }
}
