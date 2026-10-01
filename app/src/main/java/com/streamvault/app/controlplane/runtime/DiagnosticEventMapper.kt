package com.MegaStream.app.controlplane.runtime

import com.MegaStream.app.controlplane.*
import com.MegaStream.domain.diagnostics.DiagnosticEvent as DomainEvent
import com.MegaStream.domain.diagnostics.DiagnosticSanitizer
import java.time.Instant
import java.util.Collections

/** Sanitizes domain input, then narrows it to the stricter v1 transport schema. */
class DiagnosticEventMapper(private val sanitizer: DiagnosticSanitizer = DiagnosticSanitizer()) {
    /** Invalid identities are rejected, never replaced with invented identifiers. */
    fun map(event: DomainEvent): DiagnosticEvent? {
        if (!sanitizer.isValidIdentity(event)) return null
        val safe = sanitizer.sanitize(event)
        val payload = when (safe) {
            is DomainEvent.AppStarted -> AppStartedPayload(safe.appVersionCode, safe.appVersionName)
            is DomainEvent.PlaybackStarted -> PlaybackStartedPayload(
                safe.playbackSessionId.toString(), safe.channelName, SourceType.valueOf(safe.sourceType.name),
                StreamType.valueOf(safe.streamType.name), PlaybackMode.valueOf(safe.playbackMode.name),
            )
            is DomainEvent.PlaybackSample -> PlaybackSamplePayload(
                safe.playbackSessionId.toString(), VideoCodec.valueOf(safe.videoCodec.name),
                AudioCodec.valueOf(safe.audioCodec.name), VideoDecoder.valueOf(safe.videoDecoder.name),
                AudioDecoder.valueOf(safe.audioDecoder.name), safe.width.coerceAtMost(65_535),
                safe.height.coerceAtMost(65_535), safe.droppedFrames, safe.rebufferCount,
                safe.bufferedMs, safe.ttffMs, safe.memory?.let(::memory),
            )
            is DomainEvent.PlaybackProblem -> PlaybackProblemPayload(
                safe.playbackSessionId.toString(), PlaybackProblemCategory.valueOf(safe.category.name),
                safe.code.wireValue, safe.httpStatus, safe.retryAttempt,
                safe.memory?.let(::memory),
            )
            is DomainEvent.MemoryPressure -> MemoryPressurePayload(memory(safe.memory), TrimLevel.valueOf(safe.trimLevel.name))
            is DomainEvent.Crash -> CrashPayload(safe.exceptionType, frames(safe.frames))
            is DomainEvent.Anr -> AnrPayload(AnrEvidence.valueOf(safe.evidence.name), safe.durationMs, frames(safe.frames))
            is DomainEvent.PlaybackEnded -> PlaybackEndedPayload(
                safe.playbackSessionId.toString(),
                PlaybackEndReason.valueOf(safe.reason.name), safe.durationMs,
            )
            is DomainEvent.AppEnded -> AppEndedPayload(ExitReason.valueOf(safe.reason.name), ExitEvidence.valueOf(safe.evidence.name))
        }
        return DiagnosticEvent(safe.id.toString(), safe.appSessionId.toString(), safe.sequence,
            Instant.ofEpochMilli(safe.timestampMillis).toString(), payload)
    }

    private fun memory(value: DomainEvent.MemorySnapshot) = MemorySnapshot(
        value.javaUsedBytes, value.javaMaxBytes, value.nativeHeapBytes, value.pssBytes,
        value.availableSystemBytes, value.lowMemory,
    )

    private fun frames(values: List<DomainEvent.RestrictedFrame>): List<DiagnosticFrame> =
        Collections.unmodifiableList(values.map { DiagnosticFrame(it.className, it.methodName, it.line) })
}
