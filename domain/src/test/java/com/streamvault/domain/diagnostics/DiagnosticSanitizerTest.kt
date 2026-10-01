package com.MegaStream.domain.diagnostics

import com.MegaStream.domain.diagnostics.DiagnosticEvent.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DiagnosticSanitizerTest {
    private val sanitizer = DiagnosticSanitizer()
    private val id = UUID(1L, 2L)
    private val playbackId = UUID(3L, 4L)
    private val metadata = Metadata(id, UUID(5L, 6L), 12, 42)
    private val memory = MemorySnapshot(10, 20, 30, 40, 50, true)
    private val frame = RestrictedFrame("com.MegaStream.player.Player", "play", 17)

    @Test fun conservativeLabelsNormalizeSpacesAndTruncate() {
        assertEquals("BBC News HD (UK) - 2", sanitizer.sanitizeChannelLabel("  BBC   News HD (UK) - 2  "))
        val longLabel = "News ".repeat(30)
        assertEquals(longLabel.trim().take(120).trimEnd(), sanitizer.sanitizeChannelLabel(longLabel))
        assertNull(sanitizer.sanitizeChannelLabel(null))
        assertNull(sanitizer.sanitizeChannelLabel("   "))
    }

    @Test fun sensitiveEncodedAndDisguisedLabelsAreRemovedInFull() {
        val attacks = listOf(
            "https://example.com/live", "ftp://host/live", "custom://host/live",
            "user:pass@host", "example.com", "127.0.0.1", "Authorization Bearer abc",
            "COOKIE abc", "password abc", "USER name", "api key abc", "to-ken abc",
            "a u t h abc", "pass(word) abc", "session abc", "credential abc",
            "News%20HD", "%2574oken", "pass%77ord", "ｔｏｋｅｎ",
            "News&#32;HD", "News\\u0020HD", "News+HD", "YWJjZGVmZ2hpamts",
            "abc123ABC456def789", "0123456789abcdef", "1234567890",
            "dG9rZW4", "a2V5", "VVJM", "YTJWNQ", "6b6579", "746f6b656e", "d G 9 r Z W 4",
            "YTpiQGM", "aG9zdC5jb20", "614062",
            "News\nHD", "News\tHD", "News\u0000HD", "News\u007fHD",
            "News\u202eHD", "News\u200bHD", "Ｎｅｗｓ", "Nеws", "News\u00a0HD",
            "News ".repeat(30) + "token secret", "News ".repeat(30) + "%74oken",
            "News ".repeat(30) + "\u200b", "a".repeat(4097), "--- ()",
        )
        attacks.forEach { assertNull("Must reject: $it", sanitizer.sanitizeChannelLabel(it)) }
    }

    @Test fun everyPayloadPreservesAllValidFieldsAndMetadata() {
        events().forEach {
            assertEquals(it, sanitizer.sanitize(it))
            assertEquals(it, sanitizer.sanitize(sanitizer.sanitize(it)))
            assertEquals(id, it.id)
            assertEquals(metadata.appSessionId, it.appSessionId)
            assertEquals(12L, it.sequence)
            assertEquals(42L, it.timestampMillis)
        }
        assertEquals("sleep_timer", PlaybackEndReason.SLEEP_TIMER.wireValue)
        assertFalse(ExitReason.values().any { it.wireValue == "sleep_timer" })
        assertEquals("os_exit_reason", AnrEvidence.OS_EXIT_REASON.wireValue)
    }

    @Test fun metadataAndAllNumericFieldsUseServerBounds() {
        listOf(false, true).forEach { high ->
            val n = if (high) Long.MAX_VALUE else Long.MIN_VALUE
            val i = if (high) Int.MAX_VALUE else Int.MIN_VALUE
            val expected = if (high) Long.MAX_VALUE else 0L
            val m = metadata.copy(sequence = n, timestampMillis = n)
            val rawMemory = MemorySnapshot(n, n, n, n, n, high)
            val boundedMemory = MemorySnapshot(
                if (high) 1L shl 40 else 0, if (high) 1L shl 40 else 0,
                if (high) 1L shl 40 else 0, if (high) 1L shl 40 else 0,
                if (high) 1L shl 40 else 0, high,
            )
            val sample = sanitizer.sanitize(sample().copy(metadata = m, width = i, height = i,
                droppedFrames = n, rebufferCount = n, bufferedMs = n, ttffMs = n, memory = rawMemory)) as PlaybackSample
            assertEquals(if (high) Int.MAX_VALUE else 0, sample.width)
            assertEquals(sample.width, sample.height)
            assertEquals(expected, sample.droppedFrames)
            assertEquals(expected, sample.rebufferCount)
            assertEquals(expected, sample.bufferedMs)
            assertEquals(expected, sample.ttffMs)
            assertEquals(expected, sample.sequence)
            assertEquals(if (high) 253402300799999L else 0L, sample.timestampMillis)
            assertEquals(metadata.id, sample.id)
            assertEquals(metadata.appSessionId, sample.appSessionId)
            assertEquals(boundedMemory, sample.memory)
            val problem = sanitizer.sanitize(problem().copy(retryAttempt = n, httpStatus = i, memory = rawMemory)) as PlaybackProblem
            assertEquals(expected, problem.retryAttempt)
            assertNull(problem.httpStatus)
            assertEquals(boundedMemory, problem.memory)
            val pressure = sanitizer.sanitize(MemoryPressure(m, rawMemory, TrimLevel.LOW_MEMORY)) as MemoryPressure
            assertEquals(boundedMemory, pressure.memory)
            assertEquals(expected, (sanitizer.sanitize(PlaybackEnded(m, playbackId, PlaybackEndReason.SLEEP_TIMER, n)) as PlaybackEnded).durationMs)
            assertEquals(expected, (sanitizer.sanitize(Anr(m, AnrEvidence.OS_EXIT_REASON, n, listOf(frame.copy(line = i)))) as Anr).durationMs)
            assertEquals(if (high) Int.MAX_VALUE else null, (sanitizer.sanitize(Crash(m, "java.lang.RuntimeException", listOf(frame.copy(line = i)))) as Crash).frames.single().line)
            assertEquals(if (high) Int.MAX_VALUE.toLong() else 1L, (sanitizer.sanitize(AppStarted(m, n, "1.2.3")) as AppStarted).appVersionCode)
        }
        val pressure = sanitizer.sanitize(MemoryPressure(metadata, memory.copy(javaUsedBytes = 999), TrimLevel.COMPLETE)) as MemoryPressure
        assertEquals(20L, pressure.memory.javaUsedBytes)
        assertEquals(0L, (sanitizer.sanitize(AppStarted(metadata.copy(sequence = 0), 1, "1"))).sequence)
        listOf(100, 599).forEach { assertEquals(it, (sanitizer.sanitize(problem().copy(httpStatus = it)) as PlaybackProblem).httpStatus) }
        assertEquals(null, (sanitizer.sanitize(Anr(metadata, AnrEvidence.WATCHDOG_SUSPECTED, null, emptyList())) as Anr).durationMs)
    }

    @Test fun versionsAndRestrictedIdentifiersFailClosed() {
        listOf("1.2-token", "1.2-a2V5", "1.2+YTpiQGM", "https://host", "1.2 auth", "1.2.3\n", "１.２", "a2V5", "1".repeat(33)).forEach {
            assertEquals("unknown", (sanitizer.sanitize(AppStarted(metadata, 1, it)) as AppStarted).appVersionName)
        }
        listOf("1", "1.2", "1.2.3", "1.2.3.4", "2.1.6-beta", "2.1.6-rc1", "2.1.6+build").forEach {
            assertEquals(it, (sanitizer.sanitize(AppStarted(metadata, 1, it)) as AppStarted).appVersionName)
        }
        val unsafe = listOf("evil.RuntimeException", "java.lang.Token", "java.lang.a2V5", "com.MegaStream.player.YTJWNQ",
            "java.lang.746f6b656e", "java.lang.Exception: secret", "java.lang." + "E".repeat(151))
        unsafe.forEach {
            assertEquals("unknown", (sanitizer.sanitize(Crash(metadata, it, listOf(frame.copy(className = it)))) as Crash).exceptionType)
            assertTrue((sanitizer.sanitize(Crash(metadata, it, listOf(frame.copy(className = it)))) as Crash).frames.isEmpty())
        }
        listOf("getToken", "dG9rZW4", "a2V5", "6b6579", "YTJWNQ", "aG9zdC5jb20", "pass_word", "load/url", "method\n", "m".repeat(121)).forEach {
            assertTrue((sanitizer.sanitize(Crash(metadata, "java.lang.RuntimeException", listOf(frame.copy(methodName = it)))) as Crash).frames.isEmpty())
        }
        listOf("java.lang.OutOfMemoryError", "java.lang.SecurityException", "java.io.IOException", "java.lang.Exception", "android.app.Activity", "androidx.media.Player", "kotlin.collections.List",
            "kotlinx.coroutines.Job", "org.videolan.Player", "com.google.android.exoplayer2.Player", "io.github.anilbeesetti.nextlib.Player").forEach {
            val result = sanitizer.sanitize(Crash(metadata, it, listOf(frame.copy(className = it)))) as Crash
            assertEquals("Must preserve safe class $it", listOf(frame.copy(className = it)), result.frames)
            assertEquals(it, result.exceptionType)
        }
    }

    @Test fun framesAreCappedDefensivelyCopiedAndUnmodifiable() {
        val input = MutableList(40) { frame.copy(line = it) }
        val crash = sanitizer.sanitize(Crash(metadata, "java.lang.RuntimeException", input)) as Crash
        val anr = sanitizer.sanitize(Anr(metadata, AnrEvidence.OS_EXIT_REASON, 10, input)) as Anr
        input.clear()
        listOf(crash.frames, anr.frames).forEach {
            assertEquals(32, it.size)
            assertEquals(31, it.last().line)
            try { (it as MutableList<RestrictedFrame>).clear(); fail("Mutable persisted frame list") }
            catch (_: UnsupportedOperationException) { /* expected */ }
        }
        val invalidFirst = List(32) { frame.copy(methodName = "token") } + frame
        assertTrue((sanitizer.sanitize(Crash(metadata, "java.lang.RuntimeException", invalidFirst)) as Crash).frames.isEmpty())
    }

    @Test fun allAllowlistedCodeCategoryPairsAreValidated() {
        val expected = mapOf(
            "network" to "NETWORK_UNAVAILABLE DNS_FAILURE TLS_FAILURE CONNECTION_TIMEOUT READ_TIMEOUT",
            "timeout" to "CONNECTION_TIMEOUT READ_TIMEOUT STALL_DETECTED RETRY_EXHAUSTED BUFFER_UNDERRUN",
            "http" to "HTTP_400 HTTP_401 HTTP_403 HTTP_404 HTTP_408 HTTP_429 HTTP_5XX",
            "decoder" to "DECODER_INIT_FAILED DECODER_QUERY_FAILED DECODER_RUNTIME_ERROR CODEC_UNSUPPORTED",
            "drm" to "DRM_FAILED", "source" to "SOURCE_INVALID SOURCE_IO MANIFEST_PARSE_FAILED BEHIND_LIVE_WINDOW",
            "stall" to "STALL_DETECTED RETRY_EXHAUSTED BUFFER_UNDERRUN", "unknown" to "UNKNOWN",
        )
        ErrorCode.values().forEach { code -> ProblemCategory.values().forEach { category ->
            val event = problem().copy(code = code, category = category)
            val result = sanitizer.sanitize(event) as PlaybackProblem
            if (code.name in expected.getValue(category.wireValue).split(' ')) assertEquals(event, result)
            else { assertEquals(ErrorCode.UNKNOWN, result.code); assertEquals(ProblemCategory.UNKNOWN, result.category) }
        } }
    }

    private fun sample() = PlaybackSample(metadata, playbackId, VideoCodec.HEVC, AudioCodec.EAC3,
        VideoDecoder.HARDWARE, AudioDecoder.FFMPEG, 1920, 1080, 3, 4, 5000, 1200, memory)
    private fun problem() = PlaybackProblem(metadata, playbackId, ProblemCategory.HTTP, ErrorCode.HTTP_429, 429, 2, memory)
    private fun events(): List<DiagnosticEvent> = listOf(
        AppStarted(metadata, 12, "1.2.3"),
        PlaybackStarted(metadata, playbackId, "News HD", SourceType.XTREAM_CODES, StreamType.HLS, PlaybackMode.LIVE),
        sample(), sample().copy(memory = null), problem(), problem().copy(memory = null, httpStatus = null),
        MemoryPressure(metadata, memory, TrimLevel.RUNNING_LOW), Crash(metadata, "java.lang.RuntimeException", listOf(frame)),
        Anr(metadata, AnrEvidence.WATCHDOG_SUSPECTED, 5000, listOf(frame)),
        PlaybackEnded(metadata, playbackId, PlaybackEndReason.SLEEP_TIMER, 100),
        AppEnded(metadata, ExitReason.LOW_MEMORY_KILL, ExitEvidence.RECOVERED_OS),
    )
}
