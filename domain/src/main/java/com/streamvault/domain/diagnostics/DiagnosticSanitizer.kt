package com.MegaStream.domain.diagnostics

/** Fail-closed normalization boundary; rejected labels are removed, never partially redacted. */
class DiagnosticSanitizer {
    /** Nil identities cannot represent a server session; callers reject rather than fabricate IDs. */
    fun isValidIdentity(event: DiagnosticEvent): Boolean {
        val playbackId = when (event) {
            is DiagnosticEvent.PlaybackStarted -> event.playbackSessionId
            is DiagnosticEvent.PlaybackSample -> event.playbackSessionId
            is DiagnosticEvent.PlaybackProblem -> event.playbackSessionId
            is DiagnosticEvent.PlaybackEnded -> event.playbackSessionId
            else -> null
        }
        return event.id != NIL_UUID && event.appSessionId != NIL_UUID && playbackId != NIL_UUID
    }

    fun sanitize(event: DiagnosticEvent): DiagnosticEvent {
        val metadata = event.metadata.copy(
            sequence = event.sequence.coerceAtLeast(0L),
            timestampMillis = event.timestampMillis.coerceIn(0L, MAX_TIMESTAMP_MILLIS),
        )
        return when (event) {
            is DiagnosticEvent.AppStarted -> event.copy(
                metadata = metadata,
                appVersionCode = event.appVersionCode.coerceIn(1L, Int.MAX_VALUE.toLong()),
                appVersionName = event.appVersionName.takeIf {
                    it.length <= MAX_VERSION_NAME_LENGTH && VERSION_NAME.matches(it) && !hasUnsafeIdentifierContent(it)
                } ?: "unknown",
            )
            is DiagnosticEvent.PlaybackStarted -> event.copy(
                metadata = metadata, channelName = sanitizeChannelLabel(event.channelName),
            )
            is DiagnosticEvent.PlaybackSample -> event.copy(
                metadata = metadata,
                width = event.width.coerceIn(0, MAX_DIMENSION),
                height = event.height.coerceIn(0, MAX_DIMENSION),
                droppedFrames = event.droppedFrames.coerceIn(0, MAX_DROPPED_FRAMES),
                rebufferCount = event.rebufferCount.coerceIn(0, MAX_REBUFFER_COUNT),
                bufferedMs = event.bufferedMs.coerceIn(0L, MAX_BUFFER_MILLIS),
                ttffMs = event.ttffMs.coerceIn(0L, MAX_DURATION_MILLIS),
                memory = event.memory?.let(::sanitizeMemory),
            )
            is DiagnosticEvent.PlaybackProblem -> event.copy(
                metadata = metadata,
                category = if (validProblem(event)) event.category else DiagnosticEvent.ProblemCategory.UNKNOWN,
                code = if (validProblem(event)) event.code else DiagnosticEvent.ErrorCode.UNKNOWN,
                httpStatus = event.httpStatus?.takeIf { it in 100..599 },
                retryAttempt = event.retryAttempt.coerceIn(0, MAX_RETRY_COUNT),
                memory = event.memory?.let(::sanitizeMemory),
            )
            is DiagnosticEvent.PlaybackEnded -> event.copy(
                metadata = metadata, durationMs = event.durationMs.coerceIn(0L, MAX_DURATION_MILLIS),
            )
            is DiagnosticEvent.MemoryPressure -> event.copy(metadata = metadata, memory = sanitizeMemory(event.memory))
            is DiagnosticEvent.Crash -> event.copy(
                metadata = metadata,
                exceptionType = event.exceptionType.takeIf {
                    it.length <= MAX_EXCEPTION_TYPE_LENGTH && safeClassName(it)
                } ?: SAFE_EXCEPTION_TYPE,
                frames = sanitizeFrames(event.frames),
            )
            is DiagnosticEvent.Anr -> event.copy(
                metadata = metadata,
                durationMs = event.durationMs?.coerceIn(0L, MAX_DURATION_MILLIS),
                frames = sanitizeFrames(event.frames),
            )
            is DiagnosticEvent.AppEnded -> event.copy(metadata = metadata)
        }
    }

    private fun sanitizeMemory(memory: DiagnosticEvent.MemorySnapshot): DiagnosticEvent.MemorySnapshot {
        val maximum = memory.javaMaxBytes.coerceIn(0L, MAX_MEMORY_BYTES)
        return memory.copy(
            javaUsedBytes = memory.javaUsedBytes.coerceIn(0L, maximum),
            javaMaxBytes = maximum,
            nativeHeapBytes = memory.nativeHeapBytes.coerceIn(0L, MAX_MEMORY_BYTES),
            pssBytes = memory.pssBytes.coerceIn(0L, MAX_MEMORY_BYTES),
            availableSystemBytes = memory.availableSystemBytes.coerceIn(0L, MAX_MEMORY_BYTES),
        )
    }

    private fun validProblem(event: DiagnosticEvent.PlaybackProblem): Boolean {
        val categories = when (event.code) {
            DiagnosticEvent.ErrorCode.NETWORK_UNAVAILABLE,
            DiagnosticEvent.ErrorCode.DNS_FAILURE,
            DiagnosticEvent.ErrorCode.TLS_FAILURE -> "network"
            DiagnosticEvent.ErrorCode.CONNECTION_TIMEOUT,
            DiagnosticEvent.ErrorCode.READ_TIMEOUT -> "network timeout"
            DiagnosticEvent.ErrorCode.HTTP_400, DiagnosticEvent.ErrorCode.HTTP_401,
            DiagnosticEvent.ErrorCode.HTTP_403, DiagnosticEvent.ErrorCode.HTTP_404,
            DiagnosticEvent.ErrorCode.HTTP_408, DiagnosticEvent.ErrorCode.HTTP_429,
            DiagnosticEvent.ErrorCode.HTTP_5XX -> "http"
            DiagnosticEvent.ErrorCode.DECODER_INIT_FAILED, DiagnosticEvent.ErrorCode.DECODER_QUERY_FAILED,
            DiagnosticEvent.ErrorCode.DECODER_RUNTIME_ERROR, DiagnosticEvent.ErrorCode.CODEC_UNSUPPORTED -> "decoder"
            DiagnosticEvent.ErrorCode.DRM_FAILED -> "drm"
            DiagnosticEvent.ErrorCode.SOURCE_INVALID, DiagnosticEvent.ErrorCode.SOURCE_IO,
            DiagnosticEvent.ErrorCode.MANIFEST_PARSE_FAILED, DiagnosticEvent.ErrorCode.BEHIND_LIVE_WINDOW -> "source"
            DiagnosticEvent.ErrorCode.STALL_DETECTED, DiagnosticEvent.ErrorCode.RETRY_EXHAUSTED,
            DiagnosticEvent.ErrorCode.BUFFER_UNDERRUN -> "stall timeout"
            DiagnosticEvent.ErrorCode.UNKNOWN -> "unknown"
        }
        return event.category.wireValue in categories.split(' ')
    }

    private fun sanitizeFrames(frames: List<DiagnosticEvent.RestrictedFrame>): List<DiagnosticEvent.RestrictedFrame> =
        java.util.Collections.unmodifiableList(frames.asSequence().take(MAX_FRAMES).filter {
            safeClassName(it.className) && safeMethodName(it.methodName)
        }.map {
            // Missing/native line numbers remain absent, never invented.
            it.copy(line = it.line?.takeIf { line -> line >= 0 }?.coerceAtMost(MAX_FRAME_LINE))
        }.toList())

    private fun safeClassName(value: String): Boolean =
        value.length in 1..MAX_CLASS_NAME_LENGTH && CLASS_NAME.matches(value) &&
            ALLOWED_PACKAGE_PREFIXES.any { value.startsWith(it) } && !hasUnsafeIdentifierContent(value)

    private fun safeMethodName(value: String): Boolean =
        value.length in 1..MAX_METHOD_NAME_LENGTH && METHOD_NAME.matches(value) && !hasUnsafeIdentifierContent(value)

    private fun hasUnsafeIdentifierContent(value: String): Boolean {
        val compact = value.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
        return IDENTIFIER_SECRET_FRAGMENTS.any { it in compact } ||
            (value.split('.', '$', '_', '-', '+') + value.filter { it.isLetterOrDigit() }).any { hasEncodedSecret(it, 2) }
    }

    fun sanitizeChannelLabel(label: String?): String? {
        if (label == null || label.length > MAX_INPUT_LABEL_LENGTH || label.isBlank()) return null
        // Encoded punctuation, URLs, Unicode and controls fail closed rather than being repaired.
        if (label.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it !in " -()" }) {
            return null
        }
        val compact = label.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
        if (FORBIDDEN_FRAGMENTS.any { it in compact }) return null
        val words = label.split(' ', '-', '(', ')').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.any { word ->
                word.length > MAX_WORD_LENGTH ||
                    (word.length >= 12 && word.all { it in "0123456789abcdefABCDEF" }) ||
                    (word.length >= 12 && word.any { it.isDigit() } && word.any { it.isLetter() }) ||
                    (word.length >= 12 && word.any { it.isLowerCase() } && word.any { it.isUpperCase() }) ||
                    (word.length > 8 && word.all { it.isDigit() })
            }
        ) return null
        if ((words + label.filter { it.isLetterOrDigit() }).any { hasEncodedSecret(it, 2) }) return null
        // Inspect the whole input above before truncation, including any suffix beyond character 120.
        return label.trim().replace(REPEATED_SPACES, " ").take(MAX_LABEL_LENGTH).trimEnd()
    }

    private fun hasEncodedSecret(candidate: String, remainingDepth: Int): Boolean {
        if (remainingDepth == 0 || candidate.length < 4) return false
        val decoded = listOfNotNull(decodeBase64(candidate), decodeHex(candidate))
        return decoded.any { text ->
            val compact = text.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
            (text.any { it.isLetterOrDigit() } && text.any { it in ":/@.?&=\\%#;" }) ||
                FORBIDDEN_FRAGMENTS.any { it in compact } || hasEncodedSecret(text, remainingDepth - 1)
        }
    }

    private fun decodeBase64(candidate: String): String? = try {
        decodeUtf8(java.util.Base64.getDecoder().decode(candidate))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decodeHex(candidate: String): String? {
        if (candidate.length % 2 != 0 || candidate.any { it.digitToIntOrNull(16) == null }) return null
        val decoded = ByteArray(candidate.length / 2) { index ->
            ((candidate[index * 2].digitToInt(16) shl 4) or candidate[index * 2 + 1].digitToInt(16)).toByte()
        }
        return decodeUtf8(decoded)
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }

    companion object {
        private val IDENTIFIER_SECRET_FRAGMENTS = listOf(
            "auth", "bearer", "cookie", "password", "passwd", "pwd", "user", "token", "key",
            "secret", "credential", "session", "login", "jwt", "base64", "hex", "unicode",
        )
        const val MAX_TIMESTAMP_MILLIS = 253402300799999L
        // Preserve the complete nonnegative server integer range.
        const val MAX_DURATION_MILLIS = Long.MAX_VALUE
        const val MAX_BUFFER_MILLIS = Long.MAX_VALUE
        const val MAX_DROPPED_FRAMES = Long.MAX_VALUE
        const val MAX_REBUFFER_COUNT = Long.MAX_VALUE
        const val MAX_RETRY_COUNT = Long.MAX_VALUE
        const val MAX_DIMENSION = Int.MAX_VALUE
        const val MAX_FRAME_LINE = Int.MAX_VALUE
        const val MAX_FRAMES = 32
        const val MAX_VERSION_NAME_LENGTH = 32
        const val MAX_CLASS_NAME_LENGTH = 160
        const val MAX_METHOD_NAME_LENGTH = 120
        const val MAX_EXCEPTION_TYPE_LENGTH = 120
        const val SAFE_EXCEPTION_TYPE = "unknown"
        private val NIL_UUID = java.util.UUID(0L, 0L)
        private val VERSION_NAME = Regex("[0-9]+(?:\\.[0-9]+){0,3}(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?(?:\\+[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?")
        private val CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)+")
        private val METHOD_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val ALLOWED_PACKAGE_PREFIXES = listOf(
            "java.", "android.", "androidx.", "kotlin.", "kotlinx.", "com.MegaStream.",
            "org.videolan.", "com.google.android.exoplayer2.", "io.github.anilbeesetti.nextlib.",
        )
        /** At most one tebibyte of reported memory. */
        const val MAX_MEMORY_BYTES = 1_099_511_627_776L
        const val MAX_LABEL_LENGTH = 120
        private const val MAX_INPUT_LABEL_LENGTH = 4096
        private const val MAX_WORD_LENGTH = 20
        private val REPEATED_SPACES = Regex(" +")
        private val FORBIDDEN_FRAGMENTS = listOf(
            "http", "www", "ftp", "rtsp", "rtmp", "url", "uri", "auth", "bearer",
            "cookie", "password", "passwd", "pwd", "user", "token", "key", "secret",
            "credential", "session", "login", "jwt", "base64", "hex", "unicode",
        )
    }
}
