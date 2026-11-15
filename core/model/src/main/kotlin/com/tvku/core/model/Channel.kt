package com.tvku.core.model

enum class ChannelCategory {
    NEWS, SPORTS, ENTERTAINMENT, MOVIES, MUSIC, DOCUMENTARY, KIDS, EDUCATION, RELIGIOUS, GENERAL;

    companion object {
        fun fromWire(value: String?): ChannelCategory =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: GENERAL
    }
}

data class Channel(
    val id: String,
    val name: String,
    val countryCode: String,
    val category: ChannelCategory,
    val languageCodes: List<String>,
    val sources: List<ChannelSource>,
) {
    /** Playback-verified streams, best first. */
    val streams: List<ChannelSource.Stream> get() = sources.filterIsInstance<ChannelSource.Stream>()

    /** Official YouTube live channel, if any. */
    val youtube: ChannelSource.YouTubeLive? get() = sources.filterIsInstance<ChannelSource.YouTubeLive>().firstOrNull()

    /** Something TVKu can play itself (a verified stream, or the official YouTube live player). */
    val playable: ChannelSource? get() = streams.firstOrNull() ?: youtube

    /** Broadcaster platform link, if any. */
    val officialLink: ChannelSource.OfficialLink? get() = sources.filterIsInstance<ChannelSource.OfficialLink>().firstOrNull()
}

/** How a channel can be watched. TVKu never hosts or re-streams video. */
sealed interface ChannelSource {
    /**
     * Broadcaster stream (HLS/DASH) that passed TVKu's playback validation:
     * played with Media3 for at least 60 s, video and audio decoded, reconnect
     * succeeded. [heightPx] is the measured resolution, not a claim.
     */
    data class Stream(val url: String, val format: Format, val heightPx: Int?) : ChannelSource {
        enum class Format { HLS, DASH }

        init {
            require(url.startsWith("https://") && url.length <= 2048 && url.none { it.isWhitespace() }) { "streams must be https" }
            require(heightPx == null || heightPx in 1..8192) { "invalid height" }
        }

        val qualityLabel: String? get() = when {
            heightPx == null -> null
            heightPx >= 2160 -> "4K"
            heightPx >= 1080 -> "Full HD"
            heightPx >= 720 -> "HD"
            else -> "SD"
        }
    }

    /**
     * Broadcaster's official YouTube channel; its current live broadcast is
     * shown with YouTube's own embedded player. Whether it is live right now
     * is only known when the player reports it.
     */
    data class YouTubeLive(val youtubeChannelId: String) : ChannelSource {
        init {
            require(CHANNEL_ID.matches(youtubeChannelId)) { "invalid YouTube channel id" }
        }

        /** The channel's live page on YouTube itself (opens the YouTube app when installed). */
        val livePageUrl: String get() = "https://www.youtube.com/channel/$youtubeChannelId/live"

        private companion object {
            val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
        }
    }

    /**
     * Channel streamed only inside the broadcaster's own platform (e.g. Vidio,
     * RCTI+). TVKu opens the official page; Android hands it to the platform's
     * app when installed.
     */
    data class OfficialLink(val url: String, val platformName: String) : ChannelSource {
        init {
            require(url.startsWith("https://") && url.length <= 2048 && url.none { it.isWhitespace() }) { "official links must be https" }
            require(platformName.length in 2..60) { "platform name required" }
        }
    }
}
