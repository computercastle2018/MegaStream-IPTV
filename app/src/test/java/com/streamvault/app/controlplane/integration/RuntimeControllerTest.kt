package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.DevicePolicy
import com.MegaStream.app.controlplane.HeartbeatMode
import com.MegaStream.app.controlplane.KioskMode
import com.MegaStream.app.controlplane.runtime.DiagnosticsStorageStage
import com.MegaStream.app.controlplane.runtime.DiagnosticsUploadResult
import com.MegaStream.app.controlplane.runtime.InvalidBatchReason
import com.MegaStream.app.controlplane.runtime.RuntimeFailureCode
import com.MegaStream.app.controlplane.runtime.RuntimeResult
import com.MegaStream.app.controlplane.runtime.RuntimeUpdateDispatch
import com.MegaStream.domain.licensing.LicenseAccessDecision
import com.MegaStream.domain.licensing.LicenseAccessState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/** Controller orchestration tests; port fakes model external storage/network/entitlement boundaries.
 * These do not claim to verify the adapters' durable AppStarted or acknowledgement implementation.
 */
class RuntimeControllerTest {
    @Test fun manualLicenseRefreshBypassesCadenceWithoutCreatingAnotherSession() = scenario {
        controller.start().join()
        controller.tick().join()
        assertEquals(1, count("heartbeat:FOREGROUND"))
        controller.tick(force = true).join()
        assertEquals(2, count("heartbeat:FOREGROUND"))
        assertEquals(1, count("session-committed"))
        controller.tick().join()
        assertEquals(2, count("heartbeat:FOREGROUND"))
        controller.manualExit().join()
        controller.tick(force = true).join()
        assertEquals(2, count("heartbeat:FOREGROUND"))
    }

    @Test fun recoveryAndCommittedSessionPrecedeAllRuntimeAndUploadWork() = scenario {
        controller.start().join()
        assertEquals(listOf("recover", "session-committed", "gate", "register", "heartbeat:FOREGROUND", "gate", "upload"), calls)
        assertEquals(listOf("session-1"), sessions)
        assertEquals(PresenceStatus.FOREGROUND, state.presence)
    }

    @Test fun overlappingStartsShareOneRecoveryAndOneSession() = scenario {
        val release = CompletableDeferred<Unit>()
        recoverAction = { release.await() }
        val first = controller.start()
        val others = List(20) { controller.start() }
        assertTrue(others.all { it === first })
        assertEquals(listOf("recover"), calls)
        release.complete(Unit)
        first.join()
        assertEquals(1, count("session-committed"))
        assertEquals(1, count("register"))
    }

    @Test fun failedRecoveryIsTerminalForThisController() = scenario {
        recoverAction = { error(SECRET) }
        controller.start().join()
        recoverAction = {}
        clock.now += 500_000
        controller.tick().join()
        controller.start().join()
        assertEquals(1, count("recover"))
        assertEquals(0, count("session-committed"))
        assertEquals(0, count("upload"))
        assertEquals(RuntimeControllerFailure.RECOVERY_FAILURE, state.lastSafeFailure)
        assertFalse(state.effectiveDecision.allowed)
    }

    @Test fun repairedRecoveryRequiresNewController() = scenario {
        recoverAction = { error(SECRET) }
        controller.start().join()
        recoverAction = {}
        val replacement = newController()
        replacement.start().join()
        assertEquals(2, count("recover"))
        assertEquals(1, count("session-committed"))
        assertTrue(replacement.snapshot.value.effectiveDecision.allowed)
    }

    @Test fun failedSessionNeverRegistersUploadsOrDuplicatesSessionAttempt() = scenario {
        sessionAction = { error(SECRET) }
        controller.start().join()
        controller.start().join()
        clock.now += 500_000
        controller.tick().join()
        assertEquals(1, count("session-committed"))
        assertEquals(0, count("register"))
        assertEquals(0, count("upload"))
        assertEquals(RuntimeControllerFailure.SESSION_FAILURE, state.lastSafeFailure)
    }

    @Test fun blankSessionIdFailsClosedBeforeNetwork() = scenario {
        sessionAction = { " " }
        controller.start().join()
        assertEquals(RuntimeControllerFailure.SESSION_FAILURE, state.lastSafeFailure)
        assertEquals(0, count("register"))
        assertFalse(state.effectiveDecision.allowed)
    }

    @Test fun registrationFailureRetriesOnlyAtCooldownAndDoesNotUpload() = scenario {
        registerResult = RuntimeResult.Failure(RuntimeFailureCode.TRANSPORT_FAILURE)
        controller.start().join()
        assertEquals(RegistrationStatus.RETRY_PENDING, state.registration)
        assertEquals(0, count("upload"))
        clock.now = 119_999
        controller.tick().join()
        assertEquals(1, count("register"))
        registerResult = RuntimeResult.Registered
        clock.now = 120_000
        controller.tick().join()
        assertEquals(2, count("register"))
        assertEquals(1, sessions.size)
        assertEquals(RegistrationStatus.REGISTERED, state.registration)
    }

    @Test fun recreationRepeatsRecoveryAndRegistrationChoreography() = scenario {
        registerResult = RuntimeResult.Failure(RuntimeFailureCode.TRANSPORT_FAILURE)
        controller.start().join()
        scope.cancel()
        val replacementScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            calls.clear()
            registerResult = RuntimeResult.Registered
            val replacement = RuntimeController(operations, diagnostics, replacementScope, dispatcher, { clock.now })
            replacement.start().join()
            assertEquals(listOf("recover", "session-committed", "gate", "register", "heartbeat:FOREGROUND", "gate", "upload"), calls)
        } finally { replacementScope.cancel() }
    }

    @Test fun transportFailurePreservesFreshOfflinePermission() = scenario {
        heartbeatResult = RuntimeResult.Failure(RuntimeFailureCode.TRANSPORT_FAILURE)
        controller.start().join()
        assertTrue(state.effectiveDecision.allowed)
        assertEquals(PresenceStatus.UNAVAILABLE, state.presence)
        assertEquals(RuntimeControllerFailure.TRANSPORT_FAILURE, state.lastSafeFailure)
    }

    @Test fun rateLimitedTickStillRecomputesOfflineExpiry() = scenario {
        heartbeatResult = RuntimeResult.Failure(RuntimeFailureCode.TRANSPORT_FAILURE)
        controller.start().join()
        gateDecision = decision(LicenseAccessState.EXPIRED)
        clock.now = 1
        controller.tick().join()
        assertEquals(LicenseAccessState.EXPIRED, state.effectiveDecision.state)
        assertEquals(1, sessions.size)
    }

    @Test fun staleAllowedFailureCannotReopenExpiredFreshGate() = scenario {
        gateDecision = decision(LicenseAccessState.EXPIRED)
        heartbeatResult = RuntimeResult.Failure(RuntimeFailureCode.TRANSPORT_FAILURE, allowed)
        controller.start().join()
        assertEquals(LicenseAccessState.EXPIRED, state.effectiveDecision.state)
    }

    @Test fun authenticatedDenialOutranksStaleAllowedGateAcrossTicks() = scenario {
        heartbeatResult = applied(decision(LicenseAccessState.REVOKED))
        controller.start().join()
        controller.tick().join()
        assertEquals(LicenseAccessState.REVOKED, state.effectiveDecision.state)
        heartbeatResult = RuntimeResult.Failure(RuntimeFailureCode.TRANSPORT_FAILURE, allowed)
        clock.now += 120_000
        controller.tick().join()
        assertEquals(LicenseAccessState.REVOKED, state.effectiveDecision.state)
    }

    @Test fun installationDisabledForcesLocalExitAndKioskOff() = scenario {
        heartbeatResult = applied(decision(LicenseAccessState.INSTALLATION_DISABLED))
        controller.start().join()
        assertEquals(DevicePolicy(KioskMode.OFF, true), state.devicePolicy)
        assertEquals(LicenseAccessState.INSTALLATION_DISABLED, state.effectiveDecision.state)
    }

    @Test fun newlyDisabledGateOverridesHeartbeatDenialAndPolicy() = scenario {
        gateDecision = decision(LicenseAccessState.INSTALLATION_DISABLED)
        heartbeatResult = applied(decision(LicenseAccessState.EXPIRED))
        controller.start().join()
        assertEquals(LicenseAccessState.INSTALLATION_DISABLED, state.effectiveDecision.state)
        assertEquals(DevicePolicy(KioskMode.OFF, true), state.devicePolicy)
    }

    @Test fun disabledDecisionAndSafePolicyArePublishedAtomically() = scenario {
        val observed = mutableListOf<RuntimeSnapshot>()
        val observer = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            controller.snapshot.collect { observed += it }
        }
        try {
            gateDecision = decision(LicenseAccessState.INSTALLATION_DISABLED)
            heartbeatResult = applied(decision(LicenseAccessState.EXPIRED))
            controller.start().join()
            val disabled = observed.filter { it.effectiveDecision.state == LicenseAccessState.INSTALLATION_DISABLED }
            assertTrue(disabled.isNotEmpty())
            assertTrue(disabled.all { it.devicePolicy == DevicePolicy(KioskMode.OFF, true) })
            assertEquals(LicenseAccessState.INSTALLATION_DISABLED, state.effectiveDecision.state)
        } finally { observer.cancel() }
    }

    @Test fun failedGateNeverPublishesTransientAllowedHeartbeatDecision() = scenario {
        val observed = mutableListOf<RuntimeSnapshot>()
        val observer = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            controller.snapshot.collect { observed += it }
        }
        try {
            gateAction = { error(SECRET) }
            controller.start().join()
            assertTrue(observed.isNotEmpty())
            assertTrue(observed.none { it.effectiveDecision.allowed })
            assertEquals(RuntimeControllerFailure.ENTITLEMENT_FAILURE, state.lastSafeFailure)
        } finally { observer.cancel() }
    }

    @Test fun allowedHeartbeatPublishesFreshGateDeadline() = scenario {
        gateDecision = LicenseAccessDecision(LicenseAccessState.ALLOWED, 777)
        heartbeatResult = applied(LicenseAccessDecision(LicenseAccessState.ALLOWED, 999))
        controller.start().join()
        assertEquals(777L, state.effectiveDecision.offlineDeadlineEpochSeconds)
    }

    @Test fun gateExceptionOverridesEvenSuccessfulHeartbeat() = scenario {
        gateAction = { error(SECRET) }
        controller.start().join()
        assertEquals(LicenseAccessState.VERIFICATION_REQUIRED, state.effectiveDecision.state)
        assertEquals(RuntimeControllerFailure.ENTITLEMENT_FAILURE, state.lastSafeFailure)
        assertEquals(PresenceStatus.UNAVAILABLE, state.presence)
    }

    @Test fun persistenceFailureLatchesUntilSuccessfulHeartbeatAndGate() = scenario {
        heartbeatResult = RuntimeResult.Failure(RuntimeFailureCode.PERSISTENCE_FAILURE)
        controller.start().join()
        controller.tick().join()
        assertFalse(state.effectiveDecision.allowed)
        heartbeatResult = RuntimeResult.Failure(RuntimeFailureCode.TRANSPORT_FAILURE, allowed)
        clock.now += 120_000
        controller.tick().join()
        assertFalse(state.effectiveDecision.allowed)
        heartbeatResult = applied()
        clock.now += 120_000
        controller.tick().join()
        assertTrue(state.effectiveDecision.allowed)
        assertNull(state.lastSafeFailure)
    }

    @Test fun entitlementFailureDoesNotClearOnGateOnlyRecovery() = scenario {
        heartbeatResult = RuntimeResult.Failure(RuntimeFailureCode.ENTITLEMENT_FAILURE)
        controller.start().join()
        heartbeatResult = applied()
        clock.now = 1
        controller.tick().join()
        assertEquals(LicenseAccessState.VERIFICATION_REQUIRED, state.effectiveDecision.state)
        clock.now = 120_000
        controller.tick().join()
        assertTrue(state.effectiveDecision.allowed)
    }

    @Test fun successfulHeartbeatCannotClearLatchWhenPostHeartbeatGateThrows() = scenario {
        heartbeatResult = RuntimeResult.Failure(RuntimeFailureCode.PERSISTENCE_FAILURE)
        controller.start().join()
        heartbeatResult = applied()
        var gateCalls = 0
        gateAction = { if (++gateCalls == 2) error(SECRET) else allowed }
        clock.now += 120_000
        controller.tick().join()
        assertFalse(state.effectiveDecision.allowed)
        assertEquals(RuntimeControllerFailure.ENTITLEMENT_FAILURE, state.lastSafeFailure)
    }

    @Test fun commandSinkFailureRetainsAppliedDecisionAndPolicy() = scenario {
        heartbeatResult = applied().copy(updateDispatch = RuntimeUpdateDispatch.FAILED)
        controller.start().join()
        assertTrue(state.effectiveDecision.allowed)
        assertEquals(lockedPolicy, state.devicePolicy)
        assertEquals(RuntimeControllerFailure.SINK_FAILURE, state.lastSafeFailure)
        assertEquals(PresenceStatus.FOREGROUND, state.presence)
    }

    @Test fun recoverableUploadFailuresNeverDamageEntitlementAndRemainRetryable() = runBlocking {
        val failures = listOf(DiagnosticsUploadResult.TransportFailure, DiagnosticsUploadResult.InvalidResponse,
            DiagnosticsUploadResult.InvalidBatch(InvalidBatchReason.DUPLICATE_IDS))
        for (result in failures) withFixture {
            uploadResult = result
            controller.start().join()
            assertTrue("$result", state.effectiveDecision.allowed)
            assertEquals(lockedPolicy, state.devicePolicy)
            assertEquals(RuntimeControllerFailure.DIAGNOSTICS_FAILURE, state.lastSafeFailure)
            uploadResult = DiagnosticsUploadResult.EmptySnapshot
            clock.now += 120_000
            controller.tick().join()
            assertEquals(2, count("upload"))
            assertNull(state.lastSafeFailure)
        }
    }

    @Test fun everyStorageFailureLatchesUploadAndRemainsVisibleAfterHeartbeatSuccess() = runBlocking {
        for (stage in DiagnosticsStorageStage.values()) withFixture {
            uploadResult = DiagnosticsUploadResult.StorageFailure(stage)
            controller.start().join()
            uploadResult = DiagnosticsUploadResult.EmptySnapshot
            clock.now += 120_000
            controller.tick().join()
            assertEquals("$stage", 1, count("upload"))
            assertEquals(RuntimeControllerFailure.DIAGNOSTICS_BLOCKED, state.lastSafeFailure)
            assertTrue(state.effectiveDecision.allowed)
            assertEquals(2, sessions.size)
        }
    }

    @Test fun quarantineAndRecoveryMarkersStopAutomaticUploadReplay() = runBlocking {
        for (result in listOf(DiagnosticsUploadResult.BlockedByQuarantine(2), DiagnosticsUploadResult.BlockedUntilRecovery)) withFixture {
            uploadResult = result
            controller.start().join()
            clock.now += 120_000
            controller.tick().join()
            assertEquals(1, count("upload"))
            assertEquals(RuntimeControllerFailure.DIAGNOSTICS_BLOCKED, state.lastSafeFailure)
            assertTrue(state.effectiveDecision.allowed)
        }
    }

    @Test fun thrownUploadExceptionLatchesWithoutRevokingAccess() = scenario {
        uploadAction = { error(SECRET) }
        controller.start().join()
        assertTrue(state.effectiveDecision.allowed)
        assertEquals(RuntimeControllerFailure.DIAGNOSTICS_FAILURE, state.lastSafeFailure)
        clock.now += 120_000
        controller.tick().join()
        assertEquals(1, count("upload"))
        assertEquals(RuntimeControllerFailure.DIAGNOSTICS_BLOCKED, state.lastSafeFailure)
    }

    @Test fun telemetryTransportFailureDoesNotMaskRuntimeFailure() = scenario {
        heartbeatResult = RuntimeResult.Failure(RuntimeFailureCode.ENTITLEMENT_FAILURE)
        uploadResult = DiagnosticsUploadResult.TransportFailure
        controller.start().join()
        assertEquals(RuntimeControllerFailure.ENTITLEMENT_FAILURE, state.lastSafeFailure)
        assertFalse(state.effectiveDecision.allowed)
    }

    @Test fun initialIntervalClampsBothBoundsAndUsesDefault() = runBlocking {
        for ((configured, expected) in listOf(-1L to 30_000L, 0L to 30_000L, 120L to 120_000L, Long.MAX_VALUE to 300_000L)) withFixture(configured) {
            registerResult = RuntimeResult.Failure(RuntimeFailureCode.TRANSPORT_FAILURE)
            controller.start().join()
            clock.now = expected - 1
            controller.tick().join()
            assertEquals("configured=$configured", 1, count("register"))
            clock.now = expected
            controller.tick().join()
            assertEquals("configured=$configured", 2, count("register"))
        }
    }

    @Test fun serverIntervalReplacesInitialCadenceAndClampsBounds() = runBlocking {
        for ((server, expected) in listOf(-10L to 30_000L, 45L to 45_000L, Long.MAX_VALUE to 300_000L)) withFixture {
            heartbeatResult = applied().copy(refreshAfterSeconds = server)
            controller.start().join()
            clock.now = expected - 1
            controller.tick().join()
            assertEquals(1, sessions.size)
            clock.now = expected
            controller.tick().join()
            assertEquals(2, sessions.size)
        }
    }

    @Test fun monotonicRollbackRebasesWithoutImmediateRetry() = scenario {
        clock.now = 200_000
        controller.start().join()
        clock.now = 100_000
        controller.tick().join()
        assertEquals(1, sessions.size)
        clock.now = 219_999
        controller.tick().join()
        assertEquals(1, sessions.size)
        clock.now = 220_000
        controller.tick().join()
        assertEquals(2, sessions.size)
    }

    @Test fun slowAttemptStartsCooldownAtCompletionNotInvocation() = scenario {
        heartbeatAction = { clock.now += 200_000; applied() }
        controller.start().join()
        heartbeatAction = { applied() }
        clock.now = 319_999
        controller.tick().join()
        assertEquals(1, sessions.size)
        clock.now = 320_000
        controller.tick().join()
        assertEquals(2, sessions.size)
    }

    @Test fun backgroundTransitionSendsBackgroundHeartbeatWhenDueWithoutExit() = scenario {
        controller.start().join()
        clock.now += 120_000
        controller.foreground(false).join()
        assertEquals(HeartbeatMode.BACKGROUND, modes.last())
        assertEquals(PresenceStatus.BACKGROUND, state.presence)
        assertEquals(0, count("exit"))
    }

    @Test fun returningForegroundSendsForegroundHeartbeatWhenDue() = scenario {
        controller.start().join()
        controller.foreground(false).join()
        clock.now += 120_000
        controller.foreground(true).join()
        assertEquals(HeartbeatMode.FOREGROUND, modes.last())
        assertEquals(2, sessions.size)
        assertEquals(PresenceStatus.FOREGROUND, state.presence)
    }

    @Test fun unchangedForegroundNotificationDoesNotBypassExplicitTick() = scenario {
        controller.start().join()
        clock.now += 120_000
        controller.foreground(true).join()
        assertEquals(1, sessions.size)
        controller.tick().join()
        assertEquals(2, sessions.size)
    }

    @Test fun schedulerRunsForegroundHeartbeatAtDeadlineWithoutExplicitTick() = scenario {
        controller.start().join()
        dispatcher.advanceTo(119_999)
        assertEquals(1, sessions.size)
        dispatcher.advanceTo(120_000)
        assertEquals(2, sessions.size)
    }

    @Test fun backgroundSuppressesAutomaticRegistrationRetryButExplicitTickWorks() = scenario {
        registerResult = RuntimeResult.Failure(RuntimeFailureCode.TRANSPORT_FAILURE)
        controller.start().join()
        controller.foreground(false).join()
        dispatcher.advanceTo(120_000)
        dispatcher.advanceTo(240_000)
        assertEquals(1, count("register"))
        registerResult = RuntimeResult.Registered
        controller.tick().join()
        assertEquals(2, count("register"))
        assertEquals(HeartbeatMode.BACKGROUND, modes.single())
    }

    @Test fun explicitExitIsExactlyOnceAndTerminalForTicksStartsAndTimer() = scenario {
        controller.start().join()
        val exit = controller.manualExit()
        exit.join()
        assertSame(exit, controller.manualExit())
        val before = calls.toList()
        controller.start().join()
        controller.tick().join()
        controller.foreground(false).join()
        dispatcher.advanceTo(500_000)
        assertEquals(before, calls)
        assertEquals(1, count("exit"))
        assertEquals(PresenceStatus.STOPPED, state.presence)
    }

    @Test fun scopeCancellationDoesNotInventExplicitExitOrStoppedPresence() = scenario {
        controller.start().join()
        scope.cancel()
        dispatcher.advanceTo(500_000)
        assertEquals(0, count("exit"))
        assertEquals(1, sessions.size)
        assertEquals(PresenceStatus.FOREGROUND, state.presence)
    }

    @Test fun exitBeforeStartPreventsRecoveryAndSessionCreation() = scenario {
        controller.manualExit().join()
        controller.start().join()
        controller.tick().join()
        assertTrue(calls.isEmpty())
        assertEquals(PresenceStatus.STOPPED, state.presence)
    }

    @Test fun exitFailureStillStopsAndNeverRetriesExit() = scenario {
        controller.start().join()
        exitAction = { error(SECRET) }
        controller.manualExit().join()
        controller.manualExit().join()
        assertEquals(1, count("exit"))
        assertEquals(PresenceStatus.STOPPED, state.presence)
        assertEquals(RuntimeControllerFailure.EXIT_FAILURE, state.lastSafeFailure)
    }

    @Test fun concurrentTicksAndLifecycleChangesCannotOverlapSuspendedNetwork() = scenario {
        val release = CompletableDeferred<Unit>()
        heartbeatAction = { release.await(); applied() }
        val startup = controller.start()
        val queued = List(10) { controller.tick() } + controller.foreground(false)
        assertEquals(1, sessions.size)
        assertEquals(0, count("upload"))
        assertTrue(queued.none { it.isCompleted })
        release.complete(Unit)
        startup.join()
        queued.forEach { it.join() }
        assertEquals(1, sessions.size)
        assertEquals(1, count("upload"))
        assertEquals(1, maxActive)
    }

    @Test fun exitWaitsForInFlightHeartbeatAndSuppressesQueuedUploadAndTicks() = scenario {
        val release = CompletableDeferred<Unit>()
        heartbeatAction = { release.await(); applied() }
        val startup = controller.start()
        val tick = controller.tick()
        val exit = controller.manualExit()
        assertEquals(0, count("exit"))
        release.complete(Unit)
        startup.join(); tick.join(); exit.join()
        assertEquals(0, count("upload"))
        assertEquals(1, count("exit"))
        assertEquals(1, maxActive)
        assertEquals(PresenceStatus.STOPPED, state.presence)
    }

    @Test fun thrownRegistrationAndHeartbeatExceptionsExposeOnlySafeFailureCodes() = runBlocking {
        withFixture {
            registerAction = { error(SECRET) }
            controller.start().join()
            assertEquals(RuntimeControllerFailure.PERSISTENCE_FAILURE, state.lastSafeFailure)
            assertFalse(state.effectiveDecision.allowed)
            assertFalse(controller.toString().contains(SECRET))
            assertFalse(state.toString().contains(SECRET))
        }
        withFixture {
            heartbeatAction = { error(SECRET) }
            controller.start().join()
            assertEquals(RuntimeControllerFailure.ENTITLEMENT_FAILURE, state.lastSafeFailure)
            assertFalse(state.effectiveDecision.allowed)
            assertFalse(state.toString().contains(SECRET))
        }
    }

    @Test fun unexpectedRuntimeResultFailsSafelyWithoutClaimingPresence() = scenario {
        heartbeatResult = RuntimeResult.Registered
        controller.start().join()
        assertEquals(RuntimeControllerFailure.INVALID_RESPONSE, state.lastSafeFailure)
        assertEquals(PresenceStatus.UNAVAILABLE, state.presence)
    }

    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking { withFixture(block = block) }

    private suspend fun withFixture(interval: Long = 120, block: suspend Fixture.() -> Unit) {
        val fixture = Fixture(interval)
        try { fixture.block() } finally { fixture.scope.cancel() }
    }

    private class Clock(var now: Long = 0)

    /** Test-owned monotonic clock and timer queue: no wall-clock sleeps or coroutines-test dependency. */
    @OptIn(InternalCoroutinesApi::class)
    private class TimerDispatcher(private val clock: Clock) : CoroutineDispatcher(), Delay {
        private data class Timer(val at: Long, val continuation: CancellableContinuation<Unit>)
        private val timers = mutableListOf<Timer>()
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            timers += Timer(clock.now + timeMillis, continuation)
        }
        fun advanceTo(now: Long) {
            require(now >= clock.now)
            clock.now = now
            while (true) {
                val next = timers.filter { it.at <= now }.minByOrNull { it.at } ?: break
                timers.remove(next)
                if (next.continuation.isActive) next.continuation.resume(Unit)
            }
        }
    }

    private class Fixture(private val interval: Long) {
        val clock = Clock()
        val dispatcher = TimerDispatcher(clock)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val calls = mutableListOf<String>()
        val sessions = mutableListOf<String>()
        val modes = mutableListOf<HeartbeatMode>()
        var active = 0
        var maxActive = 0
        var registerResult: RuntimeResult = RuntimeResult.Registered
        var heartbeatResult: RuntimeResult = applied()
        var gateDecision = allowed
        var uploadResult: DiagnosticsUploadResult = DiagnosticsUploadResult.EmptySnapshot
        var recoverAction: suspend () -> Unit = {}
        var sessionAction: suspend () -> String = { "session-1" }
        var registerAction: suspend () -> RuntimeResult = { registerResult }
        var heartbeatAction: suspend () -> RuntimeResult = { heartbeatResult }
        var gateAction: () -> LicenseAccessDecision = { gateDecision }
        var uploadAction: suspend () -> DiagnosticsUploadResult = { uploadResult }
        var exitAction: suspend () -> Unit = {}

        private suspend fun <T> boundary(name: String, action: suspend () -> T): T {
            calls += name
            active++
            maxActive = maxOf(maxActive, active)
            return try { action() } finally { active-- }
        }

        val operations = object : RuntimeControllerOperations {
            override suspend fun register() = boundary("register", registerAction)
            override suspend fun heartbeat(appSessionId: String, mode: HeartbeatMode): RuntimeResult {
                sessions += appSessionId
                modes += mode
                return boundary("heartbeat:$mode", heartbeatAction)
            }
            override fun gate(): LicenseAccessDecision {
                calls += "gate"
                return gateAction()
            }
        }
        val diagnostics = object : RuntimeControllerDiagnostics {
            override suspend fun recoverPending() = boundary("recover", recoverAction)
            override suspend fun startSession() = boundary("session-committed", sessionAction)
            override suspend fun upload() = boundary("upload", uploadAction)
            override suspend fun manualExit() = boundary("exit", exitAction)
        }
        fun newController() = RuntimeController(operations, diagnostics, scope, dispatcher, { clock.now }, interval)
        val controller = newController()
        val state get() = controller.snapshot.value
        fun count(name: String) = calls.count { it == name }
    }

    companion object {
        private const val SECRET = "credential-super-secret https://private.invalid/token"
        private val allowed = decision(LicenseAccessState.ALLOWED)
        private val lockedPolicy = DevicePolicy(KioskMode.ALWAYS, false)
        private fun decision(state: LicenseAccessState) = LicenseAccessDecision(state)
        private fun applied(decision: LicenseAccessDecision = allowed) =
            RuntimeResult.HeartbeatApplied(decision, lockedPolicy, 120)
    }
}
