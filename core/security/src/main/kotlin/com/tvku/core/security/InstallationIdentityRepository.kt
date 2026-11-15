package com.tvku.core.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the installation identity once and returns the same identity
 * afterwards. If the stored secret can no longer be decrypted (Keystore key
 * lost), a fresh identity is created; the server then treats this as a new
 * installation, and the device key (D1) prevents a second trial.
 */
@Singleton
class InstallationIdentityRepository @Inject constructor(
    private val storage: IdentityStorage,
    private val cipher: SecretCipher,
) {
    private val mutex = Mutex()
    private val random = SecureRandom()
    @Volatile private var cached: InstallationCredentials? = null

    suspend fun getOrCreate(): InstallationCredentials = cached ?: mutex.withLock {
        cached ?: (load() ?: create()).also { cached = it }
    }

    private suspend fun load(): InstallationCredentials? {
        val stored = storage.read() ?: return null
        return try {
            val uuid = UUID.fromString(stored.installationUuid)
            val secret = cipher.decrypt(Base64.getDecoder().decode(stored.encryptedSecretBase64)).toString(Charsets.UTF_8)
            if (secret.length < MIN_SECRET_LENGTH) null else InstallationCredentials(uuid, secret)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private suspend fun create(): InstallationCredentials {
        val bytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val credentials = InstallationCredentials(UUID.randomUUID(), secret)
        storage.write(
            StoredIdentity(
                installationUuid = credentials.installationUuid.toString(),
                encryptedSecretBase64 = Base64.getEncoder().encodeToString(cipher.encrypt(secret.toByteArray(Charsets.UTF_8))),
            ),
        )
        return credentials
    }

    private companion object {
        const val SECRET_BYTES = 32       // 256 bits -> 43 base64url chars
        const val MIN_SECRET_LENGTH = 32  // matches the server-side check
    }
}
