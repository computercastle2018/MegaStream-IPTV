package com.MegaStream.app.controlplane

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class InstallationCredentialsTest {
    private val id = "00112233-4455-4677-8899-aabbccddeeff"
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))

    @Test fun `UUID must be canonical RFC4122 version four`() {
        assertTrue(InstallationCredentials.isValidInstallationId(id))
        listOf("", "1-1-4-8-1", id.uppercase(), id.replace("4677", "1677"),
            id.replace("8899", "c899"), "$id ").forEach {
            assertFalse(it, InstallationCredentials.isValidInstallationId(it))
        }
    }

    @Test fun `secret must encode exactly 32 bytes canonically without padding`() {
        assertEquals(43, secret.length)
        assertTrue(InstallationCredentials.isValidCredential(secret))
        val urlAlphabet = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { -1 })
        assertTrue(InstallationCredentials.isValidCredential(urlAlphabet))
        listOf("", secret + "=", secret.dropLast(1), secret + "A", secret.dropLast(1) + "B",
            "+" + secret.drop(1), "/" + secret.drop(1), " " + secret.drop(1)).forEach {
            assertFalse(it, InstallationCredentials.isValidCredential(it))
        }
    }

    @Test fun `constructor rejects invalid inputs without exposing them`() {
        listOf("private-invalid-id" to secret, id to "private-invalid-secret").forEach { (badId, badSecret) ->
            try {
                InstallationCredentials(badId, badSecret)
                fail("Expected rejection")
            } catch (error: IllegalArgumentException) {
                assertFalse(error.message.orEmpty().contains("private-invalid"))
            }
        }
    }

    @Test fun `injected entropy supplies UUID and all 32 secret bytes`() {
        val lengths = mutableListOf<Int>()
        val generated = SecureInstallationCredentialGenerator { bytes ->
            lengths += bytes.size
            bytes.indices.forEach { bytes[it] = it.toByte() }
        }.generate()
        assertEquals(listOf(16, 32), lengths)
        assertEquals("00010203-0405-4607-8809-0a0b0c0d0e0f", generated.installationId)
        assertArrayEquals(ByteArray(32) { it.toByte() }, Base64.getUrlDecoder().decode(generated.credential))
    }

    @Test fun `credentials and ready state redact the secret`() {
        val credentials = InstallationCredentials(id, secret)
        assertFalse(credentials.toString().contains(secret))
        assertTrue(credentials.toString().contains("[REDACTED]"))
        assertFalse(InstallationCredentialStore.State.Ready(credentials).toString().contains(secret))
    }
}
