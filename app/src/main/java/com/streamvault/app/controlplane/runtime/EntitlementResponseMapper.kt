package com.MegaStream.app.controlplane.runtime

import com.MegaStream.app.controlplane.EntitlementResponse
import com.MegaStream.app.controlplane.EntitlementState
import com.MegaStream.domain.licensing.LicenseAccessState
import com.MegaStream.domain.licensing.OnlineEntitlementDecision
import java.time.Instant
import java.util.concurrent.CancellationException

/** Semantic validation only, never lease parsing or verification. Input must come from the
 * authenticated, request-bound runtime client. Invalid data returns null, not an online denial.
 */
class EntitlementResponseMapper {
    fun map(response: EntitlementResponse): OnlineEntitlementDecision? = try {
        val source = response.decision
        val instants = TimeWindow(Instant.parse(response.serverTime), source.startsAt?.let(Instant::parse),
            source.endsAt?.let(Instant::parse), source.offlineUntil?.let(Instant::parse))
        instants.validate(source.state)
        val epochs = TimeWindow(seconds(instants.server), instants.start?.let(::seconds),
            instants.end?.let(::seconds), instants.offline?.let(::seconds))
        epochs.validate(source.state)
        validateEpochMetadata(response, epochs)
        OnlineEntitlementDecision(domainState(source.state), epochs.server, source.licenseId,
            source.licenseRevision, epochs.start, epochs.end, epochs.offline)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.time.DateTimeException) {
        null
    }

    private fun validateEpochMetadata(response: EntitlementResponse, epochs: TimeWindow<Long>) {
        val source = response.decision
        require(epochs.server > 0)
        require(response.refreshAfterSeconds <= Long.MAX_VALUE / 1000)
        if (source.state in LICENSE_STATES) {
            require(source.licenseId != null && source.licenseRevision != null)
            require(epochs.start != null && epochs.start > 0 && epochs.end != null)
        }
        require((source.state == EntitlementState.ALLOWED) == (epochs.offline != null))
        require((source.state == EntitlementState.ALLOWED) == (response.lease != null))
    }

    /** Both original Instants and floored epoch seconds must describe a valid window. */
    private class TimeWindow<T : Comparable<T>>(val server: T, val start: T?, val end: T?, val offline: T?) {
        fun validate(state: EntitlementState) {
            if (start != null && end != null) require(start < end)
            when (state) {
                EntitlementState.ALLOWED -> require(start != null && end != null && offline != null &&
                    start <= server && server < end && offline > server)
                EntitlementState.NOT_STARTED -> require(start != null && server < start)
                EntitlementState.EXPIRED -> require(end != null && server >= end)
                else -> Unit
            }
        }
    }

    private fun domainState(state: EntitlementState): LicenseAccessState = when (state) {
        EntitlementState.ALLOWED -> LicenseAccessState.ALLOWED
        EntitlementState.NOT_STARTED -> LicenseAccessState.NOT_STARTED
        EntitlementState.EXPIRED -> LicenseAccessState.EXPIRED
        EntitlementState.SUSPENDED -> LicenseAccessState.SUSPENDED
        EntitlementState.REVOKED -> LicenseAccessState.REVOKED
        EntitlementState.UNLICENSED -> LicenseAccessState.UNLICENSED
        EntitlementState.INSTALLATION_DISABLED -> LicenseAccessState.INSTALLATION_DISABLED
        EntitlementState.VERIFICATION_REQUIRED -> LicenseAccessState.VERIFICATION_REQUIRED
    }

    private fun seconds(instant: Instant): Long {
        val seconds = instant.epochSecond
        // Bound before any millisecond conversion; year 9999 is also safely below Long overflow.
        require(seconds in 0..MAX_EPOCH_SECONDS)
        require(seconds <= Long.MAX_VALUE / 1000)
        return seconds
    }

    private companion object {
        const val MAX_EPOCH_SECONDS = 253402300799L
        val LICENSE_STATES = setOf(EntitlementState.ALLOWED, EntitlementState.NOT_STARTED,
            EntitlementState.EXPIRED, EntitlementState.SUSPENDED, EntitlementState.REVOKED)
    }
}
