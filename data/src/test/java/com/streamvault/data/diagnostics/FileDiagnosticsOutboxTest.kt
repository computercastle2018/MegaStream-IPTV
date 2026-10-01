package com.MegaStream.data.diagnostics

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDiagnosticsOutboxTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_000_000_000L
    private fun id(number: Int) = UUID(1, number.toLong())
    private fun metadata(number: Int, timestamp: Long = now) = DiagnosticEvent.Metadata(id(number), id(999), number.toLong() + 1, timestamp)
    private fun app(number: Int, timestamp: Long = now) = DiagnosticEvent.AppStarted(metadata(number, timestamp), 1, "1.0")
    private fun crash(number: Int) = DiagnosticEvent.Crash(metadata(number), "java.lang.RuntimeException", emptyList())
    private fun start(number: Int, label: String?) = DiagnosticEvent.PlaybackStarted(
        metadata(number), id(50), label, DiagnosticEvent.SourceType.M3U,
        DiagnosticEvent.StreamType.HLS, DiagnosticEvent.PlaybackMode.TV_INPUT,
    )
    private fun sample(number: Int, memory: DiagnosticEvent.MemorySnapshot? = null) = DiagnosticEvent.PlaybackSample(
        metadata(number), id(50), DiagnosticEvent.VideoCodec.HEVC, DiagnosticEvent.AudioCodec.EAC3,
        DiagnosticEvent.VideoDecoder.HARDWARE, DiagnosticEvent.AudioDecoder.FFMPEG, 1920, 1080, 3_000_000_000L, 4_000_000_000L, 50, 60, memory,
    )
    private fun file() = File(temporary.newFolder(), "queue.bin")
    private fun open(file: File, clock: () -> Long = { now }) = FileDiagnosticsOutbox(file, clock = clock)

    @Test
    fun allTypedVariantsAndOptionalFieldsRoundTripWithoutLosingContractData() {
        val file = file()
        val memory = DiagnosticEvent.MemorySnapshot(10, 20, 30, 40, 50, true)
        val frames = listOf(DiagnosticEvent.RestrictedFrame("java.lang.Thread", "run", 12), DiagnosticEvent.RestrictedFrame("java.lang.String", "valueOf"))
        val events = listOf(
            app(1), start(2, "News HD"), sample(3, memory),
            DiagnosticEvent.PlaybackProblem(metadata(4), id(50), DiagnosticEvent.ProblemCategory.HTTP, DiagnosticEvent.ErrorCode.HTTP_5XX, 503, 5_000_000_000L, memory),
            DiagnosticEvent.PlaybackEnded(metadata(5), id(50), DiagnosticEvent.PlaybackEndReason.SLEEP_TIMER, 12345),
            DiagnosticEvent.MemoryPressure(metadata(6), memory, DiagnosticEvent.TrimLevel.RUNNING_CRITICAL),
            DiagnosticEvent.Crash(metadata(7), "java.lang.IllegalStateException", frames),
            DiagnosticEvent.Anr(metadata(8), DiagnosticEvent.AnrEvidence.OS_EXIT_REASON, 5000, frames),
            DiagnosticEvent.AppEnded(metadata(9), DiagnosticEvent.ExitReason.LOW_MEMORY_KILL, DiagnosticEvent.ExitEvidence.RECOVERED_OS),
            start(10, null), sample(11),
            DiagnosticEvent.PlaybackProblem(metadata(12), id(50), DiagnosticEvent.ProblemCategory.NETWORK, DiagnosticEvent.ErrorCode.DNS_FAILURE, null, 3, null),
            DiagnosticEvent.Anr(metadata(13), DiagnosticEvent.AnrEvidence.WATCHDOG_SUSPECTED, null, emptyList()),
        )
        val first = open(file)
        events.forEach { assertTrue(first.append(it)) }
        val restored = open(file)
        assertEquals(events, restored.batch())
        assertTrue(restored.ack(setOf(id(2), id(8))))
        val afterAck = open(file)
        assertEquals(events.filterNot { it.id == id(2) || it.id == id(8) }, afterAck.batch())
        assertTrue(afterAck.clear())
        assertTrue(open(file).batch().isEmpty())
        assertEquals(12L, file.length())
    }

    @Test
    fun reopenedCrashAndAnrFramesCannotBeMutatedThroughBatchSnapshots() {
        val file = file()
        val frames = listOf(DiagnosticEvent.RestrictedFrame("java.lang.Thread", "run", 12))
        val original = open(file)
        assertTrue(original.append(DiagnosticEvent.Crash(metadata(1), "java.lang.RuntimeException", frames)))
        assertTrue(original.append(DiagnosticEvent.Anr(metadata(2), DiagnosticEvent.AnrEvidence.WATCHDOG_SUSPECTED, null, frames)))
        val restored = open(file)
        val restoredEvents = restored.batch()
        val frameLists = listOf(
            (restoredEvents[0] as DiagnosticEvent.Crash).frames,
            (restoredEvents[1] as DiagnosticEvent.Anr).frames,
        )
        frameLists.forEach { restoredFrames ->
            try {
                (restoredFrames as MutableList<DiagnosticEvent.RestrictedFrame>).clear()
                throw AssertionError("Restored frames must be immutable")
            } catch (_: UnsupportedOperationException) {
                assertEquals(frames, restoredFrames)
            }
        }
        assertEquals(restoredEvents, restored.batch())
        assertEquals(restoredEvents, open(file).batch())
    }

    @Test
    fun onDiskBudgetMatchesExactHeaderAndRecordAccounting() {
        val file = file()
        val limits = DiagnosticsOutboxLimits(maxBytes = 152)
        val outbox = FileDiagnosticsOutbox(file, limits, { now })
        repeat(3) { assertTrue(outbox.append(app(it))) }
        assertEquals(152L, file.length())
        assertEquals(listOf(app(1), app(2)), outbox.batch())
        assertEquals(outbox.batch(), FileDiagnosticsOutbox(file, limits, { now }).batch())
    }

    @Test
    fun rawSinkUseSanitizesBeforeAnyBytesReachDisk() {
        val file = file()
        val outbox = open(file)
        val unsafe = start(1, "password Secret")
        assertTrue(outbox.append(unsafe))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("Secret"))
        assertEquals(listOf(unsafe.copy(channelName = null)), open(file).batch())
    }

    @Test
    fun expiryAndFutureRecordsArePhysicallyPrunedDuringRestoreAndRead() {
        val file = file()
        assertTrue(open(file).append(app(1)))
        var clock = now + DiagnosticsOutboxLimits.MAX_RETENTION_MILLIS
        val retainedAtBoundary = open(file) { clock }
        assertEquals(listOf(app(1)), retainedAtBoundary.batch())
        clock++
        assertTrue(retainedAtBoundary.batch().isEmpty())
        assertEquals(12L, file.length())
        file.writeBytes(snapshot(listOf(DiagnosticEventCodec.encode(app(2, now + 1)))))
        assertTrue(open(file).batch().isEmpty())
        assertEquals(12L, file.length())
        file.writeBytes(snapshot(listOf(DiagnosticEventCodec.encode(app(3, now - DiagnosticsOutboxLimits.MAX_RETENTION_MILLIS - 1)))))
        assertTrue(open(file).batch().isEmpty())
        assertEquals(12L, file.length())
    }

    @Test
    fun loweringCapacityOnRestoreRetainsCriticalEvidenceRatherThanDiscardingSnapshot() {
        val file = file()
        val original = open(file)
        val critical = crash(1)
        assertTrue(original.append(critical))
        assertTrue(original.append(app(2)))
        assertTrue(original.append(sample(3)))
        val exactCriticalBytes = 12L + 8 + DiagnosticEventCodec.encode(critical).size
        val smaller = FileDiagnosticsOutbox(file, DiagnosticsOutboxLimits(maxEvents = 1, maxBytes = exactCriticalBytes), { now })
        assertEquals(listOf(critical), smaller.batch())
        assertEquals(exactCriticalBytes, file.length())
    }

    @Test
    fun corruptedOrUnsafeSnapshotsFailClosedAndAreReplacedWithEmptyHeader() {
        val goodPayload = DiagnosticEventCodec.encode(app(1))
        val wrongTag = goodPayload.copyOf().apply { this[0] = 99 }
        val invalidEnum = DiagnosticEventCodec.encode(
            DiagnosticEvent.AppEnded(metadata(1), DiagnosticEvent.ExitReason.CLEAN_EXIT, DiagnosticEvent.ExitEvidence.REPORTED),
        ).apply { this[lastIndex] = 127 }
        val unsafeLabel = DiagnosticEventCodec.encode(start(1, "token Secret"))
        val nilId = DiagnosticEventCodec.encode(app(1).copy(metadata = metadata(1).copy(id = UUID(0, 0))))
        val wrongChecksum = snapshot(listOf(goodPayload)).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val attacks = listOf(
            byteArrayOf(1, 2, 3), snapshot(listOf(wrongTag)), snapshot(listOf(invalidEnum)),
            snapshot(listOf(unsafeLabel)), snapshot(listOf(nilId)), snapshot(listOf(goodPayload, goodPayload)), wrongChecksum,
            snapshot(listOf(goodPayload)) + byteArrayOf(0), header(count = 501), header(count = -1),
            header(count = 1) + intBytes(DiagnosticsOutboxLimits.MAX_PAYLOAD_BYTES + 1) + intBytes(0),
            header(count = 1) + intBytes(-1) + intBytes(0),
            header(count = 1) + intBytes(100) + intBytes(0) + byteArrayOf(1),
        )
        attacks.forEachIndexed { index, bytes ->
            val file = file()
            file.writeBytes(bytes)
            assertTrue("attack $index", open(file).batch().isEmpty())
            assertEquals("attack $index", 12L, file.length())
        }
    }

    @Test
    fun oversizedSparseSnapshotIsRejectedBeforeAllocationAndResetOnDisk() {
        val file = file()
        RandomAccessFile(file, "rw").use { it.setLength(DiagnosticsOutboxLimits.MAX_BYTES + 1) }
        assertTrue(open(file).batch().isEmpty())
        assertEquals(12L, file.length())
    }

    @Test
    fun orphanScratchIsRemovedWithoutTouchingUnrelatedFiles() {
        val file = file()
        assertTrue(open(file).append(app(1)))
        val scratch = File(file.parentFile, file.name + ".pending")
        scratch.writeText("old sensitive bytes")
        val unrelated = File(file.parentFile, "unrelated.pending").apply { writeText("keep") }
        val restored = open(file)
        assertEquals(listOf(app(1)), restored.batch())
        assertFalse(scratch.exists())
        assertEquals("keep", unrelated.readText())
        scratch.writeText("orphan before reset")
        assertTrue(restored.clear())
        assertFalse(scratch.exists())
        assertEquals("keep", unrelated.readText())
    }

    @Test
    fun filesystemFailureNeverAcceptsEventsAndRecoversAfterObstacleRemoved() {
        val file = file()
        assertTrue(file.mkdir())
        val obstacle = File(file, "keep").apply { writeText("not a queue") }
        val outbox = open(file)
        assertFalse(outbox.append(app(1)))
        assertFalse(outbox.ack(setOf(id(1))))
        assertFalse(outbox.clear())
        assertTrue(outbox.batch().isEmpty())
        assertEquals("not a queue", obstacle.readText())
        assertTrue(obstacle.delete())
        assertTrue(file.delete())
        assertTrue(outbox.append(app(1)))
        assertEquals(listOf(app(1)), open(file).batch())
    }

    @Test
    fun startupRewriteFailureHidesRestoredDataUntilPersistenceRecovers() {
        val file = file()
        assertTrue(open(file).append(app(1)))
        val scratch = File(file.parentFile, file.name + ".pending")
        assertTrue(scratch.mkdir())
        val obstacle = File(scratch, "keep").apply { writeText("obstacle") }
        val unavailable = open(file)
        assertTrue(unavailable.batch().isEmpty())
        assertFalse(unavailable.append(app(1)))
        assertFalse(unavailable.ack(setOf(id(1))))
        assertTrue(obstacle.delete())
        assertTrue(scratch.delete())
        assertEquals(listOf(app(1)), unavailable.batch())
        assertEquals(listOf(app(1)), open(file).batch())
    }

    @Test
    fun failedMutationDoesNotAcknowledgeOrForgetPreviouslyAcceptedEvents() {
        val file = file()
        val outbox = open(file)
        assertTrue(outbox.append(app(1)))
        val saved = File(file.parentFile, "saved.bin")
        Files.move(file.toPath(), saved.toPath())
        assertTrue(file.mkdir())
        val obstacle = File(file, "keep").apply { writeText("obstacle") }
        assertFalse(outbox.append(app(2)))
        assertFalse(outbox.ack(setOf(id(1))))
        assertFalse(outbox.clear())
        assertEquals(listOf(app(1)), outbox.batch())
        assertTrue(obstacle.delete())
        assertTrue(file.delete())
        Files.move(saved.toPath(), file.toPath())
        assertEquals(listOf(app(1)), open(file).batch())
    }

    private fun snapshot(payloads: List<ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(header(payloads.size))
            payloads.forEach { payload ->
                output.writeInt(payload.size)
                output.writeInt(CRC32().apply { update(payload) }.value.toInt())
                output.write(payload)
            }
        }
        return bytes.toByteArray()
    }

    private fun header(count: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x4d534451)
            output.writeInt(1)
            output.writeInt(count)
        }
    }.toByteArray()

    private fun intBytes(number: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { it.writeInt(number) }
    }.toByteArray()
}
