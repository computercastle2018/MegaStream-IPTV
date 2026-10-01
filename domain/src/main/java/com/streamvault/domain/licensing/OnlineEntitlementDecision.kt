package com.MegaStream.domain.licensing

/**
 * May only come from authenticated transport; the server does not mint denial JWTs.
 * License metadata may be null for UNLICENSED, INSTALLATION_DISABLED, or VERIFICATION_REQUIRED.
 */
data class OnlineEntitlementDecision(
    val state: LicenseAccessState,
    val serverEpochSeconds: Long,
    val licenseId: String?,
    val licenseRevision: Long?,
    val startsAtEpochSeconds: Long?,
    val endsAtEpochSeconds: Long?,
    val offlineUntilEpochSeconds: Long?
)
