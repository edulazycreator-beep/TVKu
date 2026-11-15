package com.tvku.core.model

/** What the player is asked to play. Built only from published catalog entries. */
sealed interface PlaybackSource {
    /** HLS / DASH / progressive stream played by Media3 (milestone M3). */
    data class Media(val url: String, val format: Format) : PlaybackSource {
        enum class Format { HLS, DASH, PROGRESSIVE }
    }

    /**
     * Broadcaster's official YouTube live broadcast, shown with YouTube's own
     * embedded player. TVKu never extracts the underlying stream.
     */
    data class YouTubeEmbed(val videoId: String) : PlaybackSource {
        init {
            require(VIDEO_ID.matches(videoId)) { "invalid YouTube video id" }
        }

        private companion object {
            val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        }
    }

    /**
     * Channel that is streamed only inside the broadcaster's own platform
     * (e.g. Vidio, RCTI+). TVKu does not play it; it opens the official page,
     * which Android hands to the platform's app when it is installed.
     */
    data class ExternalLink(val url: String, val platformName: String) : PlaybackSource {
        init {
            require(url.startsWith("https://") && url.length <= 2048 && url.none { it.isWhitespace() }) { "official links must be https" }
            require(platformName.length in 2..60) { "platform name required" }
        }
    }
}
