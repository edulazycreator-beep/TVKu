package com.tvku.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tvku.core.designsystem.R
import com.tvku.core.designsystem.theme.TvkuTheme
import com.tvku.core.model.StreamAvailability

/**
 * Catalog-level availability (last validation result). This is deliberately
 * NOT a "LIVE" badge: LIVE is shown only by the player, and only while video
 * frames are actually being rendered.
 */
@Composable
fun AvailabilityBadge(availability: StreamAvailability, modifier: Modifier = Modifier) {
    val (label, color) = when (availability) {
        StreamAvailability.VERIFIED -> stringResource(R.string.ds_availability_verified) to TvkuTheme.colors.success
        StreamAvailability.DEGRADED -> stringResource(R.string.ds_availability_degraded) to TvkuTheme.colors.warning
        StreamAvailability.TESTING -> stringResource(R.string.ds_availability_testing) to TvkuTheme.colors.textSecondary
        StreamAvailability.GEO_RESTRICTED -> stringResource(R.string.ds_availability_geo) to TvkuTheme.colors.warning
        StreamAvailability.OFFLINE -> stringResource(R.string.ds_availability_offline) to TvkuTheme.colors.error
        StreamAvailability.UNKNOWN -> stringResource(R.string.ds_availability_unknown) to TvkuTheme.colors.textTertiary
    }
    StatusPill(label = label, dotColor = color, modifier = modifier)
}

@Composable
internal fun StatusPill(label: String, dotColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(TvkuTheme.colors.surfaceHighlight, TvkuTheme.shapes.pill)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(dotColor, CircleShape))
        Text(label, style = TvkuTheme.typography.labelSmall, color = TvkuTheme.colors.textPrimary)
    }
}
