package com.MegaStream.domain.licensing

data class TrustedTimeAnchor(
    val serverEpochSeconds: Long,
    val elapsedRealtimeMillis: Long,
    val bootId: String
)
