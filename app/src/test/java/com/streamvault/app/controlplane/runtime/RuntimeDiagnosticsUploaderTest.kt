package com.MegaStream.app.controlplane.runtime

import com.MegaStream.app.controlplane.*
import com.MegaStream.domain.diagnostics.DiagnosticEvent as Event
import com.MegaStream.domain.diagnostics.DiagnosticsOutbox
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticsUploaderTest {
    private val session = UUID(1, 1)
    private val credentials = InstallationCredentials("12345678-1234-4234-8234-123456789abc", "A".repeat(43))
    private val now = "2026-09-30T00:00:00Z"
    private fun metadata(n: Long = 1) = Event.Metadata(UUID(2, n), session, n, 0)
    private fun event(n: Long = 1) = Event.AppStarted(metadata(n), 1, "1.0")
    private fun response(accepted: List<String> = emptyList(), duplicates: List<String> = emptyList(),
        rejected: List<RejectedDiagnosticEvent> = emptyList()) =
        ControlPlaneResult.Success(DiagnosticsBatchResponse(accepted, duplicates, rejected, now))

    private class Outbox(events: List<Event>) : DiagnosticsOutbox {
        val events = events.toMutableList()
        val acknowledgements = mutableListOf<Set<UUID>>()
        var requested = 0
        var ackSucceeds = true
        var readFailure: Exception? = null
        override fun append(event: Event): Boolean { events.add(event); return true }
        override fun batch(maxEvents: Int): List<Event> {
            requested = maxEvents
            readFailure?.let { throw it }
            return events.take(maxEvents)
        }
        override fun ack(ids: Set<UUID>): Boolean {
            acknowledgements.add(ids.toSet())
            if (ackSucceeds) events.removeAll { it.id in ids }
            return ackSucceeds
        }
        override fun clear(): Boolean { events.clear(); return true }
    }
    private class Quarantine : DiagnosticsQuarantine {
        val markers = linkedMapOf<UUID, DiagnosticRejectionCode>()
        var succeeds = true
        var failure: Exception? = null
        override fun contains(id: UUID) = id in markers
        override fun record(id: UUID, reason: DiagnosticRejectionCode): Boolean {
            failure?.let { throw it }
            if (succeeds) markers[id] = reason
            return succeeds
        }
    }
    private fun uploader(outbox: Outbox, quarantine: Quarantine = Quarantine(), max: Int = 50,
        send: (String, DiagnosticsBatchRequest) -> ControlPlaneResult<DiagnosticsBatchResponse>) =
        RuntimeDiagnosticsUploader(outbox, quarantine, DiagnosticsTransport(send), credentials, maxEvents = max)

    @Test fun capsBatchesAtFiftyAndPassesExternalCredential() {
        val box = Outbox((1L..60).map(::event))
        val result = uploader(box) { credential, request ->
            assertEquals(credentials.credential, credential)
            assertEquals(50, request.events.size)
            response(accepted = request.events.map { it.eventId })
        }.upload()
        assertEquals(DiagnosticsUploadResult.Uploaded(50, 50, 0, 0), result)
        assertEquals(10, box.events.size)
    }

    @Test fun smallerUploadStillFetchesFiftyBeforeFiltering() {
        val box = Outbox((1L..5).map(::event))
        val quarantine = Quarantine().apply { markers[event().id] = DiagnosticRejectionCode.UNKNOWN }
        uploader(box, quarantine, 1) { _, request ->
            assertEquals(listOf(event(2).id.toString()), request.events.map { it.eventId })
            response()
        }.upload()
        assertEquals(50, box.requested)
    }

    @Test fun batchAndNestedCrashAndAnrFramesAreDeepImmutableSnapshots() {
        val frames = mutableListOf(Event.RestrictedFrame("java.lang.Thread", "run", 7))
        val box = Outbox(listOf(Event.Crash(metadata(), "java.lang.Exception", frames),
            Event.Anr(metadata(2), Event.AnrEvidence.WATCHDOG_SUSPECTED, 1, frames)))
        uploader(box) { _, request ->
            frames.clear()
            val crash = request.events[0].payload as CrashPayload
            val anr = request.events[1].payload as AnrPayload
            assertEquals(1, crash.frames.size)
            assertEquals(1, anr.frames.size)
            assertEquals(DiagnosticFrame("java.lang.Thread", "run", 7), crash.frames.single())
            immutable { (request.events as MutableList).clear() }
            immutable { (crash.frames as MutableList).clear() }
            immutable { (anr.frames as MutableList).clear() }
            response()
        }.upload()
    }

    @Test fun partialResponseAcknowledgesAcceptedAndDuplicatesOnly() {
        val box = Outbox((1L..3).map(::event))
        val result = uploader(box) { _, _ -> response(listOf(event().id.toString()), listOf(event(2).id.toString())) }.upload()
        assertEquals(DiagnosticsUploadResult.Uploaded(3, 2, 0, 1), result)
        assertEquals(setOf(event().id, event(2).id), box.acknowledgements.single())
        assertEquals(listOf(event(3)), box.events)
    }

    @Test fun permanentRejectionRemainsAndIsExcludedAcrossNewUploader() {
        val box = Outbox(listOf(event(), event(2)))
        val quarantine = Quarantine()
        uploader(box, quarantine) { _, _ -> response(rejected = listOf(RejectedDiagnosticEvent(event().id.toString(), DiagnosticRejectionCode.UNSAFE_CONTENT))) }.upload()
        assertEquals(2, box.events.size)
        assertTrue(box.acknowledgements.isEmpty())
        uploader(box, quarantine) { _, request ->
            assertEquals(listOf(event(2).id.toString()), request.events.map { it.eventId })
            response()
        }.upload()
    }

    @Test fun unknownRejectionIsDurablyQuarantinedNotAcknowledged() {
        val box = Outbox(listOf(event()))
        val quarantine = Quarantine()
        uploader(box, quarantine) { _, _ -> response(rejected = listOf(RejectedDiagnosticEvent(event().id.toString(), DiagnosticRejectionCode.UNKNOWN))) }.upload()
        assertEquals(DiagnosticRejectionCode.UNKNOWN, quarantine.markers[event().id])
        assertTrue(box.acknowledgements.isEmpty())
        val result = uploader(box, quarantine) { _, _ -> throw AssertionError("must not send") }.upload()
        assertEquals(DiagnosticsUploadResult.BlockedByQuarantine(1), result)
    }

    @Test fun duplicateSnapshotIsInvalidEvenIfQuarantined() {
        val box = Outbox(listOf(event(), event()))
        val quarantine = Quarantine().apply { markers[event().id] = DiagnosticRejectionCode.UNKNOWN }
        val result = uploader(box, quarantine) { _, _ -> throw AssertionError("must not send") }.upload()
        assertEquals(DiagnosticsUploadResult.InvalidBatch(InvalidBatchReason.DUPLICATE_IDS), result)
    }

    @Test fun collectivelyRejectsDuplicateOverlapAndForeignResponseIdsWithoutSideEffects() {
        val a = event().id.toString()
        val b = event(2).id.toString()
        val foreign = event(99).id.toString()
        val rejection = RejectedDiagnosticEvent(b, DiagnosticRejectionCode.INVALID_PAYLOAD)
        val cases = listOf(
            response(listOf(a, a)), response(duplicates = listOf(a, a)),
            response(listOf(a), listOf(a)), response(listOf(b), rejected = listOf(rejection)),
            response(duplicates = listOf(b), rejected = listOf(rejection)),
            response(rejected = listOf(rejection, rejection)),
            response(listOf(foreign)), response(duplicates = listOf(foreign)),
            response(listOf(a), rejected = listOf(RejectedDiagnosticEvent(foreign, DiagnosticRejectionCode.UNKNOWN))),
        )
        for (malformed in cases) {
            val box = Outbox(listOf(event(), event(2)))
            val quarantine = Quarantine()
            assertEquals(DiagnosticsUploadResult.InvalidResponse, uploader(box, quarantine) { _, _ -> malformed }.upload())
            assertTrue(box.acknowledgements.isEmpty())
            assertTrue(quarantine.markers.isEmpty())
        }
    }

    @Test fun mutatedInvalidResponseIdIsRejectedWithoutAck() {
        val accepted = mutableListOf(event().id.toString())
        val malformed = response(accepted)
        accepted[0] = "not-a-uuid"
        val box = Outbox(listOf(event()))
        assertEquals(DiagnosticsUploadResult.InvalidResponse, uploader(box) { _, _ -> malformed }.upload())
        assertTrue(box.acknowledgements.isEmpty())
    }

    @Test fun responseSnapshotSurvivesMutationDuringQuarantinePersistence() {
        val accepted = mutableListOf(event().id.toString())
        val duplicates = mutableListOf(event(2).id.toString())
        val rejected = mutableListOf(RejectedDiagnosticEvent(event(3).id.toString(), DiagnosticRejectionCode.UNKNOWN))
        val box = Outbox((1L..3).map(::event))
        val port = object : DiagnosticsQuarantine {
            override fun contains(id: UUID) = false
            override fun record(id: UUID, reason: DiagnosticRejectionCode): Boolean {
                accepted.clear(); duplicates.clear(); rejected.clear()
                return true
            }
        }
        val result = RuntimeDiagnosticsUploader(box, port, DiagnosticsTransport { _, _ -> response(accepted, duplicates, rejected) }, credentials).upload()
        assertEquals(DiagnosticsUploadResult.Uploaded(3, 2, 1, 0), result)
        assertEquals(setOf(event().id, event(2).id), box.acknowledgements.single())
    }

    @Test fun transportFailuresAndExceptionsNeverAckOrLeakDetails() {
        val box = Outbox(listOf(event()))
        val failure = ControlPlaneResult.Failure(ControlPlaneError.local("network_error"))
        assertEquals(DiagnosticsUploadResult.TransportFailure, uploader(box) { _, _ -> failure }.upload())
        val result = uploader(box) { _, _ -> throw IllegalStateException("secret payload password") }.upload()
        assertEquals(DiagnosticsUploadResult.TransportFailure, result)
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains(credentials.credential))
        assertFalse(uploader(box) { _, _ -> failure }.toString().contains(credentials.credential))
        assertTrue(box.acknowledgements.isEmpty())
    }

    @Test fun cancellationPropagatesWithoutAck() {
        val box = Outbox(listOf(event()))
        val cancellation = CancellationException("private")
        try {
            uploader(box) { _, _ -> throw cancellation }.upload()
            fail("Expected cancellation")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertTrue(box.acknowledgements.isEmpty())
    }

    @Test fun interruptionPropagatesAndRestoresInterruptFlag() {
        val box = Outbox(listOf(event()))
        try {
            uploader(box) { _, _ -> throw InterruptedException("private") }.upload()
            fail("Expected interruption")
        } catch (_: InterruptedException) { assertTrue(Thread.currentThread().isInterrupted) }
        finally { Thread.interrupted() }
        assertTrue(box.acknowledgements.isEmpty())
    }

    @Test fun failedQuarantineStoragePreventsAckAndLatchesUntilExplicitRecovery() {
        val box = Outbox(listOf(event(), event(2)))
        val quarantine = Quarantine().apply { succeeds = false }
        var calls = 0
        val uploader = uploader(box, quarantine) { _, _ ->
            calls++
            response(listOf(event().id.toString()), rejected = listOf(RejectedDiagnosticEvent(event(2).id.toString(), DiagnosticRejectionCode.UNKNOWN)))
        }
        assertEquals(DiagnosticsUploadResult.StorageFailure(DiagnosticsStorageStage.WRITE_QUARANTINE), uploader.upload())
        assertTrue(box.acknowledgements.isEmpty())
        assertEquals(DiagnosticsUploadResult.BlockedUntilRecovery, uploader.upload())
        assertEquals(1, calls)
        quarantine.succeeds = true
        uploader.recoverAfterStorageFailure()
        assertEquals(DiagnosticsUploadResult.Uploaded(2, 1, 1, 0), uploader.upload())
        assertEquals(DiagnosticRejectionCode.UNKNOWN, quarantine.markers[event(2).id])
    }

    @Test fun throwingQuarantineStorageAlsoLatchesAndNeverAcknowledges() {
        val box = Outbox(listOf(event()))
        val quarantine = Quarantine().apply { failure = IllegalStateException("secret") }
        val uploader = uploader(box, quarantine) { _, _ -> response(rejected = listOf(RejectedDiagnosticEvent(event().id.toString(), DiagnosticRejectionCode.UNKNOWN))) }
        assertEquals(DiagnosticsUploadResult.StorageFailure(DiagnosticsStorageStage.WRITE_QUARANTINE), uploader.upload())
        assertEquals(DiagnosticsUploadResult.BlockedUntilRecovery, uploader.upload())
        assertTrue(box.acknowledgements.isEmpty())
    }

    @Test fun quarantinedHeadFiftyBlocksEvenWhenLaterEventExists() {
        val box = Outbox((1L..51).map(::event))
        val quarantine = Quarantine().apply { (1L..50).forEach { markers[event(it).id] = DiagnosticRejectionCode.UNKNOWN } }
        assertEquals(DiagnosticsUploadResult.BlockedByQuarantine(50), uploader(box, quarantine) { _, _ -> throw AssertionError("must not send") }.upload())
        assertEquals(51, box.events.size)
    }

    @Test fun emptySnapshotAndStorageFailuresAreDistinct() {
        val box = Outbox(emptyList())
        val uploader = uploader(box) { _, _ -> throw AssertionError("must not send") }
        assertEquals(DiagnosticsUploadResult.EmptySnapshot, uploader.upload())
        box.readFailure = IllegalStateException("secret")
        assertEquals(DiagnosticsUploadResult.StorageFailure(DiagnosticsStorageStage.READ_OUTBOX), uploader.upload())
    }

    @Test fun ackStorageFailureDoesNotRemoveEvents() {
        val box = Outbox(listOf(event())).apply { ackSucceeds = false }
        assertEquals(DiagnosticsUploadResult.StorageFailure(DiagnosticsStorageStage.ACKNOWLEDGE), uploader(box) { _, _ -> response(listOf(event().id.toString())) }.upload())
        assertEquals(listOf(event()), box.events)
    }

    @Test fun mapsAllNineShapesAndNarrowsBounds() {
        val m = metadata().copy(sequence = -1, timestampMillis = -1)
        val memory = Event.MemorySnapshot(-1, 2, -1, -1, -1, true)
        val fixtures = listOf(
            Event.AppStarted(m, -1, "1.0"),
            Event.PlaybackStarted(m, session, "News HD", Event.SourceType.M3U, Event.StreamType.HLS, Event.PlaybackMode.LIVE),
            Event.PlaybackSample(m, session, Event.VideoCodec.H264, Event.AudioCodec.AAC, Event.VideoDecoder.HARDWARE, Event.AudioDecoder.PLATFORM, Int.MAX_VALUE, -1, -1, -1, -1, -1, memory),
            Event.PlaybackProblem(m, session, Event.ProblemCategory.HTTP, Event.ErrorCode.HTTP_5XX, 999, Long.MAX_VALUE, memory),
            Event.MemoryPressure(m, memory, Event.TrimLevel.LOW_MEMORY),
            Event.Crash(m, "java.lang.Exception", emptyList()),
            Event.Anr(m, Event.AnrEvidence.OS_EXIT_REASON, -1, emptyList()),
            Event.PlaybackEnded(m, session, Event.PlaybackEndReason.SLEEP_TIMER, -1),
            Event.AppEnded(m, Event.ExitReason.OS_EXIT, Event.ExitEvidence.RECOVERED_OS),
        )
        val mapped = fixtures.map { DiagnosticEventMapper().map(it)!! }
        assertEquals(DiagnosticKind.entries.toSet(), mapped.map { it.kind }.toSet())
        mapped.forEach { assertEquals(0L, it.sequence); assertEquals("1970-01-01T00:00:00Z", it.occurredAt) }
        val sample = mapped[2].payload as PlaybackSamplePayload
        assertEquals(65_535, sample.width); assertEquals(0, sample.height)
        assertEquals(0L, sample.droppedFrames); assertEquals(0L, sample.memory!!.javaUsedBytes)
        val problem = mapped[3].payload as PlaybackProblemPayload
        assertEquals(Long.MAX_VALUE, problem.retryAttempt); assertNull(problem.httpStatus)
        assertEquals(PlaybackEndReason.SLEEP_TIMER, (mapped[7].payload as PlaybackEndedPayload).reason)
        val hugeMemory = Event.MemorySnapshot(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, false)
        val boundedMemory = (DiagnosticEventMapper().map(Event.MemoryPressure(m, hugeMemory, Event.TrimLevel.LOW_MEMORY))!!.payload as MemoryPressurePayload).memory
        assertEquals(1_099_511_627_776L, boundedMemory.javaMaxBytes)
        assertTrue(boundedMemory.javaUsedBytes <= boundedMemory.javaMaxBytes)
        assertEquals(1_099_511_627_776L, boundedMemory.pssBytes)
        val encoded = Json.encodeToString(mapped[7])
        assertTrue(encoded.contains("\"sleep_timer\""))
        assertEquals(mapped[7], Json.decodeFromString<DiagnosticEvent>(encoded))
    }

    @Test fun mapperRejectsNilIdentitiesIncludingPlaybackWithoutFabricatingThem() {
        val nil = UUID(0, 0)
        val mapper = DiagnosticEventMapper()
        assertNull(mapper.map(event().copy(metadata = metadata().copy(id = nil))))
        assertNull(mapper.map(event().copy(metadata = metadata().copy(appSessionId = nil))))
        assertNull(mapper.map(Event.PlaybackEnded(metadata(), nil, Event.PlaybackEndReason.USER_STOP, 1)))
        val box = Outbox(listOf(event().copy(metadata = metadata().copy(id = nil))))
        assertEquals(DiagnosticsUploadResult.InvalidBatch(InvalidBatchReason.INVALID_EVENT), uploader(box) { _, _ -> throw AssertionError("must not send") }.upload())
    }

    @Test fun mapperRedactsUntrustedLabelsVersionsAndFrames() {
        val mapper = DiagnosticEventMapper()
        assertEquals("unknown", (mapper.map(event().copy(appVersionName = "password=secret"))!!.payload as AppStartedPayload).appVersionName)
        val playback = Event.PlaybackStarted(metadata(), session, "https://user:secret@host", Event.SourceType.M3U, Event.StreamType.HLS, Event.PlaybackMode.LIVE)
        assertNull((mapper.map(playback)!!.payload as PlaybackStartedPayload).channelName)
        val crash = Event.Crash(metadata(), "password=secret", listOf(Event.RestrictedFrame("https://secret", "run"), Event.RestrictedFrame("java.lang.Thread", "run", -1)))
        val safe = mapper.map(crash)!!.payload as CrashPayload
        assertEquals("unknown", safe.exceptionType)
        assertEquals(listOf(DiagnosticFrame("java.lang.Thread", "run", null)), safe.frames)
        assertFalse(safe.toString().contains("secret"))
        val bounded = mapper.map(crash.copy(metadata = metadata().copy(timestampMillis = Long.MAX_VALUE),
            frames = List(33) { Event.RestrictedFrame("java.lang.Thread", "run", 1) }))!!
        assertEquals(32, (bounded.payload as CrashPayload).frames.size)
        assertEquals("9999-12-31T23:59:59.999Z", bounded.occurredAt)
    }

    private fun immutable(action: () -> Unit) {
        try { action(); fail("Expected unmodifiable list") }
        catch (_: UnsupportedOperationException) { /* expected */ }
    }
}
