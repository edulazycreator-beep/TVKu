package com.tvku.feature.player

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvku.core.data.catalog.CatalogRepository
import com.tvku.core.model.Channel
import com.tvku.core.model.ChannelSource
import com.tvku.core.player.embed.YouTubePlayerStatus
import com.tvku.core.player.media.MediaPlaybackStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val channel: Channel? = null,
    /** Index into [Channel.streams]; only other verified feeds of the same channel are ever tried. */
    val streamIndex: Int = 0,
    val media: MediaPlaybackStatus? = null,
    val youtube: YouTubePlayerStatus? = null,
    val measuredHeightPx: Int? = null,
    /** True when playback never started within [PlayerViewModel.START_TIMEOUT_MS]. */
    val timedOut: Boolean = false,
    /** Incremented to force a fresh player instance on retry. */
    val attempt: Int = 0,
    val notFound: Boolean = false,
) {
    val source: ChannelSource? get() = channel?.streams?.getOrNull(streamIndex) ?: channel?.youtube

    /** LIVE is shown only while frames are actually playing. */
    val showLiveBadge: Boolean
        get() = media == MediaPlaybackStatus.PLAYING || youtube == YouTubePlayerStatus.PLAYING

    val failed: Boolean
        get() = timedOut || media?.isFinal == true || youtube?.isFinal == true

    val isPlaying: Boolean get() = showLiveBadge

    @get:StringRes
    val statusText: Int
        get() = when {
            timedOut -> R.string.player_status_timeout
            media != null -> when (media) {
                MediaPlaybackStatus.CONNECTING -> R.string.player_status_connecting_stream
                MediaPlaybackStatus.BUFFERING -> R.string.player_status_buffering
                MediaPlaybackStatus.PLAYING -> R.string.player_status_playing_stream
                MediaPlaybackStatus.PAUSED -> R.string.player_status_paused
                MediaPlaybackStatus.RECONNECTING -> R.string.player_status_reconnecting
                MediaPlaybackStatus.REGION_LIMITED -> R.string.player_status_region_limited
                MediaPlaybackStatus.OFFLINE -> R.string.player_status_offline
                MediaPlaybackStatus.UNSUPPORTED -> R.string.player_status_unsupported
                MediaPlaybackStatus.FAILED -> R.string.player_status_error
            }
            else -> when (youtube ?: YouTubePlayerStatus.CONNECTING) {
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
            }
        }
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var timeout: Job? = null

    fun load(channelId: String) {
        if (_state.value.channel?.id == channelId) return
        val channel = catalog.channel(channelId)
        _state.value = PlayerUiState(channel = channel, notFound = channel?.playable == null)
        if (channel?.playable != null) armTimeout()
    }

    fun onMediaStatus(status: MediaPlaybackStatus, heightPx: Int?) {
        val s = _state.value
        // Final failure on one feed: move to the next verified feed of the same channel, if any.
        if (status.isFinal && s.channel != null && s.streamIndex + 1 < s.channel.streams.size) {
            _state.update { it.copy(streamIndex = it.streamIndex + 1, media = MediaPlaybackStatus.CONNECTING, timedOut = false) }
            armTimeout()
            return
        }
        _state.update { it.copy(media = status, measuredHeightPx = heightPx ?: it.measuredHeightPx, timedOut = it.timedOut && status != MediaPlaybackStatus.PLAYING) }
        if (status == MediaPlaybackStatus.PLAYING || status.isFinal) timeout?.cancel()
    }

    fun onYouTubeStatus(status: YouTubePlayerStatus) {
        _state.update { it.copy(youtube = status, timedOut = it.timedOut && status != YouTubePlayerStatus.PLAYING) }
        if (status == YouTubePlayerStatus.PLAYING || status.isFinal) timeout?.cancel()
    }

    fun retry() {
        _state.update { it.copy(media = null, youtube = null, timedOut = false, streamIndex = 0, attempt = it.attempt + 1) }
        armTimeout()
    }

    private fun armTimeout() {
        timeout?.cancel()
        timeout = viewModelScope.launch {
            delay(START_TIMEOUT_MS)
            _state.update { if (!it.isPlaying && !it.failed) it.copy(timedOut = true) else it }
        }
    }

    companion object {
        const val START_TIMEOUT_MS = 40_000L
    }
}
