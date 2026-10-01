package com.MegaStream.domain.licensing

import kotlinx.coroutines.flow.StateFlow

interface AppEntitlement {
    /**
     * Last evaluated observation, not timer-driven authorization.
     * Call [gate] immediately before every protected action to recompute trusted-time expiry.
     */
    val decision: StateFlow<LicenseAccessDecision>

    /** Preserves the current lease on refresh failure. */
    suspend fun recordRefreshFailure(): LicenseAccessDecision

    /**
     * [decision] must come only from a fresh authenticated control-plane response bound to the
     * request and installation. Compact imports and cached tokens are not online proof.
     * The server does not mint denial JWTs.
     */
    suspend fun applyOnlineDecision(
        decision: OnlineEntitlementDecision,
        compactLease: String?
    ): LicenseAccessDecision

    /**
     * Must recompute trusted-time expiry; callers must invoke this immediately before every
     * protected action rather than authorize from a previously observed [decision].
     */
    fun gate(): LicenseAccessDecision
}
