package com.MegaStream.domain.licensing

class TrustedTimeEvaluator {
    fun evaluate(anchor: TrustedTimeAnchor?, reading: ClockReading): Long? {
        if (anchor == null || !isContinuous(anchor, reading)) return null
        val elapsedSeconds = (reading.elapsedRealtimeMillis - anchor.elapsedRealtimeMillis) / 1_000
        if (anchor.serverEpochSeconds > Long.MAX_VALUE - elapsedSeconds) return null
        val estimatedEpochSeconds = anchor.serverEpochSeconds + elapsedSeconds
        return estimatedEpochSeconds.takeIf { reading.wallEpochSeconds >= it }
    }

    private fun isContinuous(anchor: TrustedTimeAnchor, reading: ClockReading): Boolean =
        anchor.serverEpochSeconds > 0 && reading.wallEpochSeconds > 0 &&
            anchor.elapsedRealtimeMillis >= 0 && reading.elapsedRealtimeMillis >= 0 &&
            reading.elapsedRealtimeMillis >= anchor.elapsedRealtimeMillis &&
            anchor.bootId.isNotBlank() && reading.bootId.isNotBlank() &&
            anchor.bootId == reading.bootId && reading.wallEpochSeconds >= anchor.serverEpochSeconds
}
