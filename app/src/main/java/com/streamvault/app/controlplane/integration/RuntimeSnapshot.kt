package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.DevicePolicy
import com.MegaStream.app.controlplane.KioskMode
import com.MegaStream.domain.licensing.LicenseAccessDecision
import com.MegaStream.domain.licensing.LicenseAccessState

enum class RegistrationStatus { NOT_STARTED, REGISTERING, REGISTERED, RETRY_PENDING }
enum class PresenceStatus { NOT_STARTED, FOREGROUND, BACKGROUND, UNAVAILABLE, STOPPED }
enum class RuntimeControllerFailure {
    RECOVERY_FAILURE, SESSION_FAILURE, INSTALLATION_MISMATCH, INVALID_REQUEST, INVALID_RESPONSE,
    TRANSPORT_FAILURE, PERSISTENCE_FAILURE, ENTITLEMENT_FAILURE, SINK_FAILURE,
    DIAGNOSTICS_FAILURE, DIAGNOSTICS_BLOCKED, EXIT_FAILURE,
}

/** An observation, not authorization: protected actions must still recompute the entitlement gate. */
data class RuntimeSnapshot(
    val effectiveDecision: LicenseAccessDecision = LicenseAccessDecision(LicenseAccessState.VERIFICATION_REQUIRED),
    val devicePolicy: DevicePolicy = DevicePolicy(KioskMode.OFF, true),
    val registration: RegistrationStatus = RegistrationStatus.NOT_STARTED,
    val presence: PresenceStatus = PresenceStatus.NOT_STARTED,
    val lastSafeFailure: RuntimeControllerFailure? = null,
) {
    override fun toString(): String = "RuntimeSnapshot([REDACTED])"
}
