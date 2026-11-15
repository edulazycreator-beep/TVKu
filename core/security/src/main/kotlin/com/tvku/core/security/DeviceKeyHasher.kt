package com.tvku.core.security

import java.security.MessageDigest

/**
 * Turns the app-scoped Android ID into the one-way "device key" used only to
 * stop the 5-hour trial from restarting after "clear data" (decision D1).
 *
 * Since Android 8.0, `Settings.Secure.ANDROID_ID` is unique per app signing
 * key, user and device, survives app data clearing and reinstalls, and resets
 * on factory reset. It is not IMEI, serial number or MAC address. The raw
 * value never leaves the device; only `sha256("tvku-trial-v1:" + id)` is sent.
 */
object DeviceKeyHasher {
    private const val DOMAIN = "tvku-trial-v1:"

    /** Value returned by some old, buggy Android builds for every device. */
    private const val KNOWN_BROKEN_ID = "9774d56d682e549c"

    fun hash(androidId: String?): String? {
        val id = androidId?.trim()?.lowercase()
        if (id.isNullOrEmpty() || id == KNOWN_BROKEN_ID || id.all { it == '0' }) return null
        val digest = MessageDigest.getInstance("SHA-256").digest((DOMAIN + id).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
