package com.MegaStream.app.playback.gate

import com.MegaStream.domain.licensing.LicenseAccessState
import com.MegaStream.domain.model.StreamInfo
import com.MegaStream.player.PlayerEngine
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatedPlayerEngineTest {
    private val stream = StreamInfo("https://example.invalid/live")
    private val denied = PlaybackGateVerdict.Blocked(LicenseAccessState.EXPIRED, false)

    @Test fun deniedPrepareNeverReachesMediaEngine() = runTest {
        val media = MediaBoundary(); val gate = Gate(denied)
        val engine = GatedPlayerEngine(media.engine, gate, backgroundScope)
        engine.prepare(stream)
        assertFalse("prepare" in media.calls)
        assertEquals(denied, gate.blocked.value)
    }

    @Test fun deniedPlayNeverReachesMediaEngine() = runTest {
        val media = MediaBoundary(); val gate = Gate(denied)
        GatedPlayerEngine(media.engine, gate, backgroundScope).play()
        assertFalse("play" in media.calls)
        assertTrue("stop" in media.calls)
    }

    @Test fun staleAllowedObservationCannotBypassFreshCheck() = runTest {
        val media = MediaBoundary(); val gate = Gate()
        val engine = GatedPlayerEngine(media.engine, gate, backgroundScope)
        gate.fresh = denied
        engine.prepare(stream)
        assertFalse("prepare" in media.calls)
        assertEquals(denied, gate.blocked.value)
    }

    @Test fun allowedPrepareAndPlayEachRecheck() = runTest {
        val media = MediaBoundary(); val gate = Gate()
        val engine = GatedPlayerEngine(media.engine, gate, backgroundScope)
        engine.prepare(stream); engine.play()
        assertEquals(listOf("prepare", "play"), media.calls)
        assertEquals(2, gate.checks)
    }

    @Test fun blockedTransitionStopsActiveMediaAndCapture() = runTest {
        val media = MediaBoundary(); val gate = Gate()
        val engine = GatedPlayerEngine(media.engine, gate, backgroundScope)
        engine.prepare(stream)
        gate.decision.value = denied; runCurrent()
        assertEquals(listOf("prepare", "stop", "resetForReuse"), media.calls)
        assertEquals(denied, gate.blocked.value)
    }

    @Test fun unusedEngineDoesNotRaiseBlockedUi() = runTest {
        val media = MediaBoundary(); val gate = Gate(denied)
        GatedPlayerEngine(media.engine, gate, backgroundScope)
        runCurrent()
        assertTrue(media.calls.isEmpty())
        assertNull(gate.blocked.value)
    }

    @Test fun recoveryNeverAutomaticallyResumes() = runTest {
        val media = MediaBoundary(); val gate = Gate()
        val engine = GatedPlayerEngine(media.engine, gate, backgroundScope)
        engine.play(); gate.decision.value = denied; runCurrent()
        val stoppedCalls = media.calls.toList()
        gate.decision.value = PlaybackGateVerdict.Allowed; runCurrent()
        assertEquals(stoppedCalls, media.calls)
        engine.prepare(stream)
        assertEquals("prepare", media.calls.last())
    }

    @Test fun repeatedRetryCannotBypassDenial() = runTest {
        val media = MediaBoundary(); val gate = Gate(denied)
        val engine = GatedPlayerEngine(media.engine, gate, backgroundScope)
        repeat(3) { engine.prepare(stream); engine.play() }
        assertEquals(6, gate.checks)
        assertTrue(media.calls.all { it == "stop" })
    }

    @Test fun releaseCancelsOwnObservationAndPreventsFurtherPlayback() = runTest {
        val media = MediaBoundary(); val gate = Gate()
        val engine = GatedPlayerEngine(media.engine, gate, backgroundScope)
        engine.prepare(stream); engine.release()
        gate.decision.value = denied; runCurrent(); engine.play()
        assertEquals(listOf("prepare", "release"), media.calls)
    }

    @Test fun reusableResetKeepsAuthorizationGuard() = runTest {
        val media = MediaBoundary(); val gate = Gate()
        val engine = GatedPlayerEngine(media.engine, gate, backgroundScope)
        engine.prepare(stream); engine.resetForReuse()
        gate.fresh = denied; engine.play()
        assertEquals(listOf("prepare", "resetForReuse", "stop"), media.calls)
    }

    @Test fun delegatedStateRetainsOriginalIdentity() = runTest {
        val media = MediaBoundary()
        val engine = GatedPlayerEngine(media.engine, Gate(), backgroundScope)
        assertSame(media.playing, engine.isPlaying)
        engine.pause(); engine.setVolume(0.5f)
        assertEquals(listOf("pause", "setVolume"), media.calls)
    }

    @Test fun blockedAlternateRestartPathsNeverReachMediaEngine() = runTest {
        val media = MediaBoundary(); val gate = Gate(denied)
        val engine = GatedPlayerEngine(media.engine, gate, backgroundScope)
        engine.renewStreamUrl(stream); engine.preload(stream)
        engine.resumeTimeshift(); engine.pauseTimeshift(); engine.stopLiveTimeshift()
        engine.seekToLiveEdge(); engine.seekTo(10); engine.seekForward(10); engine.seekBackward(10)
        assertEquals(9, gate.checks)
        assertTrue(media.calls.all { it == "stop" })
    }

    @Test fun preloadCleanupRemainsAvailableWhenDenied() = runTest {
        val media = MediaBoundary(); val gate = Gate(denied)
        GatedPlayerEngine(media.engine, gate, backgroundScope).preload(null)
        assertEquals(listOf("preload"), media.calls)
        assertEquals(0, gate.checks)
    }

    private class Gate(initial: PlaybackGateVerdict = PlaybackGateVerdict.Allowed) : PlaybackGate {
        override val decision = MutableStateFlow(initial)
        override val blocked = MutableStateFlow<PlaybackGateVerdict.Blocked?>(null)
        var fresh = initial
        var checks = 0
        override fun checkNow(): PlaybackGateVerdict { checks++; return fresh }
        override fun reportBlocked(reason: PlaybackGateVerdict.Blocked) { blocked.value = reason }
        override fun clearBlocked() { blocked.value = null }
    }

    /** Media engine is the external Android/Media3 boundary; no native playback is needed here. */
    private class MediaBoundary {
        val calls = mutableListOf<String>()
        val playing = MutableStateFlow(false)
        val engine = Proxy.newProxyInstance(PlayerEngine::class.java.classLoader, arrayOf(PlayerEngine::class.java)) { _, method, _ ->
            if (method.name == "getIsPlaying" || method.name == "isPlaying") playing
            else { calls += method.name; null }
        } as PlayerEngine
    }
}
