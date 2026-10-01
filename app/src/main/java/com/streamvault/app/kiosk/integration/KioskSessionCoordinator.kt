package com.MegaStream.app.kiosk.integration

import com.MegaStream.app.kiosk.DevicePolicySnapshot
import com.MegaStream.app.kiosk.KioskDenialReason
import com.MegaStream.app.kiosk.KioskFailureReason
import com.MegaStream.app.kiosk.KioskMode
import com.MegaStream.app.kiosk.KioskOperation
import com.MegaStream.app.kiosk.KioskResult
import com.MegaStream.app.kiosk.shouldLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

typealias KioskPolicy = DevicePolicySnapshot

interface KioskPolicySource {
    val policy: StateFlow<KioskPolicy?>
}

/** Android effects boundary. Implementations retain ownership and allowlist checks. */
interface KioskEngine {
    fun update(policy: KioskPolicy, appForeground: Boolean, playbackActive: Boolean): KioskResult
    fun requestManualExit(policy: KioskPolicy): KioskResult
}

/** Bounded diagnostic values only; never transports platform messages or exceptions. */
sealed interface KioskStatus {
    data object Requested : KioskStatus
    data object Applied : KioskStatus
    data object Released : KioskStatus
    data object Unsupported : KioskStatus
    data class Denied(val reason: KioskDenialReason) : KioskStatus
    data class Failed(val operation: KioskOperation, val reason: KioskFailureReason) : KioskStatus
}

data class KioskUiState(
    val allowLocalExit: Boolean,
    val confirmationPending: Boolean,
    val playerPresent: Boolean,
    val foreground: Boolean = false,
)

/**
 * Pure session state, called serially on the UI thread by the activity facade.
 * Playback is an observation, never a command. Only a fresh explicit confirmation can exit.
 */
class KioskSessionCoordinator(
    private val engine: KioskEngine,
    private val policySource: KioskPolicySource,
) {
    private var foreground = false
    private var playbackActive = false
    private var playerPresent = false
    private var confirmationPolicy: KioskPolicy? = null
    private var confirmationPending = false
    private var observedPolicySnapshot: KioskPolicy? = null
    private val mutableStatus = MutableStateFlow<KioskStatus>(KioskStatus.Released)
    val status: StateFlow<KioskStatus> = mutableStatus.asStateFlow()
    private val mutableUiState = MutableStateFlow(KioskUiState(effectivePolicy().allowLocalExit, false, false))
    val uiState: StateFlow<KioskUiState> = mutableUiState.asStateFlow()

    fun start() { foreground = true; refreshPolicy() }
    fun resume() { foreground = true; refreshPolicy() }
    fun pause() { foreground = false; refreshPolicy() }
    fun stop() { foreground = false; refreshPolicy() }

    fun setPlayerPresent(present: Boolean) {
        playerPresent = present
        if (!present) playbackActive = false
        refreshPolicy()
    }

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
        refreshPolicy()
    }

    fun refreshPolicy() {
        updateEngine(readFreshPolicy())
    }

    private fun updateEngine(policy: KioskPolicy) {
        val playerActive = playbackActive && playerPresent
        if (shouldLock(policy, true, foreground, playerActive, false)) {
            mutableStatus.value = KioskStatus.Requested
        }
        mutableStatus.value = engine.update(policy, foreground, playerActive).toStatus()
    }

    fun beginManualExit() {
        val policy = readFreshPolicy()
        confirmationPending = foreground && playerPresent && policy.allowLocalExit
        confirmationPolicy = if (confirmationPending) observedPolicySnapshot else null
        publishUi(policy)
    }

    fun cancelManualExit() {
        val policy = readFreshPolicy()
        confirmationPending = false
        confirmationPolicy = null
        publishUi(policy)
    }

    fun confirmManualExit() {
        val policy = readFreshPolicy()
        val confirmed = confirmationPending && foreground && playerPresent && policy.allowLocalExit
        confirmationPending = false
        confirmationPolicy = null
        publishUi(policy)
        if (confirmed) {
            mutableStatus.value = engine.requestManualExit(policy).toStatus()
        } else {
            updateEngine(policy)
        }
    }

    private fun effectivePolicy(): KioskPolicy = policySource.policy.value ?: SAFE_POLICY

    private fun readFreshPolicy(): KioskPolicy {
        val snapshot = policySource.policy.value
        observedPolicySnapshot = snapshot
        val policy = snapshot ?: SAFE_POLICY
        if (!foreground || !playerPresent || !policy.allowLocalExit ||
            (confirmationPending && snapshot != confirmationPolicy)) {
            confirmationPending = false
            confirmationPolicy = null
        }
        publishUi(policy)
        return policy
    }

    private fun publishUi(policy: KioskPolicy) {
        mutableUiState.value = KioskUiState(policy.allowLocalExit, confirmationPending, playerPresent, foreground)
    }

    private fun KioskResult.toStatus(): KioskStatus = when (this) {
        KioskResult.Applied -> KioskStatus.Applied
        KioskResult.Released -> KioskStatus.Released
        KioskResult.Unsupported -> KioskStatus.Unsupported
        is KioskResult.Denied -> KioskStatus.Denied(reason)
        is KioskResult.Failed -> KioskStatus.Failed(operation, reason)
    }

    private companion object {
        val SAFE_POLICY = KioskPolicy(KioskMode.OFF, allowLocalExit = true)
    }
}
