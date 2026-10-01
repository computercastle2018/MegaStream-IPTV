package com.MegaStream.app.diagnostics.runtime

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32

/**
 * One app-private file, exclusively owned by one main-process collector. All calls block: use
 * Dispatchers.IO. Never share this file between instances/processes. Corruption fails closed;
 * it is not reset automatically because that could reuse sequence numbers or lose pending ends.
 *
 * File contents are synced before atomic rename, then [syncDirectory] must sync the containing
 * directory. The production factory supplies Android's directory fsync. A failure after rename
 * is indeterminate; load observes the committed state on the next operation. No non-atomic fallback.
 */
class FileSessionRecoveryStore(
    private val file: File,
    private val syncDirectory: (File) -> Unit,
) : SessionRecoveryStore {
    override fun load(): SessionRecoveryState {
        if (!file.exists()) return SessionRecoveryState()
        if (file.length() !in 1..MAX_BYTES.toLong()) corrupt()
        val buffer = ByteArray(MAX_BYTES + 1)
        val count = file.inputStream().use { input ->
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            count
        }
        if (count > MAX_BYTES) corrupt()
        return decode(buffer.copyOf(count))
    }

    override fun save(state: SessionRecoveryState) {
        validateState(state)
        val bytes = encode(state)
        val parent = file.absoluteFile.parentFile ?: throw IOException("Missing private directory")
        if (!parent.isDirectory) throw IOException("Missing private directory")
        val scratch = File(parent, file.name + ".pending")
        FileOutputStream(scratch).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Files.move(scratch.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(parent)
    }

    private fun encode(state: SessionRecoveryState): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeLong(state.lastSequence)
            output.writeBoolean(state.current != null)
            state.current?.let { output.writeMarker(it) }
            output.writeInt(state.pending.size)
            state.pending.forEach {
                output.writeMarker(it.marker)
                output.writeEvent(it.event)
            }
        }
        val bytes = payload.toByteArray()
        val framed = ByteArrayOutputStream()
        DataOutputStream(framed).use {
            it.write(bytes)
            it.writeLong(CRC32().apply { update(bytes) }.value)
        }
        return framed.toByteArray()
    }

    private fun decode(bytes: ByteArray): SessionRecoveryState {
        if (bytes.size < 8) corrupt()
        val payloadSize = bytes.size - 8
        val checksum = DataInputStream(ByteArrayInputStream(bytes, payloadSize, 8)).readLong()
        if (CRC32().apply { update(bytes, 0, payloadSize) }.value != checksum) corrupt()
        try {
            return DataInputStream(ByteArrayInputStream(bytes, 0, payloadSize)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) corrupt()
                val sequence = input.readLong()
                val current = if (input.readBoolean()) input.readMarker() else null
                val count = input.readInt()
                if (count !in 0..SessionRecoveryState.MAX_PENDING) corrupt()
                val pending = List(count) { PendingSessionEnd(input.readMarker(), input.readEvent()) }
                if (input.available() != 0) corrupt()
                SessionRecoveryState(sequence, current, pending).also(::validateState)
            }
        } catch (_: EOFException) {
            corrupt()
        }
    }

    private fun DataOutputStream.writeMarker(marker: SessionMarker) {
        writeUuid(marker.sessionId)
        val observation = marker.observation
        writeInt(observation.pid)
        writeLong(observation.startedAtMillis)
        writeLong(observation.recordedAtMillis)
        writeInt(observation.state.ordinal)
        writeOptionalLong(observation.endedAtMillis)
        writeOptionalLong(marker.recoveryBeforeMillis)
    }

    private fun DataInputStream.readMarker(): SessionMarker {
        val id = readUuid()
        val pid = readInt()
        val started = readLong()
        val recorded = readLong()
        val state = SessionTerminationClassifier.MarkerState.entries.getOrNull(readInt()) ?: corrupt()
        val ended = readOptionalLong()
        val boundary = readOptionalLong()
        return SessionMarker(id, SessionTerminationClassifier.PreviousSessionMarker(pid, started, recorded, state, ended), boundary)
    }

    private fun DataOutputStream.writeEvent(event: DiagnosticEvent.AppEnded) {
        writeUuid(event.id)
        writeUuid(event.appSessionId)
        writeLong(event.sequence)
        writeLong(event.timestampMillis)
        writeInt(event.reason.ordinal)
        writeInt(event.evidence.ordinal)
    }

    private fun DataInputStream.readEvent(): DiagnosticEvent.AppEnded {
        val metadata = DiagnosticEvent.Metadata(readUuid(), readUuid(), readLong(), readLong())
        val reason = DiagnosticEvent.ExitReason.entries.getOrNull(readInt()) ?: corrupt()
        val evidence = DiagnosticEvent.ExitEvidence.entries.getOrNull(readInt()) ?: corrupt()
        return DiagnosticEvent.AppEnded(metadata, reason, evidence)
    }

    private fun DataOutputStream.writeUuid(id: UUID) { writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits) }
    private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong())
    private fun DataOutputStream.writeOptionalLong(timestamp: Long?) {
        writeBoolean(timestamp != null)
        if (timestamp != null) writeLong(timestamp)
    }
    private fun DataInputStream.readOptionalLong(): Long? = if (readBoolean()) readLong() else null

    private companion object {
        const val MAGIC = 0x4d535258
        const val VERSION = 1
        const val MAX_BYTES = 16 * 1024
    }
}
