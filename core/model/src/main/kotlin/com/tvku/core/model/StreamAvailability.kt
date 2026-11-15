package com.tvku.core.model

/**
 * Health of a channel's best published stream, mirroring
 * `public.stream_validation_status` on the backend. Only statuses that can
 * reach the public catalog are represented; DISCOVERED / PENDING_REVIEW /
 * REJECTED / REMOVED streams are never sent to the app.
 */
enum class StreamAvailability(val isWatchable: Boolean) {
    VERIFIED(isWatchable = true),
    DEGRADED(isWatchable = true),
    TESTING(isWatchable = false),
    GEO_RESTRICTED(isWatchable = false),
    OFFLINE(isWatchable = false),
    UNKNOWN(isWatchable = false),
    ;

    companion object {
        /** Unknown or unexpected values never become "watchable". */
        fun fromWire(value: String?): StreamAvailability =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}
