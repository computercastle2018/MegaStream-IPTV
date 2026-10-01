package com.MegaStream.domain.diagnostics

import com.MegaStream.domain.diagnostics.DiagnosticEvent.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsRecorderTest {
    private val metadata = Metadata(UUID(1, 2), UUID(3, 4), 17, 42)
    private val playbackId = UUID(5, 6)

    @Test fun recordingPreservesTypedFullPayloadAndExplicitMetadata() {
        val received = mutableListOf<DiagnosticEvent>()
        val recorder = DiagnosticsRecorder(DiagnosticsEventSink { received.add(it) })
        val memory = MemorySnapshot(1, 2, 3, 4, 5, true)
        val events = listOf(
            AppStarted(metadata, 17, "2.1.6-beta"),
            PlaybackStarted(metadata, playbackId, "News HD", SourceType.M3U, StreamType.MPEG_TS, PlaybackMode.CATCH_UP),
            PlaybackSample(metadata, playbackId, VideoCodec.AV1, AudioCodec.OPUS, VideoDecoder.SOFTWARE,
                AudioDecoder.FFMPEG, 1920, 1080, 1L shl 40, 1L shl 41, 9000, 1234, memory),
            PlaybackProblem(metadata, playbackId, ProblemCategory.NETWORK, ErrorCode.READ_TIMEOUT, null, 1L shl 42, memory),
            MemoryPressure(metadata, memory, TrimLevel.RUNNING_CRITICAL),
            Crash(metadata, "java.lang.OutOfMemoryError", listOf(RestrictedFrame("java.lang.Thread", "run", 12))),
            Anr(metadata, AnrEvidence.OS_EXIT_REASON, 5000, emptyList()),
            PlaybackEnded(metadata, playbackId, PlaybackEndReason.SLEEP_TIMER, 123),
            AppEnded(metadata, ExitReason.OOM, ExitEvidence.RECOVERED_OS),
        )
        events.forEach { assertTrue(recorder.record(it)) }
        assertEquals(events, received)
    }

    @Test fun recordingSanitizesBeforeSinkWithoutMutatingOriginal() {
        val received = mutableListOf<DiagnosticEvent>()
        val recorder = DiagnosticsRecorder(DiagnosticsEventSink { received.add(it) })
        val original = PlaybackStarted(metadata.copy(timestampMillis = -1, sequence = -1), playbackId,
            "News token abc", SourceType.LOCAL, StreamType.PROGRESSIVE, PlaybackMode.VOD)
        assertTrue(recorder.record(original))
        assertEquals(listOf(original.copy(metadata = metadata.copy(timestampMillis = 0, sequence = 0), channelName = null)), received)
        assertEquals("News token abc", original.channelName)
    }

    @Test fun nilIdentitiesNeverReachSink() {
        val nil = UUID(0, 0)
        val recorder = DiagnosticsRecorder(DiagnosticsEventSink { fail("Invalid identity reached sink"); true })
        assertFalse(recorder.record(AppStarted(metadata.copy(id = nil), 1, "1")))
        assertFalse(recorder.record(AppStarted(metadata.copy(appSessionId = nil), 1, "1")))
        listOf(
            PlaybackStarted(metadata, nil, null, SourceType.UNKNOWN, StreamType.UNKNOWN, PlaybackMode.LIVE),
            PlaybackSample(metadata, nil, VideoCodec.UNKNOWN, AudioCodec.UNKNOWN, VideoDecoder.UNKNOWN,
                AudioDecoder.UNKNOWN, 0, 0, 0, 0, 0, 0, null),
            PlaybackProblem(metadata, nil, ProblemCategory.UNKNOWN, ErrorCode.UNKNOWN, null, 0, null),
            PlaybackEnded(metadata, nil, PlaybackEndReason.UNKNOWN, 0),
        ).forEach { assertFalse(recorder.record(it)) }
    }

    @Test fun sinkRejectionPropagatesWithoutRetry() {
        var calls = 0
        val recorder = DiagnosticsRecorder(DiagnosticsEventSink { calls++; false })
        assertFalse(recorder.record(Anr(metadata, AnrEvidence.WATCHDOG_SUSPECTED, null, emptyList())))
        assertEquals(1, calls)
    }

    @Test fun unexpectedSinkExceptionPropagates() {
        val failure = IllegalStateException("test")
        val recorder = DiagnosticsRecorder(DiagnosticsEventSink { throw failure })
        try { recorder.record(AppStarted(metadata, 1, "1")); fail("Exception swallowed") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
    }
}
