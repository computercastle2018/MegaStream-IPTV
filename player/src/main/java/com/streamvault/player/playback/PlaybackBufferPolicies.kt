package com.MegaStream.player.playback

internal data class PlaybackBufferPolicy(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int
)

internal object PlaybackBufferPolicies {
    private const val LIVE_MIN_BUFFER_MS = 5_000
    private const val LIVE_MAX_BUFFER_MS = 24_000
    private const val COMPAT_LIVE_MIN_BUFFER_MS = 15_000
    private const val COMPAT_LIVE_MAX_BUFFER_MS = 45_000
    private const val VOD_MIN_BUFFER_MS = 50_000
    private const val VOD_MAX_BUFFER_MS = 120_000
    // Live TV benefits most from a very low first-frame threshold; VOD stays a
    // little more conservative so seeking and resume playback remain smooth.
    private const val LIVE_PLAYBACK_BUFFER_MS = 500
    private const val VOD_PLAYBACK_BUFFER_MS = 800
    private const val LIVE_REBUFFER_MS = 1_800
    private const val VOD_REBUFFER_MS = 2_500

    fun forPlayback(isLive: Boolean, compatibilityMode: Boolean): PlaybackBufferPolicy = when {
        compatibilityMode && isLive ->
            PlaybackBufferPolicy("compat-live", COMPAT_LIVE_MIN_BUFFER_MS, COMPAT_LIVE_MAX_BUFFER_MS, VOD_PLAYBACK_BUFFER_MS, VOD_REBUFFER_MS)
        isLive ->
            PlaybackBufferPolicy("fast-live", LIVE_MIN_BUFFER_MS, LIVE_MAX_BUFFER_MS, LIVE_PLAYBACK_BUFFER_MS, LIVE_REBUFFER_MS)
        else ->
            PlaybackBufferPolicy("stable-vod", VOD_MIN_BUFFER_MS, VOD_MAX_BUFFER_MS, VOD_PLAYBACK_BUFFER_MS, VOD_REBUFFER_MS)
    }
}
