package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.*
import com.MegaStream.app.controlplane.runtime.*
import com.MegaStream.domain.licensing.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProductionRuntimeOperationsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun failedRegistrationReplaysFrozenPayloadAndKeyThroughRealCoordinatorAfterRestart() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val client = RecordingClient()
        client.registrationResult = ControlPlaneResult.Failure(ControlPlaneError.local("network_error"))

        assertEquals(RuntimeResult.Failure(RuntimeFailureCode.TRANSPORT_FAILURE),
            operations(directory, client) { ORIGINAL }.register())
        val originalRequest = client.registrations.single()
        assertEquals(expectedRegistration(), originalRequest.first)
        assertFalse(originalRequest.second.isBlank())

        client.registrationResult = ControlPlaneResult.Success(RegistrationResponse(ID, NOW))
        // New adapter, coordinator AND durable stores: no in-memory payload/attempt can survive.
        assertEquals(RuntimeResult.Registered, operations(directory, client) { UPGRADED }.register())
        assertEquals(listOf(originalRequest, originalRequest), client.registrations)

        assertEquals(RuntimeResult.Registered, operations(directory, client) { UPGRADED }.register())
        assertEquals("Persisted success must suppress a third network registration", 2, client.registrations.size)
    }

    @Test fun successfulRegistrationDoesNotSendAgainFromFreshInstance() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val client = RecordingClient()

        assertEquals(RuntimeResult.Registered, operations(directory, client) { ORIGINAL }.register())
        assertEquals(RuntimeResult.Registered, operations(directory, client) { UPGRADED }.register())

        assertEquals(listOf(expectedRegistration()), client.registrations.map { it.first })
    }

    @Test fun heartbeatUsesLiveMetadataRatherThanFrozenRegistrationPayload() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val client = RecordingClient()
        var current = ORIGINAL
        val operations = operations(directory, client) { current }
        assertEquals(RuntimeResult.Registered, operations.register())
        current = UPGRADED

        val heartbeatResult = operations.heartbeat(SESSION, HeartbeatMode.BACKGROUND)

        assertEquals(RuntimeResult.Failure(RuntimeFailureCode.TRANSPORT_FAILURE,
            LicenseAccessDecision(LicenseAccessState.VERIFICATION_REQUIRED)), heartbeatResult)
        assertEquals(listOf(SECRET to HeartbeatRequest(
            appSessionId = SESSION, sequence = 0, mode = HeartbeatMode.BACKGROUND,
            appVersionCode = UPGRADED.appVersionCode, appVersionName = UPGRADED.appVersionName,
            managedDevice = UPGRADED.managedDevice, packageName = UPGRADED.packageName,
            channel = UPGRADED.channel,
        )), client.heartbeats)
    }

    @Test fun successfulHeartbeatUploadsInventoryAndInventoryFailureDoesNotChangeEntitlement() = runBlocking {
        val client = RecordingClient()
        client.heartbeatResult = ControlPlaneResult.Success(EntitlementResponse(
            EntitlementDecision(EntitlementState.UNLICENSED), NOW, 120,
            devicePolicy = DevicePolicy(KioskMode.OFF, true),
        ))
        var uploads = 0
        val operations = operations(temporaryFolder.newFolder(), client, upload = {
            uploads++
            throw java.io.IOException("Inventory transport unavailable")
        }, metadata = { ORIGINAL })
        assertEquals(RuntimeResult.Registered, operations.register())
        val first = operations.heartbeat(SESSION, HeartbeatMode.FOREGROUND)
        assertEquals(RuntimeResult.HeartbeatApplied(LicenseAccessDecision(LicenseAccessState.VERIFICATION_REQUIRED),
            DevicePolicy(KioskMode.OFF, true), 120), first)
        assertEquals(first, operations.heartbeat(SESSION, HeartbeatMode.FOREGROUND))
        assertEquals(2, uploads)
        client.heartbeatResult = ControlPlaneResult.Failure(ControlPlaneError.local("network_error"))
        operations.heartbeat(SESSION, HeartbeatMode.FOREGROUND)
        assertEquals(2, uploads)
    }

    private fun operations(
        directory: File,
        client: RecordingClient,
        upload: suspend () -> Unit = {},
        metadata: () -> RuntimeMetadata,
    ): ProductionRuntimeOperations {
        val stores = DurableRuntimeStores(directory)
        val entitlement = UnverifiedEntitlement()
        val coordinator = RuntimeCoordinator(
            credentials = InstallationCredentials(ID, SECRET),
            client = client,
            registrationStore = stores,
            sequenceStore = stores,
            entitlement = InstallationScopedAppEntitlement(ID, entitlement),
            updateSink = RuntimeUpdateCommandSink { _, _ -> error("Unexpected update") },
            providerSink = RuntimeProviderAssignmentSink { _, _ -> error("Unexpected provider assignment") },
        )
        return ProductionRuntimeOperations(ID, coordinator, stores, entitlement, metadata, upload)
    }

    private class RecordingClient : ControlPlaneRuntimeClient {
        var registrationResult: ControlPlaneResult<RegistrationResponse> =
            ControlPlaneResult.Success(RegistrationResponse(ID, NOW))
        val registrations = mutableListOf<Pair<RegistrationRequest, String>>()
        val heartbeats = mutableListOf<Pair<String, HeartbeatRequest>>()
        var heartbeatResult: ControlPlaneResult<EntitlementResponse> = ControlPlaneResult.Failure(ControlPlaneError.local("network_error"))

        override fun register(request: RegistrationRequest, idempotencyKey: String): ControlPlaneResult<RegistrationResponse> {
            registrations += request to idempotencyKey
            return registrationResult
        }

        override fun heartbeat(credential: String, request: HeartbeatRequest): ControlPlaneResult<EntitlementResponse> {
            heartbeats += credential to request
            return heartbeatResult
        }

        override fun diagnostics(credential: String, request: DiagnosticsBatchRequest): ControlPlaneResult<DiagnosticsBatchResponse> =
            error("Unexpected diagnostics")

        override fun getProviderAssignments(credential: String, afterRevision: Long): ControlPlaneResult<ProviderAssignmentsResponse> =
            error("Unexpected provider assignment")
    }

    private class UnverifiedEntitlement : AppEntitlement {
        override val decision = MutableStateFlow(LicenseAccessDecision(LicenseAccessState.VERIFICATION_REQUIRED))
        override fun gate(): LicenseAccessDecision = decision.value
        override suspend fun recordRefreshFailure(): LicenseAccessDecision = decision.value
        override suspend fun applyOnlineDecision(decision: OnlineEntitlementDecision, compactLease: String?): LicenseAccessDecision =
            this.decision.value
    }

    companion object {
        private const val ID = "00000000-0000-4000-8000-000000000001"
        private const val SESSION = "00000000-0000-4000-8000-000000000002"
        private const val NOW = "2026-09-30T00:00:00Z"
        private val SECRET = "A".repeat(43)
        private val ORIGINAL = RuntimeMetadata(
            1, "1.0", "Maker", "Model", 30, "11", DeviceAbi.ARM64_V8A,
            "en", false, "com.MegaStream.app", ReleaseChannel.STABLE,
        )
        private val UPGRADED = RuntimeMetadata(
            2, "2.0", "NewMaker", "NewModel", 35, "15", DeviceAbi.X86_64,
            "ar", true, "com.MegaStream.upgraded", ReleaseChannel.BETA,
        )
        private fun expectedRegistration() = RegistrationRequest(
            ID, SECRET, 1, "1.0", "Maker", "Model", 30, "11", DeviceAbi.ARM64_V8A,
            "en", false, "com.MegaStream.app", ReleaseChannel.STABLE,
        )
    }
}
