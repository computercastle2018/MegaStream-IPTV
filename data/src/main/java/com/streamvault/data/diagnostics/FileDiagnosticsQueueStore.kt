package com.MegaStream.data.diagnostics

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.DiagnosticSanitizer
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

internal class FileDiagnosticsQueueStore(
    private val file: File,
    private val limits: DiagnosticsOutboxLimits,
) : DiagnosticsQueueStore {
    override fun load(): List<DiagnosticEvent> = try {
        // The startup rewrite removes scratch. Read valid committed data first so a scratch
        // permission/cleanup failure cannot turn a recoverable outage into loss of that data.
        if (!file.exists()) emptyList() else readBounded()
    } catch (_: IOException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun readBounded(): List<DiagnosticEvent> {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val length = channel.size()
            if (length !in DiagnosticsOutboxLimits.FILE_HEADER_BYTES.toLong()..DiagnosticsOutboxLimits.MAX_BYTES) {
                throw IOException("Invalid queue size")
            }
            val buffer = ByteBuffer.allocate(length.toInt())
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw IOException("Truncated queue")
            }
            if (channel.size() != length) throw IOException("Queue changed during read")
            return decode(buffer.array())
        }
    }

    private fun decode(bytes: ByteArray): List<DiagnosticEvent> {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Invalid queue header")
            val count = input.readInt()
            if (count !in 0..DiagnosticsOutboxLimits.MAX_EVENTS) throw IOException("Invalid queue count")
            val decoded = ArrayList<DiagnosticEvent>(count)
            val ids = HashSet<java.util.UUID>()
            repeat(count) {
                val length = input.readInt()
                val expectedChecksum = input.readInt()
                if (length !in 1..DiagnosticsOutboxLimits.MAX_PAYLOAD_BYTES || length > input.available()) {
                    throw IOException("Invalid record length")
                }
                val payload = ByteArray(length)
                input.readFully(payload)
                if (checksum(payload) != expectedChecksum) throw IOException("Invalid record checksum")
                val event = DiagnosticEventCodec.decode(payload)
                if (!DiagnosticSanitizer().isValidIdentity(event) || DiagnosticSanitizer().sanitize(event) != event || !ids.add(event.id)) {
                    throw IOException("Invalid queue event")
                }
                decoded.add(event)
            }
            if (input.available() != 0) throw IOException("Trailing queue bytes")
            return decoded
        }
    }

    override fun save(events: List<DiagnosticEvent>): Boolean {
        var temporary: Path? = null
        return try {
            val target = file.absoluteFile.toPath()
            val directory = target.parent ?: throw IOException("Missing queue directory")
            Files.createDirectories(directory)
            temporary = scratchPath()
            Files.deleteIfExists(temporary)
            Files.createFile(temporary)
            write(temporary, events)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            temporary?.let { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (_: IOException) {
                    // Best effort only: failed cleanup cannot change the committed queue state.
                } catch (_: SecurityException) {
                    // The queue must still report its original persistence result.
                }
            }
        }
    }

    private fun write(path: Path, events: List<DiagnosticEvent>) {
        if (events.size > limits.maxEvents) throw IOException("Queue count exceeded")
        FileOutputStream(path.toFile()).use { fileOutput ->
            val output = DataOutputStream(fileOutput)
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(events.size)
            var bytes = DiagnosticsOutboxLimits.FILE_HEADER_BYTES.toLong()
            for (event in events) {
                if (!DiagnosticSanitizer().isValidIdentity(event) || DiagnosticSanitizer().sanitize(event) != event) {
                    throw IOException("Unsafe queue event")
                }
                val payload = DiagnosticEventCodec.encode(event)
                bytes += DiagnosticsOutboxLimits.RECORD_FRAMING_BYTES + payload.size
                if (payload.size > DiagnosticsOutboxLimits.MAX_PAYLOAD_BYTES || bytes > limits.maxBytes) {
                    throw IOException("Queue size exceeded")
                }
                output.writeInt(payload.size)
                output.writeInt(checksum(payload))
                output.write(payload)
            }
            output.flush()
            fileOutput.fd.sync()
        }
    }

    private fun scratchPath(): Path = file.absoluteFile.toPath().let { it.resolveSibling(it.fileName.toString() + ".pending") }

    private fun checksum(bytes: ByteArray): Int = CRC32().apply { update(bytes) }.value.toInt()

    private companion object {
        const val MAGIC = 0x4d534451
        const val VERSION = 1
    }
}
