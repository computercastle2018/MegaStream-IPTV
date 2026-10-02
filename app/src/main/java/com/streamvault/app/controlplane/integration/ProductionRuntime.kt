package com.MegaStream.app.controlplane.integration

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.MegaStream.data.licensing.LocalAppEntitlement
import com.MegaStream.domain.licensing.LicenseAccessDecision
import com.MegaStream.domain.licensing.LicenseAccessState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-owned composition. Exposes observations and an explicit exit, never secrets or globals. */
@Singleton
class ProductionRuntime @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val factory: AndroidRuntimeFactory,
    private val entitlement: Provider<LocalAppEntitlement>,
    private val experience: com.MegaStream.app.controlplane.DeviceExperienceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleLane = Mutex()
    private val mutableSnapshot = MutableStateFlow(RuntimeSnapshot())
    val snapshot: StateFlow<RuntimeSnapshot> = mutableSnapshot.asStateFlow()
    val updateCapability = RuntimeCapability(RuntimeCapabilityStatus.NOT_CONFIGURED,
        RuntimeCapabilityBlocker.UPDATE_LEDGER_NOT_INSTALLATION_SCOPED_OR_CONFLICT_CHECKED)
    val providerCapability = RuntimeCapability(RuntimeCapabilityStatus.NOT_CONFIGURED,
        RuntimeCapabilityBlocker.PROVIDER_ATOMIC_ASSIGNMENT_ADAPTER_MISSING)
    private var startJob: Job? = null
    @Volatile private var controller: RuntimeController? = null
    @Volatile private var exitRequested = false
    @Volatile private var runtimeReady = false

    /** Fresh local authorization, constrained by the controller's authoritative denial. */
    fun gate(): LicenseAccessDecision {
        val runtime = controller
        if (runtime == null || !runtimeReady || exitRequested) return playbackVerificationRequired()
        return try {
            val local = entitlement.get().gate()
            if (!runtimeReady || exitRequested) playbackVerificationRequired()
            else combinePlaybackDecisions(local, runtime.snapshot.value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            playbackVerificationRequired()
        }
    }

    /** Called only after activation has applied an authenticated response to the shared entitlement. */
    suspend fun refreshEntitlementAfterActivation(): LicenseAccessDecision = withContext(Dispatchers.Main.immediate) {
        val fresh = withContext(Dispatchers.IO) { gate() }
        mutableSnapshot.value = mutableSnapshot.value.copy(effectiveDecision = fresh)
        fresh
    }

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = lifecycleChanged()
        override fun onStop(owner: LifecycleOwner) = lifecycleChanged()
    }

    suspend fun awaitStartup() = withContext(Dispatchers.Main.immediate) {
        start()
        startJob?.join()
    }

    suspend fun refreshLicense() = withContext(Dispatchers.Main.immediate) {
        awaitStartup()
        lifecycleLane.withLock {
            if (!exitRequested) controller?.tick(force = true)?.join()
        }
    }

    /** Main-thread entry: nonblocking and idempotent, including after initialization failure. */
    fun start() {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        if (startJob != null || exitRequested) return
        startJob = scope.launch {
            try {
                val runtime = withContext(Dispatchers.IO) {
                    if (!isMainRuntimeProcess(context)) return@withContext null
                    factory.create(scope)
                } ?: return@launch
                lifecycleLane.withLock {
                    controller = runtime
                    if (exitRequested) {
                        runtime.manualExit().join()
                        return@withLock
                    }
                    scope.launch { runtime.snapshot.collect { mutableSnapshot.value = it } }
                    val lifecycle = ProcessLifecycleOwner.get().lifecycle
                    lifecycle.addObserver(observer)
                    // RuntimeController defaults to foreground=true. Commit the actual state BEFORE
                    // start(), otherwise WorkManager cold-starts would send a fake foreground beat.
                    runtime.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)).join()
                    val startup = runtime.start()
                    startup.join()
                    runtimeReady = !startup.isCancelled && !exitRequested
                }
                if (!exitRequested) withContext(Dispatchers.IO) { scheduleBackgroundWork() }
                scope.launch {
                    while (!exitRequested) {
                        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            experience.refresh()
                        }
                        kotlinx.coroutines.delay(60_000)
                    }
                }
            } catch (cancelled: CancellationException) {
                runtimeReady = false
                throw cancelled
            } catch (_: Exception) {
                runtimeReady = false
                // Never log exceptions: credential/storage providers may include sensitive details.
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    presence = PresenceStatus.UNAVAILABLE,
                    lastSafeFailure = RuntimeControllerFailure.PERSISTENCE_FAILURE,
                )
            }
        }
    }

    private fun lifecycleChanged() {
        scope.launch {
            lifecycleLane.withLock {
                if (!exitRequested) controller?.foreground(
                    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                )?.join()
            }
        }
    }

    internal suspend fun backgroundTick(): Boolean = withContext(Dispatchers.Main.immediate) {
        start()
        startJob?.join()
        if (exitRequested) return@withContext true
        val runtime = controller ?: return@withContext false
        lifecycleLane.withLock {
            runtime.foreground(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)).join()
            runtime.tick().join()
        }
        true
    }

    /** Only an explicit confirmed local-exit action calls this; background/onTerminate never do. */
    suspend fun manualExit() = withContext(Dispatchers.Main.immediate) {
        exitRequested = true
        runtimeReady = false
        startJob?.join()
        lifecycleLane.withLock {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            controller?.manualExit()?.join()
            mutableSnapshot.value = controller?.snapshot?.value
                ?: mutableSnapshot.value.copy(presence = PresenceStatus.STOPPED)
        }
    }

    override fun toString(): String = "ProductionRuntime([REDACTED])"

    private fun scheduleBackgroundWork() {
        val request = PeriodicWorkRequestBuilder<RuntimeHeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "megastream-control-plane-heartbeat-v1", ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }
}

/** A snapshot can constrain a fresh local grant, but can never create one. */
internal fun combinePlaybackDecisions(
    local: LicenseAccessDecision,
    runtime: RuntimeSnapshot,
): LicenseAccessDecision {
    if (runtime.presence == PresenceStatus.NOT_STARTED || runtime.presence == PresenceStatus.STOPPED ||
        runtime.lastSafeFailure in setOf(
            RuntimeControllerFailure.RECOVERY_FAILURE,
            RuntimeControllerFailure.SESSION_FAILURE,
            RuntimeControllerFailure.INSTALLATION_MISMATCH,
            RuntimeControllerFailure.PERSISTENCE_FAILURE,
            RuntimeControllerFailure.ENTITLEMENT_FAILURE,
        )
    ) return playbackVerificationRequired()
    if (!local.allowed) return local
    val observed = runtime.effectiveDecision
    // Activation applies authenticated evidence to the same local singleton before the next beat.
    // Only an obsolete lack of enrollment may yield to it; substantive runtime denials persist.
    return if (observed.allowed || observed.state == LicenseAccessState.UNLICENSED) local else observed
}

private fun playbackVerificationRequired() = LicenseAccessDecision(LicenseAccessState.VERIFICATION_REQUIRED)
