package com.MegaStream.domain.diagnostics

/** Storage implementations return false for recoverable failures; unexpected exceptions propagate. */
fun interface DiagnosticsEventSink {
    fun append(event: DiagnosticEvent): Boolean
}

/** Caller supplies real session metadata; no identity or timing is fabricated here. */
class DiagnosticsRecorder(private val sink: DiagnosticsEventSink) {
    private val sanitizer = DiagnosticSanitizer()

    /** Rejects nil identities, snapshots mutable payloads, and returns the sink's acceptance result. */
    fun record(event: DiagnosticEvent): Boolean =
        sanitizer.isValidIdentity(event) && sink.append(sanitizer.sanitize(event))
}
