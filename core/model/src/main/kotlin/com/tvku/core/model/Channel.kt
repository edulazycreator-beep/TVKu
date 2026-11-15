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
    /** Source played inside TVKu, if any. */
    val playable: ChannelSource.YouTubeLive? get() = sources.filterIsInstance<ChannelSource.YouTubeLive>().firstOrNull()

    /** Broadcaster platform link, if any. */
    val officialLink: ChannelSource.OfficialLink? get() = sources.filterIsInstance<ChannelSource.OfficialLink>().firstOrNull()
}

/** How a channel can be watched. TVKu never hosts or re-streams video. */
sealed interface ChannelSource {
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
