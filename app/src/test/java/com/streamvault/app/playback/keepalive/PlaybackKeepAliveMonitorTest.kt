package com.MegaStream.app.playback.keepalive

import com.MegaStream.player.PlaybackState
import com.MegaStream.player.PlayerEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackKeepAliveMonitorTest {
    @Test
    fun `disposed screen carries playback but terminal emissions still stop the service`() = runTest {
        val signals = EngineSignals(PlaybackState.READY, true)
        val requests = RecordingKeepAliveRequests()
        PlaybackKeepAliveMonitor(this, MutableStateFlow(signals.engine), { true },
            PlaybackKeepAliveController(requests)).use { monitor ->
            val token = monitor.attach(true, true)
            monitor.detach(token)
            runCurrent()
            assertTrue(monitor.keepPlayingInBackground())
            assertEquals(1, requests.starts)
            assertEquals(0, requests.stops)

            signals.state.value = PlaybackState.ERROR
            runCurrent()
            assertEquals(1, requests.stops)
            assertFalse(monitor.keepPlayingInBackground())
            signals.state.value = PlaybackState.READY
            runCurrent()
            assertEquals(1, requests.starts) // Detached collection may stop, never cold-start.
        }
    }

    @Test
    fun `ON_STOP before queued ready emission never creates a background cold start`() = runTest {
        val signals = EngineSignals(PlaybackState.IDLE)
        val requests = RecordingKeepAliveRequests()
        PlaybackKeepAliveMonitor(this, MutableStateFlow(signals.engine), { true },
            PlaybackKeepAliveController(requests)).use { monitor ->
            val token = monitor.attach(true, true)
            signals.state.value = PlaybackState.READY
            monitor.foregroundChanged(token, false)
            assertFalse(monitor.keepPlayingInBackground())
            runCurrent()
            assertEquals(0, requests.starts)

            monitor.foregroundChanged(token, true)
            assertEquals(1, requests.starts)
            assertTrue(monitor.keepPlayingInBackground())
        }
    }

    @Test
    fun `ON_STOP sees synchronous terminal snapshot instead of stale playing flag`() = runTest {
        val signals = EngineSignals(PlaybackState.READY, true)
        val requests = RecordingKeepAliveRequests()
        PlaybackKeepAliveMonitor(this, MutableStateFlow(signals.engine), { true },
            PlaybackKeepAliveController(requests)).use { monitor ->
            val token = monitor.attach(true, true)
            signals.state.value = PlaybackState.ENDED
            monitor.foregroundChanged(token, false)
            assertFalse(monitor.keepPlayingInBackground())
            assertEquals(1, requests.stops)
            runCurrent()
            assertEquals(1, requests.starts)
        }
    }

    @Test
    fun `engine switch observes replacement and ignores old engine changes`() = runTest {
        val first = EngineSignals(PlaybackState.READY)
        val second = EngineSignals(PlaybackState.READY)
        val engines = MutableStateFlow(first.engine)
        val requests = RecordingKeepAliveRequests()
        PlaybackKeepAliveMonitor(this, engines, { true }, PlaybackKeepAliveController(requests)).use { monitor ->
            val token = monitor.attach(true, true)
            runCurrent()
            engines.value = second.engine
            runCurrent()
            monitor.foregroundChanged(token, false)
            first.state.value = PlaybackState.ERROR
            runCurrent()
            assertEquals(0, requests.stops)
            second.state.value = PlaybackState.IDLE
            runCurrent()
            assertEquals(1, requests.stops)
            assertFalse(monitor.keepPlayingInBackground())
        }
    }

    @Test
    fun `blank current stream blocks start and provider is reread on emissions and screen trigger`() = runTest {
        val signals = EngineSignals(PlaybackState.READY)
        val requests = RecordingKeepAliveRequests()
        var stream = "  "
        PlaybackKeepAliveMonitor(this, MutableStateFlow(signals.engine), { stream.isNotBlank() },
            PlaybackKeepAliveController(requests)).use { monitor ->
            val token = monitor.attach(true, false)
            runCurrent()
            assertEquals(0, requests.starts)
            stream = "stream://active"
            monitor.screenStreamChanged(token, true)
            assertEquals(1, requests.starts)

            stream = ""
            signals.playing.value = true
            runCurrent()
            assertEquals(1, requests.stops)
            assertFalse(monitor.keepPlayingInBackground())
        }
    }

    @Test
    fun `reattachment does not duplicate requests and detached tokens lose foreground authority`() = runTest {
        val signals = EngineSignals(PlaybackState.READY)
        val requests = RecordingKeepAliveRequests()
        PlaybackKeepAliveMonitor(this, MutableStateFlow(signals.engine), { true },
            PlaybackKeepAliveController(requests)).use { monitor ->
            val first = monitor.attach(true, true)
            monitor.detach(first)
            val second = monitor.attach(false, true)
            runCurrent()
            assertEquals(1, requests.starts)
            signals.state.value = PlaybackState.IDLE
            runCurrent()
            signals.state.value = PlaybackState.READY
            monitor.foregroundChanged(first, true)
            runCurrent()
            assertEquals(1, requests.starts)
            monitor.foregroundChanged(second, true)
            assertEquals(2, requests.starts)
        }
    }

    @Test
    fun `denied foreground request cannot preserve background playback or retry on emissions`() = runTest {
        val signals = EngineSignals(PlaybackState.READY)
        val requests = RecordingKeepAliveRequests(startAccepted = false)
        PlaybackKeepAliveMonitor(this, MutableStateFlow(signals.engine), { true },
            PlaybackKeepAliveController(requests)).use { monitor ->
            val token = monitor.attach(true, true)
            runCurrent()
            signals.playing.value = true
            runCurrent()
            assertEquals(1, requests.starts)
            monitor.foregroundChanged(token, false)
            assertFalse(monitor.keepPlayingInBackground())
            signals.state.value = PlaybackState.BUFFERING
            runCurrent()
            assertEquals(1, requests.starts)
        }
    }

    @Test
    fun `close stops independently of queued collection and prevents further requests`() = runTest {
        val signals = EngineSignals(PlaybackState.READY)
        val requests = RecordingKeepAliveRequests()
        val monitor = PlaybackKeepAliveMonitor(this, MutableStateFlow(signals.engine), { true },
            PlaybackKeepAliveController(requests))
        monitor.attach(true, true)
        monitor.close()
        monitor.close()
        signals.state.value = PlaybackState.BUFFERING
        monitor.attach(true, true)
        runCurrent()
        assertEquals(1, requests.starts)
        assertEquals(1, requests.stops)
        assertFalse(monitor.keepPlayingInBackground())
    }

    /** Mock only the player boundary; all observed playback state is backed by real flows. */
    private class EngineSignals(initialState: PlaybackState, initialPlaying: Boolean = false) {
        val state = MutableStateFlow(initialState)
        val playing = MutableStateFlow(initialPlaying)
        val engine = mock<PlayerEngine> {
            on { playbackState } doReturn state
            on { isPlaying } doReturn playing
        }
    }
}
