package com.tvku.core.player.embed

/** Player state as reported by YouTube's IFrame API. */
enum class YouTubePlayerStatus {
    CONNECTING,       // page loading / player not ready yet
    READY,            // player created, not playing yet
    PLAYING,          // frames are playing: the only state that may show LIVE
    BUFFERING,
    PAUSED,
    ENDED,            // broadcast ended
    NOT_AVAILABLE,    // no live broadcast right now, or video removed/private
    EMBED_NOT_ALLOWED,
    CLIENT_IDENTITY_ERROR,
    ERROR,
    ;

    val isFinal: Boolean get() = this in setOf(ENDED, NOT_AVAILABLE, EMBED_NOT_ALLOWED, CLIENT_IDENTITY_ERROR, ERROR)

    companion object {
        /** Maps IFrame API `onStateChange` / `onError` values. */
        fun from(kind: String, code: Int): YouTubePlayerStatus = when (kind) {
            "ready" -> READY
            "state" -> when (code) {
                1 -> PLAYING
                3 -> BUFFERING
                2 -> PAUSED
                0 -> ENDED
                else -> READY       // -1 unstarted, 5 cued
            }
            "error" -> when (code) {
                100 -> NOT_AVAILABLE
                101, 150 -> EMBED_NOT_ALLOWED
                152, 153 -> CLIENT_IDENTITY_ERROR
                else -> ERROR       // 2 invalid parameter, 5 HTML5 player error
            }
            else -> ERROR
        }
    }
}
