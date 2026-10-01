package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.InstallationCredentials
import com.MegaStream.app.controlplane.runtime.DiagnosticsTransport
import com.MegaStream.app.controlplane.runtime.RuntimeDiagnosticsUploader
import com.MegaStream.app.diagnostics.runtime.AndroidExitRecoveryCollector
import com.MegaStream.app.diagnostics.runtime.ExitHistory
import com.MegaStream.app.diagnostics.runtime.FileSessionRecoveryStore
import com.MegaStream.app.diagnostics.runtime.RecoveryEnvironment
import com.MegaStream.app.diagnostics.runtime.RecoveryProcess
import com.MegaStream.data.diagnostics.FileDiagnosticsOutbox
import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.DiagnosticsEventSink
import com.MegaStream.domain.diagnostics.DiagnosticsRecorder
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProductionRuntimeDiagnosticsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun rejectedPendingEndMakesRecoveryThrowAndPreservesPendingEvent() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.rejectEnds = true
        val previous = fixture.collector(start = 100)
        previous.startSession()
        assertFalse(previous.finishSession())
        val pending = fixture.store.load().pending.single().event

        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.diagnostics.recoverPending() }
        }

        assertEquals(pending, fixture.store.load().pending.single().event)
        assertTrue(fixture.persistedEvents().isEmpty())
    }

    @Test fun incompleteRecoveryNeverReturnsSessionIdOrRecordsAppStarted() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder())
        val previousId = fixture.collector(start = 100).startSession().appSessionId
        fixture.rejectEnds = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.diagnostics.startSession() }
        }

        assertEquals(previousId, fixture.store.load().pending.single().event.appSessionId)
        assertNotNull(fixture.store.load().current)
        assertTrue(fixture.persistedEvents().isEmpty())
    }

    @Test fun rejectedAppStartedBlocksReturnUntilRetryPersistsStart() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.rejectStarts = true
        fixture.diagnostics.recoverPending()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.diagnostics.startSession() }
        }
        val allocatedSession = fixture.store.load().current!!.sessionId
        assertTrue(fixture.outbox.batch(50).isEmpty())

        fixture.rejectStarts = false
        val returnedSession = fixture.diagnostics.startSession()
        assertEquals(allocatedSession.toString(), returnedSession)
        assertEquals(returnedSession, fixture.diagnostics.startSession())
        val start = fixture.persistedEvents().single() as DiagnosticEvent.AppStarted
        assertEquals(UUID.fromString(returnedSession), start.appSessionId)
        assertEquals(7L, start.appVersionCode)
        assertEquals("7.0", start.appVersionName)
    }

    @Test fun onlyManualExitRecordsDurableReportedEndForStartedSession() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.diagnostics.recoverPending()
        val session = fixture.diagnostics.startSession()
        fixture.diagnostics.recoverPending()
        assertEquals(session, fixture.diagnostics.startSession())
        assertTrue(fixture.outbox.batch(50).single() is DiagnosticEvent.AppStarted)

        fixture.diagnostics.manualExit()
        fixture.diagnostics.manualExit()

        val events = fixture.persistedEvents()
        assertEquals(2, events.size)
        val start = events[0] as DiagnosticEvent.AppStarted
        val end = events[1] as DiagnosticEvent.AppEnded
        assertEquals(UUID.fromString(session), start.appSessionId)
        assertEquals(start.appSessionId, end.appSessionId)
        assertTrue(end.sequence > start.sequence)
        assertEquals(DiagnosticEvent.ExitReason.CLEAN_EXIT, end.reason)
        assertEquals(DiagnosticEvent.ExitEvidence.REPORTED, end.evidence)
        assertNull(fixture.store.load().current)
        assertTrue(fixture.store.load().pending.isEmpty())
    }

    private class Fixture(directory: File) {
        private val outboxFile = File(directory, "outbox")
        val outbox = FileDiagnosticsOutbox(outboxFile, clock = { 500 })
        val store = FileSessionRecoveryStore(File(directory, "sessions")) { parent ->
            FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
        var rejectStarts = false
        var rejectEnds = false
        private val recorder = DiagnosticsRecorder(DiagnosticsEventSink { event ->
            when {
                rejectStarts && event is DiagnosticEvent.AppStarted -> false
                rejectEnds && event is DiagnosticEvent.AppEnded -> false
                else -> outbox.append(event)
            }
        })
        private val quarantine = DurableDiagnosticsQuarantine(File(directory, "quarantine"), outbox)
        private val uploader = RuntimeDiagnosticsUploader(
            outbox, quarantine, DiagnosticsTransport { _, _ -> error("Unexpected upload") },
            InstallationCredentials("00000000-0000-4000-8000-000000000001", "A".repeat(43)),
        )
        val diagnostics = ProductionRuntimeDiagnostics(collector(), recorder, uploader, quarantine, 7, "7.0")

        fun collector(start: Long = 300) = AndroidExitRecoveryCollector(
            store, recorder,
            RecoveryEnvironment(RecoveryProcess(41, start, "com.MegaStream.app", 27)) { 500 },
            ExitHistory { _, _, _ -> error("API 27 must not query exit history") },
        )

        fun persistedEvents(): List<DiagnosticEvent> =
            FileDiagnosticsOutbox(outboxFile, clock = { 500 }).batch(50)
    }
}
