package com.tvku.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/** Encrypts small secrets at rest. */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    /** @throws java.security.GeneralSecurityException when the data cannot be decrypted. */
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key held in the Android Keystore.
 * Output layout: 12-byte IV || ciphertext+tag.
 */
class KeystoreSecretCipher @Inject constructor() : SecretCipher {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > IV_SIZE) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_SIZE))
        return cipher.doFinal(ciphertext, IV_SIZE, ciphertext.size - IV_SIZE)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tvku_installation_secret_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
