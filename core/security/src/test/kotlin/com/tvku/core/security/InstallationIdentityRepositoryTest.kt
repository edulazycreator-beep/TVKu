package com.tvku.core.security

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

private class InMemoryStorage : IdentityStorage {
    var value: StoredIdentity? = null
    var writes = 0
    override suspend fun read() = value
    override suspend fun write(identity: StoredIdentity) { value = identity; writes++ }
}

/** Reversible test cipher; [broken] simulates a lost Keystore key. */
private class FakeCipher : SecretCipher {
    var broken = false
    override fun encrypt(plaintext: ByteArray) = plaintext.reversedArray() + 0x7f
    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (broken) throw GeneralSecurityException("key gone")
        return ciphertext.copyOf(ciphertext.size - 1).reversedArray()
    }
}

class InstallationIdentityRepositoryTest {
    @Test
    fun `creates identity once and reuses it`() = runTest {
        val storage = InMemoryStorage()
        val first = InstallationIdentityRepository(storage, FakeCipher()).getOrCreate()
        val second = InstallationIdentityRepository(storage, FakeCipher()).getOrCreate()
        assertEquals(first, second)
        assertEquals(1, storage.writes)
    }

    @Test
    fun `secret is long enough for the server and not stored in clear`() = runTest {
        val storage = InMemoryStorage()
        val creds = InstallationIdentityRepository(storage, FakeCipher()).getOrCreate()
        assertTrue(creds.secret.length in 32..256)
        assertFalse(storage.value!!.encryptedSecretBase64.contains(creds.secret))
        assertFalse(creds.toString().contains(creds.secret))
    }

    @Test
    fun `concurrent callers get the same identity`() = runTest {
        val storage = InMemoryStorage()
        val repo = InstallationIdentityRepository(storage, FakeCipher())
        val results = (1..20).map { async { repo.getOrCreate() } }.awaitAll()
        assertEquals(1, results.toSet().size)
        assertEquals(1, storage.writes)
    }

    @Test
    fun `undecryptable secret leads to a new identity`() = runTest {
        val storage = InMemoryStorage()
        val original = InstallationIdentityRepository(storage, FakeCipher()).getOrCreate()
        val lostKey = FakeCipher().apply { broken = true }
        val replacement = InstallationIdentityRepository(storage, lostKey).getOrCreate()
        assertNotEquals(original.installationUuid, replacement.installationUuid)
        assertEquals(2, storage.writes)
    }
}
