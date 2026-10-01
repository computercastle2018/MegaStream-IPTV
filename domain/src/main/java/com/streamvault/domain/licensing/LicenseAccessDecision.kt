package com.MegaStream.domain.licensing

data class LicenseAccessDecision(
    val state: LicenseAccessState,
    val offlineDeadlineEpochSeconds: Long? = null
) {
    val allowed: Boolean get() = state == LicenseAccessState.ALLOWED
}
