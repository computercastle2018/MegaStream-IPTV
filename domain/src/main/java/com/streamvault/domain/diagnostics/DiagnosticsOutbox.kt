package com.MegaStream.domain.diagnostics

import java.util.UUID

/**
 * Local bounded queue, with no upload or transport policy. UUID deduplication covers only the active
 * queue; IDs can be reused after acknowledgement, expiry, eviction or clear. Append true means the
 * normalized event is queued (or its ID was already queued), false means rejected/not persisted.
 */
interface DiagnosticsOutbox : DiagnosticsEventSink {
    /** Immutable insertion-ordered snapshot; maxEvents outside 1..50 throws IllegalArgumentException. */
    fun batch(maxEvents: Int = 50): List<DiagnosticEvent>

    /** Removes only acknowledged IDs. False means persistence failed; retry is safe. */
    fun ack(ids: Set<UUID>): Boolean

    /** Removes all queued events. False means reset was not persisted; retry is safe. */
    fun clear(): Boolean
}
