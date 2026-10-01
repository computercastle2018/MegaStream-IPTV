package com.MegaStream.app.controlplane.runtime

import com.MegaStream.app.controlplane.*
import java.util.UUID
import java.util.concurrent.CancellationException

/** Explicit, caller-driven operations: no scheduling, retries, credential generation, or IO dispatch.
 * Run on an external IO dispatcher because the transport is blocking. Persistent ports, not this
 * instance, own cross-instance atomicity. Never authorize playback from a cached runtime result;
 * call AppEntitlement.gate immediately before protected actions.
 */
class RuntimeCoordinator(
    private val credentials: InstallationCredentials,
    private val client: ControlPlaneRuntimeClient,
    private val registrationStore: RegistrationAttemptStore,
    private val sequenceStore: HeartbeatSequenceStore,
    private val entitlement: InstallationScopedEntitlement,
    private val updateSink: RuntimeUpdateCommandSink,
    private val providerSink: RuntimeProviderAssignmentSink,
    private val mapper: EntitlementResponseMapper = EntitlementResponseMapper(),
) {
    suspend fun register(metadata: RuntimeMetadata): RuntimeResult {
        var stage = RuntimeFailureCode.PERSISTENCE_FAILURE
        return try {
            if (!bound()) return mismatch()
            val attempt = registrationStore.getOrCreate(credentials.installationId)
            if (attempt.installationId != credentials.installationId) return mismatch()
            if (!canonicalUuid(attempt.idempotencyKey)) return RuntimeResult.Failure(RuntimeFailureCode.PERSISTENCE_FAILURE)
            if (attempt.registered) return RuntimeResult.Registered
            stage = RuntimeFailureCode.INVALID_REQUEST
            val request = RegistrationRequest(credentials.installationId, credentials.credential,
                metadata.appVersionCode, metadata.appVersionName, metadata.manufacturer, metadata.model,
                metadata.androidApi, metadata.androidRelease, metadata.abi, metadata.locale,
                metadata.managedDevice, metadata.packageName, metadata.channel)
            stage = RuntimeFailureCode.TRANSPORT_FAILURE
            when (val result = client.register(request, attempt.idempotencyKey)) {
                is ControlPlaneResult.Failure -> RuntimeResult.Failure(stage)
                is ControlPlaneResult.Success -> {
                    if (result.value.installationId != credentials.installationId) return mismatch()
                    stage = RuntimeFailureCode.INVALID_RESPONSE
                    val registeredSeconds = java.time.Instant.parse(result.value.registeredAt).epochSecond
                    if (registeredSeconds !in 0..253402300799L) return RuntimeResult.Failure(stage)
                    stage = RuntimeFailureCode.PERSISTENCE_FAILURE
                    registrationStore.markRegistered(credentials.installationId, attempt.idempotencyKey)
                    RuntimeResult.Registered
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            RuntimeResult.Failure(stage)
        }
    }

    suspend fun heartbeat(
        metadata: RuntimeMetadata,
        appSessionId: String,
        mode: HeartbeatMode,
        memory: MemorySnapshot? = null,
        recoveredExit: RecoveredExit? = null,
    ): RuntimeResult {
        var stage = RuntimeFailureCode.PERSISTENCE_FAILURE
        return try {
            if (!bound()) return mismatch()
            val sequence = sequenceStore.allocateNext(credentials.installationId)
            if (sequence < 0) return refreshFailure(RuntimeFailureCode.PERSISTENCE_FAILURE)
            stage = RuntimeFailureCode.INVALID_REQUEST
            val request = HeartbeatRequest(appSessionId, sequence, mode, metadata.appVersionCode,
                metadata.appVersionName, metadata.managedDevice, memory, recoveredExit,
                metadata.packageName, metadata.channel)
            stage = RuntimeFailureCode.TRANSPORT_FAILURE
            when (val result = client.heartbeat(credentials.credential, request)) {
                is ControlPlaneResult.Failure -> {
                    if (result.error.status == 403 || result.error.code == "forbidden") {
                        return RuntimeResult.Failure(RuntimeFailureCode.ENTITLEMENT_FAILURE)
                    }
                    refreshFailure(stage)
                }
                is ControlPlaneResult.Success -> {
                    val response = result.value
                    val mapped = mapper.map(response)
                    val policy = response.devicePolicy
                    if (mapped == null || policy == null) return refreshFailure(RuntimeFailureCode.INVALID_RESPONSE)
                    stage = RuntimeFailureCode.ENTITLEMENT_FAILURE
                    val effective = entitlement.entitlement.applyOnlineDecision(mapped, response.lease)
                    val dispatch = dispatchUpdate(response.updateCommand)
                    RuntimeResult.HeartbeatApplied(effective, policy, response.refreshAfterSeconds, dispatch)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            // Failed application may leave a stale ALLOWED observation in the backing store.
            // Do not recover that observation as an effective authorization result.
            if (stage == RuntimeFailureCode.ENTITLEMENT_FAILURE) RuntimeResult.Failure(stage)
            else refreshFailure(stage)
        }
    }

    suspend fun refreshProviderAssignments(afterRevision: Long): RuntimeResult {
        var stage = RuntimeFailureCode.TRANSPORT_FAILURE
        return try {
            if (!bound()) return mismatch()
            if (afterRevision < 0) return RuntimeResult.Failure(RuntimeFailureCode.INVALID_REQUEST)
            when (val result = client.getProviderAssignments(credentials.credential, afterRevision)) {
                is ControlPlaneResult.Failure -> RuntimeResult.Failure(stage)
                is ControlPlaneResult.Success -> {
                    val response = result.value
                    if (!validProviders(response, afterRevision)) return RuntimeResult.Failure(RuntimeFailureCode.INVALID_RESPONSE)
                    stage = RuntimeFailureCode.SINK_FAILURE
                    providerSink.apply(credentials.installationId, response)
                    RuntimeResult.ProvidersApplied(response.serverRevision)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            RuntimeResult.Failure(stage)
        }
    }

    private suspend fun dispatchUpdate(command: UpdateCommand?): RuntimeUpdateDispatch {
        if (command == null) return RuntimeUpdateDispatch.NONE
        return try {
            updateSink.dispatchOnce(credentials.installationId, command)
            RuntimeUpdateDispatch.ACCEPTED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            // An optional command failure cannot discard an authenticated entitlement or kiosk policy.
            RuntimeUpdateDispatch.FAILED
        }
    }

    private suspend fun refreshFailure(code: RuntimeFailureCode): RuntimeResult.Failure = try {
        RuntimeResult.Failure(code, entitlement.entitlement.recordRefreshFailure())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (_: Exception) {
        RuntimeResult.Failure(RuntimeFailureCode.ENTITLEMENT_FAILURE)
    }

    private fun bound() = entitlement.installationId == credentials.installationId
    private fun mismatch() = RuntimeResult.Failure(RuntimeFailureCode.INSTALLATION_MISMATCH)

    private fun canonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun validProviders(response: ProviderAssignmentsResponse, after: Long): Boolean {
        if (response.serverRevision < after) return false
        val ids = mutableSetOf<String>()
        for (assignment in response.assignments) {
            if (!ids.add(assignment.assignmentId)) return false
        }
        for (tombstone in response.tombstones) {
            if (!ids.add(tombstone.assignmentId)) return false
        }
        return true
    }
}
