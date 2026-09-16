package com.MegaStream.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Adaptive bitrate (ABR) tuning so video quality drops quickly when the
 * network slows down instead of stalling, then recovers cautiously.
 *
 * Tuned vs Media3 defaults:
 * - [MAX_DURATION_FOR_QUALITY_DECREASE_MS] 15s (default 25s): reacts faster to
 *   bandwidth dips so picture quality degrades before the buffer empties.
 * - [MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS] 15s (default 25s): discards
 *   buffered high-quality segments sooner after a down-switch.
 * - [BANDWIDTH_FRACTION] 0.6 (default 0.7): targets a more conservative
 *   fraction of measured throughput, leaving headroom on unstable links.
 *
 * Single-rendition streams (most MPEG-TS live channels) simply have no other
 * track to switch to, so these parameters are a safe no-op there.
 */
internal object AdaptiveTrackSelectionPolicies {
    private const val MIN_DURATION_FOR_QUALITY_INCREASE_MS = 10_000
    private const val MAX_DURATION_FOR_QUALITY_DECREASE_MS = 15_000
    private const val MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 15_000
    private const val BANDWIDTH_FRACTION = 0.6f

    @OptIn(UnstableApi::class)
    fun factory(): AdaptiveTrackSelection.Factory = AdaptiveTrackSelection.Factory(
        MIN_DURATION_FOR_QUALITY_INCREASE_MS,
        MAX_DURATION_FOR_QUALITY_DECREASE_MS,
        MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
        BANDWIDTH_FRACTION
    )

    @OptIn(UnstableApi::class)
    fun trackSelector(context: Context): DefaultTrackSelector =
        DefaultTrackSelector(context, factory())
}
