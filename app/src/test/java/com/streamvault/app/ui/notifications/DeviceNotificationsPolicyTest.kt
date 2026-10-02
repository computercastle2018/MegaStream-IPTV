package com.MegaStream.app.ui.notifications

import com.MegaStream.app.controlplane.DeviceNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceNotificationsPolicyTest {
    private val older = DeviceNotice("00000000-0000-0000-0000-000000000001", "Earlier", "Earlier message", "2026-10-01T10:00:00Z")
    private val newer = DeviceNotice("00000000-0000-0000-0000-000000000002", "Latest", "Latest message", "2026-10-02T10:00:00Z")

    @Test
    fun popupCloseAcknowledgesOnlyVisibleNoticeAndNeverRepeatsIt() {
        val notices = listOf(older, newer)
        val visible = visibleDeviceNotices(notices, emptySet(), inboxOpen = false)
        assertEquals(listOf(newer), visible)
        val readOnClose = visible.map { it.id }.toSet()
        assertEquals(setOf(newer.id), readOnClose)
        assertEquals(listOf(older), visibleDeviceNotices(notices, readOnClose, inboxOpen = false))
        assertTrue(visibleDeviceNotices(notices, readOnClose + older.id, inboxOpen = false).isEmpty())
    }

    @Test
    fun openingInboxShowsNewestFirstWithoutChangingReadStateOrServerOrder() {
        val readIds = setOf(newer.id)
        val notices = listOf(older, newer)
        assertEquals(listOf(newer, older), visibleDeviceNotices(notices, readIds, inboxOpen = true))
        assertEquals(listOf(newer, older), visibleDeviceNotices(notices.reversed(), readIds, inboxOpen = true))
        assertEquals(listOf(newer), visibleDeviceNotices(notices.reversed(), emptySet(), inboxOpen = false))
        assertEquals(listOf(older, newer), notices)
        assertEquals(setOf(newer.id), readIds)
        assertEquals(listOf(older), visibleDeviceNotices(notices, readIds, inboxOpen = false))
        assertTrue(visibleDeviceNotices(emptyList(), readIds, inboxOpen = true).isEmpty())
    }
}
