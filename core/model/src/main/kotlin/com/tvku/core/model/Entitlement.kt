package com.tvku.core.model

import java.time.Instant

/** Overall status computed by the server (`public.premium_status`). */
enum class PremiumStatus {
    FREE, TRIAL, PENDING_DONATION, PREMIUM, EXPIRED, REVOKED, UNKNOWN;

    companion object {
        fun fromWire(value: String?): PremiumStatus = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

enum class TrialStatus {
    NOT_STARTED, ACTIVE, EXPIRED;

    companion object {
        fun fromWire(value: String?): TrialStatus = entries.firstOrNull { it.name == value } ?: NOT_STARTED
    }
}

/**
 * Server-authoritative entitlement snapshot. [serverTime] is the server clock
 * at the moment of the response; the device clock is never used to decide
 * whether premium is active.
 */
data class Entitlement(
    val status: PremiumStatus,
    val premiumFeaturesUnlocked: Boolean,
    val serverTime: Instant,
    val trialStatus: TrialStatus,
    val trialExpiresAt: Instant?,
    val trialRemainingSeconds: Long,
    val premiumExpiresAt: Instant?,
    val pendingDonationReference: String?,
    val publicCode: String?,
) {
    companion object {
        /** State before the first successful server registration. Free channels remain available. */
        val Unregistered = Entitlement(
            status = PremiumStatus.FREE,
            premiumFeaturesUnlocked = false,
            serverTime = Instant.EPOCH,
            trialStatus = TrialStatus.NOT_STARTED,
            trialExpiresAt = null,
            trialRemainingSeconds = 0,
            premiumExpiresAt = null,
            pendingDonationReference = null,
            publicCode = null,
        )
    }
}
