package com.MegaStream.app.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskPolicyMatrixTest {
    @Test
    fun `mode foreground playback ownership and manual exit matrix is exhaustive`() {
        val activePairs = mapOf(
            KioskMode.OFF to emptySet<Pair<Boolean, Boolean>>(),
            KioskMode.PLAYBACK to setOf(true to true),
            KioskMode.ALWAYS to setOf(true to false, true to true)
        )
        for (mode in KioskMode.values()) {
            for (foreground in listOf(false, true)) {
                for (playback in listOf(false, true)) {
                    for (owner in listOf(false, true)) {
                        for (manualExit in listOf(false, true)) {
                            for (allowExit in listOf(false, true)) {
                                val expected = owner && !manualExit &&
                                    (foreground to playback) in activePairs.getValue(mode)
                                assertEquals(
                                    "$mode foreground=$foreground playback=$playback owner=$owner manual=$manualExit allowExit=$allowExit",
                                    expected,
                                    shouldLock(
                                        DevicePolicySnapshot(mode, allowExit), owner,
                                        foreground, playback, manualExit
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
