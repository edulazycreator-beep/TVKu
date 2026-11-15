package com.tvku.core.security

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies the one-way device key for trial abuse prevention (see [DeviceKeyHasher]). */
@Singleton
class DeviceKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Used only for trial abuse prevention, never for advertising or tracking,
     * and only as a salted hash; disclosed in the privacy policy (decision D1).
     */
    @SuppressLint("HardwareIds")
    fun deviceKeyHash(): String? =
        DeviceKeyHasher.hash(Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))
}
