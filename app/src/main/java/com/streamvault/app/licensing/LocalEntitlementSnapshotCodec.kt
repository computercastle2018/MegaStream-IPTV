package com.MegaStream.app.licensing

import com.MegaStream.data.licensing.LocalEntitlementSnapshot
import com.MegaStream.domain.licensing.ClockReading
import com.MegaStream.domain.licensing.LicenseAccessState
import com.MegaStream.domain.licensing.OfflineLease
import com.MegaStream.domain.licensing.OnlineEntitlementDecision
import com.MegaStream.domain.licensing.TrustedTimeAnchor
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

/** Versioned storage for decoded claim metadata; no credential or compact-token field. */
internal object LocalEntitlementSnapshotCodec {
    const val MAX_BYTES = 65_536
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 4_096

    fun encode(snapshot: LocalEntitlementSnapshot): ByteArray {
        validate(snapshot)
        // Fixed allocation prevents a hostile model from growing an unbounded backing buffer.
        val buffer = ByteBuffer.allocate(MAX_BYTES)
        return try {
            buffer.putInt(VERSION)
            Writer(buffer).apply {
                optional(snapshot.lease) { lease(it) }
                optional(snapshot.anchor) { long(it.serverEpochSeconds); long(it.elapsedRealtimeMillis); text(it.bootId) }
                optional(snapshot.authenticatedDenial) { state(it) }
                optional(snapshot.lastClockReading) { long(it.wallEpochSeconds); long(it.elapsedRealtimeMillis); text(it.bootId) }
                bool(snapshot.verificationRequired)
                optional(snapshot.trustedTimeHighWaterEpochSeconds) { long(it) }
                optional(snapshot.onlineDenial) { onlineDecision(it) }
            }
            buffer.array().copyOf(buffer.position())
        } finally {
            buffer.array().fill(0)
        }
    }

    fun decode(bytes: ByteArray): LocalEntitlementSnapshot {
        require(bytes.size in 4..MAX_BYTES)
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.int == VERSION)
        val reader = Reader(buffer)
        val result = with(reader) {
            LocalEntitlementSnapshot(
                lease = optional {
                    OfflineLease(text(), text(), text(), text(), long(), long(), long(), long(), long(),
                        text(), long(), text(), buffer.int, state())
                },
                anchor = optional { TrustedTimeAnchor(long(), long(), text()) },
                authenticatedDenial = optional { state() },
                lastClockReading = optional { ClockReading(long(), long(), text()) },
                verificationRequired = bool(),
                trustedTimeHighWaterEpochSeconds = optional { long() },
                onlineDenial = optional {
                    OnlineEntitlementDecision(state(), long(), optional { text() }, optional { long() },
                        optional { long() }, optional { long() }, optional { long() })
                },
            )
        }
        require(!buffer.hasRemaining())
        validate(result)
        return result
    }

    private class Writer(private val buffer: ByteBuffer) {
        fun lease(lease: OfflineLease) {
            text(lease.issuer)
            text(lease.audience)
            text(lease.installationId)
            text(lease.credentialBinding)
            long(lease.issuedAtEpochSeconds)
            long(lease.notBeforeEpochSeconds)
            long(lease.expiresAtEpochSeconds)
            long(lease.licenseStartsAtEpochSeconds)
            long(lease.licenseEndsAtEpochSeconds)
            text(lease.licenseId)
            long(lease.licenseRevision)
            text(lease.tokenId)
            buffer.putInt(lease.policyVersion)
            state(lease.decision)
        }
        fun onlineDecision(decision: OnlineEntitlementDecision) {
            state(decision.state)
            long(decision.serverEpochSeconds)
            optional(decision.licenseId) { text(it) }
            optional(decision.licenseRevision) { long(it) }
            optional(decision.startsAtEpochSeconds) { long(it) }
            optional(decision.endsAtEpochSeconds) { long(it) }
            optional(decision.offlineUntilEpochSeconds) { long(it) }
        }
        fun bool(value: Boolean) { buffer.put(if (value) 1.toByte() else 0.toByte()) }
        fun long(value: Long) { buffer.putLong(value) }
        fun state(value: LicenseAccessState) { text(value.name) }
        fun <T> optional(value: T?, body: (T) -> Unit) { bool(value != null); if (value != null) body(value) }
        fun text(value: String) {
            require(value.length <= MAX_STRING_BYTES)
            val sizePosition = buffer.position()
            buffer.putInt(0)
            val start = buffer.position()
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val oldLimit = buffer.limit()
            buffer.limit(minOf(oldLimit, start + MAX_STRING_BYTES))
            try {
                val encoded = encoder.encode(CharBuffer.wrap(value), buffer, true)
                if (encoded.isError) encoded.throwException()
                require(encoded.isUnderflow)
                val flushed = encoder.flush(buffer)
                if (flushed.isError) flushed.throwException()
                require(flushed.isUnderflow)
            } finally { buffer.limit(oldLimit) }
            buffer.putInt(sizePosition, buffer.position() - start)
        }
    }

    private class Reader(private val buffer: ByteBuffer) {
        fun bool(): Boolean = when (buffer.get().toInt()) { 0 -> false; 1 -> true; else -> error("Invalid storage format") }
        fun long(): Long = buffer.long
        fun state(): LicenseAccessState = LicenseAccessState.valueOf(text())
        fun <T> optional(body: () -> T): T? = if (bool()) body() else null
        fun text(): String {
            val size = buffer.int
            require(size in 0..MAX_STRING_BYTES && size <= buffer.remaining())
            val view = buffer.slice().apply { limit(size) }
            // Explicit backing storage permits wiping even when malformed UTF-8 fails mid-decode.
            val decoded = CharBuffer.allocate(size)
            return try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val decodedStatus = decoder.decode(view, decoded, true)
                if (decodedStatus.isError) decodedStatus.throwException()
                require(decodedStatus.isUnderflow)
                val flushed = decoder.flush(decoded)
                if (flushed.isError) flushed.throwException()
                require(flushed.isUnderflow)
                decoded.flip()
                decoded.toString()
            } finally {
                decoded.array().fill('\u0000')
                buffer.position(buffer.position() + size)
            }
        }
    }

    private fun validate(snapshot: LocalEntitlementSnapshot) {
        validateDenialStates(snapshot)
        snapshot.lease?.let { validateLease(it) }
        snapshot.anchor?.let {
            nonnegative(it.serverEpochSeconds)
            nonnegative(it.elapsedRealtimeMillis)
            nonblankText(it.bootId)
        }
        snapshot.lastClockReading?.let {
            nonnegative(it.wallEpochSeconds)
            nonnegative(it.elapsedRealtimeMillis)
            nonblankText(it.bootId)
        }
        snapshot.trustedTimeHighWaterEpochSeconds?.let { nonnegative(it) }
        snapshot.onlineDenial?.let { validateOnlineDecision(it) }
    }

    private fun validateDenialStates(snapshot: LocalEntitlementSnapshot) {
        val authenticated = snapshot.authenticatedDenial
        val online = snapshot.onlineDenial?.state
        require(authenticated != LicenseAccessState.ALLOWED)
        require(online != LicenseAccessState.ALLOWED)
        if (authenticated != null && online != null) require(authenticated == online)
    }

    private fun validateLease(lease: OfflineLease) {
        nonblankText(lease.issuer)
        nonblankText(lease.audience)
        canonicalUuid(lease.installationId)
        canonicalUuid(lease.licenseId)
        canonicalUuid(lease.tokenId)
        validateBinding(lease.credentialBinding)
        nonnegative(lease.issuedAtEpochSeconds)
        nonnegative(lease.notBeforeEpochSeconds)
        nonnegative(lease.expiresAtEpochSeconds)
        nonnegative(lease.licenseStartsAtEpochSeconds)
        nonnegative(lease.licenseEndsAtEpochSeconds)
        require(lease.licenseRevision > 0 && lease.policyVersion == 1)
        require(lease.issuedAtEpochSeconds < lease.expiresAtEpochSeconds)
        require(lease.notBeforeEpochSeconds < lease.expiresAtEpochSeconds)
        require(lease.licenseStartsAtEpochSeconds < lease.licenseEndsAtEpochSeconds)
    }

    private fun validateOnlineDecision(decision: OnlineEntitlementDecision) {
        nonnegative(decision.serverEpochSeconds)
        decision.licenseId?.let { canonicalUuid(it) }
        decision.licenseRevision?.let { nonnegative(it) }
        decision.startsAtEpochSeconds?.let { nonnegative(it) }
        decision.endsAtEpochSeconds?.let { nonnegative(it) }
        decision.offlineUntilEpochSeconds?.let { nonnegative(it) }
        val startsAt = decision.startsAtEpochSeconds
        val endsAt = decision.endsAtEpochSeconds
        if (startsAt != null && endsAt != null) require(startsAt < endsAt)
    }

    private fun validateBinding(encodedBinding: String) {
        require(encodedBinding.length == 43 && encodedBinding.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
        })
        val binding = Base64.getUrlDecoder().decode(encodedBinding)
        try {
            require(binding.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(binding) == encodedBinding)
        } finally { binding.fill(0) }
    }

    private fun nonnegative(number: Long) { require(number >= 0) }
    private fun canonicalUuid(identifier: String) {
        require(identifier.length == 36 && UUID.fromString(identifier).toString() == identifier)
    }
    private fun nonblankText(text: String) { require(text.isNotBlank() && text.length <= MAX_STRING_BYTES) }
}
