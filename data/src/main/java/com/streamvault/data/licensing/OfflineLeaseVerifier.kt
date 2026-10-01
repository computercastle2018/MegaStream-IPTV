package com.MegaStream.data.licensing

import com.MegaStream.domain.licensing.LicenseAccessState
import com.MegaStream.domain.licensing.OfflineLease
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Authenticates lease claims; temporal access policy belongs to the domain evaluator. */
class OfflineLeaseVerifier(keys: Map<String, PublicKey>) {
    private val pinnedKeys = keys.toMap().also { pins ->
        pins.forEach { (kid, key) ->
            require(kid.isNotEmpty()) { "Empty key identifier" }
            requireP256(key)
        }
    }

    fun verify(
        compactJws: String,
        expectedInstallationId: String,
        expectedCredentialBinding: String,
    ): OfflineLease {
        require(compactJws.length <= MAX_TOKEN_LENGTH) { "Lease is too large" }
        val segments = compactJws.split('.')
        require(segments.size == 3) { "Expected compact JWS" }
        val header = canonicalObject(decode(segments[0], MAX_HEADER_LENGTH))
        val key = headerKey(header)
        val payload = decode(segments[1], MAX_PAYLOAD_LENGTH)
        val signature = joseToDer(decode(segments[2], 86))
        authenticate(key, "${segments[0]}.${segments[1]}", signature)
        return claims(canonicalObject(payload), expectedInstallationId, expectedCredentialBinding)
    }

    private fun headerKey(header: JsonObject): PublicKey {
        require(header.keys == setOf("alg", "kid") || header.keys == setOf("alg", "kid", "typ")) {
            "Unsupported JWS header"
        }
        require(header.string("alg") == "ES256") { "Unsupported algorithm" }
        if ("typ" in header) require(header.string("typ") == "JWT") { "Unsupported token type" }
        return requireNotNull(pinnedKeys[header.string("kid")]) { "Unknown key identifier" }
    }

    private fun authenticate(key: PublicKey, signingInput: String, signature: ByteArray) {
        try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(signingInput.toByteArray(Charsets.US_ASCII))
            require(verifier.verify(signature)) { "Invalid lease signature" }
        } catch (failure: GeneralSecurityException) {
            throw IllegalArgumentException("Cannot authenticate lease", failure)
        }
    }

    private fun claims(claims: JsonObject, installation: String, binding: String): OfflineLease {
        require(claims.keys == CLAIM_NAMES) { "Unexpected lease claims" }
        require(claims.string("iss") == ISSUER) { "Invalid issuer" }
        require(claims.string("aud") == AUDIENCE) { "Invalid audience" }
        require(claims.uuid("sub") == installation) { "Installation mismatch" }
        val credentialBinding = claims.string("credentialBinding")
        require(decode(credentialBinding, 43).size == 32) { "Invalid credential binding" }
        require(credentialBinding == binding) { "Credential binding mismatch" }
        val lease = lease(claims)
        require(lease.licenseRevision > 0 && lease.policyVersion == 1) { "Unsupported lease policy" }
        require(lease.issuedAtEpochSeconds < lease.expiresAtEpochSeconds) { "Invalid issuance interval" }
        require(lease.notBeforeEpochSeconds < lease.expiresAtEpochSeconds) { "Invalid validity interval" }
        require(lease.licenseStartsAtEpochSeconds < lease.licenseEndsAtEpochSeconds) { "Invalid license interval" }
        return lease
    }

    private fun lease(claims: JsonObject): OfflineLease = OfflineLease(
        issuer = claims.string("iss"),
        audience = claims.string("aud"),
        installationId = claims.uuid("sub"),
        credentialBinding = claims.string("credentialBinding"),
        issuedAtEpochSeconds = claims.integer("iat"),
        notBeforeEpochSeconds = claims.integer("nbf"),
        expiresAtEpochSeconds = claims.integer("exp"),
        licenseStartsAtEpochSeconds = claims.integer("licenseStartsAt"),
        licenseEndsAtEpochSeconds = claims.integer("licenseEndsAt"),
        licenseId = claims.uuid("lid"),
        licenseRevision = claims.integer("lrv"),
        tokenId = claims.uuid("jti"),
        policyVersion = claims.integer("policyVersion").also { require(it == 1L) }.toInt(),
        decision = requireNotNull(DECISIONS[claims.string("decision")]) { "Unknown decision" },
    )

    private fun JsonObject.string(name: String): String {
        val primitive = this[name] as? JsonPrimitive
        require(primitive != null && primitive.isString) { "Expected string claim: $name" }
        return primitive.content
    }

    private fun JsonObject.uuid(name: String): String {
        val identifier = string(name)
        require(UUID.fromString(identifier).toString() == identifier) { "Noncanonical UUID: $name" }
        return identifier
    }

    private fun JsonObject.integer(name: String): Long {
        val primitive = this[name] as? JsonPrimitive
        require(primitive != null && !primitive.isString && DECIMAL.matches(primitive.content)) {
            "Expected nonnegative integer claim: $name"
        }
        return requireNotNull(primitive.content.toLongOrNull()) { "Integer overflow: $name" }
    }

    private fun decode(segment: String, maximumLength: Int): ByteArray {
        require(segment.isNotEmpty() && segment.length <= maximumLength && BASE64URL.matches(segment)) {
            "Invalid base64url segment"
        }
        val decoded = Base64.getUrlDecoder().decode(segment)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == segment) {
            "Noncanonical base64url"
        }
        return decoded
    }

    private fun canonicalObject(encoded: ByteArray): JsonObject {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString()
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("Invalid UTF-8", failure)
        }
        requireFlatObject(text)
        val parsed = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (failure: SerializationException) {
            throw IllegalArgumentException("Invalid JSON", failure)
        }
        // Reserialization rejects duplicates, whitespace and alternative string escaping, without sorting keys.
        require(parsed != null && parsed.toString() == text) { "Expected canonical JSON object" }
        return parsed
    }

    private fun requireFlatObject(text: String) {
        require(text.startsWith('{') && text.endsWith('}')) { "Expected JSON object" }
        var quoted = false
        var escaped = false
        // Only scalar claims are supported; reject nesting before the recursive JSON parser sees it.
        for (character in text.substring(1, text.lastIndex)) {
            when {
                escaped -> escaped = false
                quoted && character == '\\' -> escaped = true
                character == '"' -> quoted = !quoted
                !quoted -> require(character !in "{}[]") { "Nested claims are unsupported" }
            }
        }
    }

    private fun joseToDer(signature: ByteArray): ByteArray {
        require(signature.size == 64) { "Expected 64-byte ES256 signature" }
        val r = derInteger(signature.copyOfRange(0, 32))
        val s = derInteger(signature.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }

    private fun derInteger(unsigned: ByteArray): ByteArray {
        val scalar = BigInteger(1, unsigned)
        require(scalar.signum() > 0 && scalar < P256.order) { "Invalid signature scalar" }
        val signed = scalar.toByteArray()
        return byteArrayOf(0x02, signed.size.toByte()) + signed
    }

    private fun requireP256(key: PublicKey) {
        require(key is ECPublicKey) { "Expected EC public key" }
        val parameters = key.params
        require(parameters != null && parameters.curve == P256.curve && parameters.generator == P256.generator &&
            parameters.order == P256.order && parameters.cofactor == P256.cofactor) { "Expected P-256 public key" }
    }

    private companion object {
        const val MAX_HEADER_LENGTH = 1024
        const val MAX_PAYLOAD_LENGTH = 8192
        const val MAX_TOKEN_LENGTH = MAX_HEADER_LENGTH + MAX_PAYLOAD_LENGTH + 88
        const val ISSUER = "https://megastrem.megastation.uk"
        const val AUDIENCE = "megastream-android"
        val BASE64URL = Regex("[A-Za-z0-9_-]+")
        val DECIMAL = Regex("0|[1-9][0-9]*")
        val P256: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        val CLAIM_NAMES = setOf(
            "iss", "aud", "sub", "credentialBinding", "iat", "nbf", "exp", "licenseStartsAt",
            "licenseEndsAt", "lid", "lrv", "jti", "policyVersion", "decision",
        )
        val DECISIONS = mapOf(
            "allowed" to LicenseAccessState.ALLOWED,
            "unlicensed" to LicenseAccessState.UNLICENSED,
            "not_started" to LicenseAccessState.NOT_STARTED,
            "expired" to LicenseAccessState.EXPIRED,
            "suspended" to LicenseAccessState.SUSPENDED,
            "revoked" to LicenseAccessState.REVOKED,
            "installation_disabled" to LicenseAccessState.INSTALLATION_DISABLED,
            "verification_required" to LicenseAccessState.VERIFICATION_REQUIRED,
        )
    }
}
