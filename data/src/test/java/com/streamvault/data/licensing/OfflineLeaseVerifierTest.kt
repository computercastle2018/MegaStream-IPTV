package com.MegaStream.data.licensing

import com.MegaStream.domain.licensing.LicenseAccessState
import com.MegaStream.domain.licensing.OfflineLease
import com.google.common.truth.Truth.assertThat
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflineLeaseVerifierTest {
    private val keys = keyPair()
    private val verifier = OfflineLeaseVerifier(mapOf("test-key" to keys.public))

    @Test
    fun authenticatedClaimsMapEveryDomainFieldWithoutEvaluatingExpiry() {
        assertThat(verify(token())).isEqualTo(OfflineLease(
            issuer = "https://megastrem.megastation.uk",
            audience = "megastream-android",
            installationId = INSTALLATION,
            credentialBinding = BINDING,
            issuedAtEpochSeconds = 20,
            notBeforeEpochSeconds = 10,
            expiresAtEpochSeconds = 100,
            licenseStartsAtEpochSeconds = 0,
            licenseEndsAtEpochSeconds = 90,
            licenseId = LICENSE,
            licenseRevision = 2,
            tokenId = TOKEN_ID,
            policyVersion = 1,
            decision = LicenseAccessState.ALLOWED,
        ))
    }

    @Test
    fun allDecisionsAndArbitraryClaimOrderingAreAccepted() {
        for (decision in LicenseAccessState.entries) {
            val claims = JsonObject(payload().toMutableMap().apply {
                put("decision", JsonPrimitive(decision.name.lowercase()))
            }.entries.reversed().associate { it.toPair() })
            assertThat(verify(token(claims.toString())).decision).isEqualTo(decision)
        }
        assertThat(verify(token(header = "{\"kid\":\"test-key\",\"alg\":\"ES256\"}")).licenseRevision)
            .isEqualTo(2L)
    }

    @Test
    fun changedPayloadSignatureAndUnpinnedKeyAreRejected() {
        val original = token().split('.')
        rejects("${original[0]}.${encode(payload().toString().replace("\"lrv\":2", "\"lrv\":3").toByteArray())}.${original[2]}")
        val signature = Base64.getUrlDecoder().decode(original[2]).apply { this[0] = (this[0].toInt() xor 1).toByte() }
        rejects("${original[0]}.${original[1]}.${encode(signature)}")
        rejects(token(signingKeys = keyPair()))
    }

    @Test
    fun installationAndCredentialExpectationsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(token(), LICENSE, BINDING) }
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(token(), INSTALLATION, encode(ByteArray(32) { 1 })) }
    }

    @Test
    fun strictHeaderRejectsMissingUnknownAndMistypedFields() {
        val headers = listOf(
            "{\"alg\":\"none\",\"kid\":\"test-key\"}",
            "{\"alg\":\"HS256\",\"kid\":\"test-key\"}",
            "{\"alg\":\"ES256\"}",
            "{\"alg\":\"ES256\",\"kid\":\"unknown\"}",
            "{\"alg\":\"ES256\",\"kid\":\"\"}",
            "{\"alg\":\"ES256\",\"kid\":1}",
            "{\"alg\":\"ES256\",\"kid\":\"test-key\",\"typ\":\"JWS\"}",
            "{\"alg\":\"ES256\",\"kid\":\"test-key\",\"jku\":\"https://example.com\"}",
            "{\"alg\":\"ES256\",\"kid\":\"test-key\",\"alg\":\"ES256\"}",
        )
        headers.forEach { rejects(token(header = it)) }
    }

    @Test
    fun malformedClaimValuesAreRejectedEvenWithValidSignatures() {
        val invalid = mapOf(
            "iss" to listOf("\"https://example.com\"", "null"),
            "aud" to listOf("\"other\"", "[\"megastream-android\"]"),
            "sub" to listOf("\"1-1-1-1-1\"", "\"ABCDEF00-0000-0000-0000-000000000001\""),
            "credentialBinding" to listOf("\"short\"", "\"$BINDING=\"", "\"${encode(ByteArray(31))}\""),
            "lid" to listOf("\"bad-uuid\""),
            "jti" to listOf("\"1-1-1-1-1\""),
            "lrv" to listOf("0", "-1", "1.0", "\"1\""),
            "policyVersion" to listOf("0", "2", "4294967297", "\"1\""),
            "decision" to listOf("\"ALLOWED\"", "\"unknown\"", "null", "{}"),
        )
        for ((name, replacements) in invalid) {
            replacements.forEach { rejects(token(replaceClaim(name, it))) }
        }
    }

    @Test
    fun numericDatesRejectNoncanonicalRepresentationsAndOverflow() {
        val invalid = listOf("-1", "-0", "01", "1.0", "1e1", "1E1", "+1", "\"1\"", "null", "true", "9223372036854775808")
        for (name in listOf("iat", "nbf", "exp", "licenseStartsAt", "licenseEndsAt")) {
            invalid.forEach { rejects(token(replaceClaim(name, it))) }
        }
        assertThat(verify(token(replaceClaim("exp", Long.MAX_VALUE.toString()))).expiresAtEpochSeconds)
            .isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun invalidIntervalsAreRejectedButRefreshedNotBeforeAndOfflineGraceAreValid() {
        for ((name, replacement) in listOf("iat" to "100", "iat" to "101", "nbf" to "100", "nbf" to "101", "licenseStartsAt" to "90", "licenseStartsAt" to "91")) {
            rejects(token(replaceClaim(name, replacement)))
        }
        val lease = verify(token())
        assertThat(lease.notBeforeEpochSeconds).isLessThan(lease.issuedAtEpochSeconds)
        assertThat(lease.expiresAtEpochSeconds).isGreaterThan(lease.licenseEndsAtEpochSeconds)
    }

    @Test
    fun duplicateMissingUnknownAndNoncanonicalJsonAreRejected() {
        val canonical = payload().toString()
        val invalid = listOf(
            canonical.dropLast(1) + ",\"iat\":20}",
            canonical.dropLast(1) + ",\"extra\":1}",
            canonical.replace("\"iat\":20,", ""),
            canonical.replace("\"iat\":20", "\"iat\": 20"),
            canonical.replace("allowed", "\\u0061llowed"),
            canonical.dropLast(1) + ",}",
            "[]", "null", "{", " " + canonical,
        )
        invalid.forEach { rejects(token(it)) }
        val malformedUtf8 = canonical.toByteArray() + byteArrayOf(0xc0.toByte(), 0xaf.toByte())
        rejects(signedSegments(encode(HEADER.toByteArray()), encode(malformedUtf8), keys))
    }

    @Test
    fun compactSegmentsRejectPaddingNoncanonicalBitsAndOversizeInputs() {
        val parts = token().split('.')
        for (index in parts.indices) {
            rejects(parts.toMutableList().apply { this[index] += "=" }.joinToString("."))
            rejects(parts.toMutableList().apply { this[index] = "" }.joinToString("."))
        }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val signature = parts[2]
        val alternate = signature.dropLast(1) + alphabet[alphabet.indexOf(signature.last()) + 1]
        rejects("${parts[0]}.${parts[1]}.$alternate")
        listOf("", "a.b", "a.b.c.d", "a".repeat(10_000), "a.%.b", "a.+/.b").forEach(::rejects)
        rejects(token(header = "{\"alg\":\"ES256\",\"kid\":\"${"a".repeat(1024)}\"}"))
        rejects(token(replaceClaim("iss", "\"${"a".repeat(8192)}\"")))
    }

    @Test
    fun zeroOutOfRangeAndWrongLengthSignatureScalarsAreRejected() {
        val parts = token().split('.')
        val order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
            .toByteArray().takeLast(32).toByteArray()
        val one = ByteArray(32).apply { this[31] = 1 }
        val signatures = listOf(ByteArray(63), ByteArray(65), ByteArray(64), ByteArray(32) + one,
            one + ByteArray(32), order + one, one + order, ByteArray(32) { 0xff.toByte() } + one)
        signatures.forEach { rejects("${parts[0]}.${parts[1]}.${encode(it)}") }
    }

    @Test
    fun pinnedKeysMustBeP256NotOtherCurvesOrAlgorithms() {
        for (curve in listOf("secp384r1", "secp521r1")) {
            assertThrows(IllegalArgumentException::class.java) { OfflineLeaseVerifier(mapOf("test-key" to keyPair(curve).public)) }
        }
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) { OfflineLeaseVerifier(mapOf("test-key" to rsa.public)) }
        assertThrows(IllegalArgumentException::class.java) { OfflineLeaseVerifier(emptyMap()).verify(token(), INSTALLATION, BINDING) }
    }

    private fun verify(compact: String) = verifier.verify(compact, INSTALLATION, BINDING)

    private fun rejects(compact: String) {
        assertThrows(IllegalArgumentException::class.java) { verify(compact) }
    }

    private fun replaceClaim(name: String, replacement: String): String {
        val claims = payload()
        return claims.toString().replace("\"$name\":${claims.getValue(name)}", "\"$name\":$replacement")
    }

    private fun payload() = buildJsonObject {
        put("iss", "https://megastrem.megastation.uk")
        put("aud", "megastream-android")
        put("sub", INSTALLATION)
        put("credentialBinding", BINDING)
        put("iat", 20)
        put("nbf", 10)
        put("exp", 100)
        put("licenseStartsAt", 0)
        put("licenseEndsAt", 90)
        put("lid", LICENSE)
        put("lrv", 2)
        put("jti", TOKEN_ID)
        put("policyVersion", 1)
        put("decision", "allowed")
    }

    private fun token(payload: String = payload().toString(), header: String = HEADER, signingKeys: KeyPair = keys): String =
        signedSegments(encode(header.toByteArray(Charsets.UTF_8)), encode(payload.toByteArray(Charsets.UTF_8)), signingKeys)

    private fun signedSegments(header: String, payload: String, signingKeys: KeyPair): String {
        val input = "$header.$payload"
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(signingKeys.private)
            update(input.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$input.${encode(rawSignature(der))}"
    }

    private fun rawSignature(der: ByteArray): ByteArray {
        val raw = ByteArray(64)
        var offset = 2
        for (index in 0..1) {
            check(der[offset++].toInt() == 2)
            val length = der[offset++].toInt() and 0xff
            val unsigned = BigInteger(der.copyOfRange(offset, offset + length)).toByteArray().takeLast(32).toByteArray()
            unsigned.copyInto(raw, index * 32 + 32 - unsigned.size)
            offset += length
        }
        return raw
    }

    private fun keyPair(curve: String = "secp256r1") = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(curve))
    }.generateKeyPair()

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val INSTALLATION = "10000000-0000-0000-0000-000000000001"
        const val LICENSE = "20000000-0000-0000-0000-000000000002"
        const val TOKEN_ID = "30000000-0000-0000-0000-000000000003"
        const val HEADER = "{\"alg\":\"ES256\",\"kid\":\"test-key\",\"typ\":\"JWT\"}"
        val BINDING: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
    }
}
