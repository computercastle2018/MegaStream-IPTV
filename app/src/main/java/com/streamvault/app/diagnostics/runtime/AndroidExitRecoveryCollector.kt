package com.MegaStream.app.diagnostics.runtime

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.DiagnosticsRecorder
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier
import java.util.UUID

/** The process name exists only inside the local OS boundary, never in persisted state/events. */
data class LocalProcessExit(
    val processName: String?,
    val snapshot: SessionTerminationClassifier.ExitReasonSnapshot,
)

fun interface ExitHistory {
    fun query(packageName: String, priorPid: Int, maxRecords: Int): List<LocalProcessExit>
}

data class RecoveryProcess(val pid: Int, val startedAtMillis: Long, val packageName: String, val apiLevel: Int)
data class RecoveryEnvironment(val process: RecoveryProcess, val clock: () -> Long)
data class SessionStart(val appSessionId: UUID, val recoveryComplete: Boolean)

/**
 * Blocking, offline recovery seam. Own exactly one instance in the main application process and
 * serialize its lifetime on Dispatchers.IO; do not create one per Activity or in secondary processes.
 * No lifecycle callbacks/DI are installed here. Call startSession once, use nextMetadata for all
 * producers sharing this session, and call finishSession only for an intentional terminal exit.
 *
 * The supplied recorder MUST use a durable idempotent sink: true means the exact event is durably
 * accepted (or already accepted with identical content). Dedupe by event ID through upload/ack too.
 * There is no transaction across marker and sink: a crash after append but before consumption retries
 * the identical persisted event. false/throw never consumes it. An in-memory sink is NOT sufficient.
 *
 * Eight unaccepted ends are retained without eviction. At capacity new tracking fails closed with
 * RecoveryBacklogFull, leaving the previous marker and its first recovery boundary intact. The app
 * may still run untracked; retry recoverPending and startSession after storage/sink recovery. Storage,
 * query and recorder exceptions propagate; never log their potentially sensitive exception text.
 */
class AndroidExitRecoveryCollector(
    private val store: SessionRecoveryStore,
    private val recorder: DiagnosticsRecorder,
    private val environment: RecoveryEnvironment,
    private val history: ExitHistory,
) {
    private val classifier = SessionTerminationClassifier()
    // Stable even if the start commit succeeds but directory fsync reports failure.
    private val allocatedSessionId = UUID.randomUUID()
    private var ownedSessionId: UUID? = null
    val currentSessionId: UUID? get() = ownedSessionId

    init {
        require(environment.process.apiLevel >= 27)
        require(environment.process.pid > 0)
        require(validTimestamp(environment.process.startedAtMillis))
        require(environment.process.packageName.isNotBlank())
    }

    /** A false recoveryComplete still means the new running marker was committed atomically. */
    @Synchronized
    fun startSession(): SessionStart {
        ownedSessionId?.let { return SessionStart(it, recoverPending()) }
        var state = load()
        if (state.current?.sessionId == allocatedSessionId) {
            ownedSessionId = allocatedSessionId
            return SessionStart(allocatedSessionId, recoverPending())
        }
        state = freezePreviousBoundary(state)
        if (state.pending.size == SessionRecoveryState.MAX_PENDING) {
            if (!recoverPending()) throw RecoveryBacklogFull()
            state = load()
        }
        val pending = state.current?.let { recoveredEnd(it, nextSequence(state)) }
        val id = allocatedSessionId
        val current = SessionMarker(id, SessionTerminationClassifier.PreviousSessionMarker(
            environment.process.pid, environment.process.startedAtMillis,
            environment.process.startedAtMillis, SessionTerminationClassifier.MarkerState.Running,
        ))
        store.save(state.copy(
            lastSequence = pending?.event?.sequence ?: state.lastSequence,
            current = current,
            pending = state.pending + listOfNotNull(pending),
        ))
        ownedSessionId = id
        return SessionStart(id, recoverPending())
    }

    /** Gaps after a failed producer are permitted; persisted sequence values are never reused. */
    @Synchronized
    fun nextMetadata(): DiagnosticEvent.Metadata {
        val state = load()
        val marker = ownedCurrent(state)
        val timestamp = now()
        require(timestamp >= marker.observation.recordedAtMillis) { "Clock regressed" }
        val metadata = metadata(marker.sessionId, nextSequence(state), timestamp)
        store.save(state.copy(lastSequence = metadata.sequence))
        return metadata
    }

    /**
     * Persists a stable REPORTED clean end before append. Once terminal intent is durable no further
     * metadata is allocated for this session, even if append fails. Retry recoverPending (or this call).
     * The original marker remains with the pending event until the recorder returns true.
     */
    @Synchronized
    fun finishSession(): Boolean {
        var state = load()
        val id = checkNotNull(ownedSessionId) { "Session not started" }
        if (state.current?.sessionId == id) {
            if (state.pending.size == SessionRecoveryState.MAX_PENDING) {
                if (!recoverPending()) throw RecoveryBacklogFull()
                state = load()
            }
            val marker = ownedCurrent(state)
            val timestamp = now()
            require(timestamp >= marker.observation.recordedAtMillis) { "Clock regressed" }
            val event = DiagnosticEvent.AppEnded(metadata(id, nextSequence(state), timestamp),
                DiagnosticEvent.ExitReason.CLEAN_EXIT, DiagnosticEvent.ExitEvidence.REPORTED)
            val cleanMarker = marker.copy(observation = marker.observation.copy(
                state = SessionTerminationClassifier.MarkerState.Clean,
                recordedAtMillis = timestamp, endedAtMillis = timestamp,
            ))
            store.save(state.copy(lastSequence = event.sequence, current = null,
                pending = state.pending + PendingSessionEnd(cleanMarker, event)))
        }
        return recoverPending()
    }

    /** Returns false at the first rejected append. Exceptions leave the durable event retryable. */
    @Synchronized
    fun recoverPending(): Boolean {
        while (true) {
            val state = load()
            val pending = state.pending.firstOrNull() ?: return true
            if (!recorder.record(pending.event)) return false
            store.save(state.copy(pending = state.pending.drop(1)))
        }
    }

    private fun freezePreviousBoundary(state: SessionRecoveryState): SessionRecoveryState {
        val current = state.current ?: return state
        if (current.recoveryBeforeMillis != null) return state
        val frozen = state.copy(current = current.copy(recoveryBeforeMillis = environment.process.startedAtMillis))
        store.save(frozen)
        return frozen
    }

    private fun recoveredEnd(marker: SessionMarker, sequence: Long): PendingSessionEnd {
        val boundary = checkNotNull(marker.recoveryBeforeMillis)
        val exit = matchingExit(marker, boundary)
        val classification = classifier.classify(marker.observation, exit, boundary)
        val timestamp = exit?.timestampMillis ?: marker.observation.endedAtMillis ?: boundary
        val event = DiagnosticEvent.AppEnded(metadata(marker.sessionId, sequence, timestamp),
            classification.exitReason(), classification.exitEvidence())
        return PendingSessionEnd(marker, event)
    }

    private fun matchingExit(marker: SessionMarker, boundary: Long): SessionTerminationClassifier.ExitReasonSnapshot? {
        if (environment.process.apiLevel < 30) return null
        val endedAtMillis = marker.observation.endedAtMillis
        return history.query(environment.process.packageName, marker.observation.pid, MAX_HISTORY)
            .take(MAX_HISTORY)
            .asSequence()
            .filter { it.processName == environment.process.packageName }
            .map { it.snapshot }
            .filter { it.pid == marker.observation.pid && validTimestamp(it.timestampMillis) &&
                it.timestampMillis >= marker.observation.recordedAtMillis && it.timestampMillis < boundary &&
                (endedAtMillis == null || it.timestampMillis <= endedAtMillis) }
            .maxByOrNull { it.timestampMillis }
    }

    private fun load(): SessionRecoveryState = store.load().also(::validateState)
    private fun now(): Long = environment.clock().also { require(validTimestamp(it)) { "Invalid clock" } }
    private fun ownedCurrent(state: SessionRecoveryState): SessionMarker =
        checkNotNull(state.current?.takeIf { it.sessionId == ownedSessionId && it.recoveryBeforeMillis == null }) {
            "No running owned session"
        }
    private fun nextSequence(state: SessionRecoveryState): Long = Math.addExact(state.lastSequence, 1)
    private fun metadata(session: UUID, sequence: Long, timestamp: Long) =
        DiagnosticEvent.Metadata(UUID.randomUUID(), session, sequence, timestamp)

    companion object { const val MAX_HISTORY = 8 }
}

private fun SessionTerminationClassifier.Result.exitReason(): DiagnosticEvent.ExitReason = when (termination) {
    SessionTerminationClassifier.Termination.Clean -> DiagnosticEvent.ExitReason.CLEAN_EXIT
    SessionTerminationClassifier.Termination.JavaCrash -> DiagnosticEvent.ExitReason.JAVA_CRASH
    SessionTerminationClassifier.Termination.Anr -> DiagnosticEvent.ExitReason.ANR
    SessionTerminationClassifier.Termination.Oom -> DiagnosticEvent.ExitReason.OOM
    SessionTerminationClassifier.Termination.LowMemoryKill -> DiagnosticEvent.ExitReason.LOW_MEMORY_KILL
    SessionTerminationClassifier.Termination.NativeCrash -> DiagnosticEvent.ExitReason.NATIVE_CRASH
    SessionTerminationClassifier.Termination.Signal -> DiagnosticEvent.ExitReason.SIGNAL
    SessionTerminationClassifier.Termination.UserRequested -> DiagnosticEvent.ExitReason.USER_REQUESTED
    SessionTerminationClassifier.Termination.SystemKill -> DiagnosticEvent.ExitReason.SYSTEM_KILL
    SessionTerminationClassifier.Termination.Unknown -> DiagnosticEvent.ExitReason.UNKNOWN
}

private fun SessionTerminationClassifier.Result.exitEvidence(): DiagnosticEvent.ExitEvidence = when {
    evidenceStrength == SessionTerminationClassifier.EvidenceStrength.Suspected -> DiagnosticEvent.ExitEvidence.WATCHDOG_SUSPECTED
    evidenceSource == SessionTerminationClassifier.EvidenceSource.SystemExit -> DiagnosticEvent.ExitEvidence.RECOVERED_OS
    else -> DiagnosticEvent.ExitEvidence.RECOVERED_MARKER
}
