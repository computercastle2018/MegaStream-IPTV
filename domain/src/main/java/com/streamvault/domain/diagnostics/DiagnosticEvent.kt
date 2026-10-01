package com.MegaStream.domain.diagnostics

import java.util.Locale
import java.util.UUID

/** Frozen control-plane v1 payloads; recording normalizes untrusted scalar values. */
sealed class DiagnosticEvent {
    abstract val metadata: Metadata
    val id: UUID get() = metadata.id
    val appSessionId: UUID get() = metadata.appSessionId
    val sequence: Long get() = metadata.sequence
    val timestampMillis: Long get() = metadata.timestampMillis

    data class Metadata(val id: UUID, val appSessionId: UUID, val sequence: Long, val timestampMillis: Long)
    data class MemorySnapshot(
        val javaUsedBytes: Long,
        val javaMaxBytes: Long,
        val nativeHeapBytes: Long,
        val pssBytes: Long,
        val availableSystemBytes: Long,
        val lowMemory: Boolean,
    )
    data class RestrictedFrame(val className: String, val methodName: String, val line: Int? = null)

    enum class SourceType { XTREAM_CODES, M3U, STALKER_PORTAL, LOCAL, UNKNOWN;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class StreamType { HLS, DASH, MPEG_TS, PROGRESSIVE, RTSP, SMOOTH_STREAMING, UNKNOWN;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class PlaybackMode { LIVE, VOD, CATCH_UP, PREVIEW, MULTIVIEW, TV_INPUT, RECORDING;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class VideoCodec { UNKNOWN, H264, HEVC, AV1, VP9, MPEG2, OTHER;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class AudioCodec { UNKNOWN, AAC, AC3, EAC3, DTS, MP3, OPUS, OTHER;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class VideoDecoder { HARDWARE, SOFTWARE, UNKNOWN;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class AudioDecoder { PLATFORM, FFMPEG, UNKNOWN;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class ProblemCategory { NETWORK, HTTP, DECODER, DRM, SOURCE, TIMEOUT, STALL, UNKNOWN;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class ErrorCode { NETWORK_UNAVAILABLE, DNS_FAILURE, TLS_FAILURE, CONNECTION_TIMEOUT, READ_TIMEOUT, HTTP_400, HTTP_401, HTTP_403, HTTP_404, HTTP_408, HTTP_429, HTTP_5XX, DECODER_INIT_FAILED, DECODER_QUERY_FAILED, DECODER_RUNTIME_ERROR, CODEC_UNSUPPORTED, DRM_FAILED, SOURCE_INVALID, SOURCE_IO, MANIFEST_PARSE_FAILED, BEHIND_LIVE_WINDOW, STALL_DETECTED, RETRY_EXHAUSTED, BUFFER_UNDERRUN, UNKNOWN;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class TrimLevel { RUNNING_MODERATE, RUNNING_LOW, RUNNING_CRITICAL, BACKGROUND, MODERATE, COMPLETE, LOW_MEMORY, UNKNOWN;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class PlaybackEndReason { USER_STOP, CHANNEL_CHANGED, COMPLETED, ERROR, LICENSE_BLOCKED, BACKGROUND, SLEEP_TIMER, UNKNOWN;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class AnrEvidence { OS_EXIT_REASON, WATCHDOG_SUSPECTED;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class ExitReason { CLEAN_EXIT, OS_EXIT, JAVA_CRASH, ANR, OOM, LOW_MEMORY_KILL, NATIVE_CRASH, SIGNAL, USER_REQUESTED, SYSTEM_KILL, UNKNOWN;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }
    enum class ExitEvidence { REPORTED, RECOVERED_OS, RECOVERED_MARKER, INFERRED, WATCHDOG_SUSPECTED;
        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }

    data class AppStarted(
        override val metadata: Metadata,
        val appVersionCode: Long,
        val appVersionName: String,
    ) : DiagnosticEvent()

    data class PlaybackStarted(
        override val metadata: Metadata,
        val playbackSessionId: UUID,
        val channelName: String?,
        val sourceType: SourceType,
        val streamType: StreamType,
        val playbackMode: PlaybackMode,
    ) : DiagnosticEvent()

    data class PlaybackSample(
        override val metadata: Metadata,
        val playbackSessionId: UUID,
        val videoCodec: VideoCodec,
        val audioCodec: AudioCodec,
        val videoDecoder: VideoDecoder,
        val audioDecoder: AudioDecoder,
        val width: Int,
        val height: Int,
        val droppedFrames: Long,
        val rebufferCount: Long,
        val bufferedMs: Long,
        val ttffMs: Long,
        val memory: MemorySnapshot?,
    ) : DiagnosticEvent()

    data class PlaybackProblem(
        override val metadata: Metadata,
        val playbackSessionId: UUID,
        val category: ProblemCategory,
        val code: ErrorCode,
        val httpStatus: Int?,
        val retryAttempt: Long,
        val memory: MemorySnapshot?,
    ) : DiagnosticEvent()

    data class MemoryPressure(
        override val metadata: Metadata,
        val memory: MemorySnapshot,
        val trimLevel: TrimLevel,
    ) : DiagnosticEvent()

    data class Crash(
        override val metadata: Metadata,
        val exceptionType: String,
        val frames: List<RestrictedFrame>,
    ) : DiagnosticEvent()

    data class Anr(
        override val metadata: Metadata,
        val evidence: AnrEvidence,
        val durationMs: Long?,
        val frames: List<RestrictedFrame>,
    ) : DiagnosticEvent()

    data class PlaybackEnded(
        override val metadata: Metadata,
        val playbackSessionId: UUID,
        val reason: PlaybackEndReason,
        val durationMs: Long,
    ) : DiagnosticEvent()

    data class AppEnded(
        override val metadata: Metadata,
        val reason: ExitReason,
        val evidence: ExitEvidence,
    ) : DiagnosticEvent()
}
