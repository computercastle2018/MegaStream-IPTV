package com.MegaStream.app.navigation

import com.MegaStream.app.playback.gate.PlaybackGateVerdict

/** Process-memory only: neither credentials nor pending media enter navigation saved state. */
internal sealed interface PlaybackNavigationIntent {
    class Player(val request: PlayerNavigationRequest) : PlaybackNavigationIntent {
        override fun toString(): String = "Player([REDACTED])"
    }
    data object MultiView : PlaybackNavigationIntent
}

/** Main-thread confined, single-slot routing state. A newer request supersedes an older one. */
internal class LicensePlaybackRouting {
    private val mutableActivePlayer = kotlinx.coroutines.flow.MutableStateFlow<PlayerNavigationRequest?>(null)
    val activePlayerFlow: kotlinx.coroutines.flow.StateFlow<PlayerNavigationRequest?> = mutableActivePlayer
    var activePlayer: PlayerNavigationRequest?
        get() = mutableActivePlayer.value
        private set(request) { mutableActivePlayer.value = request }
    var pending: PlaybackNavigationIntent? = null
        private set

    fun request(intent: PlaybackNavigationIntent, verdict: PlaybackGateVerdict): Boolean {
        if (verdict is PlaybackGateVerdict.Blocked) {
            pending = intent
            activePlayer = null
            return false
        }
        pending = null
        activePlayer = (intent as? PlaybackNavigationIntent.Player)?.request
        return true
    }

    fun suspendActive(route: String?) {
        if (pending == null) {
            pending = when (route?.substringBefore('?')?.substringBefore('/')) {
                Routes.PLAYER -> activePlayer?.let(PlaybackNavigationIntent::Player)
                Routes.MULTI_VIEW -> PlaybackNavigationIntent.MultiView
                else -> null
            }
        }
        activePlayer = null
    }

    fun retry(verdict: PlaybackGateVerdict): PlaybackNavigationIntent? {
        if (verdict !is PlaybackGateVerdict.Allowed) return null
        val intent = pending ?: return null
        pending = null
        activePlayer = (intent as? PlaybackNavigationIntent.Player)?.request
        return intent
    }

    fun cancel() {
        pending = null
        activePlayer = null
    }

    fun clearActive() { activePlayer = null }
}

internal fun isProtectedPlaybackRoute(route: String?): Boolean =
    route?.substringBefore('?')?.substringBefore('/') in setOf(Routes.PLAYER, Routes.MULTI_VIEW)
