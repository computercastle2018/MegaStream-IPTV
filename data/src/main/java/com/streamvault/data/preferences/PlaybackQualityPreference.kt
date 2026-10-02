package com.MegaStream.data.preferences

import com.MegaStream.domain.model.DEFAULT_PLAYBACK_MAX_VIDEO_HEIGHT

internal fun storedPlaybackMaxVideoHeight(stored: Int?): Int? =
    if (stored == null) DEFAULT_PLAYBACK_MAX_VIDEO_HEIGHT else stored.takeIf { it > 0 }

internal fun encodePlaybackMaxVideoHeight(height: Int?): Int = height?.takeIf { it > 0 } ?: 0
