package com.MegaStream.app.diagnostics.runtime

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.DiagnosticsEventSink
import com.MegaStream.domain.diagnostics.DiagnosticsRecorder
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID

class FileSessionRecoveryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realFileReopenPreservesCurrentPendingAndSequence() {
        val file = File(temporary.root, "session.bin")
        val previous = marker()
        val current = marker()
        val event = ended(previous.sessionId)
        val expected = SessionRecoveryState(9, current, listOf(PendingSessionEnd(previous, event)))
        store(file).save(expected)
        assertEquals(expected, store(file).load())
        assertFalse(File(temporary.root, "session.bin.pending").exists())
    }

    @Test fun realFileRetainsExactRejectedEventAcrossProcessRelaunchAndAcceptance() {
        val file = File(temporary.root, "session.bin")
        store(file).save(SessionRecoveryState(current = marker()))
        val rejected = mutableListOf<DiagnosticEvent>()
        collector(store(file), 300) { rejected += it; false }.startSession()
        val persisted = store(file).load().pending.single().event
        val accepted = mutableListOf<DiagnosticEvent>()
        collector(store(file), 500) { accepted += it; true }.startSession()
        assertEquals(persisted, rejected.single())
        assertEquals(persisted, accepted.first())
        assertEquals(2, accepted.size)
        assertTrue(store(file).load().pending.isEmpty())
    }

    @Test fun truncatedFileIsNotSilentlyResetOrOverwritten() {
        val file = File(temporary.root, "session.bin")
        store(file).save(SessionRecoveryState(current = marker()))
        val truncated = file.readBytes().dropLast(3).toByteArray()
        file.writeBytes(truncated)
        assertThrows(CorruptSessionRecoveryState::class.java) { collector(store(file), 300) { true }.startSession() }
        assertArrayEquals(truncated, file.readBytes())
    }

    @Test fun checksumCorruptionFailsClosedWithoutEmittingAnEnd() {
        val file = File(temporary.root, "session.bin")
        store(file).save(SessionRecoveryState(current = marker()))
        val corrupted = file.readBytes()
        corrupted[10] = (corrupted[10].toInt() xor 1).toByte()
        file.writeBytes(corrupted)
        var appended = false
        assertThrows(CorruptSessionRecoveryState::class.java) {
            collector(store(file), 300) { appended = true; true }.startSession()
        }
        assertFalse(appended)
        assertArrayEquals(corrupted, file.readBytes())
    }

    @Test fun oversizedAndEmptyFilesFailClosed() {
        val file = File(temporary.root, "session.bin")
        listOf(ByteArray(0), ByteArray(20 * 1024)).forEach { bytes ->
            file.writeBytes(bytes)
            assertThrows(CorruptSessionRecoveryState::class.java) { store(file).load() }
            assertEquals(bytes.size.toLong(), file.length())
        }
    }

    @Test fun orphanScratchNeverReplacesLastCommittedState() {
        val file = File(temporary.root, "session.bin")
        val expected = SessionRecoveryState(current = marker())
        store(file).save(expected)
        File(temporary.root, "session.bin.pending").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(expected, store(file).load())
        store(file).save(expected.copy(lastSequence = 1))
        assertEquals(1L, store(file).load().lastSequence)
    }

    @Test fun directorySyncFailureAfterRenameIsObservedByReloadAndSameCollectorRetry() {
        val file = File(temporary.root, "session.bin")
        var fail = true
        val disk = FileSessionRecoveryStore(file) {
            if (fail) throw IOException("directory sync unavailable")
            syncDirectory(it)
        }
        val collector = collector(disk, 300) { throw AssertionError("no prior session") }
        assertThrows(IOException::class.java) { collector.startSession() }
        val committed = store(file).load().current!!.sessionId
        fail = false
        assertEquals(committed, collector.startSession().appSessionId)
        assertNull(store(file).load().current!!.recoveryBeforeMillis)
    }

    @Test fun invalidPersistedStateCannotOverwriteValidSnapshot() {
        val file = File(temporary.root, "session.bin")
        val valid = SessionRecoveryState(current = marker())
        store(file).save(valid)
        val invalid = valid.copy(current = valid.current!!.let {
            it.copy(observation = it.observation.copy(recordedAtMillis = -1))
        })
        assertThrows(CorruptSessionRecoveryState::class.java) { store(file).save(invalid) }
        assertEquals(valid, store(file).load())
    }

    @Test fun sequenceAndPendingIdentityValidationRejectsCorruptCrossReferences() {
        val file = File(temporary.root, "session.bin")
        val previous = marker()
        val event = ended(previous.sessionId)
        val invalid = listOf(
            SessionRecoveryState(-1),
            SessionRecoveryState(0, pending = listOf(PendingSessionEnd(previous, event))),
            SessionRecoveryState(9, pending = listOf(PendingSessionEnd(previous, event.copy(
                metadata = event.metadata.copy(appSessionId = UUID.randomUUID()))))),
            SessionRecoveryState(9, previous, listOf(PendingSessionEnd(previous, event))),
        )
        invalid.forEach { state ->
            assertThrows(CorruptSessionRecoveryState::class.java) { store(file).save(state) }
        }
        assertFalse(file.exists())
    }

    @Test fun processNameAndRawPlatformReasonNeverEnterDurablePayload() {
        val file = File(temporary.root, "session.bin")
        store(file).save(SessionRecoveryState(current = marker()))
        val privateName = "sensitive-local-process-name"
        val history = ExitHistory { _, _, _ -> listOf(
            LocalProcessExit(privateName, SessionTerminationClassifier.ExitReasonSnapshot(41, 200, 6)),
            LocalProcessExit(PACKAGE, SessionTerminationClassifier.ExitReasonSnapshot(41, 210, Int.MAX_VALUE)),
        ) }
        val recorder = DiagnosticsRecorder(DiagnosticsEventSink { false })
        AndroidExitRecoveryCollector(store(file), recorder,
            RecoveryEnvironment(RecoveryProcess(42, 300, PACKAGE, 30)) { 400 }, history).startSession()
        val snapshot = store(file).load()
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(privateName))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(PACKAGE))
        assertEquals(DiagnosticEvent.ExitReason.UNKNOWN, snapshot.pending.single().event.reason)
        assertEquals(setOf("metadata", "reason", "evidence"),
            DiagnosticEvent.AppEnded::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }.toSet())
    }

    private fun store(file: File) = FileSessionRecoveryStore(file, ::syncDirectory)
    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    private fun collector(store: SessionRecoveryStore, start: Long, append: (DiagnosticEvent) -> Boolean) =
        AndroidExitRecoveryCollector(store, DiagnosticsRecorder(DiagnosticsEventSink(append)),
            RecoveryEnvironment(RecoveryProcess(42, start, PACKAGE, 27)) { start + 100 },
            ExitHistory { _, _, _ -> fail("API27 must not query"); emptyList() })

    private fun marker() = SessionMarker(UUID.randomUUID(),
        SessionTerminationClassifier.PreviousSessionMarker(41, 100, 120, SessionTerminationClassifier.MarkerState.Running))
    private fun ended(sessionId: UUID) = DiagnosticEvent.AppEnded(
        DiagnosticEvent.Metadata(UUID.randomUUID(), sessionId, 9, 200),
        DiagnosticEvent.ExitReason.UNKNOWN, DiagnosticEvent.ExitEvidence.RECOVERED_MARKER)
    private companion object { const val PACKAGE = "com.megastream.app" }
}
