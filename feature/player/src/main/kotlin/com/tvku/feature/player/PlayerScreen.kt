package com.tvku.feature.player

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvku.core.designsystem.component.TvkuEmptyState
import com.tvku.core.designsystem.component.TvkuSecondaryButton
import com.tvku.core.designsystem.component.TvkuTextButton
import com.tvku.core.designsystem.theme.TvkuTheme
import com.tvku.core.designsystem.R as DsR
import com.tvku.core.player.embed.YouTubeLivePlayer
import com.tvku.core.player.embed.YouTubePlayerStatus
import androidx.compose.ui.res.painterResource

@Composable
fun PlayerRoute(channelId: String, onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    LaunchedEffect(channelId) { viewModel.load(channelId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlayerScreen(state = state, onStatus = viewModel::onStatus, onRetry = viewModel::retry, onBack = onBack)
}

@Composable
internal fun PlayerScreen(
    state: PlayerUiState,
    onStatus: (YouTubePlayerStatus) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val channel = state.channel
    val source = channel?.playable
    if (state.notFound || channel == null || source == null) {
        Box(Modifier.fillMaxSize().background(TvkuTheme.colors.background), contentAlignment = Alignment.Center) {
            TvkuEmptyState(
                icon = painterResource(DsR.drawable.ic_tvku_tv),
                title = stringResource(R.string.player_channel_missing_title),
                message = stringResource(R.string.player_channel_missing_body),
                actionLabel = stringResource(R.string.player_back),
                onAction = onBack,
            )
        }
        return
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // A new key recreates the WebView, which is how "retry" reloads the player.
    val player: @Composable (Modifier) -> Unit = { modifier ->
        key(state.attempt) { YouTubeLivePlayer(youtubeChannelId = source.youtubeChannelId, onStatus = onStatus, modifier = modifier) }
    }

    if (landscape) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
            player(Modifier.fillMaxSize())
        }
        return
    }

    Column(Modifier.fillMaxSize().background(TvkuTheme.colors.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = TvkuTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            TvkuTextButton(stringResource(R.string.player_back), onClick = onBack)
        }
        player(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Column(Modifier.padding(TvkuTheme.spacing.pageMargin)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvkuTheme.spacing.sm)) {
                Text(channel.name, style = TvkuTheme.typography.headlineMedium, color = TvkuTheme.colors.textPrimary,
                    modifier = Modifier.semantics { heading() })
                if (state.showLiveBadge) LiveBadge()
            }
            Spacer(Modifier.height(TvkuTheme.spacing.xs))
            Text(
                statusText(state),
                style = TvkuTheme.typography.bodyMedium,
                color = if (state.status.isFinal || state.timedOut) TvkuTheme.colors.warning else TvkuTheme.colors.textSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state.status.isFinal || state.timedOut) {
                Spacer(Modifier.height(TvkuTheme.spacing.md))
                TvkuSecondaryButton(stringResource(R.string.player_retry), onClick = onRetry)
            }
            Spacer(Modifier.height(TvkuTheme.spacing.lg))
            Text(stringResource(R.string.player_source_note), style = TvkuTheme.typography.bodySmall, color = TvkuTheme.colors.textTertiary)
        }
    }
}

@Composable
private fun LiveBadge() {
    val label = stringResource(R.string.player_live)
    Row(
        Modifier.background(TvkuTheme.colors.error, TvkuTheme.shapes.pill).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(androidx.compose.ui.graphics.Color.White, CircleShape))
        Text(label, style = TvkuTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun statusText(state: PlayerUiState): String = when {
    state.timedOut -> stringResource(R.string.player_status_timeout)
    else -> stringResource(
        when (state.status) {
            YouTubePlayerStatus.CONNECTING -> R.string.player_status_connecting
            YouTubePlayerStatus.READY -> R.string.player_status_ready
            YouTubePlayerStatus.PLAYING -> R.string.player_status_playing
            YouTubePlayerStatus.BUFFERING -> R.string.player_status_buffering
            YouTubePlayerStatus.PAUSED -> R.string.player_status_paused
            YouTubePlayerStatus.ENDED -> R.string.player_status_ended
            YouTubePlayerStatus.NOT_AVAILABLE -> R.string.player_status_not_live
            YouTubePlayerStatus.EMBED_NOT_ALLOWED -> R.string.player_status_embed_not_allowed
            YouTubePlayerStatus.CLIENT_IDENTITY_ERROR -> R.string.player_status_identity
            YouTubePlayerStatus.ERROR -> R.string.player_status_error
        },
    )
}
