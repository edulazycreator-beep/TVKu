@file:OptIn(UnstableApi::class)

package com.tvku.core.player.media

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tvku.core.model.ChannelSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tier B playback validation (measurement harness, run by the
 * "Stream validation" workflow on an emulator).
 *
 * Input: instrumentation argument `streams` = base64(JSON array of {"url","format"}).
 * For each stream, with the app's own Media3 configuration:
 *   - wait up to 30 s for the first rendered video frame,
 *   - require >= 60 s of actual playback (wall-clock cap 90 s),
 *   - require decoded video frames and decoded audio,
 *   - stop and re-prepare (reconnect) and require playback again within 30 s.
 * Output: one logcat line per stream, tag TVKuValidation, prefix "RESULT ".
 * Nothing is inferred: every field is measured.
 */
@RunWith(AndroidJUnit4::class)
class StreamPlaybackValidationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun validateStreams() {
        val arg = InstrumentationRegistry.getArguments().getString("streams")
        assumeTrue("no streams argument: nothing to validate", !arg.isNullOrBlank())
        val list = JSONArray(String(Base64.decode(arg, Base64.DEFAULT), Charsets.UTF_8))
        Log.i(TAG, "validating ${list.length()} streams")
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val result = runCatching { validate(o.getString("url"), o.optString("format", "HLS")) }
                .getOrElse { JSONObject().put("outcome", "TRANSIENT_FAILURE").put("error", "harness: ${it.javaClass.simpleName}: ${it.message}") }
            result.put("url", o.getString("url"))
            Log.i(TAG, "RESULT $result")
        }
        Log.i(TAG, "DONE ${list.length()}")
    }

    private fun validate(url: String, format: String): JSONObject {
        val stream = ChannelSource.Stream(url, if (format == "DASH") ChannelSource.Stream.Format.DASH else ChannelSource.Stream.Format.HLS, null)
        val thread = HandlerThread("tvku-validator").apply { start() }
        val handler = Handler(thread.looper)
        val error = AtomicReference<PlaybackException?>(null)
        var firstFrame = CountDownLatch(1)
        val holder = AtomicReference<ExoPlayer?>(null)

        // Runs on the player's looper; exceptions are rethrown here instead of crashing that thread.
        fun <T> onPlayer(block: () -> T): T {
            val ref = AtomicReference<T>()
            val thrown = AtomicReference<Throwable?>(null)
            val done = CountDownLatch(1)
            handler.post {
                try { ref.set(block()) } catch (t: Throwable) { thrown.set(t) } finally { done.countDown() }
            }
            check(done.await(10, TimeUnit.SECONDS)) { "player thread timeout" }
            thrown.get()?.let { throw it }
            return ref.get()
        }

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = firstFrame.countDown()
            override fun onPlayerError(e: PlaybackException) {
                error.set(e)
                firstFrame.countDown()
            }
        }
        val out = JSONObject().put("format", format)
        // Media3 renders video frames only to a real output surface (without one it decodes
        // nothing and never reports a first frame). An ImageReader is a real surface whose
        // frames we acquire and drop, so the decoder runs exactly as it does behind PlayerView.
        val sink = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 4).apply {
            setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
        }
        try {
            onPlayer {
                val p = TvkuMedia3.createPlayer(context, thread.looper)
                holder.set(p)
                p.setVideoSurface(sink.surface)
                p.volume = 0f
                p.addListener(listener)
                p.setMediaItem(TvkuMedia3.mediaItem(stream))
                p.prepare()
                p.play()
            }
            val t0 = SystemClock.elapsedRealtime()
            firstFrame.await(30, TimeUnit.SECONDS)
            error.get()?.let { return out.failure(it) }
            if (onPlayer { holder.get()!!.videoDecoderCounters?.renderedOutputBufferCount ?: 0 } == 0) {
                return out.put("outcome", "TRANSIENT_FAILURE").put("error", "no video frame within 30 s")
            }
            out.put("first_frame_ms", SystemClock.elapsedRealtime() - t0)

            // Observe real playback time.
            var playedMs = 0L
            val start = SystemClock.elapsedRealtime()
            while (playedMs < 60_000 && SystemClock.elapsedRealtime() - start < 90_000) {
                SystemClock.sleep(1_000)
                error.get()?.let { return out.put("played_seconds", playedMs / 1000.0).failure(it) }
                if (onPlayer { holder.get()!!.isPlaying }) playedMs += 1_000
            }
            val frames = onPlayer { holder.get()!!.videoDecoderCounters?.renderedOutputBufferCount ?: 0 }
            val audioBuffers = onPlayer { holder.get()!!.audioDecoderCounters?.renderedOutputBufferCount ?: 0 }
            val height = onPlayer { holder.get()!!.videoSize.height }
            val width = onPlayer { holder.get()!!.videoSize.width }
            out.put("played_seconds", playedMs / 1000.0)
                .put("video_frames", frames)
                .put("audio_decoded", audioBuffers > 0)
            if (height > 0) out.put("resolution", "${width}x$height").put("height", height)
            if (playedMs < 60_000) return out.put("outcome", "TRANSIENT_FAILURE").put("error", "only ${playedMs / 1000} s of playback in 90 s")
            if (audioBuffers == 0) return out.put("outcome", "TRANSIENT_FAILURE").put("error", "no decoded audio")

            // Reconnect: stop and prepare again, as the app does after a network drop.
            firstFrame = CountDownLatch(1)
            onPlayer { holder.get()!!.stop(); holder.get()!!.prepare(); holder.get()!!.play() }
            val reconnected = firstFrame.await(30, TimeUnit.SECONDS) && error.get() == null && run {
                SystemClock.sleep(5_000)
                onPlayer { holder.get()!!.isPlaying }
            }
            out.put("reconnect_success", reconnected)
            return if (reconnected) out.put("outcome", "SUCCESS")
            else out.put("outcome", "TRANSIENT_FAILURE").put("error", "reconnect failed")
        } finally {
            runCatching { onPlayer { holder.get()?.release() } }
            sink.close()
            thread.quitSafely()
        }
    }

    private fun JSONObject.failure(e: PlaybackException): JSONObject {
        val outcome = when (e.toStreamFailure()) {
            StreamFailure.REGION_LIMITED -> "GEO_BLOCKED"
            StreamFailure.OFFLINE -> "PERMANENT_FAILURE"
            StreamFailure.UNSUPPORTED -> "UNSUPPORTED"
            StreamFailure.TRANSIENT, StreamFailure.BEHIND_LIVE -> "TRANSIENT_FAILURE"
        }
        return put("outcome", outcome).put("error", "${e.errorCodeName}: ${e.message?.take(160)}")
    }

    private companion object {
        const val TAG = "TVKuValidation"
    }
}
