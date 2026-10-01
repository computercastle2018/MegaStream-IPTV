package com.MegaStream.app.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskControllerEngineContractTest {
    private val always = DevicePolicySnapshot(KioskMode.ALWAYS, true)
    private val playback = DevicePolicySnapshot(KioskMode.PLAYBACK, true)
    private val off = DevicePolicySnapshot(KioskMode.OFF, true)
    private val checksAndStart = listOf(
        KioskOperation.CHECK_DEVICE_OWNER,
        KioskOperation.CHECK_LOCK_TASK_PERMISSION,
        KioskOperation.START_LOCK_TASK
    )
    private val failureKinds = listOf(KioskFailureReason.SECURITY, KioskFailureReason.ILLEGAL_STATE)

    @Test
    fun `fresh inactive engine remains released without touching OS`() {
        val os = RecordingKioskPlatform()
        val engine = KioskControllerEngine(os)
        assertEquals(KioskResult.Released, engine.lastResult)
        assertFalse(engine.lockRequested)
        assertFalse(engine.manualExitCompleted)
        for (input in inactiveInputs()) {
            assertEquals(KioskResult.Released, engine.update(input.policy, input.foreground, input.playing, input.manual))
            assertEquals(KioskResult.Released, engine.lastResult)
        }
        assertTrue(os.calls.isEmpty())
    }

    @Test
    fun `unmanaged active device is unsupported without permission check or lock effects`() {
        val os = RecordingKioskPlatform().apply { owner = false }
        val engine = KioskControllerEngine(os)
        assertEquals(KioskResult.Unsupported, engine.update(always, true, true))
        assertEquals(KioskResult.Unsupported, engine.lastResult)
        assertFalse(engine.lockRequested)
        assertEquals(listOf(KioskOperation.CHECK_DEVICE_OWNER), os.calls)
    }

    @Test
    fun `managed app not allowlisted is denied without starting`() {
        val os = RecordingKioskPlatform().apply { permitted = false }
        val engine = KioskControllerEngine(os)
        val denied = KioskResult.Denied(KioskDenialReason.NOT_ALLOWLISTED)
        assertEquals(denied, engine.update(always, true, false))
        assertEquals(denied, engine.lastResult)
        assertFalse(engine.lockRequested)
        assertEquals(checksAndStart.take(2), os.calls)
    }

    @Test
    fun `eligible update starts only once across repeated active inputs`() {
        val os = RecordingKioskPlatform()
        val engine = KioskControllerEngine(os)
        assertEquals(KioskResult.Applied, engine.update(always, true, false))
        assertEquals(checksAndStart, os.calls)
        repeat(3) { assertEquals(KioskResult.Applied, engine.update(always, true, true)) }
        assertEquals(KioskResult.Applied, engine.update(playback, true, true))
        assertEquals(listOf(KioskOperation.START_LOCK_TASK), os.effects())
        assertTrue(engine.lockRequested)
        assertEquals(KioskResult.Applied, engine.lastResult)
    }

    @Test
    fun `off background playback ended and manual flag each release owned lock without checks or finish`() {
        for (input in inactiveInputs()) {
            val os = RecordingKioskPlatform()
            val engine = KioskControllerEngine(os)
            engine.update(always, true, true)
            os.calls.clear()
            assertEquals(input.toString(), KioskResult.Released,
                engine.update(input.policy, input.foreground, input.playing, input.manual))
            assertEquals(input.toString(), listOf(KioskOperation.STOP_LOCK_TASK), os.calls)
            assertFalse(engine.lockRequested)
            assertFalse(engine.manualExitCompleted)
            assertEquals(KioskResult.Released, engine.lastResult)
            os.calls.clear()
            engine.update(input.policy, input.foreground, input.playing, input.manual)
            assertTrue(os.calls.isEmpty())
        }
    }

    @Test
    fun `ordinary release permits a later eligible lock request`() {
        val os = RecordingKioskPlatform()
        val engine = KioskControllerEngine(os)
        engine.update(always, true, true)
        engine.update(off, true, true)
        assertEquals(KioskResult.Applied, engine.update(playback, true, true))
        assertTrue(engine.lockRequested)
        assertEquals(listOf(KioskOperation.START_LOCK_TASK, KioskOperation.STOP_LOCK_TASK,
            KioskOperation.START_LOCK_TASK), os.effects())
    }

    @Test
    fun `manual progress flag is temporary and never finishes the task`() {
        val os = RecordingKioskPlatform()
        val engine = KioskControllerEngine(os)
        engine.update(always, true, true)
        assertEquals(KioskResult.Released, engine.update(always, true, true, true))
        assertEquals(KioskResult.Applied, engine.update(always, true, true, false))
        assertFalse(engine.manualExitCompleted)
        assertEquals(listOf(KioskOperation.START_LOCK_TASK, KioskOperation.STOP_LOCK_TASK,
            KioskOperation.START_LOCK_TASK), os.effects())
    }

    @Test
    fun `owner or allowlist revocation releases owned lock before reporting rejection`() {
        for (revokeOwner in listOf(false, true)) {
            val os = RecordingKioskPlatform()
            val engine = KioskControllerEngine(os)
            engine.update(always, true, true)
            os.calls.clear()
            if (revokeOwner) os.owner = false else os.permitted = false
            val expected = if (revokeOwner) KioskResult.Unsupported
                else KioskResult.Denied(KioskDenialReason.NOT_ALLOWLISTED)
            assertEquals(expected, engine.update(always, true, true))
            assertEquals(expected, engine.lastResult)
            assertFalse(engine.lockRequested)
            val checks = if (revokeOwner) checksAndStart.take(1) else checksAndStart.take(2)
            assertEquals(checks + KioskOperation.STOP_LOCK_TASK, os.calls)
        }
    }

    @Test
    fun `failed revocation release keeps ownership and reports failure until retry succeeds`() {
        for (revokeOwner in listOf(false, true)) {
            for (reason in failureKinds) {
                val os = RecordingKioskPlatform()
                val engine = KioskControllerEngine(os)
                engine.update(always, true, true)
                if (revokeOwner) os.owner = false else os.permitted = false
                os.failures[KioskOperation.STOP_LOCK_TASK] = reason
                os.calls.clear()
                val failure = KioskResult.Failed(KioskOperation.STOP_LOCK_TASK, reason)
                assertEquals(failure, engine.update(always, true, true))
                assertEquals(failure, engine.lastResult)
                assertTrue(engine.lockRequested)
                assertEquals(listOf(KioskOperation.STOP_LOCK_TASK), os.effects())
                os.failures.clear()
                val expected = if (revokeOwner) KioskResult.Unsupported
                    else KioskResult.Denied(KioskDenialReason.NOT_ALLOWLISTED)
                assertEquals(expected, engine.update(always, true, true))
                assertFalse(engine.lockRequested)
                assertEquals(listOf(KioskOperation.STOP_LOCK_TASK, KioskOperation.STOP_LOCK_TASK), os.effects())
            }
        }
    }

    @Test
    fun `each acquisition boundary sanitizes both exception types and cannot stop foreign lock`() {
        for (operation in checksAndStart) {
            for (reason in failureKinds) {
                val os = RecordingKioskPlatform().apply { failures[operation] = reason }
                val engine = KioskControllerEngine(os)
                val failure = KioskResult.Failed(operation, reason)
                assertEquals("$operation $reason", failure, engine.update(always, true, true))
                assertEquals(failure, engine.lastResult)
                assertFalse(engine.lockRequested)
                assertEquals(checksAndStart.take(checksAndStart.indexOf(operation) + 1), os.calls)
                os.calls.clear()
                assertEquals(KioskResult.Released, engine.update(off, true, true))
                assertTrue("No foreign stop after $operation $reason", os.calls.isEmpty())
            }
        }
    }

    @Test
    fun `failed acquisition can recover after OS boundary becomes available`() {
        for (operation in checksAndStart) {
            for (reason in failureKinds) {
                val os = RecordingKioskPlatform().apply { failures[operation] = reason }
                val engine = KioskControllerEngine(os)
                engine.update(always, true, true)
                os.failures.clear()
                os.calls.clear()
                assertEquals(KioskResult.Applied, engine.update(always, true, true))
                assertEquals(checksAndStart, os.calls)
                assertTrue(engine.lockRequested)
            }
        }
    }

    @Test
    fun `inactive stop failure preserves ownership and retries with no eligibility checks`() {
        for (reason in failureKinds) {
            val os = RecordingKioskPlatform()
            val engine = KioskControllerEngine(os)
            engine.update(always, true, true)
            os.calls.clear()
            os.failures[KioskOperation.STOP_LOCK_TASK] = reason
            val failure = KioskResult.Failed(KioskOperation.STOP_LOCK_TASK, reason)
            assertEquals(failure, engine.update(off, false, false))
            assertEquals(failure, engine.lastResult)
            assertTrue(engine.lockRequested)
            assertEquals(listOf(KioskOperation.STOP_LOCK_TASK), os.calls)
            os.failures.clear()
            assertEquals(KioskResult.Released, engine.update(off, false, false))
            assertFalse(engine.lockRequested)
            assertEquals(listOf(KioskOperation.STOP_LOCK_TASK, KioskOperation.STOP_LOCK_TASK), os.calls)
        }
    }

    @Test
    fun `disabled local exit cannot stop or finish whether lock is owned or absent`() {
        for (owned in listOf(false, true)) {
            val os = RecordingKioskPlatform()
            val engine = KioskControllerEngine(os)
            if (owned) engine.update(always, true, true)
            os.calls.clear()
            val denied = KioskResult.Denied(KioskDenialReason.LOCAL_EXIT_DISABLED)
            assertEquals(denied, engine.requestManualExit(always.copy(allowLocalExit = false)))
            assertEquals(denied, engine.lastResult)
            assertEquals(owned, engine.lockRequested)
            assertFalse(engine.manualExitCompleted)
            assertTrue(os.calls.isEmpty())
        }
    }

    @Test
    fun `allowed owned exit stops before finishing and records completion`() {
        val os = RecordingKioskPlatform()
        val engine = KioskControllerEngine(os)
        engine.update(always, true, true)
        os.calls.clear()
        assertEquals(KioskResult.Released, engine.requestManualExit(always))
        assertEquals(listOf(KioskOperation.STOP_LOCK_TASK, KioskOperation.FINISH_TASK), os.calls)
        assertFalse(engine.lockRequested)
        assertTrue(engine.manualExitCompleted)
        assertEquals(KioskResult.Released, engine.lastResult)
    }

    @Test
    fun `allowed unowned exit finishes without querying or stopping foreign lock even when unmanaged`() {
        for (owner in listOf(false, true)) {
            val os = RecordingKioskPlatform().apply { this.owner = owner; permitted = false }
            val engine = KioskControllerEngine(os)
            assertEquals(KioskResult.Released, engine.requestManualExit(always))
            assertEquals(listOf(KioskOperation.FINISH_TASK), os.calls)
            assertFalse(engine.lockRequested)
            assertTrue(engine.manualExitCompleted)
        }
    }

    @Test
    fun `manual stop failure does not finish and explicit retry releases then finishes`() {
        for (reason in failureKinds) {
            val os = RecordingKioskPlatform()
            val engine = KioskControllerEngine(os)
            engine.update(always, true, true)
            os.calls.clear()
            os.failures[KioskOperation.STOP_LOCK_TASK] = reason
            val failure = KioskResult.Failed(KioskOperation.STOP_LOCK_TASK, reason)
            assertEquals(failure, engine.requestManualExit(always))
            assertEquals(failure, engine.lastResult)
            assertTrue(engine.lockRequested)
            assertFalse(engine.manualExitCompleted)
            assertEquals(listOf(KioskOperation.STOP_LOCK_TASK), os.calls)
            os.failures.clear()
            assertEquals(KioskResult.Released, engine.requestManualExit(always))
            assertEquals(listOf(KioskOperation.STOP_LOCK_TASK, KioskOperation.STOP_LOCK_TASK,
                KioskOperation.FINISH_TASK), os.calls)
            assertFalse(engine.lockRequested)
            assertTrue(engine.manualExitCompleted)
        }
    }

    @Test
    fun `successful manual exit suppresses all later relocks and duplicate finishes`() {
        val os = RecordingKioskPlatform()
        val engine = KioskControllerEngine(os)
        engine.update(always, true, true)
        engine.requestManualExit(always)
        os.calls.clear()
        for (policy in listOf(off, playback, always)) {
            assertEquals(KioskResult.Released, engine.update(policy, true, true))
        }
        repeat(3) { assertEquals(KioskResult.Released, engine.requestManualExit(always)) }
        assertTrue(os.calls.isEmpty())
        assertFalse(engine.lockRequested)
        assertTrue(engine.manualExitCompleted)
    }

    @Test
    fun `finish failure is typed and suppresses relock until explicit exit retry succeeds`() {
        for (owned in listOf(false, true)) {
            for (reason in failureKinds) {
                val os = RecordingKioskPlatform()
                val engine = KioskControllerEngine(os)
                if (owned) engine.update(always, true, true)
                os.calls.clear()
                os.failures[KioskOperation.FINISH_TASK] = reason
                val failure = KioskResult.Failed(KioskOperation.FINISH_TASK, reason)
                assertEquals(failure, engine.requestManualExit(always))
                assertEquals(failure, engine.lastResult)
                assertFalse(engine.lockRequested)
                assertFalse(engine.manualExitCompleted)
                val expected = if (owned) listOf(KioskOperation.STOP_LOCK_TASK, KioskOperation.FINISH_TASK)
                    else listOf(KioskOperation.FINISH_TASK)
                assertEquals(expected, os.calls)
                os.calls.clear()
                repeat(3) {
                    assertEquals(KioskResult.Released, engine.update(always, true, true))
                    assertEquals(KioskResult.Released, engine.lastResult)
                }
                assertTrue("Failed finish must not trigger automatic relock or finish", os.calls.isEmpty())
                assertFalse(engine.lockRequested)
                os.failures.clear()
                assertEquals(KioskResult.Released, engine.requestManualExit(always))
                assertEquals(listOf(KioskOperation.FINISH_TASK), os.calls)
                assertTrue(engine.manualExitCompleted)
            }
        }
    }

    @Test
    fun `query exceptions on an owned lock preserve ownership until an inactive release`() {
        for (operation in checksAndStart.take(2)) {
            for (reason in failureKinds) {
                val os = RecordingKioskPlatform()
                val engine = KioskControllerEngine(os)
                engine.update(always, true, true)
                os.calls.clear()
                os.failures[operation] = reason
                val failure = KioskResult.Failed(operation, reason)
                assertEquals(failure, engine.update(always, true, true))
                assertEquals(failure, engine.lastResult)
                assertTrue(engine.lockRequested)
                assertEquals(checksAndStart.take(checksAndStart.indexOf(operation) + 1), os.calls)
                os.calls.clear()
                assertEquals(KioskResult.Released, engine.update(off, false, false))
                assertEquals(listOf(KioskOperation.STOP_LOCK_TASK), os.calls)
                assertFalse(engine.lockRequested)
            }
        }
    }

    @Test
    fun `failed manual stop latches exit intent so updates can release but never relock or finish`() {
        for (reason in failureKinds) {
            val os = RecordingKioskPlatform()
            val engine = KioskControllerEngine(os)
            engine.update(always, true, true)
            os.failures[KioskOperation.STOP_LOCK_TASK] = reason
            engine.requestManualExit(always)
            os.calls.clear()
            os.failures.clear()
            assertEquals(KioskResult.Released, engine.update(always, true, true))
            assertEquals(listOf(KioskOperation.STOP_LOCK_TASK), os.calls)
            assertFalse(engine.lockRequested)
            assertFalse(engine.manualExitCompleted)
            os.calls.clear()
            repeat(3) { assertEquals(KioskResult.Released, engine.update(always, true, true)) }
            assertTrue(os.calls.isEmpty())
            assertEquals(KioskResult.Released, engine.requestManualExit(always))
            assertEquals(listOf(KioskOperation.FINISH_TASK), os.calls)
            assertTrue(engine.manualExitCompleted)
        }
    }

    private fun inactiveInputs() = listOf(
        InactiveInput(off, true, true, false),
        InactiveInput(always, false, true, false),
        InactiveInput(playback, true, false, false),
        InactiveInput(always, true, true, true)
    )

    private data class InactiveInput(
        val policy: DevicePolicySnapshot,
        val foreground: Boolean,
        val playing: Boolean,
        val manual: Boolean
    )
}

/** Fake only the OS boundary; policy and controller are real JVM code. */
private class RecordingKioskPlatform : KioskPlatform {
    var owner = true
    var permitted = true
    val calls = mutableListOf<KioskOperation>()
    val failures = mutableMapOf<KioskOperation, KioskFailureReason>()

    override fun isDeviceOwnerApp(): Boolean {
        record(KioskOperation.CHECK_DEVICE_OWNER)
        return owner
    }

    override fun isLockTaskPermitted(): Boolean {
        record(KioskOperation.CHECK_LOCK_TASK_PERMISSION)
        return permitted
    }

    override fun startLockTask() = record(KioskOperation.START_LOCK_TASK)
    override fun stopLockTask() = record(KioskOperation.STOP_LOCK_TASK)
    override fun finishAndRemoveTask() = record(KioskOperation.FINISH_TASK)

    fun effects() = calls.filter {
        it != KioskOperation.CHECK_DEVICE_OWNER && it != KioskOperation.CHECK_LOCK_TASK_PERMISSION
    }

    private fun record(operation: KioskOperation) {
        calls += operation
        when (failures[operation]) {
            KioskFailureReason.SECURITY -> throw SecurityException("private OS detail: token=secret")
            KioskFailureReason.ILLEGAL_STATE -> throw IllegalStateException("private OS detail: task=secret")
            null -> Unit
        }
    }
}
