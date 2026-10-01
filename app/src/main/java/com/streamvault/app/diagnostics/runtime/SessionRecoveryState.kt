package com.MegaStream.app.diagnostics.runtime

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.DiagnosticSanitizer
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier
import java.io.IOException
import java.util.UUID

/** Blocking persistence boundary. A failed write may already have committed; callers must reload. */
interface SessionRecoveryStore {
    fun load(): SessionRecoveryState
    fun save(state: SessionRecoveryState)
}

data class SessionMarker(
    val sessionId: UUID,
    val observation: SessionTerminationClassifier.PreviousSessionMarker,
    /** First subsequent process start; frozen even when the recovery backlog cannot drain. */
    val recoveryBeforeMillis: Long? = null,
)

data class PendingSessionEnd(val marker: SessionMarker, val event: DiagnosticEvent.AppEnded)

data class SessionRecoveryState(
    val lastSequence: Long = 0,
    val current: SessionMarker? = null,
    val pending: List<PendingSessionEnd> = emptyList(),
) {
    companion object { const val MAX_PENDING = 8 }
}

class CorruptSessionRecoveryState : IOException("Invalid session recovery state")
class RecoveryBacklogFull : IllegalStateException("Session recovery backlog is full")

internal fun validTimestamp(timestamp: Long): Boolean =
    timestamp in 0..DiagnosticSanitizer.MAX_TIMESTAMP_MILLIS

internal fun validateState(state: SessionRecoveryState) {
    if (state.lastSequence < 0 || state.pending.size > SessionRecoveryState.MAX_PENDING) corrupt()
    val markers = listOfNotNull(state.current) + state.pending.map { it.marker }
    if (markers.map { it.sessionId }.distinct().size != markers.size) corrupt()
    markers.forEach(::validateMarker)
    val sanitizer = DiagnosticSanitizer()
    state.pending.forEach { pending ->
        val event = pending.event
        if (!isV4(event.id) || event.appSessionId != pending.marker.sessionId ||
            event.sequence !in 1..state.lastSequence || sanitizer.sanitize(event) != event
        ) corrupt()
    }
    if (state.pending.map { it.event.id }.distinct().size != state.pending.size ||
        state.pending.map { it.event.sequence }.distinct().size != state.pending.size
    ) corrupt()
}

private fun validateMarker(marker: SessionMarker) {
    val observation = marker.observation
    if (!isV4(marker.sessionId) || observation.pid <= 0 ||
        !validTimestamp(observation.startedAtMillis) || !validTimestamp(observation.recordedAtMillis) ||
        observation.recordedAtMillis < observation.startedAtMillis || observation.recoveredAtMillis != null
    ) corrupt()
    observation.endedAtMillis?.let {
        if (!validTimestamp(it) || it < observation.recordedAtMillis) corrupt()
    }
    marker.recoveryBeforeMillis?.let { if (!validTimestamp(it)) corrupt() }
}

internal fun isV4(id: UUID): Boolean = id.version() == 4 && id.variant() == 2
internal fun corrupt(): Nothing = throw CorruptSessionRecoveryState()
