package com.MegaStream.app.controlplane.runtime

import com.MegaStream.app.controlplane.*
import com.MegaStream.domain.licensing.*
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeCoordinatorTest {
    @Test fun registrationRetryAfterRecreationRetainsAttempt() = runBlocking {
        val f = Fixture()
        f.client.registration = failure()
        assertFailure(f.coordinator().register(META), RuntimeFailureCode.TRANSPORT_FAILURE)
        f.client.registration = ControlPlaneResult.Success(RegistrationResponse(ID, NOW))
        assertEquals(RuntimeResult.Registered, f.coordinator().register(META))
        assertEquals(listOf(KEY, KEY), f.client.keys)
        assertTrue(f.store.attempt.registered)
        assertEquals(ID, f.client.registrationRequest!!.installationId)
        assertEquals(SECRET, f.client.registrationRequest!!.credential)
    }

    @Test fun registeredInstallationSkipsTransportAfterRecreation() = runBlocking {
        val f = Fixture()
        f.coordinator().register(META)
        assertEquals(RuntimeResult.Registered, f.coordinator().register(META))
        assertEquals(1, f.client.keys.size)
    }

    @Test fun mismatchedRegistrationResponseNeverMarksAttempt() = runBlocking {
        val f = Fixture()
        f.client.registration = ControlPlaneResult.Success(RegistrationResponse(OTHER, NOW))
        assertFailure(f.coordinator().register(META), RuntimeFailureCode.INSTALLATION_MISMATCH)
        assertFalse(f.store.attempt.registered)
    }

    @Test fun invalidRegistrationTimeNeverMarksAttempt() = runBlocking {
        val f = Fixture()
        f.client.registration = ControlPlaneResult.Success(RegistrationResponse(ID, "1969-12-31T23:59:59Z"))
        assertFailure(f.coordinator().register(META), RuntimeFailureCode.INVALID_RESPONSE)
        assertFalse(f.store.attempt.registered)
    }

    @Test fun foreignPersistedAttemptNeverReachesTransport() = runBlocking {
        val f = Fixture()
        f.store.attempt = RegistrationAttempt(OTHER, KEY, false)
        assertFailure(f.coordinator().register(META), RuntimeFailureCode.INSTALLATION_MISMATCH)
        assertTrue(f.client.keys.isEmpty())
    }

    @Test fun corruptAttemptKeyNeverReachesTransport() = runBlocking {
        val f = Fixture()
        f.store.attempt = RegistrationAttempt(ID, "credential=$SECRET", false)
        assertFailure(f.coordinator().register(META), RuntimeFailureCode.PERSISTENCE_FAILURE)
        assertTrue(f.client.keys.isEmpty())
    }

    @Test fun persistenceFailureDoesNotInventSuccessfulRegistration() = runBlocking {
        val f = Fixture()
        f.store.markError = IllegalStateException(SECRET)
        assertFailure(f.coordinator().register(META), RuntimeFailureCode.PERSISTENCE_FAILURE)
        assertFalse(f.store.attempt.registered)
        f.store.markError = null
        f.coordinator().register(META)
        assertEquals(listOf(KEY, KEY), f.client.keys)
    }

    @Test fun heartbeatSequencesSurviveFailedSendAndCoordinatorRecreation() = runBlocking {
        val f = Fixture()
        f.client.heartbeatResult = failure()
        f.beat()
        f.client.heartbeatResult = ControlPlaneResult.Success(response())
        f.beat()
        assertEquals(listOf(0L, 1L), f.client.requests.map { it.sequence })
        assertEquals(2L, f.sequence.next)
        assertEquals(SECRET, f.client.heartbeatCredential)
    }

    @Test fun sequenceFailurePreventsSendAndPreservesEntitlement() = runBlocking {
        val f = Fixture()
        f.sequence.error = IllegalStateException(SECRET)
        val result = f.beat()
        assertFailure(result, RuntimeFailureCode.PERSISTENCE_FAILURE)
        assertSame(f.entitlement.effective, (result as RuntimeResult.Failure).decision)
        assertTrue(f.client.requests.isEmpty())
        assertEquals(1, f.entitlement.failures)
    }

    @Test fun negativeAllocatedSequenceFailsClosed() = runBlocking {
        val f = Fixture()
        f.sequence.next = -1
        assertFailure(f.beat(), RuntimeFailureCode.PERSISTENCE_FAILURE)
        assertTrue(f.client.requests.isEmpty())
    }

    @Test fun requestMetadataMemoryAndRecoveredExitReachHeartbeat() = runBlocking {
        val f = Fixture()
        val memory = MemorySnapshot(1, 2, 3, 4, 5, false)
        val exit = RecoveredExit(ExitReason.ANR, ExitEvidence.RECOVERED_OS, NOW)
        f.coordinator().heartbeat(META, SESSION, HeartbeatMode.PLAYBACK, memory, exit)
        val request = f.client.requests.single()
        assertEquals(SESSION, request.appSessionId)
        assertEquals(HeartbeatMode.PLAYBACK, request.mode)
        assertEquals(META.packageName, request.packageName)
        assertEquals(META.appVersionCode, request.appVersionCode)
        assertEquals(memory, request.memory)
        assertEquals(exit, request.recoveredExit)
    }

    @Test fun allAuthenticatedStatesReachEntitlementWithoutInventedLease() = runBlocking {
        for (state in EntitlementState.entries) {
            val f = Fixture()
            val wire = response(state)
            f.client.heartbeatResult = ControlPlaneResult.Success(wire)
            assertTrue(f.beat() is RuntimeResult.HeartbeatApplied)
            assertEquals(state.name, f.entitlement.applied.single().first.state.name)
            assertEquals(wire.lease, f.entitlement.applied.single().second)
            assertEquals(0, f.entitlement.failures)
        }
    }

    @Test fun opaqueLeaseIsUnchangedAndEffectiveStickyDecisionIsReturned() = runBlocking {
        val f = Fixture()
        f.entitlement.effective = LicenseAccessDecision(LicenseAccessState.REVOKED)
        val result = f.beat() as RuntimeResult.HeartbeatApplied
        assertEquals("opaque.not-a-verified.jwt", f.entitlement.applied.single().second)
        val mapped = f.entitlement.applied.single().first
        assertEquals(LICENSE, mapped.licenseId)
        assertEquals(1L, mapped.licenseRevision)
        assertEquals(1790726400L, mapped.serverEpochSeconds)
        assertEquals(1790640000L, mapped.startsAtEpochSeconds)
        assertEquals(1790812800L, mapped.endsAtEpochSeconds)
        assertEquals(1790812800L, mapped.offlineUntilEpochSeconds)
        assertSame(f.entitlement.effective, result.decision)
        assertEquals(LicenseAccessState.REVOKED, result.decision.state)
    }

    @Test fun devicePolicyAndRefreshTimingAreReturnedNotReinterpreted() = runBlocking {
        val f = Fixture()
        val policy = DevicePolicy(KioskMode.ALWAYS, false)
        f.client.heartbeatResult = ControlPlaneResult.Success(response().copy(devicePolicy = policy, refreshAfterSeconds = 17))
        val result = f.beat() as RuntimeResult.HeartbeatApplied
        assertEquals(policy, result.devicePolicy)
        assertEquals(17L, result.refreshAfterSeconds)
    }

    @Test fun missingHeartbeatPolicyPreservesPriorLeaseAndDoesNotDispatch() = runBlocking {
        val f = Fixture()
        f.client.heartbeatResult = ControlPlaneResult.Success(response().copy(devicePolicy = null, updateCommand = update()))
        assertFailure(f.beat(), RuntimeFailureCode.INVALID_RESPONSE)
        assertEquals(1, f.entitlement.failures)
        assertTrue(f.entitlement.applied.isEmpty())
        assertEquals(0, f.updates.effects)
    }

    @Test fun failedHeartbeatNeverSynthesizesDenialOrInvokesSinks() = runBlocking {
        val f = Fixture()
        f.client.heartbeatResult = failure()
        assertFailure(f.beat(), RuntimeFailureCode.TRANSPORT_FAILURE)
        assertTrue(f.entitlement.applied.isEmpty())
        assertEquals(1, f.entitlement.failures)
        assertEquals(0, f.updates.effects)
        assertEquals(0, f.providers.applied.size)
    }

    @Test fun forbiddenHeartbeatNeverReturnsCachedAllowedDecisionOrInvokesPorts() = runBlocking {
        val errors = listOf(
            ControlPlaneError.unknown(403),
            ControlPlaneError.fromProblem(
                """{"title":"Forbidden","status":401,"code":"forbidden","traceId":"$KEY"}""", 401),
        )
        assertEquals("unknown", errors[0].code)
        assertEquals(401, errors[1].status)
        assertEquals("forbidden", errors[1].code)
        for (error in errors) {
            val f = Fixture()
            f.client.heartbeatResult = ControlPlaneResult.Failure(error)
            val result = f.beat()
            assertFailure(result, RuntimeFailureCode.ENTITLEMENT_FAILURE)
            assertNull((result as RuntimeResult.Failure).decision)
            assertEquals(LicenseAccessState.ALLOWED, f.entitlement.effective.state)
            assertEquals(LicenseAccessState.ALLOWED, f.entitlement.decision.value.state)
            assertEquals(0, f.entitlement.failures)
            assertTrue(f.entitlement.applied.isEmpty())
            assertEquals(0, f.updates.effects)
            assertTrue(f.providers.applied.isEmpty())
        }
    }

    @Test fun timeoutAndRetryableHeartbeatFailuresPreserveCachedDecisionAndDeadline() = runBlocking {
        for (state in listOf(LicenseAccessState.ALLOWED, LicenseAccessState.EXPIRED)) {
            for (status in listOf(null, 429, 500, 503)) {
                val f = Fixture()
                val cached = f.entitlement.effective.copy(state = state)
                f.entitlement.effective = cached
                f.entitlement.decision.value = cached
                val deadline = cached.offlineDeadlineEpochSeconds
                assertNotNull(deadline)
                if (status == null) f.client.error = SocketTimeoutException("timeout")
                else f.client.heartbeatResult = ControlPlaneResult.Failure(ControlPlaneError.unknown(status))
                val result = f.beat()
                assertFailure(result, RuntimeFailureCode.TRANSPORT_FAILURE)
                assertSame(cached, (result as RuntimeResult.Failure).decision)
                assertEquals(deadline, result.decision!!.offlineDeadlineEpochSeconds)
                assertSame(cached, f.entitlement.effective)
                assertEquals(cached, f.entitlement.decision.value)
                assertEquals(1, f.entitlement.failures)
                assertTrue(f.entitlement.applied.isEmpty())
                assertEquals(0, f.updates.effects)
                assertTrue(f.providers.applied.isEmpty())
            }
        }
    }

    @Test fun invalidTemporalOrderNeverMutatesEntitlementOrDispatchesUpdate() = runBlocking {
        val base = response()
        val invalid = listOf(
            base.copy(decision = base.decision.copy(startsAt = END, endsAt = START)),
            base.copy(decision = base.decision.copy(startsAt = START, endsAt = START)),
            base.copy(decision = base.decision.copy(startsAt = END)),
            base.copy(serverTime = END),
            base.copy(decision = base.decision.copy(offlineUntil = NOW)),
            response(EntitlementState.NOT_STARTED).copy(serverTime = END),
            response(EntitlementState.EXPIRED).copy(serverTime = START),
        )
        for (wire in invalid) {
            val f = Fixture()
            f.client.heartbeatResult = ControlPlaneResult.Success(wire.copy(updateCommand = update()))
            assertFailure(f.beat(), RuntimeFailureCode.INVALID_RESPONSE)
            assertTrue(f.entitlement.applied.isEmpty())
            assertEquals(1, f.entitlement.failures)
            assertEquals(0, f.updates.effects)
        }
    }

    @Test fun unsafeEpochsAndRefreshDurationsAreRejected() {
        val mapper = EntitlementResponseMapper()
        val base = response()
        for (wire in listOf(
            base.copy(serverTime = "1969-12-31T23:59:59Z"),
            base.copy(serverTime = "1970-01-01T00:00:00Z"),
            base.copy(decision = base.decision.copy(startsAt = "0000-01-01T00:00:00Z")),
            base.copy(decision = base.decision.copy(startsAt = "1970-01-01T00:00:00Z")),
            base.copy(refreshAfterSeconds = Long.MAX_VALUE),
        )) assertNull(mapper.map(wire))
    }

    @Test fun maximumYearAndSafeMillisBoundaryRemainRepresentable() {
        val wire = response().copy(decision = response().decision.copy(
            endsAt = "9999-12-31T23:59:59Z", offlineUntil = "9999-12-31T23:59:59.999Z"),
            refreshAfterSeconds = Long.MAX_VALUE / 1000)
        val mapped = EntitlementResponseMapper().map(wire)!!
        assertEquals(253402300799L, mapped.endsAtEpochSeconds)
        assertTrue(mapped.offlineUntilEpochSeconds!! * 1000 > 0)
    }

    @Test fun fractionalTimesCannotCollapseIntoAnAllowedZeroWindow() {
        val base = response()
        val wire = base.copy(serverTime = "2026-09-30T00:00:00.100Z", decision = base.decision.copy(
            startsAt = "2026-09-30T00:00:00.000Z", endsAt = "2026-09-30T00:00:00.900Z"))
        assertNull(EntitlementResponseMapper().map(wire))
    }

    @Test fun disabledDenialPreservesKnownIdentityWithOptionalRevisionAndTimes() {
        val wire = response(EntitlementState.INSTALLATION_DISABLED).copy(decision = EntitlementDecision(
            EntitlementState.INSTALLATION_DISABLED, licenseId = LICENSE, startsAt = START))
        val mapped = EntitlementResponseMapper().map(wire)!!
        assertEquals(LicenseAccessState.INSTALLATION_DISABLED, mapped.state)
        assertEquals(LICENSE, mapped.licenseId)
        assertNull(mapped.licenseRevision)
        assertNotNull(mapped.startsAtEpochSeconds)
        assertNull(mapped.endsAtEpochSeconds)
    }

    @Test fun duplicateUpdateAcrossRecreationHasOneDurableSideEffect() = runBlocking {
        val f = Fixture()
        f.client.heartbeatResult = ControlPlaneResult.Success(response().copy(updateCommand = update()))
        f.beat()
        f.beat()
        assertEquals(1, f.updates.effects)
        assertEquals(setOf(ID to COMMAND), f.updates.claims)
        assertEquals(2, f.entitlement.applied.size)
    }

    @Test fun entitlementApplyFailurePreventsCommandDispatch() = runBlocking {
        val f = Fixture()
        f.entitlement.applyError = IllegalStateException(SECRET)
        f.client.heartbeatResult = ControlPlaneResult.Success(response().copy(updateCommand = update()))
        val result = f.beat()
        assertFailure(result, RuntimeFailureCode.ENTITLEMENT_FAILURE)
        assertNull((result as RuntimeResult.Failure).decision)
        assertEquals(LicenseAccessState.ALLOWED, f.entitlement.effective.state)
        assertEquals(LicenseAccessState.ALLOWED, f.entitlement.decision.value.state)
        assertEquals(0, f.updates.effects)
        assertEquals(0, f.entitlement.failures)
    }

    @Test fun updateSinkFailureIsRedactedAndClaimIsNotReplayed() = runBlocking {
        val f = Fixture()
        f.client.heartbeatResult = ControlPlaneResult.Success(response().copy(updateCommand = update()))
        f.updates.error = IllegalStateException(SECRET)
        val result = f.beat() as RuntimeResult.HeartbeatApplied
        assertEquals(RuntimeUpdateDispatch.FAILED, result.updateDispatch)
        assertSame(f.entitlement.effective, result.decision)
        assertEquals(DevicePolicy(KioskMode.OFF, true), result.devicePolicy)
        assertEquals(60L, result.refreshAfterSeconds)
        assertEquals(0, f.entitlement.failures)
        f.updates.error = null
        assertTrue(f.beat() is RuntimeResult.HeartbeatApplied)
        assertEquals(1, f.updates.effects)
    }

    @Test fun installationScopeMismatchStopsEveryPort() = runBlocking {
        val f = Fixture()
        val coordinator = f.coordinator(OTHER)
        assertFailure(coordinator.register(META), RuntimeFailureCode.INSTALLATION_MISMATCH)
        assertFailure(coordinator.heartbeat(META, SESSION, HeartbeatMode.FOREGROUND), RuntimeFailureCode.INSTALLATION_MISMATCH)
        assertFailure(coordinator.refreshProviderAssignments(0), RuntimeFailureCode.INSTALLATION_MISMATCH)
        assertEquals(0, f.store.reads)
        assertEquals(0L, f.sequence.next)
        assertEquals(0, f.entitlement.failures)
        assertTrue(f.client.requests.isEmpty())
        assertTrue(f.client.providerCursors.isEmpty())
    }

    @Test fun providerAuthenticatedSuccessAppliesOnlyValidatedRevision() = runBlocking {
        val f = Fixture()
        val wire = ProviderAssignmentsResponse(2, emptyList(), listOf(ProviderAssignmentTombstone(COMMAND, 2, NOW)))
        f.client.providers = ControlPlaneResult.Success(wire)
        assertEquals(RuntimeResult.ProvidersApplied(2), f.coordinator().refreshProviderAssignments(1))
        assertEquals(listOf(ID to wire), f.providers.applied)
        assertEquals(listOf(1L), f.client.providerCursors)
        assertEquals(SECRET, f.client.providerCredential)
    }

    @Test fun failedProviderRefreshNeverCallsSinkOrTouchesEntitlement() = runBlocking {
        val f = Fixture()
        f.client.providers = failure()
        assertFailure(f.coordinator().refreshProviderAssignments(0), RuntimeFailureCode.TRANSPORT_FAILURE)
        assertTrue(f.providers.applied.isEmpty())
        assertEquals(0, f.entitlement.failures)
        assertTrue(f.entitlement.applied.isEmpty())
    }

    @Test fun regressedAndDuplicateProvidersNeverApply() = runBlocking {
        val tombstone = ProviderAssignmentTombstone(COMMAND, 2, NOW)
        for (wire in listOf(
            ProviderAssignmentsResponse(0, emptyList(), emptyList()),
            ProviderAssignmentsResponse(2, emptyList(), listOf(tombstone, tombstone)),
        )) {
            val f = Fixture()
            f.client.providers = ControlPlaneResult.Success(wire)
            assertFailure(f.coordinator().refreshProviderAssignments(1), RuntimeFailureCode.INVALID_RESPONSE)
            assertTrue(f.providers.applied.isEmpty())
        }
    }

    @Test fun negativeProviderCursorDoesNotReachNetwork() = runBlocking {
        val f = Fixture()
        assertFailure(f.coordinator().refreshProviderAssignments(-1), RuntimeFailureCode.INVALID_REQUEST)
        assertTrue(f.client.providerCursors.isEmpty())
    }

    @Test fun exceptionsWithSecretsProduceOnlyClosedFailureCodes() = runBlocking {
        val f = Fixture()
        f.client.error = IllegalStateException("Bearer $SECRET https://secret.example/lease")
        val result = f.beat()
        assertFailure(result, RuntimeFailureCode.TRANSPORT_FAILURE)
        assertFalse(result.toString().contains(SECRET))
        assertFalse(result.toString().contains("secret.example"))
        assertTrue(result.toString().contains("[REDACTED]"))
        assertEquals(1, f.entitlement.failures)
    }

    @Test fun refreshFailurePortExceptionDoesNotEscapeOrRetry() = runBlocking {
        val f = Fixture()
        f.client.heartbeatResult = failure()
        f.entitlement.failureError = IllegalStateException(SECRET)
        val result = f.beat()
        assertFailure(result, RuntimeFailureCode.ENTITLEMENT_FAILURE)
        assertNull((result as RuntimeResult.Failure).decision)
        assertEquals(1, f.entitlement.failures)
    }

    @Test fun cancellationPropagatesFromEveryHeartbeatBoundary() = runBlocking {
        for (boundary in 0..4) {
            val f = Fixture()
            val cancel = CancellationException("cancelled")
            when (boundary) {
                0 -> f.sequence.error = cancel
                1 -> f.client.error = cancel
                2 -> f.entitlement.applyError = cancel
                3 -> {
                    f.client.heartbeatResult = ControlPlaneResult.Success(response().copy(updateCommand = update()))
                    f.updates.error = cancel
                }
                4 -> {
                    f.client.heartbeatResult = failure()
                    f.entitlement.failureError = cancel
                }
            }
            try { f.beat(); fail("Cancellation swallowed at boundary $boundary") }
            catch (actual: CancellationException) { assertSame(cancel, actual) }
            assertEquals(if (boundary == 4) 1 else 0, f.entitlement.failures)
        }
    }

    @Test fun registrationAndProviderCancellationRemainCallerOwned() = runBlocking {
        val f = Fixture()
        val cancel = CancellationException("cancelled")
        f.store.markError = cancel
        try { f.coordinator().register(META); fail("Cancellation swallowed") }
        catch (actual: CancellationException) { assertSame(cancel, actual) }
        assertFalse(f.store.attempt.registered)
        f.providers.error = cancel
        try { f.coordinator().refreshProviderAssignments(0); fail("Cancellation swallowed") }
        catch (actual: CancellationException) { assertSame(cancel, actual) }
    }

    @Test fun fractionalFutureStartCannotBeFlooredIntoAllowedState() {
        val base = response()
        val wire = base.copy(serverTime = "2026-09-30T00:00:00.100Z", decision = base.decision.copy(
            startsAt = "2026-09-30T00:00:00.900Z"))
        assertNull(EntitlementResponseMapper().map(wire))
    }

    @Test fun assignmentRevisionsAreIndependentOfServerCursor() = runBlocking {
        val f = Fixture()
        f.client.providers = ControlPlaneResult.Success(ProviderAssignmentsResponse(10, emptyList(),
            listOf(ProviderAssignmentTombstone(COMMAND, 1, NOW))))
        assertEquals(RuntimeResult.ProvidersApplied(10), f.coordinator().refreshProviderAssignments(9))
        assertEquals(1, f.providers.applied.size)
    }

    @Test fun interruptionIsRethrownAndThreadFlagRestoredAtEveryBoundary() {
        for (boundary in 0..6) {
            val f = Fixture()
            val interruption = InterruptedException(SECRET)
            when (boundary) {
                0 -> f.sequence.error = interruption
                1 -> f.client.error = interruption
                2 -> f.entitlement.applyError = interruption
                3 -> {
                    f.client.heartbeatResult = ControlPlaneResult.Success(response().copy(updateCommand = update()))
                    f.updates.error = interruption
                }
                4 -> {
                    f.client.heartbeatResult = failure()
                    f.entitlement.failureError = interruption
                }
                5 -> f.store.markError = interruption
                6 -> f.providers.error = interruption
            }
            try {
                runBlocking {
                    when (boundary) {
                        5 -> f.coordinator().register(META)
                        6 -> f.coordinator().refreshProviderAssignments(0)
                        else -> f.beat()
                    }
                }
                fail("Interruption swallowed at boundary $boundary")
            } catch (actual: InterruptedException) {
                assertSame(interruption, actual)
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test fun arbitraryMetadataAndAttemptStringsAreRedacted() {
        assertFalse(META.copy(manufacturer = SECRET).toString().contains(SECRET))
        assertFalse(RegistrationAttempt(SECRET, SECRET, false).toString().contains(SECRET))
    }

    private class Fixture {
        val client = Client()
        val store = Attempts()
        val sequence = Sequences()
        val entitlement = Entitlement()
        val updates = Updates()
        val providers = Providers()
        fun coordinator(scope: String = ID) = RuntimeCoordinator(InstallationCredentials(ID, SECRET), client,
            store, sequence, InstallationScopedAppEntitlement(scope, entitlement), updates, providers)
        suspend fun beat() = coordinator().heartbeat(META, SESSION, HeartbeatMode.FOREGROUND)
    }

    private class Attempts : RegistrationAttemptStore {
        var attempt = RegistrationAttempt(ID, KEY, false)
        var reads = 0
        var markError: Exception? = null
        override suspend fun getOrCreate(installationId: String): RegistrationAttempt { reads++; return attempt }
        override suspend fun markRegistered(installationId: String, idempotencyKey: String) {
            markError?.let { throw it }
            check(attempt.installationId == installationId && attempt.idempotencyKey == idempotencyKey)
            attempt = attempt.copy(registered = true)
        }
    }
    private class Sequences : HeartbeatSequenceStore {
        var next = 0L
        var error: Exception? = null
        override suspend fun allocateNext(installationId: String): Long {
            error?.let { throw it }
            return next++
        }
    }
    private class Entitlement : AppEntitlement {
        var effective = LicenseAccessDecision(LicenseAccessState.ALLOWED, 123456789L)
        override val decision = MutableStateFlow(effective)
        var failures = 0
        var applyError: Exception? = null
        var failureError: Exception? = null
        val applied = mutableListOf<Pair<OnlineEntitlementDecision, String?>>()
        override suspend fun recordRefreshFailure(): LicenseAccessDecision {
            failures++
            failureError?.let { throw it }
            return effective
        }
        override suspend fun applyOnlineDecision(decision: OnlineEntitlementDecision, compactLease: String?): LicenseAccessDecision {
            applyError?.let { throw it }
            applied += decision to compactLease
            return effective
        }
        override fun gate() = effective
    }
    private class Updates : RuntimeUpdateCommandSink {
        val claims = mutableSetOf<Pair<String, String>>()
        var effects = 0
        var error: Exception? = null
        override suspend fun dispatchOnce(installationId: String, command: UpdateCommand) {
            if (!claims.add(installationId to command.commandId)) return
            effects++
            error?.let { throw it }
        }
    }
    private class Providers : RuntimeProviderAssignmentSink {
        val applied = mutableListOf<Pair<String, ProviderAssignmentsResponse>>()
        var error: Exception? = null
        override suspend fun apply(installationId: String, response: ProviderAssignmentsResponse) {
            error?.let { throw it }
            applied += installationId to response
        }
    }
    private class Client : ControlPlaneRuntimeClient {
        var registration: ControlPlaneResult<RegistrationResponse> = ControlPlaneResult.Success(RegistrationResponse(ID, NOW))
        var heartbeatResult: ControlPlaneResult<EntitlementResponse> = ControlPlaneResult.Success(response())
        var providers: ControlPlaneResult<ProviderAssignmentsResponse> = ControlPlaneResult.Success(ProviderAssignmentsResponse(0, emptyList(), emptyList()))
        var error: Exception? = null
        var registrationRequest: RegistrationRequest? = null
        var heartbeatCredential: String? = null
        var providerCredential: String? = null
        val keys = mutableListOf<String>()
        val requests = mutableListOf<HeartbeatRequest>()
        val providerCursors = mutableListOf<Long>()
        override fun register(request: RegistrationRequest, idempotencyKey: String): ControlPlaneResult<RegistrationResponse> {
            error?.let { throw it }; registrationRequest = request; keys += idempotencyKey; return registration
        }
        override fun heartbeat(credential: String, request: HeartbeatRequest): ControlPlaneResult<EntitlementResponse> {
            error?.let { throw it }; heartbeatCredential = credential; requests += request; return heartbeatResult
        }
        override fun getProviderAssignments(credential: String, afterRevision: Long): ControlPlaneResult<ProviderAssignmentsResponse> {
            error?.let { throw it }; providerCredential = credential; providerCursors += afterRevision; return providers
        }
        override fun diagnostics(credential: String, request: DiagnosticsBatchRequest): ControlPlaneResult<DiagnosticsBatchResponse> = error("Not used")
    }

    companion object {
        private const val ID = "00000000-0000-4000-8000-000000000001"
        private const val OTHER = "00000000-0000-4000-8000-000000000002"
        private const val KEY = "00000000-0000-4000-8000-000000000003"
        private const val SESSION = "00000000-0000-4000-8000-000000000004"
        private const val LICENSE = "00000000-0000-4000-8000-000000000005"
        private const val COMMAND = "00000000-0000-4000-8000-000000000006"
        private val SECRET = "A".repeat(43)
        private const val START = "2026-09-29T00:00:00Z"
        private const val NOW = "2026-09-30T00:00:00Z"
        private const val END = "2026-10-01T00:00:00Z"
        private val META = RuntimeMetadata(1, "1.0", "Maker", "Model", 30, "11", DeviceAbi.ARM64_V8A,
            "en", false, "com.MegaStream.app", ReleaseChannel.STABLE)
        private fun response(state: EntitlementState = EntitlementState.ALLOWED): EntitlementResponse {
            val licensed = state in setOf(EntitlementState.ALLOWED, EntitlementState.NOT_STARTED,
                EntitlementState.EXPIRED, EntitlementState.SUSPENDED, EntitlementState.REVOKED)
            return EntitlementResponse(EntitlementDecision(state, if (licensed) LICENSE else null,
                if (licensed) 1 else null, if (licensed) START else null, if (licensed) END else null,
                if (state == EntitlementState.ALLOWED) END else null),
                when (state) { EntitlementState.NOT_STARTED -> "2026-09-28T00:00:00Z"; EntitlementState.EXPIRED -> END; else -> NOW },
                60, if (state == EntitlementState.ALLOWED) "opaque.not-a-verified.jwt" else null,
                devicePolicy = DevicePolicy(KioskMode.OFF, true))
        }
        private fun update() = UpdateCommand(COMMAND, "release", 2, "2", false, InstallMode.PROMPT,
            "https://megastrem.megastation.uk/app.apk", "a".repeat(64), sizeBytes = 10, notes = "private notes")
        private fun failure() = ControlPlaneResult.Failure(ControlPlaneError.local("network_error"))
        private fun assertFailure(result: RuntimeResult, code: RuntimeFailureCode) {
            assertTrue("Expected $code, got $result", result is RuntimeResult.Failure)
            assertEquals(code, (result as RuntimeResult.Failure).code)
        }
    }
}
