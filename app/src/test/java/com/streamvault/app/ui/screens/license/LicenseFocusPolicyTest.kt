package com.MegaStream.app.ui.screens.license

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.MegaStream.app.controlplane.EntitlementState
import com.MegaStream.domain.licensing.LicenseAccessState

class LicenseFocusPolicyTest {
    @Test
    fun verifiedPlayback_hasNoInitialOrRecoveryFocus_evenWhileBusyOrRetryable() {
        for (mode in LicenseActivationMode.entries) {
            for (busy in listOf(false, true)) {
                val state = LicenseActivationState(
                    entitlement = LicenseEntitlementSnapshot(LicenseAccessState.ALLOWED),
                    mode = mode, busy = busy, pollStatus = LicenseActivationPollStatus.RETRYABLE_FAILURE,
                )
                assertThat(LicenseFocusPolicy.order(state)).isEmpty()
                assertThat(LicenseFocusPolicy.initialFocus(state)).isNull()
                assertThat(LicenseFocusPolicy.recoveryTarget(state)).isNull()
            }
        }
    }

    @Test
    fun transportAcceptance_keepsActivationControlsUntilIndependentlyVerified() {
        val state = LicenseActivationState(transportState = EntitlementState.ALLOWED)
        assertThat(LicenseFocusPolicy.initialFocus(state)).isEqualTo(LicenseFocusTarget.DIRECT_CHOICE)
        assertThat(LicenseFocusPolicy.order(state)).contains(LicenseFocusTarget.KEY)
        assertThat(LicenseFocusPolicy.recoveryTarget(state)).isEqualTo(LicenseFocusTarget.KEY)
        assertThat(LicenseFocusPolicy.initialFocus(state.copy(disposed = true))).isNull()
    }

    @Test
    fun directEntry_startsWithDirectChoice_thenCodeChoice_keyAndSubmit() {
        assertThat(LicenseFocusPolicy.order(LicenseActivationState())).containsExactly(
            LicenseFocusTarget.DIRECT_CHOICE, LicenseFocusTarget.CODE_CHOICE,
            LicenseFocusTarget.KEY, LicenseFocusTarget.SUBMIT,
        ).inOrder()
    }

    @Test
    fun codeEntry_routesToRequest_thenRetryAndStopOnlyWhenRetryable() {
        for (status in LicenseActivationPollStatus.entries) {
            val state = LicenseActivationState(mode = LicenseActivationMode.ACTIVATION_CODE, pollStatus = status)
            val expected = mutableListOf(LicenseFocusTarget.DIRECT_CHOICE,
                LicenseFocusTarget.CODE_CHOICE, LicenseFocusTarget.REQUEST)
            if (status == LicenseActivationPollStatus.RETRYABLE_FAILURE) {
                expected.add(LicenseFocusTarget.RETRY)
                expected.add(LicenseFocusTarget.STOP)
            }
            assertThat(LicenseFocusPolicy.order(state)).containsExactlyElementsIn(expected).inOrder()
        }
    }

    @Test
    fun busyOperation_skipsDisabledFieldsAndRequestButtons_butKeepsStopReachable() {
        for (mode in LicenseActivationMode.entries) {
            assertThat(LicenseFocusPolicy.order(LicenseActivationState(mode = mode, busy = true)))
                .containsExactly(LicenseFocusTarget.DIRECT_CHOICE, LicenseFocusTarget.CODE_CHOICE,
                    LicenseFocusTarget.STOP).inOrder()
        }
    }

    @Test
    fun disappearingControl_recoversToAnEnabledActionForEveryModeAndStatus() {
        for (mode in LicenseActivationMode.entries) {
            for (status in LicenseActivationPollStatus.entries) {
                for (busy in listOf(false, true)) {
                    val state = LicenseActivationState(mode = mode, pollStatus = status, busy = busy)
                    val expected = when {
                        busy -> LicenseFocusTarget.STOP
                        mode == LicenseActivationMode.DIRECT_KEY -> LicenseFocusTarget.KEY
                        status == LicenseActivationPollStatus.RETRYABLE_FAILURE -> LicenseFocusTarget.RETRY
                        else -> LicenseFocusTarget.REQUEST
                    }
                    assertThat(LicenseFocusPolicy.recoveryTarget(state)).isEqualTo(expected)
                    assertThat(LicenseFocusPolicy.order(state)).contains(expected)
                }
            }
        }
        assertThat(LicenseFocusPolicy.recoveryTarget(LicenseActivationState(disposed = true))).isNull()
    }

    @Test
    fun disposedScreen_hasNoInteractiveFocusTargets() {
        for (mode in LicenseActivationMode.entries) {
            assertThat(LicenseFocusPolicy.order(LicenseActivationState(mode = mode, disposed = true)))
                .isEmpty()
        }
    }
}
