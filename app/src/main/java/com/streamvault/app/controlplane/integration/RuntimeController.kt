package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.DevicePolicy
import com.MegaStream.app.controlplane.HeartbeatMode
import com.MegaStream.app.controlplane.KioskMode
import com.MegaStream.app.controlplane.runtime.DiagnosticsUploadResult
import com.MegaStream.app.controlplane.runtime.RuntimeFailureCode
import com.MegaStream.app.controlplane.runtime.RuntimeResult
import com.MegaStream.app.controlplane.runtime.RuntimeUpdateDispatch
import com.MegaStream.domain.licensing.LicenseAccessDecision
import com.MegaStream.domain.licensing.LicenseAccessState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RuntimeControllerOperations {
    suspend fun register(): RuntimeResult
    suspend fun heartbeat(appSessionId: String, mode: HeartbeatMode): RuntimeResult
    fun gate(): LicenseAccessDecision
}

interface RuntimeControllerDiagnostics {
    /** Idempotently reconciles previous sessions; failure prevents creating or uploading a new session. */
    suspend fun recoverPending()
    /** Returns only after a new session and its AppStarted event are durably committed. */
    suspend fun startSession(): String
    suspend fun upload(): DiagnosticsUploadResult
    suspend fun manualExit()
}

/**
 * One IO lane for all runtime and diagnostic operations. Background is not an exit. Network work is
 * rate limited even for explicit ticks; every tick still recomputes the local entitlement gate.
 * Recreate only after repairing failed session/storage state; no automatic uploader recovery occurs.
 */
class RuntimeController(
    private val operations: RuntimeControllerOperations,
    private val diagnostics: RuntimeControllerDiagnostics,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    heartbeatIntervalSeconds: Long = 120,
) {
    private val lane = Mutex()
    private val lifecycle = Any()
    private val mutableSnapshot = MutableStateFlow(RuntimeSnapshot())
    val snapshot: StateFlow<RuntimeSnapshot> = mutableSnapshot.asStateFlow()

    private var startJob: Job? = null
    private var exitJob: Job? = null
    private var scheduler: Job? = null
    @Volatile private var exitRequested = false
    @Volatile private var intervalMillis = heartbeatIntervalSeconds.coerceIn(30, 300) * 1_000
    private var inForeground = true
    private var sessionId: String? = null
    private var startupBlocked = false
    private var uploadBlocked = false
    private var verificationRequired = false
    private var authoritativeDenial: LicenseAccessDecision? = null
    private var lastAttemptCompletedAt: Long? = null

    /** Concurrent/repeated callers share the same startup job, including after startup failure. */
    fun start(): Job = synchronized(lifecycle) {
        startJob ?: scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            lane.withLock {
                if (exitRequested) return@withLock
                if (!prepareSession()) return@withLock
                runTick()
                synchronized(lifecycle) {
                    if (!exitRequested) scheduler = launchScheduler()
                }
            }
        }.also { startJob = it; it.start() }
    }

    fun foreground(value: Boolean): Job = scope.launch(ioDispatcher) {
        lane.withLock {
            if (exitRequested) return@withLock
            val changed = inForeground != value
            inForeground = value
            if (changed) runTick()
        }
    }

    fun tick(): Job = scope.launch(ioDispatcher) {
        lane.withLock { if (!exitRequested) runTick() }
    }

    /** Explicit, terminal, exactly-once exit. Queued ticks and a later start cannot reopen a session. */
    fun manualExit(): Job = synchronized(lifecycle) {
        exitJob ?: run {
            exitRequested = true
            scheduler?.cancel()
            scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                lane.withLock {
                    try {
                        if (sessionId != null) diagnostics.manualExit()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failure(RuntimeControllerFailure.EXIT_FAILURE)
                    } finally {
                        mutableSnapshot.value = mutableSnapshot.value.copy(presence = PresenceStatus.STOPPED)
                    }
                }
            }.also { exitJob = it; it.start() }
        }
    }

    private fun launchScheduler(): Job = scope.launch(ioDispatcher) {
        while (isActive && !exitRequested) {
            delay(intervalMillis)
            lane.withLock {
                if (!exitRequested && inForeground) runTick()
            }
        }
    }

    private suspend fun prepareSession(): Boolean {
        var stage = RuntimeControllerFailure.RECOVERY_FAILURE
        return try {
            diagnostics.recoverPending()
            if (exitRequested) return false
            stage = RuntimeControllerFailure.SESSION_FAILURE
            sessionId = diagnostics.startSession().also { require(it.isNotBlank()) }
            !exitRequested
        } catch (cancelled: CancellationException) {
            startupBlocked = true
            throw cancelled
        } catch (_: Exception) {
            startupBlocked = true
            blockVerification(stage)
            false
        }
    }

    private suspend fun runTick() {
        recomputeGate()
        if (sessionId == null || startupBlocked || exitRequested) return
        val now = try {
            monotonicMillis()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            blockVerification(RuntimeControllerFailure.PERSISTENCE_FAILURE)
            return
        }
        val previous = lastAttemptCompletedAt
        if (previous != null) {
            // A broken monotonic source must not turn a rollback into an immediate retry.
            if (now < previous) {
                lastAttemptCompletedAt = now
                return
            }
            if (now - previous < intervalMillis) return
        }
        try {
            if (mutableSnapshot.value.registration != RegistrationStatus.REGISTERED && !register()) return
            if (exitRequested) return
            heartbeat()
            if (!exitRequested && !uploadBlocked) upload()
        } finally {
            // Cadence begins at completion, so a slow blocking call cannot cause a retry burst.
            try {
                lastAttemptCompletedAt = monotonicMillis()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                lastAttemptCompletedAt = now
                blockVerification(RuntimeControllerFailure.PERSISTENCE_FAILURE)
            }
        }
    }

    private fun recomputeGate() {
        try {
            val gated = operations.gate()
            if (authoritativeDenial != null && !gated.allowed) authoritativeDenial = gated
            publishDecision(when {
                verificationRequired -> unavailableDecision()
                gated.allowed -> authoritativeDenial ?: gated
                else -> gated
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            blockVerification(RuntimeControllerFailure.ENTITLEMENT_FAILURE)
        }
    }

    private suspend fun register(): Boolean {
        mutableSnapshot.value = mutableSnapshot.value.copy(registration = RegistrationStatus.REGISTERING)
        val result = try {
            operations.register()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RuntimeResult.Failure(RuntimeFailureCode.PERSISTENCE_FAILURE)
        }
        if (result == RuntimeResult.Registered) {
            mutableSnapshot.value = mutableSnapshot.value.copy(registration = RegistrationStatus.REGISTERED)
            return true
        }
        mutableSnapshot.value = mutableSnapshot.value.copy(registration = RegistrationStatus.RETRY_PENDING)
        applyFailure((result as? RuntimeResult.Failure) ?: RuntimeResult.Failure(RuntimeFailureCode.INVALID_RESPONSE))
        return false
    }

    private suspend fun heartbeat() {
        val result = try {
            operations.heartbeat(requireNotNull(sessionId), if (inForeground) HeartbeatMode.FOREGROUND else HeartbeatMode.BACKGROUND)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RuntimeResult.Failure(RuntimeFailureCode.ENTITLEMENT_FAILURE)
        }
        when (result) {
            is RuntimeResult.HeartbeatApplied -> applyHeartbeat(result)
            is RuntimeResult.Failure -> applyFailure(result)
            else -> applyFailure(RuntimeResult.Failure(RuntimeFailureCode.INVALID_RESPONSE))
        }
    }

    private fun applyHeartbeat(result: RuntimeResult.HeartbeatApplied) {
        authoritativeDenial = result.decision.takeUnless { it.allowed }
        intervalMillis = result.refreshAfterSeconds.coerceIn(30, 300) * 1_000
        val gated = try {
            operations.gate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            blockVerification(
                RuntimeControllerFailure.ENTITLEMENT_FAILURE,
                safePolicy(result.decision, mutableSnapshot.value.devicePolicy),
            )
            return
        }
        verificationRequired = false
        val effective = when {
            gated.state == LicenseAccessState.INSTALLATION_DISABLED -> gated
            authoritativeDenial != null -> requireNotNull(authoritativeDenial)
            else -> gated
        }
        if (effective.state == LicenseAccessState.INSTALLATION_DISABLED) authoritativeDenial = effective
        mutableSnapshot.value = mutableSnapshot.value.copy(
            effectiveDecision = effective,
            devicePolicy = safePolicy(effective, result.devicePolicy),
            presence = if (inForeground) PresenceStatus.FOREGROUND else PresenceStatus.BACKGROUND,
            lastSafeFailure = when {
                result.updateDispatch == RuntimeUpdateDispatch.FAILED -> RuntimeControllerFailure.SINK_FAILURE
                uploadBlocked -> RuntimeControllerFailure.DIAGNOSTICS_BLOCKED
                else -> null
            },
        )
    }

    private fun applyFailure(result: RuntimeResult.Failure) {
        val code = when (result.code) {
            RuntimeFailureCode.INSTALLATION_MISMATCH -> RuntimeControllerFailure.INSTALLATION_MISMATCH
            RuntimeFailureCode.INVALID_REQUEST -> RuntimeControllerFailure.INVALID_REQUEST
            RuntimeFailureCode.INVALID_RESPONSE -> RuntimeControllerFailure.INVALID_RESPONSE
            RuntimeFailureCode.TRANSPORT_FAILURE -> RuntimeControllerFailure.TRANSPORT_FAILURE
            RuntimeFailureCode.PERSISTENCE_FAILURE -> RuntimeControllerFailure.PERSISTENCE_FAILURE
            RuntimeFailureCode.ENTITLEMENT_FAILURE -> RuntimeControllerFailure.ENTITLEMENT_FAILURE
            RuntimeFailureCode.SINK_FAILURE -> RuntimeControllerFailure.SINK_FAILURE
        }
        when (result.code) {
            RuntimeFailureCode.PERSISTENCE_FAILURE, RuntimeFailureCode.ENTITLEMENT_FAILURE,
            RuntimeFailureCode.INSTALLATION_MISMATCH -> blockVerification(code)
            else -> {
                failure(code, unavailable = true)
                if (!verificationRequired && authoritativeDenial == null) {
                    result.decision?.takeIf { !it.allowed || mutableSnapshot.value.effectiveDecision.allowed }
                        ?.let(::publishDecision)
                }
            }
        }
    }

    private suspend fun upload() {
        val result = try {
            diagnostics.upload()
        } catch (cancelled: CancellationException) {
            // Cancellation can interrupt a quarantine write: never automatically replay it.
            uploadBlocked = true
            throw cancelled
        } catch (_: Exception) {
            uploadBlocked = true
            failure(RuntimeControllerFailure.DIAGNOSTICS_FAILURE)
            return
        }
        when (result) {
            is DiagnosticsUploadResult.StorageFailure, DiagnosticsUploadResult.BlockedUntilRecovery,
            is DiagnosticsUploadResult.BlockedByQuarantine -> {
                uploadBlocked = true
                failure(RuntimeControllerFailure.DIAGNOSTICS_BLOCKED)
            }
            is DiagnosticsUploadResult.InvalidBatch, DiagnosticsUploadResult.InvalidResponse,
            DiagnosticsUploadResult.TransportFailure -> failure(RuntimeControllerFailure.DIAGNOSTICS_FAILURE)
            is DiagnosticsUploadResult.Uploaded, DiagnosticsUploadResult.EmptySnapshot -> Unit
        }
    }

    private fun blockVerification(
        code: RuntimeControllerFailure,
        policy: DevicePolicy = mutableSnapshot.value.devicePolicy,
    ) {
        verificationRequired = true
        mutableSnapshot.value = mutableSnapshot.value.copy(
            effectiveDecision = unavailableDecision(),
            devicePolicy = policy,
            presence = PresenceStatus.UNAVAILABLE,
            lastSafeFailure = code,
        )
    }

    private fun failure(code: RuntimeControllerFailure, unavailable: Boolean = false) {
        // Telemetry transport trouble must not hide the substantive runtime failure from this cycle.
        if (code == RuntimeControllerFailure.DIAGNOSTICS_FAILURE && mutableSnapshot.value.lastSafeFailure != null) return
        mutableSnapshot.value = mutableSnapshot.value.copy(
            lastSafeFailure = code,
            presence = if (unavailable) PresenceStatus.UNAVAILABLE else mutableSnapshot.value.presence,
        )
    }

    private fun publishDecision(decision: LicenseAccessDecision) {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            effectiveDecision = decision,
            devicePolicy = safePolicy(decision, mutableSnapshot.value.devicePolicy),
        )
    }

    private fun safePolicy(decision: LicenseAccessDecision, policy: DevicePolicy): DevicePolicy =
        if (decision.state == LicenseAccessState.INSTALLATION_DISABLED) DevicePolicy(KioskMode.OFF, true) else policy

    private fun unavailableDecision() = LicenseAccessDecision(LicenseAccessState.VERIFICATION_REQUIRED)

    override fun toString(): String = "RuntimeController([REDACTED])"
}
