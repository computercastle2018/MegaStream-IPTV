package com.MegaStream.app.diagnostics.runtime

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.DiagnosticSanitizer
import com.MegaStream.domain.diagnostics.DiagnosticsEventSink
import com.MegaStream.domain.diagnostics.DiagnosticsRecorder
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class AndroidExitRecoveryCollectorTest {
    @Test fun firstStartCreatesV4RunningMarkerWithoutInventingPreviousEnd() {
        val fixture = Fixture(seed = false)
        val started = fixture.collector().startSession()
        assertTrue(started.recoveryComplete)
        assertEquals(4, started.appSessionId.version())
        assertEquals(2, started.appSessionId.variant())
        assertEquals(started.appSessionId, fixture.store.state.current?.sessionId)
        assertEquals(SessionTerminationClassifier.MarkerState.Running, fixture.store.state.current?.observation?.state)
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun allPlatformReasonsMapThroughClassifierWithoutRawReasonPayload() {
        val expected = listOf("UNKNOWN", "CLEAN_EXIT", "SIGNAL", "LOW_MEMORY_KILL", "JAVA_CRASH",
            "NATIVE_CRASH", "ANR", "SYSTEM_KILL", "SYSTEM_KILL", "SYSTEM_KILL", "USER_REQUESTED",
            "USER_REQUESTED", "SYSTEM_KILL", "UNKNOWN", "SYSTEM_KILL", "SYSTEM_KILL", "SYSTEM_KILL")
        (expected.indices + listOf(-1, Int.MAX_VALUE)).forEach { reason ->
            val fixture = Fixture()
            fixture.exits = listOf(exit(reason))
            fixture.collector().startSession()
            assertEquals("platform reason $reason", expected.getOrNull(reason) ?: "UNKNOWN", fixture.events.single().reason.name)
        }
    }

    @Test fun everyMarkerStateHasConservativeFallbackEvidence() {
        val cases = mapOf(
            SessionTerminationClassifier.MarkerState.Running to Pair("UNKNOWN", "RECOVERED_MARKER"),
            SessionTerminationClassifier.MarkerState.Clean to Pair("CLEAN_EXIT", "RECOVERED_MARKER"),
            SessionTerminationClassifier.MarkerState.JavaCrash to Pair("JAVA_CRASH", "RECOVERED_MARKER"),
            SessionTerminationClassifier.MarkerState.Oom to Pair("OOM", "RECOVERED_MARKER"),
            SessionTerminationClassifier.MarkerState.WatchdogTimeout to Pair("ANR", "WATCHDOG_SUSPECTED"),
            SessionTerminationClassifier.MarkerState.WatchdogRecovered to Pair("UNKNOWN", "RECOVERED_MARKER"),
        )
        cases.forEach { (state, expected) ->
            val fixture = Fixture()
            fixture.store.state = fixture.store.state.copy(current = marker(state = state))
            fixture.collector(api = 27).startSession()
            assertEquals(expected, fixture.events.single().let { it.reason.name to it.evidence.name })
        }
    }

    @Test fun api27Through29NeverQueriesHistory() {
        (27..29).forEach { api ->
            val fixture = Fixture()
            fixture.historyFailure = IllegalStateException("must not query")
            fixture.collector(api = api).startSession()
            assertEquals(0, fixture.queries.size)
            assertEquals(DiagnosticEvent.ExitReason.UNKNOWN, fixture.events.single().reason)
        }
    }

    @Test fun api30EmptyHistoryFallsBackToUnknownMarkerEvidence() {
        val fixture = Fixture()
        fixture.collector().startSession()
        assertEquals(DiagnosticEvent.ExitReason.UNKNOWN, fixture.events.single().reason)
        assertEquals(DiagnosticEvent.ExitEvidence.RECOVERED_MARKER, fixture.events.single().evidence)
    }

    @Test fun api30QueriesExactPackagePriorPidAndEightRecords() {
        val fixture = Fixture()
        fixture.exits = List(8) { exit(0, time = 150 + it.toLong()) } + exit(6, time = 250)
        fixture.collector().startSession()
        assertEquals(listOf(Triple(PACKAGE, 41, 8)), fixture.queries)
        assertEquals(DiagnosticEvent.ExitReason.UNKNOWN, fixture.events.single().reason)
    }

    @Test fun processNameAndPidMustBothMatchBeforeAttribution() {
        val fixture = Fixture()
        fixture.exits = listOf(exit(6, name = "$PACKAGE:worker"), exit(5, pid = 99), exit(4, time = 180))
        fixture.collector().startSession()
        assertEquals(DiagnosticEvent.ExitReason.JAVA_CRASH, fixture.events.single().reason)
    }

    @Test fun strictCurrentStartBoundaryRejectsStaleNegativeFutureAndOverflowExits() {
        listOf(-1L, 119L, 300L, 301L, Long.MAX_VALUE).forEach { timestamp ->
            val fixture = Fixture()
            fixture.exits = listOf(exit(6, time = timestamp))
            fixture.collector().startSession()
            assertEquals("timestamp $timestamp", DiagnosticEvent.ExitReason.UNKNOWN, fixture.events.single().reason)
        }
    }

    @Test fun recordedBoundaryIsInclusiveAndCurrentStartMinusOneIsValid() {
        listOf(120L, 299L).forEach { timestamp ->
            val fixture = Fixture()
            fixture.exits = listOf(exit(6, time = timestamp))
            fixture.collector().startSession()
            assertEquals(timestamp, fixture.events.single().timestampMillis)
            assertEquals(DiagnosticEvent.ExitEvidence.RECOVERED_OS, fixture.events.single().evidence)
        }
    }

    @Test fun newestAttributableExitWinsRegardlessOfListOrder() {
        val fixture = Fixture()
        fixture.exits = listOf(exit(4, time = 140), exit(6, time = 250), exit(3, time = 200))
        fixture.collector().startSession()
        assertEquals(DiagnosticEvent.ExitReason.ANR, fixture.events.single().reason)
    }

    @Test fun authoritativeMarkerEndExcludesLaterHistory() {
        val fixture = Fixture()
        fixture.store.state = fixture.store.state.copy(current = marker().let {
            it.copy(observation = it.observation.copy(endedAtMillis = 180))
        })
        fixture.exits = listOf(exit(6, time = 181), exit(4, time = 180))
        fixture.collector().startSession()
        assertEquals(DiagnosticEvent.ExitReason.JAVA_CRASH, fixture.events.single().reason)
    }

    @Test fun nonzeroSelfExitCannotBecomeClean() {
        val fixture = Fixture()
        fixture.exits = listOf(exit(1, status = 1))
        fixture.collector().startSession()
        assertEquals(DiagnosticEvent.ExitReason.UNKNOWN, fixture.events.single().reason)
    }

    @Test fun oomMarkerRefinesJavaCrashWithoutCallingLmkOom() {
        listOf(4 to DiagnosticEvent.ExitReason.OOM, 3 to DiagnosticEvent.ExitReason.LOW_MEMORY_KILL).forEach { (reason, expected) ->
            val fixture = Fixture()
            fixture.store.state = fixture.store.state.copy(current = marker(state = SessionTerminationClassifier.MarkerState.Oom))
            fixture.exits = listOf(exit(reason))
            fixture.collector().startSession()
            assertEquals(expected, fixture.events.single().reason)
        }
    }

    @Test fun sigkillRemainsSignalEvenWithExplicitOomMarker() {
        val fixture = Fixture()
        fixture.store.state = fixture.store.state.copy(current = marker(state = SessionTerminationClassifier.MarkerState.Oom))
        fixture.exits = listOf(exit(2, status = 9))
        fixture.collector().startSession()
        assertEquals(DiagnosticEvent.ExitReason.SIGNAL, fixture.events.single().reason)
        assertEquals(DiagnosticEvent.ExitEvidence.RECOVERED_OS, fixture.events.single().evidence)
    }

    @Test fun acceptedAppendConsumesOnlyPreviousMarkerAndKeepsCurrent() {
        val fixture = Fixture()
        val old = fixture.store.state.current!!.sessionId
        val started = fixture.collector().startSession()
        assertEquals(old, fixture.events.single().appSessionId)
        assertEquals(started.appSessionId, fixture.store.state.current!!.sessionId)
        assertTrue(fixture.store.state.pending.isEmpty())
    }

    @Test fun rejectedAppendRetainsPreviousAndNewCurrentAtomically() {
        val fixture = Fixture()
        fixture.accept = false
        val old = fixture.store.state.current!!
        val started = fixture.collector().startSession()
        assertFalse(started.recoveryComplete)
        assertEquals(old.sessionId, fixture.store.state.pending.single().marker.sessionId)
        assertEquals(started.appSessionId, fixture.store.state.current!!.sessionId)
        assertEquals(fixture.events.single(), fixture.store.state.pending.single().event)
    }

    @Test fun appendThrowRetriesExactPersistedEventWithoutReclassification() {
        val fixture = Fixture()
        fixture.appendFailure = IOException("sink unavailable")
        val collector = fixture.collector()
        assertThrows(IOException::class.java) { collector.startSession() }
        val pending = fixture.store.state.pending.single().event
        fixture.appendFailure = null
        fixture.exits = listOf(exit(6))
        assertTrue(collector.recoverPending())
        assertEquals(listOf(pending, pending), fixture.events)
        assertEquals(1, fixture.queries.size)
    }

    @Test fun pendingEventSurvivesRelaunchWhilePreviousCurrentAlsoBecomesPending() {
        val fixture = Fixture()
        fixture.accept = false
        val first = fixture.collector().startSession()
        val pending = fixture.store.state.pending.single().event
        fixture.collector(start = 500).startSession()
        assertEquals(2, fixture.store.state.pending.size)
        assertEquals(pending, fixture.store.state.pending.first().event)
        assertEquals(first.appSessionId, fixture.store.state.pending.last().event.appSessionId)
        assertEquals(pending, fixture.events.last())
    }

    @Test fun pendingCommitFailureNeverCallsSinkOrReplacesPriorMarker() {
        val fixture = Fixture()
        fixture.store.failBefore = { it.pending.isNotEmpty() }
        assertThrows(IOException::class.java) { fixture.collector().startSession() }
        assertTrue(fixture.events.isEmpty())
        assertEquals(41, fixture.store.state.current!!.observation.pid)
    }

    @Test fun crashAfterAppendBeforeConsumeRetriesIdempotentExactEvent() {
        val fixture = Fixture()
        val accepted = mutableMapOf<UUID, DiagnosticEvent.AppEnded>()
        fixture.onAppend = { event ->
            accepted[event.id]?.let { assertEquals(it, event) }
            accepted[event.id] = event
        }
        fixture.store.failBefore = { it.pending.isEmpty() && fixture.events.isNotEmpty() }
        val collector = fixture.collector()
        assertThrows(IOException::class.java) { collector.startSession() }
        val persisted = fixture.store.state.pending.single().event
        fixture.store.failBefore = { false }
        assertTrue(collector.recoverPending())
        assertEquals(listOf(persisted, persisted), fixture.events)
        assertEquals(1, accepted.size)
    }

    @Test fun committedThenThrowingStartRetryUsesSameOwnedSession() {
        val fixture = Fixture(seed = false)
        fixture.store.failAfter = { it.current != null }
        val collector = fixture.collector()
        assertThrows(IOException::class.java) { collector.startSession() }
        val committed = fixture.store.state.current!!.sessionId
        fixture.store.failAfter = { false }
        assertEquals(committed, collector.startSession().appSessionId)
        assertTrue(fixture.events.isEmpty())
        assertNull(fixture.store.state.current!!.recoveryBeforeMillis)
    }

    @Test fun boundedBacklogRefusesNewTrackingWithoutLosingMarkersOrMovingBoundary() {
        val fixture = Fixture()
        fixture.accept = false
        repeat(8) { fixture.collector(start = 300L + it * 100).startSession() }
        val before = fixture.store.state
        assertThrows(RecoveryBacklogFull::class.java) { fixture.collector(start = 1200).startSession() }
        assertEquals(before.pending, fixture.store.state.pending)
        assertEquals(before.current!!.sessionId, fixture.store.state.current!!.sessionId)
        assertEquals(1200L, fixture.store.state.current!!.recoveryBeforeMillis)
        assertThrows(RecoveryBacklogFull::class.java) { fixture.collector(start = 1500).startSession() }
        assertEquals(1200L, fixture.store.state.current!!.recoveryBeforeMillis)
    }

    @Test fun manualEndPersistsCleanReportedMarkerOnRejectionAndRetriesAcrossRelaunch() {
        val fixture = Fixture(seed = false)
        val collector = fixture.collector()
        val session = collector.startSession().appSessionId
        fixture.accept = false
        assertFalse(collector.finishSession())
        val pending = fixture.store.state.pending.single()
        assertEquals(SessionTerminationClassifier.MarkerState.Clean, pending.marker.observation.state)
        assertEquals(400L, pending.marker.observation.endedAtMillis)
        assertEquals(DiagnosticEvent.ExitEvidence.REPORTED, pending.event.evidence)
        fixture.accept = true
        fixture.collector(start = 500).startSession()
        assertEquals(listOf(session, session), fixture.events.map { it.appSessionId })
        assertEquals(pending.event, fixture.events.last())
        assertTrue(fixture.queries.isEmpty())
    }

    @Test fun successfulManualEndNeverProducesAnotherEndOnNextLaunch() {
        val fixture = Fixture(seed = false)
        val collector = fixture.collector()
        collector.startSession()
        assertTrue(collector.finishSession())
        assertTrue(collector.finishSession())
        fixture.collector(start = 500).startSession()
        assertEquals(1, fixture.events.size)
        assertEquals(DiagnosticEvent.ExitReason.CLEAN_EXIT, fixture.events.single().reason)
    }

    @Test fun terminalSessionCannotAllocateAdditionalMetadata() {
        val fixture = Fixture(seed = false)
        val collector = fixture.collector()
        collector.startSession()
        collector.finishSession()
        assertThrows(IllegalStateException::class.java) { collector.nextMetadata() }
    }

    @Test fun sequenceAllocationPersistsAcrossCollectorsAndRecovery() {
        val fixture = Fixture(seed = false)
        val first = fixture.collector()
        first.startSession()
        assertEquals(1L, first.nextMetadata().sequence)
        assertEquals(2L, first.nextMetadata().sequence)
        val second = fixture.collector(start = 350)
        second.startSession()
        assertEquals(3L, fixture.events.single().sequence)
        assertEquals(4L, second.nextMetadata().sequence)
    }

    @Test fun sequenceOverflowRefusesAllocationWithoutWrapping() {
        val fixture = Fixture(seed = false)
        val collector = fixture.collector()
        collector.startSession()
        fixture.store.state = fixture.store.state.copy(lastSequence = Long.MAX_VALUE)
        assertThrows(ArithmeticException::class.java) { collector.nextMetadata() }
        assertEquals(Long.MAX_VALUE, fixture.store.state.lastSequence)
    }

    @Test fun recoverySequenceOverflowRetainsPriorMarker() {
        val fixture = Fixture()
        val old = fixture.store.state.current!!.sessionId
        fixture.store.state = fixture.store.state.copy(lastSequence = Long.MAX_VALUE)
        assertThrows(ArithmeticException::class.java) { fixture.collector().startSession() }
        assertEquals(old, fixture.store.state.current!!.sessionId)
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun invalidClocksAndClockRegressionCannotCreateSanitizerChangedEvents() {
        val fixture = Fixture(seed = false)
        val collector = fixture.collector()
        collector.startSession()
        listOf(-1L, 299L, DiagnosticSanitizer.MAX_TIMESTAMP_MILLIS + 1, Long.MAX_VALUE).forEach { timestamp ->
            fixture.now = timestamp
            assertThrows(IllegalArgumentException::class.java) { collector.nextMetadata() }
        }
        assertEquals(0L, fixture.store.state.lastSequence)
    }

    @Test fun pendingPayloadAlreadyMatchesDomainSanitizer() {
        val fixture = Fixture()
        fixture.accept = false
        fixture.exits = listOf(exit(6))
        fixture.collector().startSession()
        val event = fixture.store.state.pending.single().event
        assertEquals(event, DiagnosticSanitizer().sanitize(event))
        assertEquals(event, fixture.events.single())
    }

    @Test fun historyFailurePreservesMarkerAndFreezesFirstProcessStartBoundary() {
        val fixture = Fixture()
        fixture.historyFailure = IOException("history unavailable")
        assertThrows(IOException::class.java) { fixture.collector().startSession() }
        assertEquals(300L, fixture.store.state.current!!.recoveryBeforeMillis)
        fixture.historyFailure = null
        fixture.exits = listOf(exit(6, time = 350))
        fixture.collector(start = 500).startSession()
        assertEquals(DiagnosticEvent.ExitReason.UNKNOWN, fixture.events.single().reason)
    }

    @Test fun corruptMarkerFailsClosedWithoutQueryOrOverwrite() {
        val fixture = Fixture()
        fixture.store.state = fixture.store.state.copy(current = marker().let {
            it.copy(observation = it.observation.copy(startedAtMillis = -1))
        })
        val before = fixture.store.state
        assertThrows(CorruptSessionRecoveryState::class.java) { fixture.collector().startSession() }
        assertEquals(before, fixture.store.state)
        assertTrue(fixture.queries.isEmpty())
    }

    @Test fun processStartConversionUsesBootAgeAndRejectsImpossibleInputs() {
        assertEquals(700L, processStartEpochMillis(1000, 500, 200))
        assertEquals(1000L, processStartEpochMillis(1000, 500, 500))
        listOf(Triple(-1L, 500L, 200L), Triple(100L, 500L, 200L),
            Triple(1000L, 500L, 501L), Triple(1000L, Long.MAX_VALUE, -1L),
            Triple(Long.MAX_VALUE, 500L, 200L)).forEach { (wall, elapsed, started) ->
            assertThrows(IllegalArgumentException::class.java) { processStartEpochMillis(wall, elapsed, started) }
        }
    }

    @Test fun unsupportedApiAndInvalidProcessIdentityAreRejected() {
        val fixture = Fixture()
        assertThrows(IllegalArgumentException::class.java) { fixture.collector(api = 26) }
        assertThrows(IllegalArgumentException::class.java) { fixture.collector(start = -1) }
    }

    private class Fixture(seed: Boolean = true) {
        val store = MemoryStore(SessionRecoveryState(current = if (seed) marker() else null))
        val events = mutableListOf<DiagnosticEvent.AppEnded>()
        val queries = mutableListOf<Triple<String, Int, Int>>()
        var exits = emptyList<LocalProcessExit>()
        var accept = true
        var now = 400L
        var appendFailure: Exception? = null
        var historyFailure: Exception? = null
        var onAppend: (DiagnosticEvent.AppEnded) -> Unit = {}
        fun collector(start: Long = 300, api: Int = 30): AndroidExitRecoveryCollector {
            val recorder = DiagnosticsRecorder(DiagnosticsEventSink {
                val event = it as DiagnosticEvent.AppEnded
                events += event
                onAppend(event)
                appendFailure?.let { throw it }
                accept
            })
            val history = ExitHistory { name, pid, max ->
                queries += Triple(name, pid, max)
                historyFailure?.let { throw it }
                exits
            }
            return AndroidExitRecoveryCollector(store, recorder,
                RecoveryEnvironment(RecoveryProcess(42, start, PACKAGE, api)) { now }, history)
        }
    }

    private class MemoryStore(var state: SessionRecoveryState) : SessionRecoveryStore {
        var failBefore: (SessionRecoveryState) -> Boolean = { false }
        var failAfter: (SessionRecoveryState) -> Boolean = { false }
        override fun load() = state
        override fun save(state: SessionRecoveryState) {
            if (failBefore(state)) throw IOException("before commit")
            this.state = state
            if (failAfter(state)) throw IOException("after commit")
        }
    }

    private companion object {
        const val PACKAGE = "com.megastream.app"
        fun marker(state: SessionTerminationClassifier.MarkerState = SessionTerminationClassifier.MarkerState.Running) =
            SessionMarker(UUID.randomUUID(), SessionTerminationClassifier.PreviousSessionMarker(41, 100, 120, state))
        fun exit(reason: Int, time: Long = 200, pid: Int = 41, name: String = PACKAGE, status: Int = 0) =
            LocalProcessExit(name, SessionTerminationClassifier.ExitReasonSnapshot(pid, time, reason, status))
    }
}
