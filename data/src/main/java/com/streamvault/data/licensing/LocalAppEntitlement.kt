package com.MegaStream.data.licensing

import com.MegaStream.domain.licensing.AppEntitlement
import com.MegaStream.domain.licensing.ClockReading
import com.MegaStream.domain.licensing.EntitlementEvaluator
import com.MegaStream.domain.licensing.LicenseAccessDecision
import com.MegaStream.domain.licensing.LicenseAccessState
import com.MegaStream.domain.licensing.OfflineLease
import com.MegaStream.domain.licensing.OnlineEntitlementDecision
import com.MegaStream.domain.licensing.TrustedTimeAnchor
import com.MegaStream.domain.licensing.TrustedTimeEvaluator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay

/** Local-only authorization: no response or an invalid response never refreshes lease or anchor. */
class LocalAppEntitlement(
    private val verifier: OfflineLeaseVerifier,
    private val expectedInstallationId: String,
    private val expectedCredentialBinding: String,
    private val store: LocalEntitlementStateStore,
    private val clock: () -> ClockReading,
) : AppEntitlement {
    private val entitlementEvaluator = EntitlementEvaluator()
    private val trustedTimeEvaluator = TrustedTimeEvaluator()
    private val mutableDecision = MutableStateFlow(synchronized(store) {
        val reading = clock()
        evaluate(observeClock(store.read(), reading), reading)
    })
    override val decision: StateFlow<LicenseAccessDecision> = mutableDecision.asStateFlow()

    override suspend fun recordRefreshFailure(): LicenseAccessDecision = gate()

    /** Requires a fresh authenticated control-plane response bound to this request and installation. */
    override suspend fun applyOnlineDecision(
        decision: OnlineEntitlementDecision,
        compactLease: String?,
    ): LicenseAccessDecision {
        val candidate = if (decision.state == LicenseAccessState.ALLOWED) compactLease?.let(::verifyOrNull) else null
        if (candidate != null && matchesOnlineGrant(decision, candidate)) {
            val ahead = candidate.issuedAtEpochSeconds - clock().wallEpochSeconds
            // Let a slightly slow device clock catch up; never accept a future or unverified lease.
            if (ahead in 1..5) delay(ahead * 1000)
        }
        return synchronized(store) {
            val reading = clock()
            val previous = observeClock(store.read(), reading)
            val next = when {
                candidate != null && matchesOnlineGrant(decision, candidate) && canAccept(candidate, previous, reading) ->
                    acceptedSnapshot(candidate, previous, reading)
                decision.state != LicenseAccessState.ALLOWED && compactLease == null && freshOnlineDenial(decision, previous) ->
                    withOnlineDenial(previous, decision)
                else -> previous
            }
            if (next != previous) store.write(next)
            publish(evaluate(next, reading))
        }
    }

    private fun matchesOnlineGrant(online: OnlineEntitlementDecision, lease: OfflineLease): Boolean {
        val startsAt = online.startsAtEpochSeconds ?: return false
        val endsAt = online.endsAtEpochSeconds ?: return false
        return online.state == LicenseAccessState.ALLOWED && lease.decision == LicenseAccessState.ALLOWED &&
            online.serverEpochSeconds >= startsAt && online.serverEpochSeconds < endsAt &&
            online.serverEpochSeconds == lease.issuedAtEpochSeconds && online.licenseId == lease.licenseId &&
            online.licenseRevision == lease.licenseRevision &&
            startsAt == lease.licenseStartsAtEpochSeconds && endsAt == lease.licenseEndsAtEpochSeconds &&
            online.offlineUntilEpochSeconds == lease.expiresAtEpochSeconds
    }

    private fun freshOnlineDenial(online: OnlineEntitlementDecision, previous: LocalEntitlementSnapshot): Boolean {
        if (!terminalDenialPermits(previous.authenticatedDenial, online.state) ||
            !unknownDenialPermits(previous.onlineDenial, online.state)
        ) return false
        if (!validOnlineDenialMetadata(online) || !preservesDeniedIdentity(previous, online.licenseId, online.state)) return false
        val oldDenial = previous.onlineDenial
        val oldLease = previous.lease
        val latestEpoch = maxOf(oldDenial?.serverEpochSeconds ?: 0L, oldLease?.issuedAtEpochSeconds ?: 0L)
        if (online.serverEpochSeconds < latestEpoch) return false
        if (online.serverEpochSeconds == latestEpoch && previous.authenticatedDenial != null) return false
        val incomingRevision = online.licenseRevision
        if (online.licenseId != null && incomingRevision != null) {
            if (oldDenial != null && online.licenseId == oldDenial.licenseId &&
                incomingRevision < (oldDenial.licenseRevision ?: 0L)) return false
            if (oldLease != null && online.licenseId == oldLease.licenseId &&
                incomingRevision < oldLease.licenseRevision) return false
        }
        return true
    }

    private fun validOnlineDenialMetadata(online: OnlineEntitlementDecision): Boolean {
        if (online.serverEpochSeconds <= 0 || (online.licenseRevision ?: 0L) < 0 || online.licenseId?.isBlank() == true) return false
        if (online.state in setOf(LicenseAccessState.UNLICENSED, LicenseAccessState.INSTALLATION_DISABLED,
                LicenseAccessState.VERIFICATION_REQUIRED)) return true
        val revision = online.licenseRevision ?: return false
        val startsAt = online.startsAtEpochSeconds ?: return false
        val endsAt = online.endsAtEpochSeconds ?: return false
        return online.licenseId != null && revision > 0 && startsAt > 0 && endsAt > startsAt
    }

    private fun preservesDeniedIdentity(previous: LocalEntitlementSnapshot, licenseId: String?, next: LicenseAccessState): Boolean {
        if (previous.authenticatedDenial == null || next == LicenseAccessState.INSTALLATION_DISABLED) return true
        val deniedId = previous.onlineDenial?.licenseId ?: previous.lease?.licenseId
        return deniedId == null || licenseId == null || deniedId == licenseId
    }

    private fun withOnlineDenial(
        previous: LocalEntitlementSnapshot,
        online: OnlineEntitlementDecision,
    ): LocalEntitlementSnapshot {
        val effectiveId = online.licenseId ?: previous.onlineDenial?.licenseId ?: previous.lease?.licenseId
        val knownRevision = listOfNotNull(
            previous.onlineDenial?.takeIf { it.licenseId == effectiveId }?.licenseRevision,
            previous.lease?.takeIf { it.licenseId == effectiveId }?.licenseRevision,
        ).maxOrNull()
        val effective = online.copy(
            licenseId = effectiveId,
            licenseRevision = if (online.licenseId == null) listOfNotNull(online.licenseRevision, knownRevision).maxOrNull()
                else online.licenseRevision ?: knownRevision,
        )
        // A denial has no lease. Keep the signed lease and its original anchor unchanged.
        return previous.copy(authenticatedDenial = online.state, onlineDenial = effective)
    }

    override fun gate(): LicenseAccessDecision = synchronized(store) {
        val reading = clock()
        publish(evaluate(observeClock(store.read(), reading), reading))
    }

    private fun verifyOrNull(compactLease: String): OfflineLease? = try {
        verifier.verify(compactLease, expectedInstallationId, expectedCredentialBinding)
    } catch (_: IllegalArgumentException) {
        // Authentication failures are equivalent to no response, not a new authorization decision.
        null
    }

    private fun acceptedSnapshot(
        candidate: OfflineLease,
        previous: LocalEntitlementSnapshot,
        reading: ClockReading,
    ): LocalEntitlementSnapshot {
        val acceptanceNow = acceptanceTime(previous, reading)
        return LocalEntitlementSnapshot(
            lease = candidate,
            // Delivery delay and wall-clock advancement can shorten, never extend, a lease.
            anchor = TrustedTimeAnchor(
                acceptanceNow,
                reading.elapsedRealtimeMillis,
                reading.bootId,
            ),
            authenticatedDenial = null,
            lastClockReading = reading,
            verificationRequired = false,
            trustedTimeHighWaterEpochSeconds = acceptanceNow,
        )
    }

    private fun canAccept(
        candidate: OfflineLease,
        previous: LocalEntitlementSnapshot,
        reading: ClockReading,
    ): Boolean {
        if (reading.wallEpochSeconds <= 0 || reading.elapsedRealtimeMillis < 0 || reading.bootId.isBlank() ||
            candidate.issuedAtEpochSeconds > reading.wallEpochSeconds ||
            candidate.expiresAtEpochSeconds <= reading.wallEpochSeconds
        ) return false
        if (!terminalDenialPermits(previous.authenticatedDenial, candidate.decision) ||
            !unknownDenialPermits(previous.onlineDenial, candidate.decision)
        ) return false
        if (!preservesDeniedIdentity(previous, candidate.licenseId, candidate.decision) ||
            !respectsOnlineDenial(candidate, previous.onlineDenial)
        ) return false
        val oldLease = previous.lease
        if (oldLease != null && !isFreshResponse(candidate, oldLease)) return false
        return canRestoreAllowed(candidate, previous, reading)
    }

    private fun canRestoreAllowed(candidate: OfflineLease, previous: LocalEntitlementSnapshot, reading: ClockReading): Boolean {
        val acceptanceNow = acceptanceTime(previous, reading)
        if (reading.wallEpochSeconds < acceptanceNow) return false
        val deniedLicense = previous.onlineDenial?.licenseId ?: previous.lease?.licenseId
        if (previous.authenticatedDenial != null && deniedLicense != null && candidate.licenseId != deniedLicense) return false
        return (previous.authenticatedDenial == null && !previous.verificationRequired) ||
            entitlementEvaluator.evaluate(candidate, acceptanceNow).allowed
    }

    private fun respectsOnlineDenial(candidate: OfflineLease, denial: OnlineEntitlementDecision?): Boolean {
        if (denial == null) return true
        if (candidate.issuedAtEpochSeconds <= denial.serverEpochSeconds) return false
        if ((denial.licenseId == null || candidate.licenseId == denial.licenseId) &&
            candidate.licenseRevision < (denial.licenseRevision ?: 0L)
        ) return false
        return if (denial.licenseId == null) denial.state == LicenseAccessState.VERIFICATION_REQUIRED || denial.state == LicenseAccessState.UNLICENSED
            else candidate.licenseId == denial.licenseId
    }

    private fun unknownDenialPermits(denial: OnlineEntitlementDecision?, next: LicenseAccessState): Boolean =
        denial == null || denial.licenseId != null || denial.state == LicenseAccessState.VERIFICATION_REQUIRED ||
            denial.state == LicenseAccessState.UNLICENSED ||
            next == LicenseAccessState.REVOKED || next == LicenseAccessState.INSTALLATION_DISABLED

    private fun terminalDenialPermits(previous: LicenseAccessState?, next: LicenseAccessState): Boolean = when (previous) {
        LicenseAccessState.INSTALLATION_DISABLED -> false
        LicenseAccessState.REVOKED -> next == LicenseAccessState.INSTALLATION_DISABLED
        else -> true
    }

    private fun acceptanceTime(snapshot: LocalEntitlementSnapshot, reading: ClockReading): Long = maxOf(
        reading.wallEpochSeconds,
        snapshot.trustedTimeHighWaterEpochSeconds ?: 0L,
        trustedTimeEvaluator.evaluate(snapshot.anchor, reading) ?: 0L,
    )

    private fun isFreshResponse(candidate: OfflineLease, previous: OfflineLease): Boolean {
        if (candidate.tokenId == previous.tokenId) return false
        val sameLicense = candidate.licenseId == previous.licenseId
        if (sameLicense && candidate.licenseRevision < previous.licenseRevision) return false
        return candidate.issuedAtEpochSeconds > previous.issuedAtEpochSeconds
    }

    private fun observeClock(snapshot: LocalEntitlementSnapshot, reading: ClockReading): LocalEntitlementSnapshot {
        val last = snapshot.lastClockReading
        val rolledBack = last != null && (reading.bootId != last.bootId ||
            reading.wallEpochSeconds < last.wallEpochSeconds ||
            reading.elapsedRealtimeMillis < last.elapsedRealtimeMillis)
        val trustedNow = trustedTimeEvaluator.evaluate(snapshot.anchor, reading)
        val discontinuous = snapshot.anchor != null && trustedNow == null
        val observed = snapshot.copy(
            lastClockReading = reading,
            verificationRequired = snapshot.verificationRequired || rolledBack || discontinuous,
            trustedTimeHighWaterEpochSeconds = listOfNotNull(snapshot.trustedTimeHighWaterEpochSeconds, trustedNow).maxOrNull(),
        )
        // Persist uncertainty across process recreation; failures never replace lease/anchor/denial.
        if (observed != snapshot) store.write(observed)
        return observed
    }

    private fun evaluate(snapshot: LocalEntitlementSnapshot, reading: ClockReading): LicenseAccessDecision {
        val lease = snapshot.lease
        if (lease != null && (lease.installationId != expectedInstallationId ||
                lease.credentialBinding != expectedCredentialBinding)
        ) return LicenseAccessDecision(LicenseAccessState.VERIFICATION_REQUIRED)
        val trustedNow = if (snapshot.verificationRequired) null else
            trustedTimeEvaluator.evaluate(snapshot.anchor, reading)
        return entitlementEvaluator.evaluate(lease, trustedNow, snapshot.authenticatedDenial)
    }

    private fun publish(nextDecision: LicenseAccessDecision): LicenseAccessDecision {
        mutableDecision.value = nextDecision
        return nextDecision
    }
}
