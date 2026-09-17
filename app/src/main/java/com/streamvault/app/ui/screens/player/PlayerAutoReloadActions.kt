package com.MegaStream.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.MegaStream.domain.model.ContentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Automatic channel reload ("auto retry") for live streams that drop out.
 *
 * When a live channel stops working (stream ended, repeated network/source
 * failures, stalled playback that exhausted the engine's recovery ladder),
 * the player previously stayed on the error overlay until the viewer pressed
 * Retry. These extensions reload the same channel automatically with a
 * short, capped backoff so brief provider hiccups recover on their own.
 *
 * Limits:
 * - LIVE content only (VOD/catch-up keep their own completion semantics).
 * - [MAX_AUTO_RELOAD_ATTEMPTS] attempts per streak; the counter resets as
 *   soon as playback reaches READY again.
 * - Manual actions (zapping, retry button) supersede the pending job.
 */

private const val MAX_AUTO_RELOAD_ATTEMPTS = 6

private fun autoReloadDelayMs(attempt: Int): Long = when (attempt) {
    0 -> 3_000L
    1 -> 6_000L
    2 -> 10_000L
    else -> 15_000L
}

internal fun PlayerViewModel.scheduleLiveAutoReload(reason: String) {
    if (currentContentType != ContentType.LIVE || isCatchUpPlayback()) return
    if (autoReloadAttempts >= MAX_AUTO_RELOAD_ATTEMPTS) {
        appendRecoveryAction("Auto-reload gave up after $MAX_AUTO_RELOAD_ATTEMPTS attempts")
        return
    }
    autoReloadJob?.cancel()
    val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return
    // A different channel than the one that kept failing gets a fresh budget.
    if (channel.id != autoReloadLastChannelId) {
        autoReloadAttempts = 0
    }
    autoReloadLastChannelId = channel.id
    val attempt = autoReloadAttempts
    val delayMs = autoReloadDelayMs(attempt)
    android.util.Log.w(
        "PlayerVM",
        "Scheduling live auto-reload attempt=${attempt + 1}/$MAX_AUTO_RELOAD_ATTEMPTS in ${delayMs}ms reason=$reason channel=${channel.name}"
    )
    showPlayerNotice(
        message = "Channel stopped responding. Reloading ${channel.name} shortly...",
        recoveryType = PlayerRecoveryType.NETWORK,
        durationMs = delayMs + 2_000L,
        isRetryNotice = true
    )
    autoReloadJob = viewModelScope.launch {
        delay(delayMs)
        // Bail out if the viewer moved on to something else while waiting.
        if (currentContentType != ContentType.LIVE) return@launch
        val stillSameChannel = currentChannelFlow.value?.id == channel.id
        if (!stillSameChannel) return@launch
        autoReloadAttempts += 1
        appendRecoveryAction("Auto-reload attempt ${autoReloadAttempts}/$MAX_AUTO_RELOAD_ATTEMPTS")
        retryStream(
            streamUrl = currentStreamUrl,
            epgChannelId = currentChannelFlow.value?.epgChannelId
        )
    }
}

internal fun PlayerViewModel.cancelLiveAutoReload(playbackRecovered: Boolean) {
    autoReloadJob?.cancel()
    autoReloadJob = null
    if (playbackRecovered) {
        autoReloadAttempts = 0
    }
}
