package com.MegaStream.domain.licensing

data class ClockReading(
    val wallEpochSeconds: Long,
    val elapsedRealtimeMillis: Long,
    val bootId: String
)
