package com.MegaStream.app.ui.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceNotificationTimeoutTest {
    @Test
    fun toneHistoryRetainsOnlyTheLatestHundredIds() {
        val sounded = mutableSetOf<String>()
        repeat(150) { assertTrue(claimDeviceNoticeTone("notice-$it", false, true, sounded)) }
        assertEquals((50 until 150).map { "notice-$it" }.toSet(), sounded)
        assertFalse(claimDeviceNoticeTone("notice-149", false, true, sounded))
        assertEquals(100, sounded.size)
    }

    @Test
    fun toneIsClaimedOncePerPopupAndNeverByInboxOrBackground() {
        val sounded = mutableSetOf<String>()
        assertFalse(claimDeviceNoticeTone("first", true, true, sounded))
        assertFalse(claimDeviceNoticeTone("first", false, false, sounded))
        assertFalse(claimDeviceNoticeTone(null, false, true, sounded))
        assertTrue(sounded.isEmpty())
        assertTrue(claimDeviceNoticeTone("first", false, true, sounded))
        assertFalse(claimDeviceNoticeTone("first", true, true, sounded))
        assertFalse(claimDeviceNoticeTone("first", false, false, sounded))
        assertFalse(claimDeviceNoticeTone("first", false, true, sounded))
        assertTrue(claimDeviceNoticeTone("second", false, true, sounded))
        assertEquals(setOf("first", "second"), sounded)
    }

    @Test
    fun unsolicitedPopupAcknowledgesOnlyAfterSixtySeconds() = runTest {
        val acknowledged = mutableListOf<String>()
        launch { awaitDeviceNoticeTimeout("notice", false, true, { true }, acknowledged::add) }
        advanceTimeBy(59_999)
        assertTrue(acknowledged.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("notice"), acknowledged)
    }

    @Test
    fun manualInboxBackgroundAndAbsentNoticeNeverExpire() = runTest {
        val acknowledged = mutableListOf<String>()
        awaitDeviceNoticeTimeout("notice", true, true, { true }, acknowledged::add)
        awaitDeviceNoticeTimeout("notice", false, false, { true }, acknowledged::add)
        awaitDeviceNoticeTimeout(null, false, true, { true }, acknowledged::add)
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(acknowledged.isEmpty())
    }

    @Test
    fun newNoticeCancelsOldTimerAndReceivesFullCountdown() = runTest {
        val acknowledged = mutableListOf<String>()
        val oldTimer = launch { awaitDeviceNoticeTimeout("old", false, true, { true }, acknowledged::add) }
        advanceTimeBy(30_000)
        oldTimer.cancel()
        launch { awaitDeviceNoticeTimeout("new", false, true, { true }, acknowledged::add) }
        advanceTimeBy(59_999)
        assertTrue(acknowledged.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("new"), acknowledged)
    }

    @Test
    fun backgroundOrInboxChangeAtTimeoutDoesNotAcknowledge() = runTest {
        val acknowledged = mutableListOf<String>()
        var eligible = true
        launch { awaitDeviceNoticeTimeout("notice", false, true, { eligible }, acknowledged::add) }
        advanceTimeBy(59_999)
        eligible = false
        advanceTimeBy(1)
        runCurrent()
        assertTrue(acknowledged.isEmpty())
    }

    @Test
    fun leavingResumedCancelsCountdownAndReturnStartsFresh() = runTest {
        val acknowledged = mutableListOf<String>()
        val timer = launch { awaitDeviceNoticeTimeout("notice", false, true, { true }, acknowledged::add) }
        advanceTimeBy(59_000)
        timer.cancel()
        advanceTimeBy(120_000)
        assertTrue(acknowledged.isEmpty())
        launch { awaitDeviceNoticeTimeout("notice", false, true, { true }, acknowledged::add) }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("notice"), acknowledged)
    }
}
