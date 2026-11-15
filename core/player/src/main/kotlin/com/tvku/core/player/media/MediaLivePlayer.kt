@file:OptIn(UnstableApi::class)

package com.tvku.core.player.media

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tvku.core.model.ChannelSource
import kotlinx.coroutines.delay

/** Honest playback state: LIVE may only be shown in [PLAYING]. */
enum class MediaPlaybackStatus {
    CONNECTING, BUFFERING, PLAYING, PAUSED, RECONNECTING, REGION_LIMITED, OFFLINE, UNSUPPORTED, FAILED;

    val isFinal: Boolean get() = this in setOf(REGION_LIMITED, OFFLINE, UNSUPPORTED, FAILED)
}

/**
 * Plays a verified HLS/DASH stream with Media3. One player instance per
 * composition, released when it leaves the screen; paused in background.
 * Transient failures are retried automatically with exponential backoff
 * (2 s, 4 s, 8 s) before giving up.
 */
@Composable
fun MediaLivePlayer(
    stream: ChannelSource.Stream,
    onStatus: (MediaPlaybackStatus, heightPx: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentOnStatus by rememberUpdatedState(onStatus)
    val player = remember(stream.url) { TvkuMedia3.createPlayer(context) }
    var failure by remember(stream.url) { mutableStateOf<StreamFailure?>(null) }
    var retries by remember(stream.url) { mutableIntStateOf(0) }
    var firstFrame by remember(stream.url) { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
                retries = 0
                report(player, firstFrame, currentOnStatus)
            }

            override fun onPlaybackStateChanged(state: Int) = report(player, firstFrame, currentOnStatus)
            override fun onIsPlayingChanged(isPlaying: Boolean) = report(player, firstFrame, currentOnStatus)

            override fun onPlayerError(error: PlaybackException) {
                failure = error.toStreamFailure()
            }
        }
        player.addListener(listener)
        player.setMediaItem(TvkuMedia3.mediaItem(stream))
        player.prepare()
        player.playWhenReady = true
        currentOnStatus(MediaPlaybackStatus.CONNECTING, null)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Recovery policy for errors.
    LaunchedEffect(failure) {
        when (failure ?: return@LaunchedEffect) {
            StreamFailure.BEHIND_LIVE -> {
                player.seekToDefaultPosition()
                player.prepare()
                failure = null
            }
            StreamFailure.TRANSIENT -> if (retries < MAX_RETRIES) {
                currentOnStatus(MediaPlaybackStatus.RECONNECTING, null)
                delay(2_000L shl retries)
                retries++
                player.prepare()
                failure = null
            } else {
                currentOnStatus(MediaPlaybackStatus.FAILED, null)
            }
            StreamFailure.REGION_LIMITED -> currentOnStatus(MediaPlaybackStatus.REGION_LIMITED, null)
            StreamFailure.OFFLINE -> currentOnStatus(MediaPlaybackStatus.OFFLINE, null)
            StreamFailure.UNSUPPORTED -> currentOnStatus(MediaPlaybackStatus.UNSUPPORTED, null)
        }
    }

    // Pause when the app goes to background; resume when it returns.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setKeepContentOnPlayerReset(true)
                this.player = player
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        onRelease = { view -> view.player = null },
    )
}

private const val MAX_RETRIES = 3

private fun report(player: Player, firstFrame: Boolean, onStatus: (MediaPlaybackStatus, Int?) -> Unit) {
    val height = player.videoSize.height.takeIf { it > 0 }
    val status = when {
        player.isPlaying && firstFrame -> MediaPlaybackStatus.PLAYING
        player.playbackState == Player.STATE_BUFFERING -> if (firstFrame) MediaPlaybackStatus.BUFFERING else MediaPlaybackStatus.CONNECTING
        player.playbackState == Player.STATE_ENDED -> MediaPlaybackStatus.OFFLINE
        player.playbackState == Player.STATE_READY && !player.playWhenReady -> MediaPlaybackStatus.PAUSED
        else -> MediaPlaybackStatus.CONNECTING
    }
    onStatus(status, height)
}
