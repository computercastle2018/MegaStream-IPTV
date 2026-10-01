package com.MegaStream.data.diagnostics

import com.MegaStream.domain.diagnostics.DiagnosticEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/** Closed local schema, independent of transport: no reflection or Java object serialization. */
internal object DiagnosticEventCodec {
    fun encode(event: DiagnosticEvent): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(tag(event))
            output.uuid(event.metadata.id)
            output.uuid(event.metadata.appSessionId)
            output.writeLong(event.metadata.sequence)
            output.writeLong(event.metadata.timestampMillis)
            output.payload(event)
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.payload(event: DiagnosticEvent) {
        when (event) {
            is DiagnosticEvent.AppStarted -> {
                writeLong(event.appVersionCode)
                writeUTF(event.appVersionName)
            }
            is DiagnosticEvent.PlaybackStarted -> {
                uuid(event.playbackSessionId)
                writeBoolean(event.channelName != null)
                event.channelName?.let(::writeUTF)
                writeByte(event.sourceType.ordinal)
                writeByte(event.streamType.ordinal)
                writeByte(event.playbackMode.ordinal)
            }
            is DiagnosticEvent.PlaybackSample -> {
                uuid(event.playbackSessionId)
                writeByte(event.videoCodec.ordinal)
                writeByte(event.audioCodec.ordinal)
                writeByte(event.videoDecoder.ordinal)
                writeByte(event.audioDecoder.ordinal)
                writeInt(event.width)
                writeInt(event.height)
                writeLong(event.droppedFrames)
                writeLong(event.rebufferCount)
                writeLong(event.bufferedMs)
                writeLong(event.ttffMs)
                optionalMemory(event.memory)
            }
            is DiagnosticEvent.PlaybackProblem -> {
                uuid(event.playbackSessionId)
                writeByte(event.category.ordinal)
                writeByte(event.code.ordinal)
                writeBoolean(event.httpStatus != null)
                event.httpStatus?.let(::writeInt)
                writeLong(event.retryAttempt)
                optionalMemory(event.memory)
            }
            is DiagnosticEvent.MemoryPressure -> {
                memory(event.memory)
                writeByte(event.trimLevel.ordinal)
            }
            is DiagnosticEvent.Crash -> {
                writeUTF(event.exceptionType)
                frames(event.frames)
            }
            is DiagnosticEvent.Anr -> {
                writeByte(event.evidence.ordinal)
                writeBoolean(event.durationMs != null)
                event.durationMs?.let(::writeLong)
                frames(event.frames)
            }
            is DiagnosticEvent.PlaybackEnded -> {
                uuid(event.playbackSessionId)
                writeByte(event.reason.ordinal)
                writeLong(event.durationMs)
            }
            is DiagnosticEvent.AppEnded -> {
                writeByte(event.reason.ordinal)
                writeByte(event.evidence.ordinal)
            }
        }
    }

    fun decode(payload: ByteArray): DiagnosticEvent {
        if (payload.size !in 1..DiagnosticsOutboxLimits.MAX_PAYLOAD_BYTES) throw IOException("Invalid event size")
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val tag = input.readUnsignedByte()
            val metadata = DiagnosticEvent.Metadata(input.uuid(), input.uuid(), input.readLong(), input.readLong())
            val event = input.payload(tag, metadata)
            if (input.available() != 0) throw IOException("Trailing event bytes")
            return event
        }
    }

    private fun DataInputStream.payload(tag: Int, metadata: DiagnosticEvent.Metadata): DiagnosticEvent = when (tag) {
        1 -> DiagnosticEvent.AppStarted(metadata, readLong(), ascii(32))
        2 -> DiagnosticEvent.PlaybackStarted(
            metadata, uuid(), if (boolean()) ascii(120) else null,
            enumeration(DiagnosticEvent.SourceType.values()), enumeration(DiagnosticEvent.StreamType.values()),
            enumeration(DiagnosticEvent.PlaybackMode.values()),
        )
        3 -> DiagnosticEvent.PlaybackSample(
            metadata, uuid(), enumeration(DiagnosticEvent.VideoCodec.values()),
            enumeration(DiagnosticEvent.AudioCodec.values()), enumeration(DiagnosticEvent.VideoDecoder.values()),
            enumeration(DiagnosticEvent.AudioDecoder.values()), readInt(), readInt(), readLong(), readLong(),
            readLong(), readLong(), optionalMemory(),
        )
        4 -> DiagnosticEvent.PlaybackProblem(
            metadata, uuid(), enumeration(DiagnosticEvent.ProblemCategory.values()),
            enumeration(DiagnosticEvent.ErrorCode.values()), if (boolean()) readInt() else null,
            readLong(), optionalMemory(),
        )
        5 -> DiagnosticEvent.PlaybackEnded(
            metadata, uuid(), enumeration(DiagnosticEvent.PlaybackEndReason.values()), readLong(),
        )
        6 -> DiagnosticEvent.MemoryPressure(metadata, memory(), enumeration(DiagnosticEvent.TrimLevel.values()))
        7 -> DiagnosticEvent.Crash(metadata, ascii(120), frames())
        8 -> DiagnosticEvent.Anr(
            metadata, enumeration(DiagnosticEvent.AnrEvidence.values()), if (boolean()) readLong() else null, frames(),
        )
        9 -> DiagnosticEvent.AppEnded(
            metadata, enumeration(DiagnosticEvent.ExitReason.values()), enumeration(DiagnosticEvent.ExitEvidence.values()),
        )
        else -> throw IOException("Unknown event type")
    }

    private fun DataOutputStream.optionalMemory(snapshot: DiagnosticEvent.MemorySnapshot?) {
        writeBoolean(snapshot != null)
        snapshot?.let { memory(it) }
    }

    private fun DataInputStream.optionalMemory(): DiagnosticEvent.MemorySnapshot? = if (boolean()) memory() else null

    private fun DataOutputStream.memory(snapshot: DiagnosticEvent.MemorySnapshot) {
        writeLong(snapshot.javaUsedBytes)
        writeLong(snapshot.javaMaxBytes)
        writeLong(snapshot.nativeHeapBytes)
        writeLong(snapshot.pssBytes)
        writeLong(snapshot.availableSystemBytes)
        writeBoolean(snapshot.lowMemory)
    }

    private fun DataInputStream.memory() = DiagnosticEvent.MemorySnapshot(
        readLong(), readLong(), readLong(), readLong(), readLong(), boolean(),
    )

    private fun DataOutputStream.frames(frames: List<DiagnosticEvent.RestrictedFrame>) {
        writeByte(frames.size)
        frames.forEach { frame ->
            writeUTF(frame.className)
            writeUTF(frame.methodName)
            writeBoolean(frame.line != null)
            frame.line?.let(::writeInt)
        }
    }

    private fun DataInputStream.frames(): List<DiagnosticEvent.RestrictedFrame> {
        val count = readUnsignedByte()
        if (count > 32) throw IOException("Invalid frame count")
        return List(count) {
            DiagnosticEvent.RestrictedFrame(ascii(160), ascii(120), if (boolean()) readInt() else null)
        }
    }

    private fun tag(event: DiagnosticEvent): Int = when (event) {
        is DiagnosticEvent.AppStarted -> 1
        is DiagnosticEvent.PlaybackStarted -> 2
        is DiagnosticEvent.PlaybackSample -> 3
        is DiagnosticEvent.PlaybackProblem -> 4
        is DiagnosticEvent.PlaybackEnded -> 5
        is DiagnosticEvent.MemoryPressure -> 6
        is DiagnosticEvent.Crash -> 7
        is DiagnosticEvent.Anr -> 8
        is DiagnosticEvent.AppEnded -> 9
    }

    private fun DataOutputStream.uuid(id: UUID) {
        writeLong(id.mostSignificantBits)
        writeLong(id.leastSignificantBits)
    }

    private fun DataInputStream.uuid(): UUID = UUID(readLong(), readLong())

    private fun DataInputStream.ascii(maxLength: Int): String {
        val length = readUnsignedShort()
        if (length > maxLength || length > available()) throw IOException("Invalid string size")
        val bytes = ByteArray(length)
        readFully(bytes)
        if (bytes.any { it < 0 }) throw IOException("Non-ASCII string")
        return String(bytes, Charsets.US_ASCII)
    }

    private fun DataInputStream.boolean(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IOException("Invalid boolean")
    }

    private fun <T> DataInputStream.enumeration(options: Array<T>): T =
        options.getOrNull(readUnsignedByte()) ?: throw IOException("Invalid enum value")
}
