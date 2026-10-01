package com.MegaStream.app.controlplane.runtime

import com.MegaStream.app.controlplane.*
import com.MegaStream.domain.licensing.AppEntitlement
import com.MegaStream.domain.licensing.LicenseAccessDecision

/** Blocking authenticated transport: callers must run the coordinator on an external IO dispatcher.
 * Success must be fresh and bound to the requested credential and installation. Entitlement responses
 * have no installation ID: this binding is a transport guarantee, not inferred from response data.
 */
interface ControlPlaneRuntimeClient {
    fun register(request: RegistrationRequest, idempotencyKey: String): ControlPlaneResult<RegistrationResponse>
    fun heartbeat(credential: String, request: HeartbeatRequest): ControlPlaneResult<EntitlementResponse>
    fun diagnostics(credential: String, request: DiagnosticsBatchRequest): ControlPlaneResult<DiagnosticsBatchResponse>
    fun getProviderAssignments(credential: String, afterRevision: Long): ControlPlaneResult<ProviderAssignmentsResponse>
}

class ControlPlaneRuntimeClientAdapter(private val client: ControlPlaneClient) : ControlPlaneRuntimeClient {
    override fun register(request: RegistrationRequest, idempotencyKey: String) = client.register(request, idempotencyKey)
    override fun heartbeat(credential: String, request: HeartbeatRequest) = client.heartbeat(credential, request)
    override fun diagnostics(credential: String, request: DiagnosticsBatchRequest) = client.diagnostics(credential, request)
    override fun getProviderAssignments(credential: String, afterRevision: Long) = client.getProviderAssignments(credential, afterRevision)
}

/** Non-secret device/application metadata only; credentials are injected separately. */
data class RuntimeMetadata(
    val appVersionCode: Long,
    val appVersionName: String,
    val manufacturer: String,
    val model: String,
    val androidApi: Int,
    val androidRelease: String,
    val abi: DeviceAbi,
    val locale: String,
    val managedDevice: Boolean,
    val packageName: String,
    val channel: ReleaseChannel,
) {
    override fun toString() = "RuntimeMetadata([REDACTED])"
}

data class RegistrationAttempt(val installationId: String, val idempotencyKey: String, val registered: Boolean) {
    override fun toString() = "RegistrationAttempt([REDACTED])"
}

/** Durable, atomic across instances/processes. getOrCreate commits one UUID before returning and
 * retains the same installation-bound attempt across retries/recreation until success. Unreadable
 * state must throw, never reset. markRegistered atomically checks BOTH ID and key, and is idempotent.
 * Credential rotation must be coordinated externally; an installation cannot silently change secrets.
 */
interface RegistrationAttemptStore {
    suspend fun getOrCreate(installationId: String): RegistrationAttempt
    suspend fun markRegistered(installationId: String, idempotencyKey: String)
}

/** Atomically persist a strictly increasing nonnegative sequence BEFORE returning, shared across
 * instances/processes for an installation. Failed sends and crashes consume numbers. Never reuse,
 * reset, wrap, or fall back on unreadable state; throw on exhaustion or persistence failure.
 */
fun interface HeartbeatSequenceStore {
    suspend fun allocateNext(installationId: String): Long
}

/** The owner guarantees the backing AppEntitlement is bound to this installation AND the exact
 * injected credential. AppEntitlement exposes neither identity; the runtime checks the declared ID,
 * while the credential/backing-store assertion remains the composition owner's responsibility.
 */
interface InstallationScopedEntitlement {
    val installationId: String
    val entitlement: AppEntitlement
}

class InstallationScopedAppEntitlement(
    override val installationId: String,
    override val entitlement: AppEntitlement,
) : InstallationScopedEntitlement

/** Durable dispatch-once for (installationId, command.commandId), atomic across instances/processes.
 * Persist a claim before any side effect, never release it on exception/cancellation, and reject
 * conflicting payloads for an existing key using a safe digest, not retained URLs/notes/secrets.
 * The durable ledger retains IDs and safe state only. A crash in the claim/dispatch gap sacrifices execution
 * rather than replaying. Repeated calls must not repeat side effects. Adapt update models externally.
 */
fun interface RuntimeUpdateCommandSink {
    suspend fun dispatchOnce(installationId: String, command: UpdateCommand)
}

/** Atomically apply installation-scoped revisions, deduplicating retries and rejecting rollback.
 * Persist changes and the revision together; a failure must not advance the durable cursor alone.
 * Consume synchronously or deep-copy lists and configuration header maps before retaining them.
 * Implementations must treat configurations as secrets and never log the response.
 */
fun interface RuntimeProviderAssignmentSink {
    suspend fun apply(installationId: String, response: ProviderAssignmentsResponse)
}

enum class RuntimeFailureCode {
    INSTALLATION_MISMATCH, INVALID_REQUEST, INVALID_RESPONSE, TRANSPORT_FAILURE,
    PERSISTENCE_FAILURE, ENTITLEMENT_FAILURE, SINK_FAILURE,
}

enum class RuntimeUpdateDispatch { NONE, ACCEPTED, FAILED }

/** No raw transport objects, credentials, opaque leases, or exception details leave the coordinator. */
sealed interface RuntimeResult {
    data object Registered : RuntimeResult {
        override fun toString() = "RuntimeResult.Registered([REDACTED])"
    }
    data class HeartbeatApplied(
        val decision: LicenseAccessDecision,
        val devicePolicy: DevicePolicy,
        val refreshAfterSeconds: Long,
        val updateDispatch: RuntimeUpdateDispatch = RuntimeUpdateDispatch.NONE,
    ) : RuntimeResult {
        override fun toString() = "RuntimeResult.HeartbeatApplied([REDACTED])"
    }
    data class ProvidersApplied(val serverRevision: Long) : RuntimeResult {
        override fun toString() = "RuntimeResult.ProvidersApplied([REDACTED])"
    }
    data class Failure(val code: RuntimeFailureCode, val decision: LicenseAccessDecision? = null) : RuntimeResult {
        override fun toString() = "RuntimeResult.Failure(code=$code, [REDACTED])"
    }
}
