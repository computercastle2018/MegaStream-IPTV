package com.MegaStream.app.controlplane.runtime

import com.MegaStream.app.controlplane.InstallationCredentials
import com.MegaStream.app.controlplane.ControlPlaneResult
import com.MegaStream.app.controlplane.DiagnosticRejectionCode
import com.MegaStream.app.controlplane.DiagnosticsBatchRequest
import com.MegaStream.app.controlplane.DiagnosticsBatchResponse
import com.MegaStream.domain.diagnostics.DiagnosticsOutbox
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException

fun interface DiagnosticsTransport {
    fun upload(credential: String, request: DiagnosticsBatchRequest): ControlPlaneResult<DiagnosticsBatchResponse>
}

/**
 * Durable markers, not an event store. record true acknowledges persistence, not just an in-memory write.
 * Retain markers while corresponding originals exist, including across process restarts. Originals stay
 * in the bounded outbox and consume capacity subject to its existing eviction/expiry policy.
 * Never evict a marker while its original exists. The adapter must retire stale markers after originals
 * disappear: outbox UUIDs can be reused after expiry, eviction, acknowledgement or clear.
 */
interface DiagnosticsQuarantine {
    fun contains(id: UUID): Boolean
    fun record(id: UUID, reason: DiagnosticRejectionCode): Boolean
}

enum class InvalidBatchReason { DUPLICATE_IDS, INVALID_EVENT, OVERSIZED_SNAPSHOT }
enum class DiagnosticsStorageStage { READ_OUTBOX, READ_QUARANTINE, WRITE_QUARANTINE, ACKNOWLEDGE }

/** Counts and closed vocabulary only: never carries exceptions, identifiers, payloads or credentials. */
sealed interface DiagnosticsUploadResult {
    /** An empty observed snapshot is not proof of a durably drained queue. */
    data object EmptySnapshot : DiagnosticsUploadResult
    data class BlockedByQuarantine(val count: Int) : DiagnosticsUploadResult
    data object BlockedUntilRecovery : DiagnosticsUploadResult
    data class InvalidBatch(val reason: InvalidBatchReason) : DiagnosticsUploadResult
    data object InvalidResponse : DiagnosticsUploadResult
    data object TransportFailure : DiagnosticsUploadResult
    data class StorageFailure(val stage: DiagnosticsStorageStage) : DiagnosticsUploadResult
    data class Uploaded(val sent: Int, val acknowledged: Int, val quarantined: Int, val unmentioned: Int) : DiagnosticsUploadResult
}

/**
 * Pure blocking orchestration: callers serialize calls (including recovery) and supply their own IO
 * dispatcher. No scheduler or credential persistence. Injected credentials, outbox and quarantine must
 * belong to the SAME installation for this instance's entire lifetime; replace all three together when
 * changing installation. A ControlPlaneClient can be adapted with
 * DiagnosticsTransport(client::diagnostics).
 *
 * Only the head 50 are inspected, even when maxEvents is smaller. Quarantined originals can block
 * later events: an entirely quarantined head returns BlockedByQuarantine, not a fake empty queue.
 *
 * A failed quarantine write latches this instance BEFORE persistence, including cancellation during
 * that write. Callers MUST stop automatic retries, including recreating this uploader after a restart,
 * until durable storage has been repaired/reconciled. Otherwise unknown server rejections can hot-loop.
 */
class RuntimeDiagnosticsUploader(
    private val outbox: DiagnosticsOutbox,
    private val quarantine: DiagnosticsQuarantine,
    private val transport: DiagnosticsTransport,
    private val credentials: InstallationCredentials,
    private val maxEvents: Int = 50,
) {
    init { require(maxEvents in 1..50) }
    private val mapper = DiagnosticEventMapper()
    private var recoveryRequired = false

    /** Explicit operator/caller recovery only; never call from an automatic retry loop. */
    fun recoverAfterStorageFailure() { recoveryRequired = false }

    private fun validatedResponse(response: DiagnosticsBatchResponse, sentIds: Set<String>): DiagnosticsBatchResponse? {
        // Copy every list before validating or performing side effects; DTO constructors revalidate syntax.
        val accepted = Collections.unmodifiableList(ArrayList(response.acceptedEventIds))
        val duplicates = Collections.unmodifiableList(ArrayList(response.duplicateEventIds))
        val rejected = Collections.unmodifiableList(response.rejected.map { it.copy() })
        val copied = DiagnosticsBatchResponse(accepted, duplicates, rejected, response.serverTime)
        val allIds = accepted + duplicates + rejected.map { it.eventId }
        // Membership also proves exact canonical UUID spelling against the immutable sent snapshot.
        return copied.takeIf { allIds.size <= sentIds.size && allIds.toSet().size == allIds.size && allIds.all { it in sentIds } }
    }

    fun upload(): DiagnosticsUploadResult {
        if (recoveryRequired) return DiagnosticsUploadResult.BlockedUntilRecovery
        var failure: DiagnosticsUploadResult = DiagnosticsUploadResult.StorageFailure(DiagnosticsStorageStage.READ_OUTBOX)
        try {
            val snapshot = outbox.batch(50).toList()
            if (snapshot.size > 50) return DiagnosticsUploadResult.InvalidBatch(InvalidBatchReason.OVERSIZED_SNAPSHOT)
            if (snapshot.map { it.id }.toSet().size != snapshot.size)
                return DiagnosticsUploadResult.InvalidBatch(InvalidBatchReason.DUPLICATE_IDS)
            if (snapshot.isEmpty()) return DiagnosticsUploadResult.EmptySnapshot
            failure = DiagnosticsUploadResult.StorageFailure(DiagnosticsStorageStage.READ_QUARANTINE)
            val candidates = snapshot.filterNot { quarantine.contains(it.id) }.take(maxEvents)
            if (candidates.isEmpty()) return DiagnosticsUploadResult.BlockedByQuarantine(snapshot.size)
            failure = DiagnosticsUploadResult.InvalidBatch(InvalidBatchReason.INVALID_EVENT)
            val events = candidates.map { mapper.map(it)
                ?: return DiagnosticsUploadResult.InvalidBatch(InvalidBatchReason.INVALID_EVENT) }
            val request = DiagnosticsBatchRequest(events = Collections.unmodifiableList(ArrayList(events)))
            failure = DiagnosticsUploadResult.TransportFailure
            val response = when (val result = transport.upload(credentials.credential, request)) {
                is ControlPlaneResult.Failure -> return DiagnosticsUploadResult.TransportFailure
                is ControlPlaneResult.Success -> result.value
            }
            failure = DiagnosticsUploadResult.InvalidResponse
            val validated = validatedResponse(response, events.map { it.eventId }.toSet())
                ?: return DiagnosticsUploadResult.InvalidResponse
            val rejected = validated.rejected
            failure = DiagnosticsUploadResult.StorageFailure(DiagnosticsStorageStage.WRITE_QUARANTINE)
            for (reject in rejected) {
                recoveryRequired = true
                if (!quarantine.record(UUID.fromString(reject.eventId), reject.code)) return failure
                recoveryRequired = false
            }
            val acknowledged = (validated.acceptedEventIds + validated.duplicateEventIds).mapTo(linkedSetOf()) { UUID.fromString(it) }
            failure = DiagnosticsUploadResult.StorageFailure(DiagnosticsStorageStage.ACKNOWLEDGE)
            if (acknowledged.isNotEmpty() && !outbox.ack(Collections.unmodifiableSet(acknowledged))) return failure
            return DiagnosticsUploadResult.Uploaded(events.size, acknowledged.size, rejected.size, events.size - acknowledged.size - rejected.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            return failure
        }
    }
}
