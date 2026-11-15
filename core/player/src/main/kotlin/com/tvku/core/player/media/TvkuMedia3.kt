@file:OptIn(UnstableApi::class)

package com.tvku.core.player.media

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.tvku.core.model.ChannelSource

/**
 * The single Media3 configuration used both by the app and by the playback
 * validator, so "verified" means "plays with exactly what users get".
 */
object TvkuMedia3 {
    const val USER_AGENT = "TVKu/1 (Android; Media3)"

    fun createPlayer(context: Context, looper: Looper? = null): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(false)   // never downgrade to cleartext
        val mediaSources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 4))
        // Live TV favours steady playback over instant start.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 40_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            .build()
        val builder = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
        looper?.let(builder::setLooper)
        return builder.build().apply {
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
    }

    fun mediaItem(stream: ChannelSource.Stream): MediaItem = MediaItem.Builder()
        .setUri(stream.url)
        .setMimeType(if (stream.format == ChannelSource.Stream.Format.HLS) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MPD)
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(12_000).build())
        .build()
}

/** Why a stream stopped, from the user's point of view. */
enum class StreamFailure {
    /** Network trouble or server hiccup: worth retrying automatically. */
    TRANSIENT,
    /** Fell behind the live window: jump back to the live edge. */
    BEHIND_LIVE,
    /** 403/451: the broadcaster limits this stream to some regions. */
    REGION_LIMITED,
    /** 404/410 or broken manifest: the stream is offline. */
    OFFLINE,
    /** Codec/format this device cannot decode. */
    UNSUPPORTED,
}

@OptIn(UnstableApi::class)
fun PlaybackException.toStreamFailure(): StreamFailure {
    val http = generateSequence(cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    return when {
        errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> StreamFailure.BEHIND_LIVE
        http != null && http.responseCode in setOf(403, 451) -> StreamFailure.REGION_LIMITED
        http != null && http.responseCode in setOf(404, 410) -> StreamFailure.OFFLINE
        errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> StreamFailure.OFFLINE
        errorCode in PARSING_ERRORS -> StreamFailure.OFFLINE
        errorCode in DECODER_ERRORS -> StreamFailure.UNSUPPORTED
        else -> StreamFailure.TRANSIENT
    }
}

private val PARSING_ERRORS = setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
)

private val DECODER_ERRORS = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
)
