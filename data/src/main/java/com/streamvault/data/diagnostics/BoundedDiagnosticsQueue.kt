package com.MegaStream.data.diagnostics

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.DiagnosticSanitizer
import java.util.Collections
import java.util.UUID

internal interface DiagnosticsQueueStore {
    fun load(): List<DiagnosticEvent>
    fun save(events: List<DiagnosticEvent>): Boolean
}

/** One monitor serializes read/prune/write transactions, including persistence. */
internal class BoundedDiagnosticsQueue(
    private val store: DiagnosticsQueueStore,
    private val limits: DiagnosticsOutboxLimits,
    private val clock: () -> Long,
) {
    private val sanitizer = DiagnosticSanitizer()
    private var events: List<DiagnosticEvent> = emptyList()
    private var ready = false

    init {
        val restored = store.load()
        val valid = restored.mapNotNull { original ->
            val normalized = sanitizer.sanitize(original)
            normalized.takeIf { sanitizer.isValidIdentity(it) && it == original }
        }.distinctBy { it.id }
        events = bounded(live(valid, now()))
        // Rewrite on startup so expired/corrupt records cannot remain as readable queue data.
        ready = store.save(events)
    }

    @Synchronized
    fun append(event: DiagnosticEvent): Boolean {
        if (!sanitizer.isValidIdentity(event) || !ensureReady()) return false
        val currentTime = now()
        if (!commit(live(events, currentTime))) return false
        val normalized = sanitizer.sanitize(event)
        if (!isLive(normalized, currentTime)) return false
        if (events.any { it.id == normalized.id }) return true
        if (DiagnosticEventCodec.encode(normalized).size > DiagnosticsOutboxLimits.MAX_PAYLOAD_BYTES) return false
        val candidate = bounded(events + normalized)
        if (candidate.none { it.id == normalized.id }) return false
        return commit(candidate)
    }

    @Synchronized
    fun batch(maxEvents: Int): List<DiagnosticEvent> {
        require(maxEvents in 1..DiagnosticsOutboxLimits.MAX_BATCH_EVENTS)
        if (!ensureReady()) return emptyList()
        val retained = live(events, now())
        if (!commit(retained)) return emptyList()
        return Collections.unmodifiableList(ArrayList(retained.take(maxEvents)))
    }

    @Synchronized
    fun ack(ids: Set<UUID>): Boolean = ensureReady() && commit(live(events, now()).filterNot { it.id in ids })

    @Synchronized
    fun clear(): Boolean {
        // Always persist reset, including after an unreadable/corrupt initial file.
        if (!store.save(emptyList())) return false
        events = emptyList()
        ready = true
        return true
    }

    private fun ensureReady(): Boolean {
        if (!ready) ready = store.save(live(events, now()).also { events = it })
        return ready
    }

    private fun commit(candidate: List<DiagnosticEvent>): Boolean {
        if (candidate == events) return true
        if (!store.save(candidate)) return false
        events = candidate
        return true
    }

    private fun live(candidates: List<DiagnosticEvent>, currentTime: Long): List<DiagnosticEvent> =
        candidates.filter { isLive(it, currentTime) }

    private fun isLive(event: DiagnosticEvent, currentTime: Long): Boolean =
        event.timestampMillis in (currentTime - limits.retentionMillis).coerceAtLeast(0)..currentTime

    private fun now(): Long = clock().coerceIn(0L, DiagnosticSanitizer.MAX_TIMESTAMP_MILLIS)

    private fun bounded(candidates: List<DiagnosticEvent>): List<DiagnosticEvent> {
        val retained = candidates.toMutableList()
        var bytes = serializedSize(retained)
        while (retained.size > limits.maxEvents || bytes > limits.maxBytes) {
            val index = retained.indices.minByOrNull { priority(retained[it]) } ?: break
            bytes -= recordSize(retained.removeAt(index))
        }
        return retained.toList()
    }

    private fun serializedSize(candidates: List<DiagnosticEvent>): Long =
        DiagnosticsOutboxLimits.FILE_HEADER_BYTES.toLong() + candidates.sumOf { recordSize(it) }

    private fun recordSize(event: DiagnosticEvent): Long =
        DiagnosticsOutboxLimits.RECORD_FRAMING_BYTES + DiagnosticEventCodec.encode(event).size.toLong()

    private fun priority(event: DiagnosticEvent): Int = when (event) {
        is DiagnosticEvent.PlaybackSample -> 0
        is DiagnosticEvent.Crash, is DiagnosticEvent.Anr -> 2
        else -> 1
    }
}
