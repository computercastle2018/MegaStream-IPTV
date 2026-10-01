package com.MegaStream.app.ui.screens.license

import com.MegaStream.app.controlplane.*
import com.MegaStream.domain.licensing.LicenseAccessState
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LicenseActivationRegressionTest {
    private val epoch = Instant.parse("2026-09-30T00:00:00Z").toEpochMilli()
    private val secret = "private-license-key"
    private val token = "private-poll-token"

    private inner class Port : LicenseActivationPort {
        var directCalls = 0
        var codeCalls = 0
        val polls = mutableListOf<Pair<String, String>>()
        var direct: suspend (String) -> ControlPlaneResult<EntitlementResponse> = { allowed() }
        var code: suspend () -> ControlPlaneResult<ActivationCodeResponse> = { codeResponse() }
        var poll: suspend (String, String) -> ControlPlaneResult<ActivationStatusResponse> = { _, _ -> pending(1) }
        override suspend fun activateDirect(rawKey: String): ControlPlaneResult<EntitlementResponse> {
            directCalls++
            return direct(rawKey)
        }
        override suspend fun requestCode(): ControlPlaneResult<ActivationCodeResponse> {
            codeCalls++
            return code()
        }
        override suspend fun pollCode(code: String, pollToken: String): ControlPlaneResult<ActivationStatusResponse> {
            polls += code to pollToken
            return poll(code, pollToken)
        }
    }

    private fun response(state: EntitlementState): EntitlementResponse {
        val licensed = state in setOf(EntitlementState.ALLOWED, EntitlementState.NOT_STARTED,
            EntitlementState.EXPIRED, EntitlementState.SUSPENDED, EntitlementState.REVOKED)
        return EntitlementResponse(
            decision = EntitlementDecision(
                state = state,
                licenseId = if (licensed) "12345678-1234-4234-8234-123456789abc" else null,
                licenseRevision = if (licensed) 1 else null,
                startsAt = if (licensed) "2026-01-01T00:00:00Z" else null,
                endsAt = if (licensed) "2027-01-01T00:00:00Z" else null,
                offlineUntil = if (state == EntitlementState.ALLOWED) "2026-10-01T00:00:00Z" else null,
            ),
            serverTime = "2026-09-30T00:00:00Z",
            refreshAfterSeconds = 60,
            lease = if (state == EntitlementState.ALLOWED) "private-signed-lease" else null,
        )
    }

    private fun allowed() = ControlPlaneResult.Success(response(EntitlementState.ALLOWED))
    private fun activated(state: EntitlementState = EntitlementState.ALLOWED) =
        ControlPlaneResult.Success(ActivationStatusResponse.Activated(response(state)))
    private fun codeResponse(lifetimeMillis: Long = 60_000) = ControlPlaneResult.Success(
        ActivationCodeResponse("DISPLAY-CODE", Instant.ofEpochMilli(epoch + lifetimeMillis).toString(), ActivationCodeStatus.PENDING, token),
    )
    private fun pending(seconds: Long) = ControlPlaneResult.Success(ActivationStatusResponse.Pending(
        ActivationPendingResponse(ActivationCodeStatus.PENDING, "2026-09-30T00:00:00Z", seconds),
    ))
    private fun network() = ControlPlaneResult.Failure(ControlPlaneError.local("network_error"))
    private fun http(status: Int) = ControlPlaneResult.Failure(ControlPlaneError.unknown(status))

    private fun TestScope.model(port: Port, max: Int = 60, timeout: Long = 15_000, scope: CoroutineScope = backgroundScope) =
        LicenseActivationViewModel(port, scope, nowEpochMillis = { epoch + testScheduler.currentTime }, maxPollAttempts = max, callTimeoutMillis = timeout)
    private fun TestScope.codeModel(port: Port, max: Int = 60, timeout: Long = 15_000): LicenseActivationViewModel =
        model(port, max, timeout).also { it.selectMode(LicenseActivationMode.ACTIVATION_CODE) }
    private fun assertWiped(key: CharArray) = assertTrue(key.all { it == '\u0000' })
    private fun assertNoSecrets(state: LicenseActivationState) {
        val rendering = state.toString()
        for (forbidden in listOf(secret, token, "private-signed-lease", "private-exception-detail")) {
            assertFalse("State leaked $forbidden", rendering.contains(forbidden))
        }
    }

    @Test fun directKeyIsWipedSynchronouslyAndForwardedExactlyOnce() = runTest {
        val port = Port()
        var forwarded: String? = null
        port.direct = { forwarded = it; allowed() }
        val vm = model(port)
        val key = "  $secret  ".toCharArray()
        vm.submitKey(key)
        assertWiped(key)
        assertTrue(vm.state.value.busy)
        assertEquals(0, port.directCalls)
        runCurrent()
        assertEquals("  $secret  ", forwarded)
        assertEquals(1, port.directCalls)
        assertFalse(vm.state.value.busy)
        assertNoSecrets(vm.state.value)
    }

    @Test fun invalidKeysAreWipedAndNeverReachTransport() = runTest {
        val port = Port()
        val vm = model(port)
        for (input in listOf("", "  ", "a\nb", "x".repeat(513))) {
            val key = input.toCharArray()
            vm.submitKey(key)
            assertWiped(key)
            assertEquals(LicenseActivationError.INVALID_KEY, vm.state.value.error)
            assertFalse(vm.state.value.busy)
        }
        runCurrent()
        assertEquals(0, port.directCalls)
    }

    @Test fun duplicateDirectSubmissionIsRejectedBeforeDispatchAndWiped() = runTest {
        val port = Port().apply { direct = { awaitCancellation() } }
        val vm = model(port)
        vm.submitKey(secret.toCharArray())
        val duplicate = "different-key".toCharArray()
        vm.submitKey(duplicate)
        assertWiped(duplicate)
        runCurrent()
        assertEquals(1, port.directCalls)
        vm.stopPolling()
        runCurrent()
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
    }

    @Test fun wrongModeAndDisposedSubmissionBothWipeWithoutTransport() = runTest {
        val port = Port()
        val vm = codeModel(port)
        val wrongMode = secret.toCharArray()
        vm.submitKey(wrongMode)
        assertWiped(wrongMode)
        vm.dispose()
        val disposedKey = secret.toCharArray()
        vm.submitKey(disposedKey)
        assertWiped(disposedKey)
        runCurrent()
        assertEquals(0, port.directCalls)
        assertTrue(vm.state.value.disposed)
    }

    @Test fun transportAllowedNeverAuthorizesPlaybackWithoutVerifiedSnapshot() = runTest {
        val vm = model(Port())
        vm.submitKey(secret.toCharArray())
        runCurrent()
        assertEquals(EntitlementState.ALLOWED, vm.state.value.transportState)
        assertEquals(LicenseActivationPollStatus.ACTIVATED, vm.state.value.pollStatus)
        assertEquals(LicenseAccessState.UNLICENSED, vm.state.value.entitlement.state)
        assertFalse(vm.state.value.playbackUnblocked)
        vm.updateEntitlement(LicenseEntitlementSnapshot(LicenseAccessState.ALLOWED, 1_800_000_000))
        assertTrue(vm.state.value.playbackUnblocked)
        assertEquals(1_800_000_000L, vm.state.value.entitlement.expiresAtEpochSeconds)
        vm.dispose()
        assertFalse(vm.state.value.playbackUnblocked)
    }

    @Test fun everyDomainDenialRemainsBlockedEvenWhenTransportAllows() = runTest {
        val vm = model(Port())
        vm.submitKey(secret.toCharArray())
        runCurrent()
        for (domainState in LicenseAccessState.entries) {
            vm.updateEntitlement(LicenseEntitlementSnapshot(domainState))
            assertEquals(domainState.name, domainState == LicenseAccessState.ALLOWED, vm.state.value.playbackUnblocked)
        }
        vm.dispose()
    }

    @Test fun everyTransportTerminalDecisionPreservesVerifiedDomainSnapshot() = runTest {
        for (decision in EntitlementState.entries) {
            val port = Port().apply { poll = { _, _ -> activated(decision) } }
            val vm = codeModel(port)
            val snapshot = LicenseEntitlementSnapshot(LicenseAccessState.SUSPENDED, 1_800_000_000)
            vm.updateEntitlement(snapshot)
            vm.requestCode()
            runCurrent()
            assertEquals(decision, vm.state.value.transportState)
            assertEquals(snapshot, vm.state.value.entitlement)
            assertFalse(vm.state.value.playbackUnblocked)
            assertFalse(vm.state.value.canRetryPolling)
            assertEquals(if (decision == EntitlementState.ALLOWED) LicenseActivationPollStatus.ACTIVATED else LicenseActivationPollStatus.STOPPED, vm.state.value.pollStatus)
            vm.dispose()
        }
    }

    @Test fun transportFailuresMapToClosedErrorsWithoutExposingDetails() = runTest {
        val cases = listOf(
            network() to LicenseActivationError.NETWORK,
            http(429) to LicenseActivationError.RATE_LIMITED,
            http(401) to LicenseActivationError.REJECTED,
            http(503) to LicenseActivationError.NETWORK,
            ControlPlaneResult.Failure(ControlPlaneError.local("invalid_response")) to LicenseActivationError.INVALID_RESPONSE,
            ControlPlaneResult.Failure(ControlPlaneError.local("unknown")) to LicenseActivationError.UNKNOWN,
        )
        for ((failure, expected) in cases) {
            val port = Port().apply { direct = { failure } }
            val vm = model(port)
            vm.submitKey(secret.toCharArray())
            runCurrent()
            assertEquals(expected, vm.state.value.error)
            assertFalse(vm.state.value.busy)
            assertNoSecrets(vm.state.value)
            vm.dispose()
        }
    }

    @Test fun unexpectedTransportExceptionsAreRedactedAndDoNotWedgeBusy() = runTest {
        val port = Port().apply { direct = { throw IllegalStateException("private-exception-detail $secret") } }
        val vm = model(port)
        vm.submitKey(secret.toCharArray())
        runCurrent()
        assertEquals(LicenseActivationError.UNKNOWN, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertNoSecrets(vm.state.value)
        port.direct = { allowed() }
        vm.clearError()
        vm.submitKey(secret.toCharArray())
        runCurrent()
        assertEquals(LicenseActivationPollStatus.ACTIVATED, vm.state.value.pollStatus)
    }

    @Test fun cancellationBeforeDirectLaunchPreventsAnyKeyDelivery() = runTest {
        val port = Port()
        val vm = model(port)
        val key = secret.toCharArray()
        vm.submitKey(key)
        vm.selectMode(LicenseActivationMode.ACTIVATION_CODE)
        assertWiped(key)
        runCurrent()
        assertEquals(0, port.directCalls)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
    }

    @Test fun cancellationBeforeCodeRequestLaunchPreventsCodeCreation() = runTest {
        val port = Port()
        val vm = codeModel(port)
        vm.requestCode()
        vm.stopPolling()
        runCurrent()
        assertEquals(0, port.codeCalls)
        assertNull(vm.state.value.activationCode)
        assertEquals(LicenseActivationPollStatus.STOPPED, vm.state.value.pollStatus)
    }

    @Test fun noncooperativeDirectResponseCannotCrossModeGeneration() = runTest {
        val port = Port().apply { direct = { withContext(NonCancellable) { delay(2_000); allowed() } } }
        val vm = model(port)
        vm.submitKey(secret.toCharArray())
        runCurrent()
        vm.selectMode(LicenseActivationMode.ACTIVATION_CODE)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(LicenseActivationMode.ACTIVATION_CODE, vm.state.value.mode)
        assertEquals(LicenseActivationPollStatus.IDLE, vm.state.value.pollStatus)
        assertNull(vm.state.value.transportState)
        assertFalse(vm.state.value.busy)
    }

    @Test fun noncooperativeCodeResponseCannotResurrectStoppedSession() = runTest {
        val port = Port().apply { code = { withContext(NonCancellable) { delay(2_000); codeResponse() } } }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        vm.stopPolling()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(LicenseActivationPollStatus.STOPPED, vm.state.value.pollStatus)
        assertNull(vm.state.value.activationCode)
        assertEquals(0, port.polls.size)
    }

    @Test fun noncooperativePollResponseCannotActivateDisposedModel() = runTest {
        val port = Port().apply { poll = { _, _ -> withContext(NonCancellable) { delay(2_000); activated() } } }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        vm.dispose()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(vm.state.value.disposed)
        assertNull(vm.state.value.transportState)
        assertFalse(vm.state.value.playbackUnblocked)
        assertEquals(LicenseActivationPollStatus.STOPPED, vm.state.value.pollStatus)
    }

    @Test fun positiveServerDelayIsNotShortenedByExplicitRetryOrDuplicateRequest() = runTest {
        val port = Port().apply { poll = { _, _ -> pending(5) } }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        vm.retryPolling()
        vm.requestCode()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, port.polls.size)
        assertEquals(1, port.codeCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, port.polls.size)
        vm.dispose()
    }

    @Test fun zeroServerDelayUsesOneSecondMinimumInsteadOfBusyLoop() = runTest {
        val port = Port().apply { poll = { _, _ -> pending(0) } }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, port.polls.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, port.polls.size)
        vm.dispose()
    }

    @Test fun overflowingServerDelayWaitsUntilExpiryWithoutAnotherPoll() = runTest {
        val port = Port().apply {
            code = { codeResponse(3_000) }
            poll = { _, _ -> pending(Long.MAX_VALUE) }
        }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, port.polls.size)
        assertEquals(LicenseActivationPollStatus.EXPIRED, vm.state.value.pollStatus)
        assertFalse(vm.state.value.canRetryPolling)
    }

    @Test fun codeAtExactExpiryIsDisplayedButNeverPolled() = runTest {
        val port = Port().apply { code = { codeResponse(0) } }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        assertEquals("DISPLAY-CODE", vm.state.value.activationCode)
        assertEquals(0, port.polls.size)
        assertEquals(LicenseActivationPollStatus.EXPIRED, vm.state.value.pollStatus)
        assertFalse(vm.state.value.busy)
    }

    @Test fun expiryDuringNoncooperativePollDiscardsLateActivation() = runTest {
        val port = Port().apply {
            code = { codeResponse(1_000) }
            poll = { _, _ -> withContext(NonCancellable) { delay(2_000); activated() } }
        }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(LicenseActivationPollStatus.EXPIRED, vm.state.value.pollStatus)
        assertFalse(vm.state.value.busy)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(vm.state.value.transportState)
        assertEquals(LicenseActivationPollStatus.EXPIRED, vm.state.value.pollStatus)
    }

    @Test fun idleRetryableFailureExpiresWithoutUserAction() = runTest {
        val port = Port().apply {
            code = { codeResponse(2_000) }
            poll = { _, _ -> network() }
        }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        assertTrue(vm.state.value.canRetryPolling)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(LicenseActivationPollStatus.EXPIRED, vm.state.value.pollStatus)
        assertFalse(vm.state.value.canRetryPolling)
        vm.retryPolling()
        runCurrent()
        assertEquals(1, port.polls.size)
    }

    @Test fun attemptsStayBoundedAcrossTransientExplicitRetries() = runTest {
        val port = Port().apply { poll = { _, _ -> network() } }
        val vm = codeModel(port, max = 2)
        vm.requestCode()
        runCurrent()
        assertEquals(1, vm.state.value.pollAttempts)
        vm.retryPolling()
        runCurrent()
        assertEquals(2, vm.state.value.pollAttempts)
        assertEquals(LicenseActivationPollStatus.EXHAUSTED, vm.state.value.pollStatus)
        vm.retryPolling()
        runCurrent()
        assertEquals(2, port.polls.size)
        assertEquals(1, port.codeCalls)
    }

    @Test fun transientFailureRequiresExplicitRetryWithSameCodeAndToken() = runTest {
        val port = Port().apply { poll = { _, _ -> network() } }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, port.polls.size)
        assertEquals(1, port.codeCalls)
        assertEquals(LicenseActivationPollStatus.RETRYABLE_FAILURE, vm.state.value.pollStatus)
        port.poll = { _, _ -> activated() }
        vm.retryPolling()
        vm.retryPolling()
        runCurrent()
        assertEquals(listOf("DISPLAY-CODE" to token, "DISPLAY-CODE" to token), port.polls)
        assertEquals(1, port.codeCalls)
        assertEquals(LicenseActivationPollStatus.ACTIVATED, vm.state.value.pollStatus)
        assertNoSecrets(vm.state.value)
    }

    @Test fun unauthorizedPollIsTerminalAndCannotRetryRetainedCode() = runTest {
        val port = Port().apply { poll = { _, _ -> http(401) } }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        assertEquals(LicenseActivationError.REJECTED, vm.state.value.error)
        assertEquals(LicenseActivationPollStatus.STOPPED, vm.state.value.pollStatus)
        vm.retryPolling()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, port.polls.size)
        assertFalse(vm.state.value.canRetryPolling)
    }

    @Test fun stopDropsRetryCapabilityWhileKeepingCallerScopeAlive() = runTest {
        val port = Port().apply { poll = { _, _ -> network() } }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        vm.stopPolling()
        vm.retryPolling()
        runCurrent()
        assertEquals(1, port.polls.size)
        assertEquals(LicenseActivationPollStatus.STOPPED, vm.state.value.pollStatus)
        assertFalse(vm.state.value.canRetryPolling)
        assertTrue(backgroundScope.coroutineContext[Job]!!.isActive)
        vm.dispose()
        assertTrue(backgroundScope.coroutineContext[Job]!!.isActive)
    }

    @Test fun repeatedModeChangesResetSessionWithoutLosingVerifiedEntitlement() = runTest {
        val port = Port()
        val vm = codeModel(port)
        val verified = LicenseEntitlementSnapshot(LicenseAccessState.ALLOWED, 1_800_000_000)
        vm.updateEntitlement(verified)
        vm.requestCode()
        runCurrent()
        repeat(4) {
            vm.selectMode(LicenseActivationMode.DIRECT_KEY)
            vm.selectMode(LicenseActivationMode.ACTIVATION_CODE)
        }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(verified, vm.state.value.entitlement)
        assertEquals(1, port.polls.size)
        assertNull(vm.state.value.activationCode)
        assertEquals(0, vm.state.value.pollAttempts)
        assertEquals(LicenseActivationPollStatus.IDLE, vm.state.value.pollStatus)
    }

    @Test fun externalScopeCancellationStopsIdleRetryAndFutureOperations() = runTest {
        val parent = SupervisorJob(backgroundScope.coroutineContext[Job])
        val injected = CoroutineScope(backgroundScope.coroutineContext + parent)
        val port = Port().apply { poll = { _, _ -> network() } }
        val vm = model(port, scope = injected)
        vm.selectMode(LicenseActivationMode.ACTIVATION_CODE)
        vm.requestCode()
        runCurrent()
        assertTrue(vm.state.value.canRetryPolling)
        parent.cancel()
        runCurrent()
        vm.retryPolling()
        vm.requestCode()
        runCurrent()
        assertEquals(LicenseActivationPollStatus.STOPPED, vm.state.value.pollStatus)
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.canRetryPolling)
        assertEquals(1, port.polls.size)
        assertEquals(1, port.codeCalls)
    }

    @Test fun directTimeoutIsTimelyEvenWhenTransportReturnsLate() = runTest {
        val port = Port().apply { direct = { withContext(NonCancellable) { delay(2_000); allowed() } } }
        val vm = model(port, timeout = 1_000)
        vm.submitKey(secret.toCharArray())
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(LicenseActivationError.TIMEOUT, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(vm.state.value.transportState)
        assertEquals(LicenseActivationError.TIMEOUT, vm.state.value.error)
    }

    @Test fun codeRequestTimeoutDoesNotCreateLateCodeOrBeginPolling() = runTest {
        val port = Port().apply { code = { withContext(NonCancellable) { delay(2_000); codeResponse() } } }
        val vm = codeModel(port, timeout = 1_000)
        vm.requestCode()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(LicenseActivationError.TIMEOUT, vm.state.value.error)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(vm.state.value.activationCode)
        assertEquals(0, port.polls.size)
    }

    @Test fun pollTimeoutIsRetryableButLateAllowedCannotOverwriteRetry() = runTest {
        val port = Port().apply { poll = { _, _ -> withContext(NonCancellable) { delay(2_000); activated() } } }
        val vm = codeModel(port, timeout = 1_000)
        vm.requestCode()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(LicenseActivationError.TIMEOUT, vm.state.value.error)
        assertTrue(vm.state.value.canRetryPolling)
        port.poll = { _, _ -> http(403) }
        vm.retryPolling()
        runCurrent()
        assertEquals(LicenseActivationError.REJECTED, vm.state.value.error)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(vm.state.value.transportState)
        assertEquals(LicenseActivationError.REJECTED, vm.state.value.error)
        assertEquals(2, vm.state.value.pollAttempts)
    }

    @Test fun cooperativePortCancellationIsNotConvertedToUiError() = runTest {
        val port = Port().apply { direct = { throw CancellationException("private-exception-detail") } }
        val vm = model(port)
        vm.submitKey(secret.toCharArray())
        runCurrent()
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
        assertEquals(LicenseActivationPollStatus.STOPPED, vm.state.value.pollStatus)
        assertNoSecrets(vm.state.value)
    }

    @Test fun pendingResponsesCannotExceedMaximumAttemptCount() = runTest {
        val port = Port().apply { poll = { _, _ -> pending(0) } }
        val vm = codeModel(port, max = 2)
        vm.requestCode()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, vm.state.value.pollAttempts)
        assertEquals(LicenseActivationPollStatus.EXHAUSTED, vm.state.value.pollStatus)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, port.polls.size)
        assertFalse(vm.state.value.busy)
    }

    @Test fun directNetworkFailureNeverRetriesOrCreatesCodeAutomatically() = runTest {
        val port = Port().apply { direct = { network() } }
        val vm = model(port)
        vm.submitKey(secret.toCharArray())
        runCurrent()
        assertEquals(LicenseActivationError.NETWORK, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        vm.retryPolling()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, port.directCalls)
        assertEquals(0, port.codeCalls)
        assertEquals(0, port.polls.size)
        assertFalse(vm.state.value.canRetryPolling)
    }

    @Test fun disposedModelRejectsEveryCommandWithoutMutatingSnapshot() = runTest {
        val port = Port()
        val vm = codeModel(port)
        vm.updateEntitlement(LicenseEntitlementSnapshot(LicenseAccessState.ALLOWED))
        vm.dispose()
        val frozen = vm.state.value
        vm.selectMode(LicenseActivationMode.DIRECT_KEY)
        vm.updateEntitlement(LicenseEntitlementSnapshot(LicenseAccessState.REVOKED))
        vm.requestCode()
        vm.retryPolling()
        vm.stopPolling()
        vm.clearError()
        vm.dispose()
        val key = secret.toCharArray()
        vm.submitKey(key)
        assertWiped(key)
        runCurrent()
        assertEquals(frozen, vm.state.value)
        assertFalse(vm.state.value.playbackUnblocked)
        assertEquals(0, port.directCalls)
        assertEquals(0, port.codeCalls)
        assertEquals(0, port.polls.size)
    }

    @Test fun lifecycleStoreClearDisposesWithoutCancellingCallerScope() = runTest {
        val port = Port().apply { poll = { _, _ -> network() } }
        val vm = codeModel(port)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("license", vm)
        vm.requestCode()
        runCurrent()
        assertTrue(vm.state.value.canRetryPolling)
        store.clear()
        runCurrent()
        assertTrue(vm.state.value.disposed)
        assertFalse(vm.state.value.canRetryPolling)
        assertFalse(vm.state.value.playbackUnblocked)
        assertTrue(backgroundScope.coroutineContext[Job]!!.isActive)
        vm.retryPolling()
        runCurrent()
        assertEquals(1, port.polls.size)
    }

    @Test fun clearingErrorDoesNotResetRetryBudgetOrCreateNetworkTraffic() = runTest {
        val port = Port().apply { poll = { _, _ -> throw IOException("private-exception-detail") } }
        val vm = codeModel(port)
        vm.requestCode()
        runCurrent()
        assertEquals(LicenseActivationError.NETWORK, vm.state.value.error)
        vm.clearError()
        runCurrent()
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.canRetryPolling)
        assertEquals(1, vm.state.value.pollAttempts)
        assertEquals(1, port.polls.size)
        assertNoSecrets(vm.state.value)
    }
}
