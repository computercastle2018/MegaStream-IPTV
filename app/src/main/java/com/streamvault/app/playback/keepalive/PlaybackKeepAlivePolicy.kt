package com.MegaStream.app.playback.keepalive

import com.MegaStream.player.PlaybackState

internal data class PlaybackKeepAliveInput(
    val hasStream: Boolean,
    val playbackState: PlaybackState,
    val isPlaying: Boolean,
    val screenAttached: Boolean,
    val foregroundEligible: Boolean,
    val released: Boolean = false
)

/** Terminal states win even if the engine's separately emitted playing flag is stale. */
internal fun PlaybackKeepAliveInput.hasActivePlayback(): Boolean =
    !released && hasStream &&
        playbackState != PlaybackState.ERROR && playbackState != PlaybackState.ENDED &&
        (isPlaying || playbackState == PlaybackState.BUFFERING || playbackState == PlaybackState.READY)

/** A background or detached screen may carry an existing request, but never create one. */
internal fun reducePlaybackKeepAlive(
    input: PlaybackKeepAliveInput,
    serviceRequested: Boolean
): Boolean = input.hasActivePlayback() &&
    (serviceRequested || (input.screenAttached && input.foregroundEligible))

internal enum class PlaybackKeepAliveCommand { PROMOTE, IGNORE_STALE_OWNER, STOP }

internal fun reducePlaybackKeepAliveCommand(
    isStart: Boolean,
    permitValid: Boolean,
    hasOwners: Boolean
): PlaybackKeepAliveCommand = when {
    !isStart -> PlaybackKeepAliveCommand.STOP
    permitValid -> PlaybackKeepAliveCommand.PROMOTE
    hasOwners -> PlaybackKeepAliveCommand.IGNORE_STALE_OWNER
    else -> PlaybackKeepAliveCommand.STOP
}
