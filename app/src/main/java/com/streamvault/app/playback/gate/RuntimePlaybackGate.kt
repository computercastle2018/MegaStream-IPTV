package com.MegaStream.app.playback.gate

import com.MegaStream.domain.licensing.LicenseAccessDecision
import com.MegaStream.domain.licensing.LicenseAccessState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Local authorization is reevaluated on observations and at most every second while active. */
class RuntimePlaybackGate(
    private val gate: () -> LicenseAccessDecision,
    observations: Flow<*>,
    scope: CoroutineScope,
) : PlaybackGate {
    private val mutableDecision = MutableStateFlow<PlaybackGateVerdict>(verificationRequired())
    override val decision: StateFlow<PlaybackGateVerdict> = mutableDecision.asStateFlow()
    private val mutableBlocked = MutableStateFlow<PlaybackGateVerdict.Blocked?>(null)
    override val blocked: StateFlow<PlaybackGateVerdict.Blocked?> = mutableBlocked.asStateFlow()

    init {
        scope.launch {
            observations.collect { checkNow() }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                checkNow()
            }
        }
    }

    @Synchronized
    override fun checkNow(): PlaybackGateVerdict {
        val next = try {
            val fresh = gate()
            if (fresh.allowed) PlaybackGateVerdict.Allowed else PlaybackGateVerdict.Blocked(
                fresh.state,
                fresh.state == LicenseAccessState.UNLICENSED ||
                    fresh.state == LicenseAccessState.VERIFICATION_REQUIRED,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Storage failures must never preserve a previously allowed observation.
            verificationRequired()
        }
        mutableDecision.value = next
        if (next is PlaybackGateVerdict.Allowed) mutableBlocked.value = null
        return next
    }

    override fun reportBlocked(reason: PlaybackGateVerdict.Blocked) {
        mutableBlocked.value = reason
    }

    override fun clearBlocked() {
        mutableBlocked.value = null
    }

    private fun verificationRequired() = PlaybackGateVerdict.Blocked(
        LicenseAccessState.VERIFICATION_REQUIRED, actionable = true,
    )
}
