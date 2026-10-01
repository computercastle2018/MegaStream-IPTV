package com.MegaStream.app.kiosk.integration

import com.MegaStream.app.kiosk.KioskControllerEngine
import com.MegaStream.app.kiosk.KioskDenialReason
import com.MegaStream.app.kiosk.KioskFailureReason
import com.MegaStream.app.kiosk.KioskMode
import com.MegaStream.app.kiosk.KioskOperation
import com.MegaStream.app.kiosk.KioskPlatform
import com.MegaStream.app.kiosk.KioskResult
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskSessionCoordinatorTest {
    @Test fun `all modes foreground and playback combinations enforce only eligible locks`() {
        for (mode in KioskMode.values()) for (foreground in listOf(false, true)) {
            for (playing in listOf(false, true)) for (present in listOf(false, true)) {
                val f = Fixture(KioskPolicy(mode, true))
                f.session.setPlayerPresent(present)
                f.session.setPlaybackActive(playing)
                if (foreground) f.session.start() else f.session.stop()
                val expected = foreground && (mode == KioskMode.ALWAYS ||
                    (mode == KioskMode.PLAYBACK && playing && present))
                assertEquals("$mode foreground=$foreground playing=$playing present=$present",
                    expected, f.engine.lockRequested)
                assertEquals(if (expected) KioskStatus.Applied else KioskStatus.Released, f.session.status.value)
                assertEquals(0, f.platform.finishes)
            }
        }
    }

    @Test fun `unknown policy releases existing lock and exposes safe local exit`() {
        val f = ready()
        f.source.policy.value = null
        f.session.refreshPolicy()
        assertFalse(f.engine.lockRequested)
        assertTrue(f.session.uiState.value.allowLocalExit)
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `initial session never acquires until foreground reported`() {
        val f = Fixture()
        assertEquals(KioskStatus.Released, f.session.status.value)
        f.session.setPlayerPresent(true)
        f.session.setPlaybackActive(true)
        assertEquals(0, f.platform.starts)
    }

    @Test fun `pause releases and resume relocks without clearing playback observation`() {
        val f = ready(KioskMode.PLAYBACK)
        f.session.pause()
        assertFalse(f.engine.lockRequested)
        f.session.resume()
        assertTrue(f.engine.lockRequested)
        assertEquals(2, f.platform.starts)
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `stop releases and start relocks without manual exit`() {
        val f = ready()
        f.session.stop()
        assertFalse(f.engine.lockRequested)
        f.session.start()
        assertTrue(f.engine.lockRequested)
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `player disposal releases playback mode without ending task`() {
        val f = ready(KioskMode.PLAYBACK)
        f.session.setPlayerPresent(false)
        assertFalse(f.engine.lockRequested)
        assertFalse(f.session.uiState.value.playerPresent)
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `player disposal retains always lock while foreground`() {
        val f = ready()
        f.session.setPlayerPresent(false)
        assertTrue(f.engine.lockRequested)
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `automatic events never request manual exit at engine boundary`() {
        val engine = FakeEngine()
        val source = Source(KioskPolicy(KioskMode.ALWAYS, true))
        val session = KioskSessionCoordinator(engine, source)
        session.start(); session.resume(); session.setPlayerPresent(true)
        session.setPlaybackActive(true); session.beginManualExit(); session.cancelManualExit()
        session.pause(); session.stop(); session.setPlayerPresent(false)
        source.policy.value = KioskPolicy(KioskMode.OFF, false)
        session.refreshPolicy(); session.confirmManualExit()
        assertEquals(0, engine.exits)
        assertTrue(engine.updates > 0)
    }

    @Test fun `begin only displays confirmation and does not release or finish`() {
        val f = ready()
        f.session.beginManualExit()
        assertTrue(f.session.uiState.value.confirmationPending)
        assertTrue(f.engine.lockRequested)
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `cancel prevents later confirmation from finishing`() {
        val f = ready()
        f.session.beginManualExit(); f.session.cancelManualExit(); f.session.confirmManualExit()
        assertFalse(f.session.uiState.value.confirmationPending)
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `confirmed exit releases before finish and is consumed`() {
        val f = ready()
        f.session.beginManualExit(); f.session.confirmManualExit(); f.session.confirmManualExit()
        assertEquals(listOf("start", "stop", "finish"), f.platform.effects)
        assertFalse(f.session.uiState.value.confirmationPending)
        assertEquals(KioskStatus.Released, f.session.status.value)
    }

    @Test fun `confirmation without begin cannot finish`() {
        val f = ready()
        f.session.confirmManualExit()
        assertEquals(0, f.platform.finishes)
        assertTrue(f.engine.lockRequested)
    }

    @Test fun `disabled exit refuses begin and confirm`() {
        val f = ready()
        f.source.policy.value = KioskPolicy(KioskMode.ALWAYS, false)
        f.session.beginManualExit(); f.session.confirmManualExit()
        assertFalse(f.session.uiState.value.allowLocalExit)
        assertFalse(f.session.uiState.value.confirmationPending)
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `background invalidates pending confirmation even after resume`() {
        val f = ready()
        f.session.beginManualExit(); f.session.pause(); f.session.resume(); f.session.confirmManualExit()
        assertFalse(f.session.uiState.value.confirmationPending)
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `player absence invalidates confirmation even after returning`() {
        val f = ready()
        f.session.beginManualExit(); f.session.setPlayerPresent(false)
        f.session.setPlayerPresent(true); f.session.confirmManualExit()
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `mode-only policy change invalidates confirmation without collected emission`() {
        val f = ready()
        f.session.beginManualExit()
        f.source.policy.value = KioskPolicy(KioskMode.PLAYBACK, true)
        f.session.confirmManualExit()
        assertFalse(f.session.uiState.value.confirmationPending)
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `fresh revoked permission prevents confirmation before collection`() {
        val f = ready()
        f.session.beginManualExit()
        f.source.policy.value = KioskPolicy(KioskMode.ALWAYS, false)
        f.session.confirmManualExit()
        assertFalse(f.session.uiState.value.allowLocalExit)
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `observed changed policy stays invalidated if original restored`() {
        val f = ready()
        f.session.beginManualExit()
        f.source.policy.value = KioskPolicy(KioskMode.OFF, true)
        f.session.refreshPolicy()
        f.source.policy.value = KioskPolicy(KioskMode.ALWAYS, true)
        f.session.confirmManualExit()
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `null and explicit safe snapshot are distinct confirmation policies`() {
        val f = Fixture(null)
        f.session.start(); f.session.setPlayerPresent(true); f.session.beginManualExit()
        f.source.policy.value = KioskPolicy(KioskMode.OFF, true)
        f.session.confirmManualExit()
        assertEquals(0, f.platform.finishes)
    }

    @Test fun `equal policy copy preserves explicit confirmation`() {
        val f = ready()
        f.session.beginManualExit()
        f.source.policy.value = f.source.policy.value!!.copy()
        f.session.confirmManualExit()
        assertEquals(1, f.platform.finishes)
    }

    @Test fun `begin while background or player absent cannot authorize exit`() {
        for (player in listOf(false, true)) {
            val f = Fixture()
            if (!player) f.session.start() else f.session.setPlayerPresent(true)
            f.session.beginManualExit(); f.session.confirmManualExit()
            assertFalse(f.session.uiState.value.confirmationPending)
            assertEquals(0, f.platform.finishes)
        }
    }

    @Test fun `unmanaged device reports unsupported without pinning`() {
        val f = Fixture()
        f.platform.owner = false
        f.session.start()
        assertEquals(KioskStatus.Unsupported, f.session.status.value)
        assertEquals(0, f.platform.starts)
    }

    @Test fun `non allowlisted managed device reports typed denial`() {
        val f = Fixture()
        f.platform.permitted = false
        f.session.start()
        assertEquals(KioskStatus.Denied(KioskDenialReason.NOT_ALLOWLISTED), f.session.status.value)
        assertEquals(0, f.platform.starts)
    }

    @Test fun `platform failure is typed and redacts sensitive exception messages`() {
        val f = Fixture()
        f.platform.failStart = true
        f.session.start()
        assertEquals(KioskStatus.Failed(KioskOperation.START_LOCK_TASK, KioskFailureReason.SECURITY), f.session.status.value)
        assertFalse(f.session.status.value.toString().contains("secret"))
        assertFalse(f.session.uiState.value.toString().contains("secret"))
    }

    @Test fun `finish failure never retries on automatic events and requires new confirmation`() {
        val f = ready()
        f.platform.failFinish = true
        f.session.beginManualExit(); f.session.confirmManualExit()
        assertEquals(KioskStatus.Failed(KioskOperation.FINISH_TASK, KioskFailureReason.ILLEGAL_STATE), f.session.status.value)
        f.platform.failFinish = false
        f.session.pause(); f.session.resume(); f.session.refreshPolicy(); f.session.confirmManualExit()
        assertEquals(1, f.platform.finishes)
        assertFalse(f.engine.lockRequested)
        f.session.beginManualExit(); f.session.confirmManualExit()
        assertEquals(2, f.platform.finishes)
    }

    @Test fun `every automatic action reads latest policy rather than cached policy`() {
        val actions: List<(KioskSessionCoordinator) -> Unit> = listOf(
            { it.start() }, { it.resume() }, { it.pause() }, { it.stop() },
            { it.setPlayerPresent(true) }, { it.setPlaybackActive(true) }, { it.refreshPolicy() },
        )
        for (action in actions) {
            val f = ready()
            f.source.policy.value = KioskPolicy(KioskMode.OFF, false)
            action(f.session)
            assertFalse(f.engine.lockRequested)
            assertFalse(f.session.uiState.value.allowLocalExit)
            assertEquals(0, f.platform.finishes)
        }
    }

    @Test fun `engine denial from explicit exit is surfaced without free text`() {
        val engine = FakeEngine().apply { result = KioskResult.Denied(KioskDenialReason.LOCAL_EXIT_DISABLED) }
        val session = KioskSessionCoordinator(engine, Source(KioskPolicy(KioskMode.OFF, true)))
        session.start(); session.setPlayerPresent(true); session.beginManualExit(); session.confirmManualExit()
        assertEquals(1, engine.exits)
        assertEquals(KioskStatus.Denied(KioskDenialReason.LOCAL_EXIT_DISABLED), session.status.value)
    }

    @Test fun `returning player requires a fresh active observation`() {
        val f = ready(KioskMode.PLAYBACK)
        f.session.setPlayerPresent(false)
        f.session.setPlayerPresent(true)
        assertFalse(f.engine.lockRequested)
        f.session.setPlaybackActive(true)
        assertTrue(f.engine.lockRequested)
    }

    @Test fun `UI foreground follows all lifecycle actions`() {
        val f = Fixture()
        assertFalse(f.session.uiState.value.foreground)
        f.session.start(); assertTrue(f.session.uiState.value.foreground)
        f.session.pause(); assertFalse(f.session.uiState.value.foreground)
        f.session.resume(); assertTrue(f.session.uiState.value.foreground)
        f.session.stop(); assertFalse(f.session.uiState.value.foreground)
    }

    @Test fun `eligible lock attempt publishes requested before final outcome`() {
        val source = Source(KioskPolicy(KioskMode.ALWAYS, true))
        lateinit var session: KioskSessionCoordinator
        val seen = mutableListOf<KioskStatus>()
        val boundary = object : KioskEngine {
            override fun update(policy: KioskPolicy, appForeground: Boolean, playbackActive: Boolean): KioskResult {
                seen += session.status.value
                return if (appForeground) KioskResult.Applied else KioskResult.Released
            }
            override fun requestManualExit(policy: KioskPolicy): KioskResult = KioskResult.Released
        }
        session = KioskSessionCoordinator(boundary, source)
        session.refreshPolicy()
        assertEquals(listOf(KioskStatus.Released), seen)
        session.start()
        assertEquals(listOf(KioskStatus.Released, KioskStatus.Requested), seen)
        assertEquals(KioskStatus.Applied, session.status.value)
    }

    private fun ready(mode: KioskMode = KioskMode.ALWAYS): Fixture = Fixture(KioskPolicy(mode, true)).also {
        it.session.setPlayerPresent(true); it.session.setPlaybackActive(true); it.session.start()
    }

    private class Source(initial: KioskPolicy?) : KioskPolicySource {
        override val policy = MutableStateFlow(initial)
    }

    private class Fixture(policy: KioskPolicy? = KioskPolicy(KioskMode.ALWAYS, true)) {
        val source = Source(policy)
        val platform = Platform()
        val engine = KioskControllerEngine(platform)
        val session = KioskSessionCoordinator(object : KioskEngine {
            override fun update(policy: KioskPolicy, appForeground: Boolean, playbackActive: Boolean) =
                engine.update(policy, appForeground, playbackActive)
            override fun requestManualExit(policy: KioskPolicy) = engine.requestManualExit(policy)
        }, source)
    }

    private class FakeEngine : KioskEngine {
        var result: KioskResult = KioskResult.Released
        var updates = 0
        var exits = 0
        override fun update(policy: KioskPolicy, appForeground: Boolean, playbackActive: Boolean): KioskResult {
            updates++
            return result
        }
        override fun requestManualExit(policy: KioskPolicy): KioskResult { exits++; return result }
    }

    private class Platform : KioskPlatform {
        var owner = true
        var permitted = true
        var failStart = false
        var failFinish = false
        var starts = 0
        var finishes = 0
        val effects = mutableListOf<String>()
        override fun isDeviceOwnerApp() = owner
        override fun isLockTaskPermitted() = permitted
        override fun startLockTask() {
            if (failStart) throw SecurityException("token=secret")
            starts++; effects += "start"
        }
        override fun stopLockTask() { effects += "stop" }
        override fun finishAndRemoveTask() {
            finishes++; effects += "finish"
            if (failFinish) throw IllegalStateException("provider=secret")
        }
    }
}
