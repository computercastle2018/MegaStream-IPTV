package com.MegaStream.app.ui.screens.license

import com.MegaStream.app.controlplane.EntitlementState
import com.MegaStream.domain.licensing.LicenseAccessState

/**
 * Caller-supplied, independently verified domain entitlement, never a control-plane response.
 * expiresAtEpochSeconds is the verified license expiry, not the activation code's expiry.
 */
data class LicenseEntitlementSnapshot(
    val state: LicenseAccessState = LicenseAccessState.UNLICENSED,
    val expiresAtEpochSeconds: Long? = null,
)

enum class LicenseActivationMode { DIRECT_KEY, ACTIVATION_CODE }

enum class LicenseActivationPollStatus {
    IDLE, REQUESTING, POLLING, WAITING, RETRYABLE_FAILURE,
    ACTIVATED, EXPIRED, EXHAUSTED, STOPPED,
}

enum class LicenseActivationError {
    INVALID_KEY, NETWORK, TIMEOUT, RATE_LIMITED, REJECTED, INVALID_RESPONSE, UNKNOWN,
}

data class LicenseActivationState(
    val entitlement: LicenseEntitlementSnapshot = LicenseEntitlementSnapshot(),
    val mode: LicenseActivationMode = LicenseActivationMode.DIRECT_KEY,
    val busy: Boolean = false,
    val pollStatus: LicenseActivationPollStatus = LicenseActivationPollStatus.IDLE,
    val activationCode: String? = null,
    val codeExpiresAtEpochMillis: Long? = null,
    val pollAttempts: Int = 0,
    val error: LicenseActivationError? = null,
    val transportState: EntitlementState? = null,
    val disposed: Boolean = false,
) {
    /** UI indication only, not authorization: protected actions must use AppEntitlement.gate. */
    val playbackUnblocked: Boolean
        get() = entitlement.state == LicenseAccessState.ALLOWED && !disposed

    val canRetryPolling: Boolean
        get() = !disposed && !busy && pollStatus == LicenseActivationPollStatus.RETRYABLE_FAILURE
}
