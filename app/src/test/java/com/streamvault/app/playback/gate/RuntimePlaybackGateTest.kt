package com.MegaStream.app.playback.gate

import com.MegaStream.domain.licensing.LicenseAccessDecision
import com.MegaStream.domain.licensing.LicenseAccessState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimePlaybackGateTest {
    @Test fun initialObservationFailsClosedBeforeEvaluation() = runTest {
        val gate = RuntimePlaybackGate({ allowed() }, emptyFlow<Unit>(), backgroundScope)
        assertEquals(LicenseAccessState.VERIFICATION_REQUIRED, (gate.decision.value as PlaybackGateVerdict.Blocked).reason)
    }

    @Test fun everyCheckReadsFreshAuthorization() = runTest {
        var calls = 0
        val gate = RuntimePlaybackGate({ calls++; allowed() }, emptyFlow<Unit>(), backgroundScope)
        gate.checkNow(); gate.checkNow()
        assertEquals(2, calls)
    }

    @Test fun staleAllowedObservationCannotAuthorize() = runTest {
        var current = allowed()
        val gate = RuntimePlaybackGate({ current }, emptyFlow<Unit>(), backgroundScope)
        gate.checkNow()
        current = LicenseAccessDecision(LicenseAccessState.REVOKED)
        assertEquals(LicenseAccessState.REVOKED, (gate.checkNow() as PlaybackGateVerdict.Blocked).reason)
    }

    @Test fun timerReevaluatesDeadlineWithoutAnyObservation() = runTest {
        val gate = RuntimePlaybackGate({
            if (testScheduler.currentTime < 2_000) allowed() else LicenseAccessDecision(LicenseAccessState.EXPIRED)
        }, emptyFlow<Unit>(), backgroundScope)
        gate.checkNow(); runCurrent()
        advanceTimeBy(1_999); runCurrent()
        assertSame(PlaybackGateVerdict.Allowed, gate.decision.value)
        advanceTimeBy(1); runCurrent()
        assertEquals(LicenseAccessState.EXPIRED, (gate.decision.value as PlaybackGateVerdict.Blocked).reason)
    }

    @Test fun observationPayloadCannotGrantAccess() = runTest {
        val observations = MutableStateFlow(allowed())
        val gate = RuntimePlaybackGate({ LicenseAccessDecision(LicenseAccessState.REVOKED) }, observations, backgroundScope)
        runCurrent()
        assertEquals(LicenseAccessState.REVOKED, (gate.decision.value as PlaybackGateVerdict.Blocked).reason)
    }

    @Test fun observationTriggersImmediateFreshCheck() = runTest {
        val observations = MutableStateFlow(0)
        var current = allowed()
        val gate = RuntimePlaybackGate({ current }, observations, backgroundScope)
        runCurrent()
        current = LicenseAccessDecision(LicenseAccessState.SUSPENDED)
        observations.value++
        runCurrent()
        assertEquals(LicenseAccessState.SUSPENDED, (gate.decision.value as PlaybackGateVerdict.Blocked).reason)
    }

    @Test fun storageFailureReplacesPreviouslyAllowedDecision() = runTest {
        var fail = false
        val gate = RuntimePlaybackGate({ if (fail) error("storage unavailable") else allowed() }, emptyFlow<Unit>(), backgroundScope)
        gate.checkNow(); fail = true
        assertEquals(LicenseAccessState.VERIFICATION_REQUIRED, (gate.checkNow() as PlaybackGateVerdict.Blocked).reason)
    }

    @Test fun cancellationIsNotConvertedIntoLicenseDenial() = runTest {
        val gate = RuntimePlaybackGate({ throw CancellationException("cancel") }, emptyFlow<Unit>(), backgroundScope)
        assertThrows(CancellationException::class.java) { gate.checkNow() }
    }

    @Test fun clearBlockedDoesNotGrantAuthorization() = runTest {
        val gate = RuntimePlaybackGate({ LicenseAccessDecision(LicenseAccessState.EXPIRED) }, emptyFlow<Unit>(), backgroundScope)
        val denial = gate.checkNow() as PlaybackGateVerdict.Blocked
        gate.reportBlocked(denial); gate.clearBlocked()
        assertNull(gate.blocked.value)
        assertEquals(denial, gate.decision.value)
    }

    @Test fun periodicDenialDoesNotOpenUiWithoutProtectedAttempt() = runTest {
        val gate = RuntimePlaybackGate({ LicenseAccessDecision(LicenseAccessState.EXPIRED) }, emptyFlow<Unit>(), backgroundScope)
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertNull(gate.blocked.value)
    }

    @Test fun reportBroadcastsDenialWithoutChangingAuthorization() = runTest {
        val gate = RuntimePlaybackGate({ allowed() }, emptyFlow<Unit>(), backgroundScope)
        gate.checkNow()
        val reported = PlaybackGateVerdict.Blocked(LicenseAccessState.EXPIRED, false)
        gate.reportBlocked(reported)
        assertEquals(reported, gate.blocked.value)
        assertSame(PlaybackGateVerdict.Allowed, gate.decision.value)
    }

    @Test fun successfulStartupRecheckClearsObsoleteActivationRequest() = runTest {
        val gate = RuntimePlaybackGate({ allowed() }, emptyFlow<Unit>(), backgroundScope)
        val reported = PlaybackGateVerdict.Blocked(LicenseAccessState.UNLICENSED, true)
        gate.reportBlocked(reported); gate.checkNow()
        assertNull(gate.blocked.value)
    }

    @Test fun renewedAuthorizationRecoversOnTimerWithoutAutoPlayback() = runTest {
        var current = LicenseAccessDecision(LicenseAccessState.UNLICENSED)
        val gate = RuntimePlaybackGate({ current }, emptyFlow<Unit>(), backgroundScope)
        gate.checkNow(); runCurrent()
        current = allowed()
        advanceTimeBy(1_000); runCurrent()
        assertSame(PlaybackGateVerdict.Allowed, gate.decision.value)
    }

    private fun allowed() = LicenseAccessDecision(LicenseAccessState.ALLOWED, 2_000)
}

@RunWith(Parameterized::class)
class PlaybackGateVerdictMappingTest(private val reason: LicenseAccessState, private val actionable: Boolean) {
    @Test fun mapsFreshLicenseDecision() = runTest {
        val gate = RuntimePlaybackGate({ LicenseAccessDecision(reason) }, emptyFlow<Unit>(), backgroundScope)
        val actual = gate.checkNow()
        if (reason == LicenseAccessState.ALLOWED) assertSame(PlaybackGateVerdict.Allowed, actual)
        else assertEquals(PlaybackGateVerdict.Blocked(reason, actionable), actual)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun decisions(): List<Array<Any>> =
            LicenseAccessState.entries.map { arrayOf(it, it == LicenseAccessState.UNLICENSED || it == LicenseAccessState.VERIFICATION_REQUIRED) }
    }
}
