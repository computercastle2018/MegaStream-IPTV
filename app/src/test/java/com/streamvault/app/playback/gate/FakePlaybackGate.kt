package com.MegaStream.app.playback.gate

import kotlinx.coroutines.flow.MutableStateFlow

class FakePlaybackGate(initial: PlaybackGateVerdict = PlaybackGateVerdict.Allowed) : PlaybackGate {
    override val decision = MutableStateFlow(initial)
    override val blocked = MutableStateFlow<PlaybackGateVerdict.Blocked?>(null)
    var currentVerdict: PlaybackGateVerdict = initial
    var checks = 0
    val reports = mutableListOf<PlaybackGateVerdict.Blocked>()

    override fun checkNow(): PlaybackGateVerdict {
        checks++
        return currentVerdict
    }

    override fun reportBlocked(reason: PlaybackGateVerdict.Blocked) {
        reports += reason
        blocked.value = reason
    }

    override fun clearBlocked() {
        blocked.value = null
    }
}
