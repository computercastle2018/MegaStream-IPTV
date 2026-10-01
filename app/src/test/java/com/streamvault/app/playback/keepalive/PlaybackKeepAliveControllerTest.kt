package com.MegaStream.app.playback.keepalive

import com.MegaStream.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackKeepAliveControllerTest {
    private val active = PlaybackKeepAliveInput(true, PlaybackState.READY, false, true, true)

    @Test
    fun `successful start is deduplicated through state changes background and detach`() {
        val requests = RecordingKeepAliveRequests()
        val controller = PlaybackKeepAliveController(requests)
        controller.update(active)
        controller.update(active.copy(playbackState = PlaybackState.BUFFERING))
        controller.update(active.copy(foregroundEligible = false))
        controller.update(active.copy(foregroundEligible = false, screenAttached = false))
        assertEquals(1, requests.starts)
        assertEquals(0, requests.stops)
        assertTrue(controller.serviceRequested)

        controller.update(active.copy(playbackState = PlaybackState.ERROR, isPlaying = true))
        assertEquals(1, requests.stops)
        assertFalse(controller.serviceRequested)
    }

    @Test
    fun `denied start does not retry repeated or active state inputs and cannot carry`() {
        val requests = RecordingKeepAliveRequests(startAccepted = false)
        val controller = PlaybackKeepAliveController(requests)
        controller.update(active)
        repeat(3) { controller.update(active) }
        controller.update(active.copy(playbackState = PlaybackState.BUFFERING, isPlaying = true))
        assertEquals(1, requests.starts)
        assertFalse(controller.serviceRequested)

        controller.update(active.copy(foregroundEligible = false))
        controller.update(active.copy(screenAttached = false, foregroundEligible = false))
        assertEquals(1, requests.starts)
        assertEquals(0, requests.stops)

        requests.startAccepted = true
        controller.update(active)
        assertEquals(2, requests.starts)
        assertTrue(controller.serviceRequested)
    }

    @Test
    fun `terminal stop then background ready cannot restart without foreground eligibility`() {
        val requests = RecordingKeepAliveRequests()
        val controller = PlaybackKeepAliveController(requests)
        controller.update(active)
        controller.update(active.copy(playbackState = PlaybackState.ENDED, isPlaying = true))
        controller.update(active.copy(foregroundEligible = false))
        assertEquals(1, requests.starts)
        assertFalse(controller.serviceRequested)
        controller.update(active)
        assertEquals(2, requests.starts)
    }

    @Test
    fun `close stops once and ignores subsequent engine emissions`() {
        val requests = RecordingKeepAliveRequests()
        val controller = PlaybackKeepAliveController(requests)
        controller.update(active)
        controller.close()
        controller.close()
        controller.update(active)
        assertEquals(1, requests.starts)
        assertEquals(1, requests.stops)
        assertFalse(controller.serviceRequested)
    }

    @Test
    fun `failed stop does not claim that the successful request disappeared`() {
        val requests = RecordingKeepAliveRequests(stopAccepted = false)
        val controller = PlaybackKeepAliveController(requests)
        controller.update(active)
        controller.update(active.copy(released = true))
        assertTrue(controller.serviceRequested)
        requests.stopAccepted = true
        controller.close()
        assertEquals(2, requests.stops)
        assertFalse(controller.serviceRequested)
    }
}

/** Fake OS boundary; the reducer/controller under test remain real. */
internal class RecordingKeepAliveRequests(
    var startAccepted: Boolean = true,
    var stopAccepted: Boolean = true
) : PlaybackKeepAliveRequests {
    var starts = 0
    var stops = 0
    override fun start(): Boolean {
        starts++
        return startAccepted
    }
    override fun stop(): Boolean {
        stops++
        return stopAccepted
    }
}
