package com.MegaStream.data.diagnostics

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.DiagnosticsOutbox
import java.io.File
import java.util.UUID

/**
 * Offline queue persisted through synced temporary files and atomic replacement.
 *
 * Callers must supply an app-private directory, never external/shared storage, and dispatch all
 * operations off the UI thread: persistence performs synchronous fsync IO. One instance/process
 * must exclusively own the file and its directory; this is not a cross-process store. The exact
 * sibling <file-name>.pending is reserved scratch, cleaned on startup/reset. Temporary files can
 * temporarily consume one additional bounded snapshot.
 * A corrupt snapshot is discarded in full; startup rewrites the sanitized, bounded live queue.
 * Startup/rewrite failure makes reads empty and writes false until persistence recovers. Failed
 * mutations never accept their candidate; independent expiry pruning can still occur on append.
 * No fallback to non-atomic replacement.
 * The containing filesystem must support atomic rename; directory fsync is not guaranteed.
 */
class FileDiagnosticsOutbox(
    file: File,
    limits: DiagnosticsOutboxLimits = DiagnosticsOutboxLimits(),
    clock: () -> Long = { System.currentTimeMillis() },
) : DiagnosticsOutbox {
    private val queue = BoundedDiagnosticsQueue(FileDiagnosticsQueueStore(file, limits), limits, clock)

    override fun append(event: DiagnosticEvent): Boolean = queue.append(event)
    override fun batch(maxEvents: Int): List<DiagnosticEvent> = queue.batch(maxEvents)
    override fun ack(ids: Set<UUID>): Boolean = queue.ack(ids)
    override fun clear(): Boolean = queue.clear()
}
