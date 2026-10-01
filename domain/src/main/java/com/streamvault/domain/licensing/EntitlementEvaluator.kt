package com.MegaStream.domain.licensing

class EntitlementEvaluator {
    fun evaluate(
        lease: OfflineLease?,
        trustedNowEpochSeconds: Long?,
        authenticatedDenial: LicenseAccessState? = null
    ): LicenseAccessDecision {
        if (authenticatedDenial != null && authenticatedDenial != LicenseAccessState.ALLOWED) {
            return LicenseAccessDecision(authenticatedDenial)
        }
        if (lease != null && lease.decision != LicenseAccessState.ALLOWED) {
            return LicenseAccessDecision(lease.decision)
        }
        if (lease == null || trustedNowEpochSeconds == null || trustedNowEpochSeconds <= 0 ||
            !hasValidTimes(lease) || trustedNowEpochSeconds < lease.issuedAtEpochSeconds
        ) return LicenseAccessDecision(LicenseAccessState.VERIFICATION_REQUIRED)
        return evaluateTimeWindow(lease, trustedNowEpochSeconds)
    }

    private fun evaluateTimeWindow(lease: OfflineLease, now: Long): LicenseAccessDecision {
        val deadline = offlineDeadline(lease)
            ?: return LicenseAccessDecision(LicenseAccessState.VERIFICATION_REQUIRED)
        val state = when {
            now >= deadline -> LicenseAccessState.VERIFICATION_REQUIRED
            now < lease.notBeforeEpochSeconds || now < lease.licenseStartsAtEpochSeconds ->
                LicenseAccessState.NOT_STARTED
            else -> LicenseAccessState.ALLOWED
        }
        return LicenseAccessDecision(state, deadline)
    }

    private fun hasValidTimes(lease: OfflineLease): Boolean =
        lease.issuedAtEpochSeconds > 0 && lease.notBeforeEpochSeconds > 0 &&
            lease.expiresAtEpochSeconds > 0 && lease.licenseStartsAtEpochSeconds > 0 &&
            lease.licenseEndsAtEpochSeconds > 0 &&
            lease.notBeforeEpochSeconds < lease.expiresAtEpochSeconds &&
            lease.issuedAtEpochSeconds < lease.expiresAtEpochSeconds &&
            lease.licenseStartsAtEpochSeconds < lease.licenseEndsAtEpochSeconds

    private fun offlineDeadline(lease: OfflineLease): Long? {
        if (lease.issuedAtEpochSeconds > Long.MAX_VALUE - OFFLINE_GRACE_SECONDS ||
            lease.licenseEndsAtEpochSeconds > Long.MAX_VALUE - OFFLINE_GRACE_SECONDS
        ) return null
        // Only an authenticated online denial ends an otherwise valid offline grace period early.
        return minOf(
            lease.issuedAtEpochSeconds + OFFLINE_GRACE_SECONDS,
            lease.licenseEndsAtEpochSeconds + OFFLINE_GRACE_SECONDS,
            lease.expiresAtEpochSeconds
        )
    }

    private companion object {
        const val OFFLINE_GRACE_SECONDS = 72L * 60 * 60
    }
}
