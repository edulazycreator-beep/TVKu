package com.tvku.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireMappingTest {
    @Test
    fun `known availability values map one to one`() {
        assertEquals(StreamAvailability.VERIFIED, StreamAvailability.fromWire("VERIFIED"))
        assertEquals(StreamAvailability.GEO_RESTRICTED, StreamAvailability.fromWire("GEO_RESTRICTED"))
    }

    @Test
    fun `unknown or missing availability is never watchable`() {
        listOf(null, "", "verified", "REJECTED", "SOMETHING_NEW").forEach {
            val mapped = StreamAvailability.fromWire(it)
            assertEquals(StreamAvailability.UNKNOWN, mapped)
            assertFalse(mapped.isWatchable)
        }
    }

    @Test
    fun `only verified and degraded streams are watchable`() {
        val watchable = StreamAvailability.entries.filter { it.isWatchable }.toSet()
        assertEquals(setOf(StreamAvailability.VERIFIED, StreamAvailability.DEGRADED), watchable)
    }

    @Test
    fun `premium status falls back to UNKNOWN for unexpected values`() {
        assertEquals(PremiumStatus.PENDING_DONATION, PremiumStatus.fromWire("PENDING_DONATION"))
        assertEquals(PremiumStatus.UNKNOWN, PremiumStatus.fromWire("premium"))
        assertTrue(Entitlement.Unregistered.status == PremiumStatus.FREE)
        assertFalse(Entitlement.Unregistered.premiumFeaturesUnlocked)
    }
}
