package com.MegaStream.data.diagnostics

/** Limits include the binary header and per-record length/checksum framing, even in memory. */
data class DiagnosticsOutboxLimits(
    val maxEvents: Int = MAX_EVENTS,
    val maxBytes: Long = MAX_BYTES,
    val retentionMillis: Long = MAX_RETENTION_MILLIS,
) {
    init {
        require(maxEvents in 1..MAX_EVENTS)
        require(maxBytes in FILE_HEADER_BYTES.toLong()..MAX_BYTES)
        require(retentionMillis in 1..MAX_RETENTION_MILLIS)
    }

    companion object {
        const val MAX_EVENTS = 500
        const val MAX_BYTES = 5L * 1024 * 1024
        const val MAX_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val MAX_BATCH_EVENTS = 50
        const val MAX_PAYLOAD_BYTES = 16 * 1024
        internal const val FILE_HEADER_BYTES = 12
        internal const val RECORD_FRAMING_BYTES = 8
    }
}
