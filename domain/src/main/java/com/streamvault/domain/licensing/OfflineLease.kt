package com.MegaStream.domain.licensing

/** Claims must be authenticated and bound to this installation before evaluation. */
data class OfflineLease(
    val issuer: String,
    val audience: String,
    val installationId: String,
    val credentialBinding: String,
    val issuedAtEpochSeconds: Long,
    val notBeforeEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val licenseStartsAtEpochSeconds: Long,
    val licenseEndsAtEpochSeconds: Long,
    val licenseId: String,
    val licenseRevision: Long,
    val tokenId: String,
    val policyVersion: Int,
    val decision: LicenseAccessState
)
