package com.MegaStream.player.tracks

import android.content.SharedPreferences
import com.MegaStream.domain.model.isSupportedAdminPlaybackQuality

object ManagedPlaybackQualityPreferences {
    const val FILE_NAME = "device-experience"
    const val QUALITY_KEY = "playback-quality"

    fun readQuality(preferences: SharedPreferences): String? =
        preferences.getString(QUALITY_KEY, null)?.takeIf(::isSupportedAdminPlaybackQuality)

    fun readMaxHeight(preferences: SharedPreferences): Int? =
        readQuality(preferences)?.toIntOrNull()
}
