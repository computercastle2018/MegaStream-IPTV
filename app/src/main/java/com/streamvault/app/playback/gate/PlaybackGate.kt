package com.MegaStream.app.playback.gate

import com.MegaStream.domain.licensing.LicenseAccessState
import kotlinx.coroutines.flow.StateFlow

sealed interface PlaybackGateVerdict {
    data object Allowed : PlaybackGateVerdict

    data class Blocked(val reason: LicenseAccessState, val actionable: Boolean) : PlaybackGateVerdict {
        init {
            require(reason != LicenseAccessState.ALLOWED) { "An allowed decision cannot block playback" }
        }
    }
}

interface PlaybackGate {
    /** Observation for stopping active playback, never authorization for a new protected action. */
    val decision: StateFlow<PlaybackGateVerdict>

    /** Explicit UI feedback only; periodic checks do not create or dismiss this report. */
    val blocked: StateFlow<PlaybackGateVerdict.Blocked?>

    /** Recomputes authorization immediately before a protected action. */
    fun checkNow(): PlaybackGateVerdict
    fun reportBlocked(reason: PlaybackGateVerdict.Blocked)
    fun clearBlocked()
}
