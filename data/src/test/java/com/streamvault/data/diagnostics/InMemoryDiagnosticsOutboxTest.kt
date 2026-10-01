package com.MegaStream.data.diagnostics

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.DiagnosticsOutbox
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryDiagnosticsOutboxTest {
    private val now = 1_000_000_000L
    private fun id(number: Int): UUID = UUID(1, number.toLong())
    private fun metadata(number: Int, timestamp: Long = now) = DiagnosticEvent.Metadata(id(number), id(999), number.toLong() + 1, timestamp)
    private fun app(number: Int, timestamp: Long = now) = DiagnosticEvent.AppStarted(metadata(number, timestamp), 1, "1.0")
    private fun sample(number: Int) = DiagnosticEvent.PlaybackSample(
        metadata(number), id(9999), DiagnosticEvent.VideoCodec.UNKNOWN, DiagnosticEvent.AudioCodec.UNKNOWN,
        DiagnosticEvent.VideoDecoder.UNKNOWN, DiagnosticEvent.AudioDecoder.UNKNOWN, 0, 0, 0, 0, 0, 0, null,
    )
    private fun crashEvent(number: Int) = DiagnosticEvent.Crash(metadata(number), "java.lang.RuntimeException", emptyList())
    private fun anrEvent(number: Int) = DiagnosticEvent.Anr(metadata(number), DiagnosticEvent.AnrEvidence.WATCHDOG_SUSPECTED, null, emptyList())

    @Test
    fun hardCountBoundAndFiftyEventBatchesKeepNewestNormalEvents() {
        val outbox = InMemoryDiagnosticsOutbox(clock = { now })
        repeat(510) { assertTrue(outbox.append(app(it))) }
        assertEquals(50, outbox.batch().size)
        assertEquals((10 until 510).map { id(it) }, drain(outbox).map { it.id })
    }

    @Test
    fun incomingSampleCannotEvictCriticalQueueAndPriorityTiesEvictOldest() {
        val outbox = InMemoryDiagnosticsOutbox(DiagnosticsOutboxLimits(maxEvents = 2), { now })
        val crash = crashEvent(1)
        val anr = anrEvent(2)
        assertTrue(outbox.append(crash))
        assertTrue(outbox.append(anr))
        assertFalse(outbox.append(sample(3)))
        assertFalse(outbox.append(app(4)))
        assertEquals(listOf(crash, anr), outbox.batch())
        val newerCrash = crashEvent(5)
        assertTrue(outbox.append(newerCrash))
        assertEquals(listOf(anr, newerCrash), outbox.batch())
    }

    @Test
    fun samplesAreEvictedBeforeNormalEventsAndNormalBeforeCritical() {
        val outbox = InMemoryDiagnosticsOutbox(DiagnosticsOutboxLimits(maxEvents = 3), { now })
        val crash = crashEvent(1)
        listOf(crash, app(2), sample(3)).forEach { assertTrue(outbox.append(it)) }
        val anr = anrEvent(4)
        assertTrue(outbox.append(anr))
        assertEquals(listOf(crash, app(2), anr), outbox.batch())
        assertTrue(outbox.append(app(5)))
        assertEquals(listOf(crash, anr, app(5)), outbox.batch())
        assertFalse(outbox.append(sample(6)))
    }

    @Test
    fun byteBudgetIncludesHeaderAndRecordFramingExactly() {
        // Tag 1 + two UUIDs 32 + sequence/time 16 + versionCode 8 + UTF "1.0" 5 + framing 8 + header 12.
        val exact = InMemoryDiagnosticsOutbox(DiagnosticsOutboxLimits(maxBytes = 82), { now })
        assertTrue(exact.append(app(1)))
        assertTrue(exact.append(app(2)))
        assertEquals(listOf(app(2)), exact.batch())
        val tooSmall = InMemoryDiagnosticsOutbox(DiagnosticsOutboxLimits(maxBytes = 81), { now })
        assertFalse(tooSmall.append(app(1)))
        assertTrue(tooSmall.batch().isEmpty())
    }

    @Test
    fun activeIdDeduplicationAllowsReuseAfterAcknowledgementAndReset() {
        val outbox = InMemoryDiagnosticsOutbox(clock = { now })
        assertTrue(outbox.append(app(1)))
        assertTrue(outbox.append(crashEvent(1)))
        assertEquals(listOf(app(1)), outbox.batch())
        assertTrue(outbox.ack(setOf(id(999))))
        assertTrue(outbox.ack(setOf(id(1))))
        assertTrue(outbox.append(app(1)))
        assertTrue(outbox.clear())
        assertTrue(outbox.append(app(1)))
        assertEquals(listOf(app(1)), outbox.batch())
    }

    @Test
    fun expiryIsCheckedAtAppendAndReadAndFutureEventsAreRejected() {
        var clock = now
        val outbox = InMemoryDiagnosticsOutbox(clock = { clock })
        val cutoff = now - DiagnosticsOutboxLimits.MAX_RETENTION_MILLIS
        assertTrue(outbox.append(app(1, cutoff)))
        assertFalse(outbox.append(app(2, cutoff - 1)))
        assertFalse(outbox.append(app(3, now + 1)))
        clock++
        assertTrue(outbox.batch().isEmpty())
        assertTrue(outbox.append(app(1, clock)))
        clock += DiagnosticsOutboxLimits.MAX_RETENTION_MILLIS + 1
        assertTrue(outbox.append(app(4, clock)))
        assertEquals(listOf(app(4, clock)), outbox.batch())
    }

    @Test
    fun bypassingRecorderStillSanitizesAndSnapshotsCannotMutateQueue() {
        val outbox = InMemoryDiagnosticsOutbox(clock = { now })
        val unsafe = DiagnosticEvent.PlaybackStarted(
            metadata(1), id(3), "password abc", DiagnosticEvent.SourceType.UNKNOWN,
            DiagnosticEvent.StreamType.UNKNOWN, DiagnosticEvent.PlaybackMode.LIVE,
        )
        assertTrue(outbox.append(unsafe))
        val snapshot = outbox.batch()
        assertEquals(listOf(unsafe.copy(channelName = null)), snapshot)
        try {
            (snapshot as MutableList<DiagnosticEvent>).clear()
            throw AssertionError("Snapshot must be immutable")
        } catch (_: UnsupportedOperationException) {
            assertEquals(snapshot, outbox.batch())
        }
        assertTrue(outbox.append(app(2)))
        assertEquals(1, snapshot.size)
    }

    @Test
    fun callerFrameMutationCannotChangeQueuedEventOrStoredByteBudget() {
        val frames = mutableListOf(DiagnosticEvent.RestrictedFrame("java.lang.Thread", "run", 12))
        val outbox = InMemoryDiagnosticsOutbox(clock = { now })
        assertTrue(outbox.append(DiagnosticEvent.Crash(metadata(1), "java.lang.RuntimeException", frames)))
        frames.clear()
        val snapshot = outbox.batch().single() as DiagnosticEvent.Crash
        assertEquals(1, snapshot.frames.size)
        try {
            (snapshot.frames as MutableList<DiagnosticEvent.RestrictedFrame>).clear()
            throw AssertionError("Frames must be immutable")
        } catch (_: UnsupportedOperationException) {
            assertEquals(1, (outbox.batch().single() as DiagnosticEvent.Crash).frames.size)
        }
    }

    @Test
    fun nilCommonAndPlaybackIdentitiesAreRejectedRatherThanFabricated() {
        val nil = UUID(0, 0)
        val outbox = InMemoryDiagnosticsOutbox(clock = { now })
        val invalid = listOf(
            app(1).copy(metadata = metadata(1).copy(id = nil)),
            app(1).copy(metadata = metadata(1).copy(appSessionId = nil)),
            sample(1).copy(playbackSessionId = nil),
            DiagnosticEvent.PlaybackEnded(metadata(1), nil, DiagnosticEvent.PlaybackEndReason.UNKNOWN, 0),
        )
        invalid.forEach { assertFalse(outbox.append(it)) }
        assertTrue(outbox.batch().isEmpty())
    }

    @Test
    fun concurrentWritersAndReadersRespectBoundsAndUniqueIdentity() {
        val outbox = InMemoryDiagnosticsOutbox(clock = { now })
        val executor = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 8).map { thread -> Callable {
                repeat(100) { index ->
                    assertTrue(outbox.append(app(thread * 100 + index)))
                    assertTrue(outbox.batch().size <= 50)
                }
            } }
            executor.invokeAll(jobs, 30, TimeUnit.SECONDS).forEach { it.get() }
            val retained = drain(outbox)
            assertEquals(500, retained.size)
            assertEquals(500, retained.map { it.id }.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun configurableLimitsCannotExceedHardLimits() {
        val invalid = listOf<() -> Unit>(
            { DiagnosticsOutboxLimits(maxEvents = 501) }, { DiagnosticsOutboxLimits(maxEvents = 0) },
            { DiagnosticsOutboxLimits(maxBytes = 5L * 1024 * 1024 + 1) }, { DiagnosticsOutboxLimits(maxBytes = 11) },
            { DiagnosticsOutboxLimits(retentionMillis = 604_800_001L) }, { DiagnosticsOutboxLimits(retentionMillis = 0) },
            { InMemoryDiagnosticsOutbox().batch(51) }, { InMemoryDiagnosticsOutbox().batch(0) },
        )
        invalid.forEach { attempt ->
            try {
                attempt()
                throw AssertionError("Expected invalid limit rejection")
            } catch (_: IllegalArgumentException) {
                // Explicit rejection keeps a mistaken caller configuration visible.
            }
        }
    }

    private fun drain(outbox: DiagnosticsOutbox): List<DiagnosticEvent> {
        val drained = mutableListOf<DiagnosticEvent>()
        while (true) {
            val batch = outbox.batch()
            if (batch.isEmpty()) return drained
            drained.addAll(batch)
            assertTrue(outbox.ack(batch.map { it.id }.toSet()))
        }
    }
}
