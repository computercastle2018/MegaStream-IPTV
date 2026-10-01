package com.MegaStream.app.controlplane

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Installation-scoped random credentials; never derived from device identifiers. */
class InstallationCredentials(val installationId: String, val credential: String) {
    init {
        require(isValidInstallationId(installationId)) { "Invalid installation ID" }
        require(isValidCredential(credential)) { "Invalid installation credential" }
    }

    override fun toString(): String =
        "InstallationCredentials(installationId=$installationId, credential=[REDACTED])"

    companion object {
        fun isValidInstallationId(value: String): Boolean = try {
            val uuid = UUID.fromString(value)
            uuid.version() == 4 && uuid.variant() == 2 && uuid.toString() == value
        } catch (_: IllegalArgumentException) {
            false
        }

        fun isValidCredential(value: String): Boolean {
            if (!value.matches(Regex("[A-Za-z0-9_-]{43}"))) return false
            return try {
                val bytes = Base64.getUrlDecoder().decode(value)
                bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }
}

fun interface InstallationCredentialGenerator {
    fun generate(): InstallationCredentials
}

/** Entropy injection keeps generation independently testable without Android. */
class SecureInstallationCredentialGenerator(
    private val entropy: (ByteArray) -> Unit = SecureRandom()::nextBytes,
) : InstallationCredentialGenerator {
    override fun generate(): InstallationCredentials {
        val uuidBytes = ByteArray(16).also(entropy)
        uuidBytes[6] = ((uuidBytes[6].toInt() and 0x0f) or 0x40).toByte()
        uuidBytes[8] = ((uuidBytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(uuidBytes)
        val id = UUID(buffer.long, buffer.long).toString()
        val secret = ByteArray(32).also(entropy)
        return try {
            InstallationCredentials(id, Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
        } finally {
            secret.fill(0)
            uuidBytes.fill(0)
        }
    }
}
