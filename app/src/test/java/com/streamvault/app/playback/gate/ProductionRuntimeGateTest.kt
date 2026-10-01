package com.MegaStream.app.playback.gate

import com.MegaStream.app.controlplane.integration.PresenceStatus
import com.MegaStream.app.controlplane.integration.RuntimeControllerFailure
import com.MegaStream.app.controlplane.integration.RuntimeSnapshot
import com.MegaStream.app.controlplane.integration.combinePlaybackDecisions
import com.MegaStream.domain.licensing.LicenseAccessDecision
import com.MegaStream.domain.licensing.LicenseAccessState
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductionRuntimeGateTest {
    private val allowed = LicenseAccessDecision(LicenseAccessState.ALLOWED, 42)
    private fun runtime(state: LicenseAccessState) = RuntimeSnapshot(
        effectiveDecision = LicenseAccessDecision(state), presence = PresenceStatus.FOREGROUND,
    )

    @Test fun signedLocalActivationOverridesOnlyStaleUnlicensed() {
        assertEquals(allowed, combinePlaybackDecisions(allowed, runtime(LicenseAccessState.UNLICENSED)))
    }

    @Test fun allSubstantiveRuntimeDenialsOverrideLocalAllow() {
        listOf(LicenseAccessState.REVOKED, LicenseAccessState.INSTALLATION_DISABLED,
            LicenseAccessState.EXPIRED, LicenseAccessState.SUSPENDED, LicenseAccessState.NOT_STARTED,
            LicenseAccessState.VERIFICATION_REQUIRED).forEach { reason ->
            assertEquals(reason, combinePlaybackDecisions(allowed, runtime(reason)).state)
        }
    }

    @Test fun freshLocalExpiryOverridesRuntimeAllow() {
        val expired = LicenseAccessDecision(LicenseAccessState.EXPIRED)
        assertEquals(expired, combinePlaybackDecisions(expired, runtime(LicenseAccessState.ALLOWED)))
    }

    @Test fun startupAndStoppedPresenceCannotAuthorize() {
        listOf(PresenceStatus.NOT_STARTED, PresenceStatus.STOPPED).forEach { presence ->
            assertEquals(LicenseAccessState.VERIFICATION_REQUIRED,
                combinePlaybackDecisions(allowed, runtime(LicenseAccessState.ALLOWED).copy(presence = presence)).state)
        }
    }

    @Test fun hardRuntimeFailuresCannotBeClearedByActivation() {
        listOf(RuntimeControllerFailure.RECOVERY_FAILURE, RuntimeControllerFailure.SESSION_FAILURE,
            RuntimeControllerFailure.INSTALLATION_MISMATCH, RuntimeControllerFailure.PERSISTENCE_FAILURE,
            RuntimeControllerFailure.ENTITLEMENT_FAILURE).forEach { failure ->
            assertEquals(LicenseAccessState.VERIFICATION_REQUIRED,
                combinePlaybackDecisions(allowed, runtime(LicenseAccessState.UNLICENSED).copy(lastSafeFailure = failure)).state)
        }
    }

    @Test fun offlineTransportAndTelemetryDoNotInvalidateFreshLocalLease() {
        listOf(RuntimeControllerFailure.TRANSPORT_FAILURE, RuntimeControllerFailure.DIAGNOSTICS_FAILURE,
            RuntimeControllerFailure.DIAGNOSTICS_BLOCKED).forEach { failure ->
            assertEquals(allowed, combinePlaybackDecisions(allowed,
                runtime(LicenseAccessState.ALLOWED).copy(lastSafeFailure = failure, presence = PresenceStatus.UNAVAILABLE)))
        }
    }
}
