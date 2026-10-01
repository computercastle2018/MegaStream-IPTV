package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.*
import com.MegaStream.app.controlplane.runtime.*
import com.MegaStream.domain.diagnostics.DiagnosticEvent as Event
import com.MegaStream.domain.diagnostics.DiagnosticsOutbox
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DurableRuntimeStoresTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val installation = "installation-one"
    private val credentials = InstallationCredentials("12345678-1234-4234-8234-123456789abc", "A".repeat(43))

    @Test fun registrationAttemptSurvivesRecreationAndRejectsMismatchedIdentity() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val first = DurableRuntimeStores(directory).getOrCreate(installation)
        val restarted = DurableRuntimeStores(directory)
        assertEquals(first, restarted.getOrCreate(installation))
        assertFalse(first.registered)
        expectFailure<IllegalStateException> { restarted.markRegistered(installation, UUID.randomUUID().toString()) }
        expectFailure<IllegalStateException> { restarted.markRegistered("different-installation", first.idempotencyKey) }
        assertEquals(first, DurableRuntimeStores(directory).getOrCreate(installation))
        restarted.markRegistered(installation, first.idempotencyKey)
        DurableRuntimeStores(directory).markRegistered(installation, first.idempotencyKey)
        assertEquals(first.copy(registered = true), DurableRuntimeStores(directory).getOrCreate(installation))
    }

    @Test fun sequenceStartsAtZeroAndPersistsStrictlyIncreasingValuesAcrossInstances() = runBlocking {
        val directory = temporaryFolder.newFolder()
        repeat(5) { assertEquals(it.toLong(), DurableRuntimeStores(directory).allocateNext(installation)) }
        assertEquals(0L, DurableRuntimeStores(directory).allocateNext("independent-installation"))
        assertEquals(5L, DurableRuntimeStores(directory).allocateNext(installation))
    }

    @Test fun concurrentInstancesAllocateUniqueContiguousSequences() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val allocations = (1..8).map {
            async(Dispatchers.Default) {
                val store = DurableRuntimeStores(directory)
                List(8) { store.allocateNext(installation) }.also { values ->
                    assertTrue(values.zipWithNext().all { (before, after) -> after > before })
                }
            }
        }.awaitAll().flatten()
        assertEquals((0L..63L).toList(), allocations.sorted())
        assertEquals(64L, DurableRuntimeStores(directory).allocateNext(installation))
    }

    @Test fun corruptOrTruncatedStateAndWitnessFailClosedWithoutReset() = runBlocking {
        val mutations: List<Pair<String, (ByteArray) -> ByteArray>> = listOf(
            "runtime.state" to { bytes -> bytes.copyOf(bytes.size / 2) },
            "runtime.state" to { bytes -> bytes.copyOf().also { it[it.lastIndex - 1] = '9'.code.toByte() } },
            "runtime.initialized" to { _ -> "corrupt witness".toByteArray() },
        )
        for ((filename, mutate) in mutations) {
            val directory = temporaryFolder.newFolder()
            assertEquals(0L, DurableRuntimeStores(directory).allocateNext(installation))
            val file = File(directory, filename)
            file.writeBytes(mutate(file.readBytes()))
            val damaged = file.readBytes()
            repeat(2) {
                val store = DurableRuntimeStores(directory)
                expectFailure<IllegalStateException> { store.allocateNext(installation) }
                expectFailure<IllegalStateException> { store.getOrCreate(installation) }
                assertArrayEquals(damaged, file.readBytes())
            }
        }
    }

    @Test fun missingStateWithInitializationWitnessNeverCreatesFreshLedger() = runBlocking {
        val directory = temporaryFolder.newFolder()
        DurableRuntimeStores(directory).allocateNext(installation)
        assertTrue(File(directory, "runtime.initialized").isFile)
        assertTrue(File(directory, "runtime.state").delete())
        expectFailure<IllegalStateException> { DurableRuntimeStores(directory).allocateNext(installation) }
        assertFalse(File(directory, "runtime.state").exists())
    }

    @Test fun validExhaustedSequenceFailsWithoutWrappingOrChangingRegistration() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val attempt = DurableRuntimeStores(directory).getOrCreate(installation)
        val state = File(directory, "runtime.state")
        val fields = state.readText().substringAfter("\n").substringAfter("\n").lines()[1].split('\t').toMutableList()
        assertEquals(5, fields.size)
        assertEquals("-", fields[4])
        fields[3] = Long.MAX_VALUE.toString()
        state.writeText(envelope("R2\n${fields.joinToString("\t")}\n"))
        val exhausted = state.readBytes()
        repeat(2) {
            val store = DurableRuntimeStores(directory)
            assertEquals(attempt, store.getOrCreate(installation))
            expectFailure<IllegalStateException> { store.allocateNext(installation) }
            assertArrayEquals(exhausted, state.readBytes())
        }
    }

    @Test fun failedAtomicWritePreservesPriorSequenceAndRepairDoesNotReuseReturnedValues() = runBlocking {
        val directory = temporaryFolder.newFolder()
        assertEquals(0L, DurableRuntimeStores(directory).allocateNext(installation))
        val state = File(directory, "runtime.state")
        val before = state.readBytes()
        val obstacle = File(directory, "runtime.state.next")
        assertTrue(obstacle.mkdir())
        expectFailure<IllegalStateException> { DurableRuntimeStores(directory).allocateNext(installation) }
        assertArrayEquals(before, state.readBytes())
        assertTrue(obstacle.delete())
        assertEquals(1L, DurableRuntimeStores(directory).allocateNext(installation))
        assertEquals(2L, DurableRuntimeStores(directory).allocateNext(installation))
    }

    @Test fun registrationMetadataFreezesEveryFieldAcrossRecreationAndRegistration() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val original = RuntimeMetadata(1L, "1.0", "original maker", "original model", 27, "8.1",
            DeviceAbi.ARM64_V8A, "ar-SA", false, "com.example.original", ReleaseChannel.STABLE)
        val changed = RuntimeMetadata(2L, "2.0", "new maker", "new model", 35, "15",
            DeviceAbi.X86_64, "en-US", true, "com.example.changed", ReleaseChannel.BETA)
        val store = DurableRuntimeStores(directory)
        assertEquals(original, store.registrationMetadata(installation, original))
        val attempt = store.getOrCreate(installation)
        val restarted = DurableRuntimeStores(directory)
        assertEquals(original, restarted.registrationMetadata(installation, changed))
        assertEquals(attempt, restarted.getOrCreate(installation))
        restarted.markRegistered(installation, attempt.idempotencyKey)
        assertEquals(original, DurableRuntimeStores(directory).registrationMetadata(installation, changed))
        assertEquals(attempt.copy(registered = true), DurableRuntimeStores(directory).getOrCreate(installation))
        val legacyAttempt = store.getOrCreate("existing-attempt")
        assertEquals(original, store.registrationMetadata("existing-attempt", original))
        assertEquals(legacyAttempt, DurableRuntimeStores(directory).getOrCreate("existing-attempt"))
        assertEquals(original, DurableRuntimeStores(directory).registrationMetadata("existing-attempt", changed))
    }

    @Test fun rejectedOriginalSurvivesRestartAndOnlyObservedAbsencePermitsUuidReuse() = runBlocking(Dispatchers.IO) {
        val directory = temporaryFolder.newFolder()
        val original = event()
        val outbox = MemoryOutbox(listOf(original))
        val quarantine = DurableDiagnosticsQuarantine(directory, outbox)
        var calls = 0
        val first = uploader(outbox, quarantine) { _, _ ->
            calls++
            response(rejected = listOf(RejectedDiagnosticEvent(original.id.toString(), DiagnosticRejectionCode.UNSAFE_CONTENT)))
        }
        assertEquals(DiagnosticsUploadResult.Uploaded(1, 0, 1, 0), quarantine.withUploadIntent { first.upload() })
        assertEquals(listOf(original), outbox.events)
        assertTrue(outbox.acknowledgements.isEmpty())
        val restarted = DurableDiagnosticsQuarantine(directory, outbox)
        val second = uploader(outbox, restarted) { _, request ->
            calls++
            response(accepted = request.events.map { it.eventId })
        }
        assertEquals(DiagnosticsUploadResult.BlockedByQuarantine(1), restarted.withUploadIntent { second.upload() })
        assertEquals(1, calls)
        assertTrue(outbox.clear())
        // This fake provides a truthful complete snapshot; no masked-empty recovery guarantee is assumed.
        assertEquals(DiagnosticsUploadResult.EmptySnapshot, restarted.withUploadIntent { second.upload() })
        assertTrue(outbox.append(original))
        assertEquals(DiagnosticsUploadResult.Uploaded(1, 1, 0, 0), restarted.withUploadIntent { second.upload() })
        assertEquals(2, calls)
        assertTrue(outbox.events.isEmpty())
        assertEquals(listOf(setOf(original.id)), outbox.acknowledgements)
    }

    @Test fun fullFiftyEventHeadCannotRetireMarkerForAnOriginalOutsideSnapshot() = runBlocking(Dispatchers.IO) {
        val directory = temporaryFolder.newFolder()
        val original = event()
        val outbox = MemoryOutbox(listOf(original))
        val quarantine = DurableDiagnosticsQuarantine(directory, outbox)
        val reject = uploader(outbox, quarantine) { _, _ ->
            response(rejected = listOf(RejectedDiagnosticEvent(original.id.toString(), DiagnosticRejectionCode.UNKNOWN)))
        }
        assertEquals(DiagnosticsUploadResult.Uploaded(1, 0, 1, 0), quarantine.withUploadIntent { reject.upload() })
        outbox.events.clear()
        outbox.events.addAll((2L..51L).map(::event))
        outbox.events.add(original) // The marked original exists, but is hidden beyond the complete head.
        val restarted = DurableDiagnosticsQuarantine(directory, outbox)
        var calls = 0
        val uploadHead = uploader(outbox, restarted) { _, request ->
            calls++
            assertEquals((2L..51L).map { event(it).id.toString() }, request.events.map { it.eventId })
            response(accepted = request.events.map { it.eventId })
        }
        assertEquals(DiagnosticsUploadResult.Uploaded(50, 50, 0, 0), restarted.withUploadIntent { uploadHead.upload() })
        assertEquals(listOf(original), outbox.events)
        val again = DurableDiagnosticsQuarantine(directory, outbox)
        val blocked = uploader(outbox, again) { _, _ -> throw AssertionError("Quarantined original must not reach network") }
        assertEquals(DiagnosticsUploadResult.BlockedByQuarantine(1), again.withUploadIntent { blocked.upload() })
        assertEquals(1, calls)
    }

    @Test fun markerWriteFailureLatchesAcrossRestartUntilExplicitOperatorRecovery() = runBlocking(Dispatchers.IO) {
        val directory = temporaryFolder.newFolder()
        val original = event()
        val outbox = MemoryOutbox(listOf(original))
        val quarantine = DurableDiagnosticsQuarantine(directory, outbox)
        val obstacle = File(directory, "diagnostics.state.next")
        var calls = 0
        val first = uploader(outbox, quarantine) { _, _ ->
            calls++
            // Transport runs only after the durable upload intent has been committed.
            assertTrue(obstacle.mkdir())
            response(rejected = listOf(RejectedDiagnosticEvent(original.id.toString(), DiagnosticRejectionCode.UNKNOWN)))
        }
        assertEquals(DiagnosticsUploadResult.StorageFailure(DiagnosticsStorageStage.WRITE_QUARANTINE),
            quarantine.withUploadIntent { first.upload() })
        assertEquals(listOf(original), outbox.events)
        assertTrue(outbox.acknowledgements.isEmpty())
        assertTrue(obstacle.delete())
        val restarted = DurableDiagnosticsQuarantine(directory, outbox)
        val afterRestart = uploader(outbox, restarted) { _, request ->
            calls++
            response(accepted = request.events.map { it.eventId })
        }
        assertEquals(DiagnosticsUploadResult.BlockedUntilRecovery, restarted.withUploadIntent { afterRestart.upload() })
        assertEquals(1, calls)
        // Explicit operator disposition of the unknown rejected original, not an automatic retry.
        assertTrue(outbox.clear())
        restarted.repairAfterStorageFailure()
        first.recoverAfterStorageFailure()
        assertEquals(DiagnosticsUploadResult.EmptySnapshot, quarantine.withUploadIntent { first.upload() })
        assertTrue(outbox.append(event(2)))
        assertEquals(DiagnosticsUploadResult.Uploaded(1, 1, 0, 0), restarted.withUploadIntent { afterRestart.upload() })
        assertEquals(2, calls)
    }

    @Test fun thrownBlockAndCancellationPreserveCrashIntentAcrossRestart() = runBlocking(Dispatchers.IO) {
        for (cancel in listOf(false, true)) {
            val directory = temporaryFolder.newFolder()
            val original = event()
            val outbox = MemoryOutbox(listOf(original))
            val quarantine = DurableDiagnosticsQuarantine(directory, outbox)
            if (cancel) {
                val upload = uploader(outbox, quarantine) { _, _ -> throw CancellationException("cancel transport") }
                expectFailure<CancellationException> { quarantine.withUploadIntent { upload.upload() } }
            } else {
                expectFailure<IllegalStateException> {
                    quarantine.withUploadIntent { throw IllegalArgumentException("interrupted caller") }
                }
            }
            val restarted = DurableDiagnosticsQuarantine(directory, outbox)
            val upload = uploader(outbox, restarted) { _, _ -> throw AssertionError("Crash latch must stop transport") }
            assertEquals(DiagnosticsUploadResult.BlockedUntilRecovery, restarted.withUploadIntent { upload.upload() })
            assertEquals(listOf(original), outbox.events)
            assertTrue(outbox.acknowledgements.isEmpty())
        }
    }

    @Test fun corruptQuarantineStatePreventsTransportWithoutResettingLedger() = runBlocking(Dispatchers.IO) {
        val directory = temporaryFolder.newFolder()
        val outbox = MemoryOutbox(emptyList())
        val quarantine = DurableDiagnosticsQuarantine(directory, outbox)
        val empty = uploader(outbox, quarantine) { _, _ -> throw AssertionError("Empty outbox must not upload") }
        assertEquals(DiagnosticsUploadResult.EmptySnapshot, quarantine.withUploadIntent { empty.upload() })
        val state = File(directory, "diagnostics.state")
        state.writeText(state.readText().dropLast(1))
        val damaged = state.readBytes()
        assertTrue(outbox.append(event()))
        val restarted = DurableDiagnosticsQuarantine(directory, outbox)
        val upload = uploader(outbox, restarted) { _, _ -> throw AssertionError("Corruption must stop transport") }
        expectFailure<IllegalStateException> { restarted.withUploadIntent { upload.upload() } }
        assertArrayEquals(damaged, state.readBytes())
        assertTrue(outbox.acknowledgements.isEmpty())
    }

    private fun event(number: Long = 1): Event = Event.AppStarted(Event.Metadata(UUID(2, number), UUID(1, 1), number, 0), 1, "1.0")

    private class MemoryOutbox(initial: List<Event>) : DiagnosticsOutbox {
        val events = initial.toMutableList()
        val acknowledgements = mutableListOf<Set<UUID>>()
        override fun append(event: Event): Boolean { events.add(event); return true }
        override fun batch(maxEvents: Int): List<Event> = events.take(maxEvents)
        override fun ack(ids: Set<UUID>): Boolean {
            acknowledgements.add(ids.toSet())
            events.removeAll { it.id in ids }
            return true
        }
        override fun clear(): Boolean { events.clear(); return true }
    }

    private fun response(accepted: List<String> = emptyList(), rejected: List<RejectedDiagnosticEvent> = emptyList()) =
        ControlPlaneResult.Success(DiagnosticsBatchResponse(accepted, emptyList(), rejected, "2026-09-30T00:00:00Z"))

    private fun uploader(outbox: MemoryOutbox, quarantine: DurableDiagnosticsQuarantine,
        transport: (String, DiagnosticsBatchRequest) -> ControlPlaneResult<DiagnosticsBatchResponse>) =
        RuntimeDiagnosticsUploader(outbox, quarantine, DiagnosticsTransport(transport), credentials)

    private fun envelope(payload: String): String {
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "MEGASTREAM-DURABLE-1\n$checksum\n$payload"
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            if (failure is T) return
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
