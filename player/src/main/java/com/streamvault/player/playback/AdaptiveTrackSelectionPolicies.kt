package com.MegaStream.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Downshift while a healthy buffer remains, then require more buffer before upgrading.
 * Single-rendition streams have no alternative quality to select.
 */
internal object AdaptiveTrackSelectionPolicies {
    private const val MIN_DURATION_FOR_QUALITY_INCREASE_MS = 15_000
    private const val MAX_DURATION_FOR_QUALITY_DECREASE_MS = 30_000
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
