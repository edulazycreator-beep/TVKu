package com.tvku.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class DeviceKeyHasherTest {
    private fun sha256Hex(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `hash is domain separated sha256 hex`() {
        val hash = DeviceKeyHasher.hash("a1b2c3d4e5f60718")
        assertEquals(sha256Hex("tvku-trial-v1:a1b2c3d4e5f60718"), hash)
        assertTrue(hash!!.matches(Regex("^[a-f0-9]{64}$")))   // server-side format check
    }

    @Test
    fun `raw identifier never appears in the output`() {
        val id = "a1b2c3d4e5f60718"
        assertTrue(!DeviceKeyHasher.hash(id)!!.contains(id))
        assertNotEquals(sha256Hex(id), DeviceKeyHasher.hash(id))
    }

    @Test
    fun `normalises case and whitespace`() {
        assertEquals(DeviceKeyHasher.hash("abcdef0123456789"), DeviceKeyHasher.hash("  ABCDEF0123456789 "))
    }

    @Test
    fun `missing or known-broken ids yield no device key`() {
        listOf(null, "", "   ", "9774d56d682e549c", "0000000000000000").forEach { assertNull(DeviceKeyHasher.hash(it)) }
    }
}
