package com.MegaStream.data.diagnostics

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.DiagnosticsOutbox
import java.util.UUID

/** Thread-safe volatile queue using exactly the persistent format's byte accounting. */
class InMemoryDiagnosticsOutbox(
    limits: DiagnosticsOutboxLimits = DiagnosticsOutboxLimits(),
    clock: () -> Long = { System.currentTimeMillis() },
) : DiagnosticsOutbox {
    private val queue = BoundedDiagnosticsQueue(
        object : DiagnosticsQueueStore {
            override fun load(): List<DiagnosticEvent> = emptyList()
            override fun save(events: List<DiagnosticEvent>): Boolean = true
        },
        limits,
        clock,
    )

    override fun append(event: DiagnosticEvent): Boolean = queue.append(event)
    override fun batch(maxEvents: Int): List<DiagnosticEvent> = queue.batch(maxEvents)
    override fun ack(ids: Set<UUID>): Boolean = queue.ack(ids)
    override fun clear(): Boolean = queue.clear()
}
