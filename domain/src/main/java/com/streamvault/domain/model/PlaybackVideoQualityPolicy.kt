package com.MegaStream.domain.model

const val DEFAULT_PLAYBACK_MAX_VIDEO_HEIGHT = 1080

fun isSupportedAdminVideoHeight(height: Int): Boolean = height in setOf(480, 720, 1080, 2160)

fun isSupportedAdminPlaybackQuality(quality: String): Boolean = quality in setOf("auto", "480", "720", "1080", "2160")

data class PlaybackVideoQualityPolicy(val adminMaxVideoHeight: Int? = null) {
    init {
        require(adminMaxVideoHeight == null || isSupportedAdminVideoHeight(adminMaxVideoHeight))
    }

    fun maxVideoHeight(localMaxHeight: Int?, constrainedPlayback: Boolean): Int? {
        val height = adminMaxVideoHeight ?: localMaxHeight
        return if (constrainedPlayback) minOf(720, height ?: 720) else height
    }

    fun permitsManualTrack(height: Int): Boolean =
        adminMaxVideoHeight == null || (height > 0 && height <= adminMaxVideoHeight)
}
