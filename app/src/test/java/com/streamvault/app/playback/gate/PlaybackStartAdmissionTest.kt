package com.MegaStream.app.playback.gate

import com.MegaStream.domain.licensing.LicenseAccessState
import com.MegaStream.domain.manager.RecordingManager
import com.MegaStream.domain.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStartAdmissionTest {
    private val denied = PlaybackGateVerdict.Blocked(LicenseAccessState.EXPIRED, actionable = true)

    @Test fun `fresh denial blocks a start even when observed state remains allowed`() {
        val gate = FakePlaybackGate().apply { currentVerdict = denied }
        var started = false
        val admitted = gate.withPlaybackAdmission { started = true }
        assertNull(admitted)
        assertFalse(started)
        assertEquals(listOf(denied), gate.reports)
        assertEquals(PlaybackGateVerdict.Allowed, gate.decision.value)
    }

    @Test fun `fresh allowance permits a start despite a stale blocked observation`() {
        val gate = FakePlaybackGate(denied).apply { currentVerdict = PlaybackGateVerdict.Allowed }
        val receipt = gate.withPlaybackAdmission { "remote-load-receipt" }
        assertEquals("remote-load-receipt", receipt)
        assertTrue(gate.reports.isEmpty())
    }

    @Test fun `retry rechecks authorization rather than retaining earlier allowance`() {
        val gate = FakePlaybackGate()
        val starts = mutableListOf<String>()
        gate.withPlaybackAdmission { starts += "first" }
        gate.currentVerdict = denied
        gate.withPlaybackAdmission { starts += "retry" }
        assertEquals(listOf("first"), starts)
        assertEquals(2, gate.checks)
    }

    @Test fun `blocked cast cleanup happens before reporting and never loads`() {
        val gate = FakePlaybackGate(denied)
        val effects = mutableListOf<String>()
        gate.withPlaybackAdmission(onBlocked = {
            assertNull(gate.blocked.value)
            effects += "stop"
        }) { effects += "load" }
        assertEquals(listOf("stop"), effects)
        assertEquals(denied, gate.blocked.value)
    }

    @Test fun `cleanup failure still reports typed denial`() {
        val gate = FakePlaybackGate(denied)
        val failure = IllegalStateException("remote stop failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            gate.withPlaybackAdmission(onBlocked = { throw failure }) { fail("Must not start") }
        }
        assertSame(failure, thrown)
        assertEquals(denied, gate.blocked.value)
    }

    @Test fun `protected operation failure propagates rather than becoming false success`() {
        val gate = FakePlaybackGate()
        val failure = IllegalStateException("capture failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            gate.withPlaybackAdmission { throw failure }
        }
        assertSame(failure, thrown)
        assertTrue(gate.reports.isEmpty())
    }

    @Test fun `a new allowed request can start after a prior refusal`() {
        val gate = FakePlaybackGate(denied)
        var starts = 0
        gate.withPlaybackAdmission { starts++ }
        gate.currentVerdict = PlaybackGateVerdict.Allowed
        gate.withPlaybackAdmission { starts++ }
        assertEquals(1, starts)
        assertEquals(2, gate.checks)
    }

    @Test fun `deadline stops active recording without deleting completed or scheduled items`() = runTest {
        val gate = FakePlaybackGate()
        val recordings = FakeAdmissionRecordings(listOf(
            recording("live", RecordingStatus.RECORDING),
            recording("done", RecordingStatus.COMPLETED),
            recording("later", RecordingStatus.SCHEDULED),
        ))
        gate.stopRecordingsWhenBlocked(backgroundScope, recordings)
        runCurrent()
        assertTrue(recordings.stopped.isEmpty())
        gate.decision.value = denied
        runCurrent()
        assertEquals(listOf("live"), recordings.stopped)
        assertEquals(denied, gate.blocked.value)
    }

    @Test fun `recording appearing after denial is stopped too`() = runTest {
        val gate = FakePlaybackGate(denied)
        val recordings = FakeAdmissionRecordings()
        gate.stopRecordingsWhenBlocked(backgroundScope, recordings)
        runCurrent()
        assertTrue(gate.reports.isEmpty())
        recordings.items.value = listOf(recording("late", RecordingStatus.RECORDING))
        runCurrent()
        assertEquals(listOf("late"), recordings.stopped)
        assertEquals(denied, gate.blocked.value)
    }

    @Test fun `cancelled consumer observation does not keep controlling captures`() = runTest {
        val gate = FakePlaybackGate()
        val recordings = FakeAdmissionRecordings(listOf(recording("live", RecordingStatus.RECORDING)))
        val observation = gate.stopRecordingsWhenBlocked(backgroundScope, recordings)
        runCurrent()
        observation.cancel()
        gate.decision.value = denied
        runCurrent()
        assertTrue(recordings.stopped.isEmpty())
        assertTrue(gate.reports.isEmpty())
    }

    private fun recording(id: String, status: RecordingStatus) = RecordingItem(
        id = id, providerId = 1, channelId = 2, channelName = "Channel",
        streamUrl = "https://example.test/live", scheduledStartMs = 1, scheduledEndMs = 100,
        status = status,
    )
}

internal class FakeAdmissionRecordings(initial: List<RecordingItem> = emptyList()) : RecordingManager {
    val items = MutableStateFlow(initial)
    val stopped = mutableListOf<String>()
    val retried = mutableListOf<String>()
    val enabled = mutableListOf<Pair<String, Boolean>>()
    val cancelled = mutableListOf<String>()
    override fun observeRecordingItems() = items
    override fun observeStorageState() = flowOf(RecordingStorageState())
    override suspend fun startManualRecording(request: RecordingRequest): Result<RecordingItem> = error("Unexpected manual capture")
    override suspend fun scheduleRecording(request: RecordingRequest): Result<RecordingItem> = error("Unexpected schedule")
    override suspend fun stopRecording(recordingId: String): Result<Unit> {
        stopped += recordingId
        items.value = items.value.map { if (it.id == recordingId) it.copy(status = RecordingStatus.COMPLETED) else it }
        return Result.Success(Unit)
    }
    override suspend fun cancelRecording(recordingId: String): Result<Unit> {
        cancelled += recordingId
        return Result.Success(Unit)
    }
    override suspend fun deleteRecording(recordingId: String): Result<Unit> = error("Unexpected deletion")
    override suspend fun retryRecording(recordingId: String): Result<Unit> {
        retried += recordingId
        return Result.Success(Unit)
    }
    override suspend fun setScheduleEnabled(recordingId: String, enabled: Boolean): Result<Unit> {
        this.enabled += recordingId to enabled
        return Result.Success(Unit)
    }
}
